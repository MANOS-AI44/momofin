package com.gerard.momofin

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionHelper {

    val SMS_PERMS = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS
    )

    fun hasSmsPermission(context: Context): Boolean =
        SMS_PERMS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun hasPostNotificationsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Vrai si l'app est autorisée à lire les notifications (NotificationListenerService activé).
     * C'est la VRAIE permission qui marche sur Android 14/15/16 pour lire les SMS.
     */
    fun hasNotificationAccess(context: Context): Boolean {
        val enabledListeners = AndroidSettings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        val cn = ComponentName(context, SmsNotificationListener::class.java)
        return enabledListeners.contains(cn.flattenToString())
            || enabledListeners.contains(cn.flattenToShortString())
    }

    fun shouldShowSmsSettings(activity: Activity): Boolean {
        if (hasSmsPermission(activity)) return false
        for (perm in SMS_PERMS) {
            if (ContextCompat.checkSelfPermission(activity, perm) != PackageManager.PERMISSION_GRANTED) {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)) {
                    if (Settings.hasAskedPermissions(activity)) return true
                }
            }
        }
        return false
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Ouvre la page "Accès aux notifications" du système — c'est la méthode qui marche sur Android 13+ */
    fun openNotificationListenerSettings(context: Context) {
        val intent = Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppSettings(context)
        }
    }

    /** Sur Android 11+, on peut viser directement les réglages de notif de l'app */
    fun openAppNotificationSettings(context: Context) {
        val intent = Intent().apply {
            action = AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS
            putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppSettings(context)
        }
    }

    fun androidVersionLabel(): String {
        val api = Build.VERSION.SDK_INT
        val name = when (api) {
            34 -> "Android 14"
            33 -> "Android 13"
            32, 31 -> "Android 12"
            30 -> "Android 11"
            29 -> "Android 10"
            in 35..50 -> "Android ${api - 20}"
            else -> "Android ${Build.VERSION.RELEASE}"
        }
        return "$name (API $api)"
    }

    /** Ouvrir les parametres de batterie/optimisation (utile quand 'L'app a ete restreinte') */
    fun openBatteryOptimizationSettings(context: Context) {
        val intent = Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { context.startActivity(intent) } catch (_: Exception) { openAppSettings(context) }
    }

    /** Demande explicitement a etre exempte des optimisations batterie */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { context.startActivity(intent) } catch (_: Exception) { openAppSettings(context) }
    }
}
