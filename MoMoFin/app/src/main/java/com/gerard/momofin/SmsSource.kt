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
                    if (!MomoFilter.isMomoSms(sender, body)) continue
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
        val s = (sender ?: "").lowercase()
        val b = (body ?: "").lowercase()
        return when {
            s.contains("mtn") || s.contains("momo") || b.contains("momo") -> "MTN"
            s.contains("orange") || b.contains("orange money") -> "Orange"
            s.contains("airtel") -> "Airtel"
            s.contains("moov") -> "Moov"
            s.contains("wave") -> "Wave"
            s.contains("djamo") -> "djamo"
            else -> "Autre"
        }
    }
}
