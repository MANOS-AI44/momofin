package com.gerard.momofin

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gerard.momofin.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.edtUrl.setText(Settings.getUrl(this))
        binding.edtToken.setText(Settings.getToken(this))

        binding.btnToggleAdvanced.setOnClickListener {
            binding.advancedZone.visibility =
                if (binding.advancedZone.visibility == android.view.View.GONE) android.view.View.VISIBLE
                else android.view.View.GONE
        }

        binding.btnSave.setOnClickListener {
            val url = binding.edtUrl.text.toString().trim()
            val token = binding.edtToken.text.toString().trim()
            if (url.isBlank() || token.isBlank()) {
                Toast.makeText(this, R.string.settings_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Toast.makeText(this, R.string.settings_url_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Settings.save(this, url, token)
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }

        binding.btnTest.setOnClickListener { testConnection() }
        binding.switchPrimary.isChecked = Settings.isPrimaryDevice(this)
        binding.switchPrimary.setOnCheckedChangeListener { _, checked ->
            Settings.setPrimaryDevice(this, checked)
            Toast.makeText(this,
                if (checked) "Ce téléphone capte les SMS"
                else "Ce téléphone consulte seulement (pas de capture SMS)",
                Toast.LENGTH_LONG).show()
        }

        binding.btnImportInbox.setOnClickListener { doImportInbox() }
        binding.btnReset.setOnClickListener { confirmReset() }

        // Compte connecte + deconnexion
        val curUrl = Settings.getUrl(this)
        val curToken = Settings.getToken(this)
        binding.txtCurrentUser.text = if (curToken.isBlank()) "Aucun compte connecte" else "Connecte : $curUrl"
        binding.btnLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Se deconnecter")
                .setMessage("Voulez-vous vraiment vous deconnecter ?")
                .setPositiveButton("Oui") { _, _ ->
                    Settings.clearAuth(this)
                    Toast.makeText(this, "Deconnecte", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, LoginActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    private fun testConnection() {
        val url = binding.edtUrl.text.toString().trim().removeSuffix("/")
        val token = binding.edtToken.text.toString().trim()
        if (url.isBlank() || token.isBlank()) {
            Toast.makeText(this, R.string.settings_required, Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnTest.isEnabled = false
        binding.txtResult.text = getString(R.string.testing)
        CoroutineScope(Dispatchers.IO).launch {
            val r = RailwayClient.ping(url, token)
            withContext(Dispatchers.Main) {
                binding.btnTest.isEnabled = true
                binding.txtResult.text = if (r.ok) "✅ ${r.message}" else "❌ ${r.message}"
            }
        }
    }

    private fun doImportInbox() {
        Toast.makeText(this, "Importation en cours…", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            val count = SmsSource.importInbox(this@SettingsActivity)
            withContext(Dispatchers.Main) {
                val msg = when {
                    count < 0 -> "Permission SMS refusée. Activez-la dans les Paramètres Android."
                    count == 0 -> "Aucun SMS Mobile Money trouvé dans la boîte."
                    else -> "✅ $count SMS Mobile Money importés depuis la boîte."
                }
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Importation terminée")
                    .setMessage(msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_title)
            .setMessage(R.string.reset_message)
            .setPositiveButton(R.string.reset_confirm) { _, _ -> doReset() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doReset() {
        try {
            // Vider toutes les bases SQLite locales
            for (db in listOf("notif_sms.db", "patron_folders.db", "momo_sms.db")) {
                deleteDatabase(db)
            }
            // Vider les SharedPreferences
            getSharedPreferences("momofin_settings", MODE_PRIVATE).edit().clear().apply()
            getSharedPreferences("momosms_settings", MODE_PRIVATE).edit().clear().apply()

            // Vider les PDF générés
            File(filesDir, "pdfs").listFiles()?.forEach { it.delete() }

            Toast.makeText(this, R.string.reset_done, Toast.LENGTH_LONG).show()

            // Retour à l'écran de login (état "nouvelle installation")
            val intent = Intent(this, LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            finishAffinity()
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
