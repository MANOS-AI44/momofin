package com.gerard.momosms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Receveur de SMS : capte les SMS entrants en temps réel et stocke
 * uniquement ceux qui proviennent d'un opérateur Mobile Money.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val store = MomoSmsStore(context.applicationContext)

        // Regrouper le corps de chaque SMS multi-part par expéditeur
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
