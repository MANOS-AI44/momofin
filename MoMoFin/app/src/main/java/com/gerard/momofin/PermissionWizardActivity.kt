package com.gerard.momofin

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Assistant pas-a-pas pour autoriser tout ce dont MoMo Fin a besoin
 * pour lire les SMS Mobile Money en arriere-plan.
 * 6 etapes detectees automatiquement, chacune avec un bouton 'Reparer'.
 */
class PermissionWizardActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Activer les SMS"
        val scroll = android.widget.ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 80)
        }
        scroll.addView(container)
        setContentView(scroll)
        renderSteps()
    }

    override fun onResume() {
        super.onResume()
        renderSteps()
    }

    private fun renderSteps() {
        container.removeAllViews()

        addHeader("⚙️ Assistant d'activation",
            "Suivez les étapes ci-dessous. Tout ce qui est en rouge bloque la lecture des SMS Mobile Money.")

        // Étape 1 : Restriction "L'application a été restreinte" (Honor/Xiaomi/Huawei)
        addStep(
            num = 1,
            title = "Lever la restriction de l'application",
            done = !isAppRestricted(),
            description = if (isAppRestricted())
                "Android a placé MoMo Fin en mode restreint. Cela bloque tout fonctionnement en arrière-plan."
            else "✓ L'app n'est plus restreinte.",
            actionLabel = "Ouvrir Infos appli",
            action = { openAppInfo() }
        )

        // Étape 2 : Optimisation batterie
        addStep(
            num = 2,
            title = "Désactiver l'optimisation de la batterie",
            done = isBatteryOptimizationIgnored(),
            description = if (!isBatteryOptimizationIgnored())
                "Sans cette exception, Android stoppe MoMo Fin après quelques minutes en arrière-plan."
            else "✓ MoMo Fin est exempté de l'optimisation batterie.",
            actionLabel = "Désactiver maintenant",
            action = { requestIgnoreBatteryOpt() }
        )

        // Étape 3 : Permission SMS
        addStep(
            num = 3,
            title = "Autoriser la lecture des SMS",
            done = hasSmsPermission(),
            description = if (!hasSmsPermission())
                "Permet de lire la boîte SMS et capter les SMS Mobile Money en direct."
            else "✓ SMS autorisés.",
            actionLabel = "Donner l'autorisation",
            action = { requestSmsPermission() }
        )

        // Étape 4 : Permission notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) {
            addStep(
                num = 4,
                title = "Autoriser les notifications",
                done = hasPostNotifPermission(),
                description = if (!hasPostNotifPermission())
                    "Nécessaire pour afficher les confirmations de synchronisation et lire les notifs SMS."
                else "✓ Notifications autorisées.",
                actionLabel = "Donner l'autorisation",
                action = { requestPostNotifPermission() }
            )
        }

        // Étape 5 : Accès aux notifications (NotificationListener)
        addStep(
            num = 5,
            title = "Activer l'accès aux notifications",
            done = hasNotificationAccess(),
            description = if (!hasNotificationAccess())
                "Sur Android 13+, c'est la SEULE manière fiable de lire les SMS Mobile Money en temps réel.\n\n→ Activez le toggle MoMo Fin dans la liste qui s'ouvre."
            else "✓ MoMo Fin lit les notifications.",
            actionLabel = "Activer l'accès",
            action = { openNotifListenerSettings() }
        )

        // Étape 6 : Verification finale
        val allOk = !isAppRestricted() && isBatteryOptimizationIgnored() && hasSmsPermission()
                && (Build.VERSION.SDK_INT < 33 || hasPostNotifPermission())
                && hasNotificationAccess()
        if (allOk) {
            val ok = TextView(this).apply {
                text = "🎉 Toutes les autorisations sont accordées !\n\nMoMo Fin va maintenant capter les SMS Orange / MTN / MOOV automatiquement."
                textSize = 16f
                setTextColor(0xFF059669.toInt())
                setBackgroundColor(0xFFD1FAE5.toInt())
                setPadding(30, 30, 30, 30)
            }
            container.addView(ok, lp(0, 30, 0, 0))
        }

        // Bouton fermer
        val close = android.widget.Button(this).apply {
            text = "← Retour à MoMo Fin"
            setBackgroundResource(R.drawable.btn_primary)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setOnClickListener { finish() }
        }
        container.addView(close, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 140).apply {
            setMargins(0, 50, 0, 0)
        })
    }

    private fun addHeader(title: String, desc: String) {
        container.addView(TextView(this).apply {
            text = title
            textSize = 22f
            setTextColor(0xFF0D47A1.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = desc
            textSize = 13f
            setTextColor(0xFF6B7280.toInt())
        }, lp(0, 8, 0, 24))
    }

    private fun addStep(num: Int, title: String, done: Boolean, description: String, actionLabel: String, action: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 30)
            setBackgroundColor(if (done) 0xFFD1FAE5.toInt() else 0xFFFEE2E2.toInt())
        }
        card.addView(TextView(this).apply {
            text = (if (done) "✅  Étape $num : " else "⚠️  Étape $num : ") + title
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (done) 0xFF065F46.toInt() else 0xFF991B1B.toInt())
        })
        card.addView(TextView(this).apply {
            text = description
            textSize = 13f
            setTextColor(0xFF374151.toInt())
        }, lp(0, 12, 0, if (!done) 16 else 0))
        if (!done) {
            val btn = android.widget.Button(this).apply {
                text = "👉  $actionLabel"
                setBackgroundResource(R.drawable.btn_warning)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setOnClickListener { action() }
            }
            card.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 130))
        }
        container.addView(card, lp(0, 0, 0, 16))
    }

    private fun lp(l: Int, t: Int, r: Int, b: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(l, t, r, b) }

    // ===== Detections =====
    private fun isAppRestricted(): Boolean {
        // Sur Android 9+, on peut detecter le mode 'restricted' via UsageStatsManager bucket
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
                usm.appStandbyBucket >= 40  // 40+ = restricted/rare
            } else false
        } catch (_: Exception) { false }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        && ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    private fun hasPostNotifPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationAccess(): Boolean {
        val list = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val cn = ComponentName(this, SmsNotificationListener::class.java)
        return list.contains(cn.flattenToString()) || list.contains(cn.flattenToShortString())
    }

    // ===== Actions =====
    private fun openAppInfo() {
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        AlertDialog.Builder(this)
            .setTitle("📋 Quoi faire dans Infos appli")
            .setMessage("1. Si vous voyez '⚠️ L'application a été restreinte' en haut → cliquez 'Annuler l'interdiction'\n\n2. Puis cliquez 'Autorisations' → activez SMS + Notifications\n\n3. Revenez ici quand c'est fait.")
            .setPositiveButton("Compris", null)
            .show()
    }

    private fun requestIgnoreBatteryOpt() {
        // 1ere tentative : popup systeme direct (necessite permission REQUEST_IGNORE_BATTERY_OPTIMIZATIONS au manifest)
        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            return
        } catch (e: Exception) {
            android.util.Log.w("PermWizard", "Direct ignore-batt failed: $e")
        }
        // 2eme tentative : ouvre la liste des apps avec optimisation batterie
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AlertDialog.Builder(this)
                .setTitle("📋 Trouver MoMo Fin")
                .setMessage("Dans la liste qui s'est ouverte :\n\n1. Touchez le filtre en haut, choisissez 'Toutes les applications'\n2. Trouvez 'MoMo Fin'\n3. Selectionnez 'Ne pas optimiser'")
                .setPositiveButton("Compris", null).show()
            return
        } catch (e: Exception) {
            android.util.Log.w("PermWizard", "Battery settings list failed: $e")
        }
        // 3eme tentative : ouvrir Infos appli + instructions textuelles
        openAppInfo()
        Toast.makeText(this, "⚠️ Allez dans Batterie → Optimisation batterie → MoMo Fin → Ne pas optimiser", Toast.LENGTH_LONG).show()
    }

    private fun requestSmsPermission() {
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS), 1234)
    }

    private fun requestPostNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1235)
        }
    }

    private fun openNotifListenerSettings() {
        // 1ere tentative : intent direct vers Acces aux notifications
        var opened = false
        try {
            val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i); opened = true
        } catch (e: Exception) {
            android.util.Log.w("PermWizard", "ACTION_NOTIFICATION_LISTENER_SETTINGS failed: $e")
        }
        // 2eme : intent privé (marche sur Honor/Huawei)
        if (!opened) try {
            val i = Intent()
            i.setClassName("com.android.settings", "com.android.settings.Settings\$NotificationAccessSettingsActivity")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i); opened = true
        } catch (e: Exception) {
            android.util.Log.w("PermWizard", "NotificationAccessSettingsActivity failed: $e")
        }
        // 3eme : fallback Infos appli
        if (!opened) openAppInfo()

        AlertDialog.Builder(this)
            .setTitle("📋 Activer MoMo Fin")
            .setMessage("Dans la liste 'Acces aux notifications' qui doit s'ouvrir :\n\n1. Defilez jusqu'a 'MoMo Fin'\n2. Touchez le toggle pour l'activer\n3. Confirmez 'Autoriser' dans le popup\n\nSI LE TOGGLE EST GRIS (Controle par parametre restreint) :\n→ Retournez a l'Etape 1 et touchez 'Annuler l'interdiction' dans Infos appli.")
            .setPositiveButton("Compris", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderSteps()  // raffraichir UI
    }
}
