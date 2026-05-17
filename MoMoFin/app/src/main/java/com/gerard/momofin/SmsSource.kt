package com.gerard.momofin

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat

/**
 * Lit les SMS Mobile Money depuis 3 sources, dans l'ordre :
 *   1. NotificationStore — alimenté par SmsNotificationListener (marche sur Android 13+/14/15/16)
 *   2. App MoMo SMS via ContentProvider partagé
 *   3. Boîte SMS du téléphone (fallback) si READ_SMS accordé
 * Les doublons (même sender + body + timestamp) sont éliminés.
 */
object SmsSource {

    private val MOMO_SMS_URI: Uri = Uri.parse("content://com.gerard.momosms.provider/sms")

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
        all.addAll(fromMomoSms(context) ?: emptyList())
        all.addAll(fromInbox(context))

        // Dédupliquer par signature (sender + body + timestamp à 10s près)
        val seen = mutableSetOf<String>()
        val unique = mutableListOf<Raw>()
        for (r in all.sortedByDescending { it.timestamp }) {
            val sig = "${r.sender.trim().lowercase()}|${r.body.trim().take(100)}|${r.timestamp / 10_000}"
            if (seen.add(sig)) unique.add(r)
        }
        return unique
    }

    private fun fromNotifications(context: Context): List<Raw> {
        return try {
            NotificationStore(context).all()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fromMomoSms(context: Context): List<Raw>? {
        return try {
            val list = mutableListOf<Raw>()
            context.contentResolver.query(
                MOMO_SMS_URI, null, null, null, "timestamp DESC"
            )?.use { c ->
                if (c.count == 0) return@use
                val iId = c.getColumnIndex("_id")
                val iAddr = c.getColumnIndex("sender")
                val iBody = c.getColumnIndex("body")
                val iTs = c.getColumnIndex("timestamp")
                val iOp = c.getColumnIndex("operator")
                while (c.moveToNext()) {
                    list.add(
                        Raw(
                            id = if (iId >= 0) c.getLong(iId) else 0L,
                            sender = if (iAddr >= 0) c.getString(iAddr) ?: "" else "",
                            body = if (iBody >= 0) c.getString(iBody) ?: "" else "",
                            timestamp = if (iTs >= 0) c.getLong(iTs) else 0L,
                            operator = if (iOp >= 0) c.getString(iOp) ?: "" else ""
                        )
                    )
                }
            }
            if (list.isEmpty()) null else list
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun fromInbox(context: Context): List<Raw> {
        if (ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        val list = mutableListOf<Raw>()
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms._ID
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
                    list.add(
                        Raw(
                            id = c.getLong(iId),
                            sender = sender,
                            body = body,
                            timestamp = c.getLong(iDate),
                            operator = MomoFilter.detectOperator(sender, body)
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return list
    }
}

object MomoFilter {
    private val SENDERS = listOf(
        "MoMo", "MTN", "MTN MoMo", "M-Money", "MTNMoMo",
        "Orange", "OrangeMoney", "Orange Money", "OM",
        "Airtel", "AirtelMoney", "Airtel Money",
        "Moov", "Wave"
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
            else -> "Autre"
        }
    }
}
