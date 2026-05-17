package com.gerard.momofin

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Lit les SMS Mobile Money via les NOTIFICATIONS du téléphone.
 * Avantage : fonctionne sur Android 13/14/15/16 même sans READ_SMS
 * (Google bloque READ_SMS aux apps non-SMS sur les versions récentes).
 *
 * Nécessite que l'utilisateur autorise "Accès aux notifications" dans Paramètres Android.
 */
class SmsNotificationListener : NotificationListenerService() {

    private val TAG = "SmsNotifListener"

    // Packages des apps de messagerie courantes — à élargir si besoin
    private val SMS_PACKAGES = setOf(
        "com.android.mms",
        "com.google.android.apps.messaging",          // Google Messages
        "com.samsung.android.messaging",              // Samsung Messages
        "com.android.messaging",
        "com.huawei.message",
        "com.xiaomi.mms",
        "com.miui.smshelper",
        "com.oppo.mms",
        "com.vivo.mms"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName ?: return
            // Soit une app SMS connue, soit on tente quand même
            val extras = sbn.notification?.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: ""

            if (text.isBlank()) return

            // Filtrer Mobile Money
            if (!isFromSmsApp(pkg) && !looksLikeMomoSms(title, text)) return
            if (!looksLikeMomoSms(title, text)) return

            val timestamp = sbn.postTime
            Log.d(TAG, "SMS MoMo capté : sender=$title body=${text.take(50)}…")

            // Stocker dans la base partagée
            val store = NotificationStore(applicationContext)
            store.insert(title, text, timestamp, detectOperator(title, text))
            store.close()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lecture notif", e)
        }
    }

    private fun isFromSmsApp(pkg: String): Boolean = SMS_PACKAGES.contains(pkg)

    private fun looksLikeMomoSms(sender: String?, body: String?): Boolean {
        val s = (sender ?: "").lowercase()
        val b = (body ?: "").lowercase()
        // Réutilise la logique de MoMoFilter local
        val senders = listOf("momo", "mtn", "orange", "airtel", "moov", "wave", "mobile money")
        if (senders.any { s.contains(it) }) return true
        val kw = listOf("fcfa", "f cfa", "rwf", "xof", "transaction", "recu", "reçu", "envoy", "retrait", "depot", "solde", "id transaction")
        return kw.count { b.contains(it) } >= 2
    }

    private fun detectOperator(sender: String?, body: String?): String {
        val s = (sender ?: "").lowercase()
        val b = (body ?: "").lowercase()
        return when {
            s.contains("mtn") || s.contains("momo") -> "MTN"
            s.contains("orange") || b.contains("orange") -> "Orange"
            s.contains("airtel") -> "Airtel"
            s.contains("moov") -> "Moov"
            s.contains("wave") -> "Wave"
            else -> "Autre"
        }
    }
}
