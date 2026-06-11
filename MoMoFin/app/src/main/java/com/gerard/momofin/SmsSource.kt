package com.gerard.momofin

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat

/**
 * Lit les SMS Mobile Money depuis 2 sources, dédoublonnés automatiquement :
 *   1. NotificationStore — alimenté par SmsNotificationListener + SmsReceiver
 *   2. Boîte SMS du téléphone (fallback) si READ_SMS accordé
 */
object SmsSource {

    data class Raw(
        val id: Long,
        val sender: String,
        val body: String,
        val timestamp: Long,
        val operator: String
    )

    fun loadAll(context: Context): List<Raw> {
        val all = mutableListOf<Raw>()
        all.addAll(fromNotifications(context))
        all.addAll(fromInbox(context))

        val seen = mutableSetOf<String>()
        val unique = mutableListOf<Raw>()
        for (r in all.sortedByDescending { it.timestamp }) {
            val sig = "${r.sender.trim().lowercase()}|${r.body.trim().take(100)}|${r.timestamp / 10_000}"
            if (seen.add(sig)) unique.add(r)
        }
        return unique
    }

    private fun fromNotifications(context: Context): List<Raw> {
        return try { NotificationStore(context).all() } catch (_: Exception) { emptyList() }
    }

    /**
     * Importe les SMS de la boîte SMS du téléphone et les enregistre dans NotificationStore.
     * Utile pour récupérer les SMS reçus AVANT l'installation de l'app.
     * Renvoie le nombre de SMS importés.
     */
    fun importInbox(context: Context): Int {
        if (ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) return -1

        val store = NotificationStore(context)
        var inserted = 0
        val projection = arrayOf(
            Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms._ID
        )
        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, projection, null, null, "date DESC"
            )?.use { c ->
                val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                while (c.moveToNext()) {
                    val sender = c.getString(iAddr) ?: continue
                    val body = c.getString(iBody) ?: continue
                    if (!MomoFilter.isMomoSms(sender, body)) continue
                    val op = MomoFilter.detectOperator(sender, body)
                    if (store.insert(sender, body, c.getLong(iDate), op) > 0) inserted++
                }
            }
        } catch (_: Exception) {}
        store.close()
        return inserted
    }

    private fun fromInbox(context: Context): List<Raw> {
        if (ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        val list = mutableListOf<Raw>()
        val projection = arrayOf(
            Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms._ID
        )
        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, projection, null, null, "date DESC"
            )?.use { c ->
                val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                while (c.moveToNext()) {
                    val sender = c.getString(iAddr) ?: continue
                    val body = c.getString(iBody) ?: continue
                    // On ne filtre PLUS ici : le parser strict decidera.
                    // Cela evite de rejeter par erreur un vrai SMS Momo dont l'expediteur
                    // ne fait pas partie de la liste connue (ex : code court 1WIN3).
                    list.add(Raw(
                        id = c.getLong(iId), sender = sender, body = body,
                        timestamp = c.getLong(iDate),
                        operator = MomoFilter.detectOperator(sender, body)
                    ))
                }
            }
        } catch (_: Exception) {}
        return list
    }

    data class ScanResult(val processed: Int, val matched: Int, val newInCache: Int, val errMsg: String = "")

    /**
     * Scan INCREMENTAL de la boite SMS : ne lit que les SMS nouveaux depuis le dernier scan
     * (via Settings.lastInboxId). Parse en streaming -> insert dans le cache. Aucun build de
     * liste en RAM : on traite SMS par SMS. Robuste pour des dizaines de milliers de SMS.
     */
    fun scanInboxIntoCache(context: Context, cache: TransactionCache): ScanResult {
        if (ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) return ScanResult(0, 0, 0, "permission_sms_refusee")

        val lastId = Settings.getLastInboxId(context)
        var processed = 0; var matched = 0; var newOnes = 0
        var maxId = lastId
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE
        )
        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, projection,
                "${Telephony.Sms._ID} > ?", arrayOf(lastId.toString()),
                "${Telephony.Sms._ID} ASC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                while (c.moveToNext()) {
                    processed++
                    val id = c.getLong(iId); if (id > maxId) maxId = id
                    val sender = c.getString(iAddr) ?: continue
                    val body = c.getString(iBody) ?: continue
                    val ts = c.getLong(iDate)
                    val op = MomoFilter.detectOperator(sender, body)
                    val tx = try {
                        TransactionParser.parse(id, sender, body, ts, op)
                    } catch (_: Throwable) { null } ?: continue
                    matched++
                    if (cache.insert(tx)) newOnes++
                }
            }
            if (maxId > lastId) Settings.setLastInboxId(context, maxId)
        } catch (e: OutOfMemoryError) {
            if (maxId > lastId) Settings.setLastInboxId(context, maxId)
            return ScanResult(processed, matched, newOnes, "memoire saturee a ${processed} SMS")
        } catch (e: Exception) {
            return ScanResult(processed, matched, newOnes, e.message ?: "erreur scan inbox")
        }
        return ScanResult(processed, matched, newOnes)
    }

    /** Scan les SMS captes via NotificationListener (NotificationStore) -> cache. Tres rapide. */
    fun scanNotificationsIntoCache(context: Context, cache: TransactionCache): ScanResult {
        var processed = 0; var matched = 0; var newOnes = 0
        try {
            for (r in NotificationStore(context).all()) {
                processed++
                val tx = try {
                    TransactionParser.parse(r.id, r.sender, r.body, r.timestamp, r.operator)
                } catch (_: Throwable) { null } ?: continue
                matched++
                if (cache.insert(tx)) newOnes++
            }
        } catch (e: Throwable) {
            return ScanResult(processed, matched, newOnes, e.message ?: "erreur scan notifs")
        }
        return ScanResult(processed, matched, newOnes)
    }

    data class Diag(
        val notifCount: Int,
        val inboxCount: Int,
        val hasReadSms: Boolean,
        val hasNotifAccess: Boolean
    )

    /** Diagnostique l'etat de la capture SMS (pour expliquer pourquoi rien n'apparait). */
    fun diagnose(context: Context): Diag {
        val notif = try { NotificationStore(context).all().size } catch (_: Exception) { 0 }
        val hasRead = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
        val inbox = if (hasRead) try {
            var n = 0
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms._ID), null, null, null
            )?.use { c -> n = c.count }
            n
        } catch (_: Exception) { 0 } else 0
        val hasNotif = PermissionHelper.hasNotificationAccess(context)
        return Diag(notif, inbox, hasRead, hasNotif)
    }
}

