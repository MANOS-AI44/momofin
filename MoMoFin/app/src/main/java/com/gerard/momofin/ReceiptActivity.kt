package com.gerard.momofin

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gerard.momofin.databinding.ActivityReceiptsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReceiptActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReceiptsBinding
    private lateinit var store: ReceiptStore
    private val nf = NumberFormat.getNumberInstance(Locale.FRENCH)
    private val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH)
    private val objetsPresets = arrayOf("Achat de cryptos", "Achat en Chine", "Achat de vêtements",
        "Achat de téléphone", "Transfert d'argent", "Autre")
    private var serverObjects: List<RailwayClient.ReceiptObject> = emptyList()
    private var receiptRulesDefault: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiptsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ReceiptStore(this)

        binding.btnNewReceipt.setOnClickListener { askNewReceipt() }
        binding.btnRestoreReceipts.setOnClickListener { restoreFromServer(true) }

        if (store.all().isEmpty() && Settings.isConfigured(this)) restoreFromServer(false)
        loadConfig()
        render()
    }

    override fun onResume() { super.onResume(); render() }

    private fun loadConfig() {
        if (!Settings.isConfigured(this)) return
        val url = Settings.getUrl(this); val token = Settings.getToken(this)
        CoroutineScope(Dispatchers.IO).launch {
            val cfg = RailwayClient.pullReceiptConfig(url, token)
            withContext(Dispatchers.Main) {
                serverObjects = cfg.objects
                receiptRulesDefault = cfg.rules
            }
        }
    }

    private fun render() {
        val container = binding.listReceipts
        container.removeAllViews()
        val list = store.all()
        if (list.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Aucun reçu pour l'instant. Touchez « Nouveau reçu »."
                setTextColor(0xFF888888.toInt())
                setPadding(12, 40, 12, 12)
            })
            return
        }
        for (r in list) {
            val row = TextView(this).apply {
                text = android.text.Html.fromHtml(
                    "<b>${r.clientName}</b>  <small><font color='#6B7280'>(touchez pour PDF/partage)</font></small><br/>" +
                    "<small>${df.format(Date(r.timestamp))} • ${r.objet}<br/>" +
                    "<font color='#1565C0'><b>${nf.format(r.amount)} ${r.currency}</b></font>" +
                    (if (r.partnerName.isNotBlank()) " • Partenaire : ${r.partnerName}" else "") +
                    "</small>",
                    android.text.Html.FROM_HTML_MODE_COMPACT
                )
                textSize = 13f
                setPadding(16, 14, 16, 14)
                setBackgroundColor(0xFFFFFFFF.toInt())
                isClickable = true
                setOnClickListener { openReceipt(r) }
                setOnLongClickListener { confirmDelete(r); true }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, 8)
            container.addView(row, lp)
        }
    }

    private fun askNewReceipt() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad/2, pad, 0) }

        val edtPartner = EditText(this).apply { hint = "Entreprise partenaire (optionnel)"; setSingleLine() }
        val edtClient = EditText(this).apply { hint = "Nom et prénom du client"; setSingleLine() }
        // Liste d'objets : ceux configures sur le serveur, sinon les presets
        val objetLabels = if (serverObjects.isNotEmpty()) serverObjects.map { it.label }.toTypedArray() else objetsPresets
        val acObjet = AutoCompleteTextView(this).apply {
            hint = "Objet (cryptos, Chine, vêtements...)"
            setAdapter(ArrayAdapter(this@ReceiptActivity, android.R.layout.simple_dropdown_item_1line, objetLabels))
            setSingleLine()
        }
        val edtAmount = EditText(this).apply { hint = "Montant (FCFA)"; inputType = InputType.TYPE_CLASS_NUMBER }

        layout.addView(edtPartner); layout.addView(edtClient); layout.addView(acObjet); layout.addView(edtAmount)

        AlertDialog.Builder(this)
            .setTitle("Nouveau reçu")
            .setView(layout)
            .setPositiveButton("Créer") { _, _ ->
                val client = edtClient.text.toString().trim()
                val objet = acObjet.text.toString().trim()
                val amount = edtAmount.text.toString().trim().replace(" ", "").replace(",", ".").toDoubleOrNull() ?: 0.0
                if (client.isBlank() || objet.isBlank()) {
                    Toast.makeText(this, "Renseignez au moins le client et l'objet", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val cond = serverObjects.firstOrNull { it.label.equals(objet, ignoreCase = true) }?.conditions
                    ?.ifBlank { receiptRulesDefault } ?: receiptRulesDefault
                store.create(edtPartner.text.toString().trim(), client, objet, cond, amount, "FCFA")
                render()
                autoBackup()
                Toast.makeText(this, "✅ Reçu créé", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun openReceipt(r: Receipt) {
        // Le PDF/partage sera branché au commit suivant. Pour l'instant, apercu texte.
        Toast.makeText(this, "Reçu de ${r.clientName} — PDF bientôt disponible", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(r: Receipt) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer le reçu ?")
            .setMessage("Reçu de ${r.clientName}")
            .setPositiveButton("Supprimer") { _, _ -> store.delete(r.id); render(); autoBackup() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun autoBackup() {
        if (!Settings.isConfigured(this)) return
        val url = Settings.getUrl(this); val token = Settings.getToken(this)
        CoroutineScope(Dispatchers.IO).launch { RailwayClient.syncReceipts(url, token, store) }
    }

    private fun restoreFromServer(manual: Boolean) {
        if (!Settings.isConfigured(this)) {
            if (manual) Toast.makeText(this, "Configurez d'abord le serveur (Paramètres)", Toast.LENGTH_LONG).show()
            return
        }
        val url = Settings.getUrl(this); val token = Settings.getToken(this)
        CoroutineScope(Dispatchers.IO).launch {
            val remote = RailwayClient.pullReceipts(url, token)
            withContext(Dispatchers.Main) {
                if (remote.isEmpty()) {
                    if (manual) Toast.makeText(this@ReceiptActivity, "Aucun reçu sauvegardé sur le serveur", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                store.replaceAllFromRemote(remote)
                render()
                Toast.makeText(this@ReceiptActivity, "✅ ${remote.size} reçu(s) restauré(s)", Toast.LENGTH_LONG).show()
            }
        }
    }
}
