package com.gerard.momofin

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat

/**
 * Lit les SMS Mobile Money depuis deux sources possibles :
 *   1. App MoMo SMS (préférée) via son ContentProvider
 *   2. Boîte SMS du téléphone (fallback) avec permission READ_SMS
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
        return fromMomoSms(context) ?: fromInbox(context)
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
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
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
        return list
    }
}

/**
 * Filtre embarqué (copie locale du filtre de MoMo SMS) pour le fallback.
 */
object MomoFilter {
    private val SENDERS = listOf(
        "MoMo", "MTN", "MTN MoMo", "M-Money", "MTNMoMo",
        "Orange", "OrangeMoney", "Orange Money", "OM",
        "Airtel", "AirtelMoney", "Airtel Money"
    )
    private val KEYWORDS = listOf(
        "momo", "mobile money", "transaction", "received", "sent",
        "reçu", "envoy", "transfert", "transfer", "ref",
        "rwf", "xof", "xaf", "ugx", "ghs", "kes", "tzs", "fcfa",
        "id:", "txn", "txid"
    )

    fun isMomoSms(sender: String?, body: String?): Boolean {
        val s = (sender ?: "").lowercase()
        val b = (body ?: "").lowercase()
        if (SENDERS.any { s.contains(it.lowercase()) }) return true
        val hits = KEYWORDS.count { b.contains(it) }
        return hits >= 2
    }

    fun detectOperator(sender: String?, body: String?): String {
        val s = (sender ?: "").lowercase()
        val b = (body ?: "").lowercase()
        return when {
            s.contains("mtn") || s.contains("momo") || b.contains("momo") -> "MTN"
            s.contains("orange") || b.contains("orange money") -> "Orange"
            s.contains("airtel") -> "Airtel"
            else -> "Autre"
        }
    }
}