object MomoFilter {
    private val SENDERS = listOf(
        "MoMo", "MTN", "MTN MoMo", "M-Money", "MTNMoMo",
        "Orange", "OrangeMoney", "Orange Money", "OM",
        "Airtel", "AirtelMoney", "Airtel Money",
        "Moov", "Wave", "djamo", "Yas"
    )
    private val KEYWORDS = listOf(
        "momo", "mobile money", "transaction", "received", "sent",
        "reçu", "recu", "envoy", "transfert", "transfer", "ref",
        "rwf", "xof", "xaf", "ugx", "ghs", "kes", "tzs", "fcfa",
        "id:", "id transaction", "txn", "txid",
        "depot", "retrait", "solde"
    )

    fun isMomoSms(sender: String?, body: String?): Boolean {
        val s = (sender ?: "").lowercase()
        val b = (body ?: "").lowercase()
        if (SENDERS.any { s.contains(it.lowercase()) }) return true
        return KEYWORDS.count { b.contains(it) } >= 2
    }

    fun detectOperator(sender: String?, body: String?): String {
        val s = (sender ?: "").lowercase().trim()
        val b = (body ?: "").lowercase()
        // ORANGE : sender +454 ou variantes
        if (s == "+454" || s == "454" || s.startsWith("+454") || s.contains("orange")) return "Orange"
        if (b.contains("orange money")) return "Orange"
        // Signatures Orange dans le body (sender numerique)
        if (Regex("(?i)id\\s+transaction[:\\s]+c[io]\\d").containsMatchIn(body ?: "")) return "Orange"
        if (Regex("(?i)vigilance\\s+arnaque").containsMatchIn(body ?: "")) return "Orange"
        // MTN
        if (s.contains("mobilemoney") || s.contains("mobile money") || s.contains("mtn") || s.contains("momo") || s == "mm") return "MTN"
        if (b.contains("mtn momo") || b.contains("mtn mobile money")) return "MTN"
        // MOOV
        if (s.contains("moovmoney") || s.contains("moov money") || s.contains("moov") || s.contains("flooz")) return "MOOV"
        if (b.contains("moov money") || b.contains("flooz")) return "MOOV"
        // Autres
        if (s.contains("airtel")) return "Airtel"
        if (s.contains("wave") || b.contains("wave")) return "Wave"
        if (s.contains("djamo")) return "djamo"
        return "Autre"
    }
}
