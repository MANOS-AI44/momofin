package com.gerard.momofin

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionHelper {

    val REQUIRED_PERMS = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS
    )

    fun hasSmsPermission(context: Context): Boolean =
        REQUIRED_PERMS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * True si l'utilisateur a coché "Ne plus demander" pour au moins une perm SMS.
     * Dans ce cas la demande système ne s'affichera plus et il faut ouvrir les paramètres.
     */
    fun shouldShowSettings(activity: Activity): Boolean {
        if (hasSmsPermission(activity)) return false
        for (perm in REQUIRED_PERMS) {
            if (ContextCompat.checkSelfPermission(activity, perm) != PackageManager.PERMISSION_GRANTED) {
                // Si on a déjà demandé et que l'utilisateur a refusé, le système renvoie false pour shouldShowRequestPermissionRationale
                // → c'est le signal qu'il faut envoyer dans Paramètres
                if (!ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)) {
                    // Mais attention : avant la 1re demande aussi, shouldShow renvoie false.
                    // On considère donc que SI on a refusé au moins une fois, on est dans le cas "Paramètres requis".
                    // On utilise un flag dans Settings pour savoir si la demande a déjà été faite.
                    if (Settings.hasAskedPermissions(activity)) return true
                }
            }
        }
        return false
    }

    /** Ouvre la page Paramètres de l'app (autorisations, notifications, etc.) */
    fun openAppSettings(context: Context) {
        val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
