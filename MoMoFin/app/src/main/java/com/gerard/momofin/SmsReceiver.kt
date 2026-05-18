package com.gerard.momofin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Capture les SMS Mobile Money en temps réel à leur arrivée.
 * Les stocke dans NotificationStore (même source que les notifications captées).
 * Fonctionne UNIQUEMENT si READ_SMS / RECEIVE_SMS sont accordées (anciennes versions Android).
 * Sur Android 13+, le NotificationListener prend le relais.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // Si ce téléphone n'est pas prioritaire, on ignore
        if (!Settings.isPrimaryDevice(context.applicationContext)) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val store = NotificationStore(context.applicationContext)

        val grouped = messages.groupBy { it.originatingAddress ?: "" }
        for ((sender, parts) in grouped) {
            val body = parts.joinToString(separator = "") { it.messageBody ?: "" }
            val ts = parts.first().timestampMillis
            if (MomoFilter.isMomoSms(sender, body)) {
                val operator = MomoFilter.detectOperator(sender, body)
                store.insert(sender, body, ts, operator)
            }
        }
        store.close()
    }
}
