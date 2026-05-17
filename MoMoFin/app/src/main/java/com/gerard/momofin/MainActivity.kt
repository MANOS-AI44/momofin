package com.gerard.momofin

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.gerard.momofin.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: DailyAdapter
    private var current: List<Transaction> = emptyList()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        Settings.setAskedPermissions(this)
        if (result.values.any { !it }) {
            // L'utilisateur a refusé → afficher la bannière + bouton Paramètres
            showPermissionBanner()
        } else {
            hidePermissionBanner()
        }
        loadData()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = DailyAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnRefresh.setOnClickListener { loadData() }
        binding.btnPdf.setOnClickListener { generatePdf() }
        binding.btnPatron.setOnClickListener {
            startActivity(Intent(this, PatronActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnSync.setOnClickListener { syncToRailway() }

        binding.btnOpenAppSettings.setOnClickListener {
            PermissionHelper.openAppSettings(this)
        }
        binding.btnRequestPerms.setOnClickListener {
            requestPermissions()
        }

        ensurePermissionsAndLoad()
    }

    override fun onResume() {
        super.onResume()
        // Re-vérifier en revenant des paramètres système
        if (PermissionHelper.hasSmsPermission(this)) {
            hidePermissionBanner()
            loadData()
        } else if (Settings.hasAskedPermissions(this)) {
            showPermissionBanner()
        }
    }

    private fun ensurePermissionsAndLoad() {
        if (PermissionHelper.hasSmsPermission(this)) {
            hidePermissionBanner()
            loadData()
        } else {
            requestPermissions()
        }
    }

    private fun requestPermissions() {
        if (PermissionHelper.shouldShowSettings(this)) {
            // L'utilisateur a déjà refusé → on l'invite à passer par les Paramètres
            showPermissionBanner()
            AlertDialog.Builder(this)
                .setTitle(R.string.perm_required_title)
                .setMessage(R.string.perm_required_message)
                .setPositiveButton(R.string.perm_open_settings) { _, _ ->
                    PermissionHelper.openAppSettings(this)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            permLauncher.launch(PermissionHelper.REQUIRED_PERMS)
        }
    }

    private fun showPermissionBanner() {
        binding.bannerPerms.visibility = View.VISIBLE
    }

    private fun hidePermissionBanner() {
        binding.bannerPerms.visibility = View.GONE
    }

    private fun loadData() {
        CoroutineScope(Dispatchers.IO).launch {
            val raws = SmsSource.loadAll(this@MainActivity)
            val txs = raws.map {
                TransactionParser.parse(
                    rawId = it.id,
                    sender = it.sender,
                    body = it.body,
                    smsTimestamp = it.timestamp,
                    operator = it.operator
                )
            }
            withContext(Dispatchers.Main) {
                current = txs
                adapter.submit(txs)
                val recu = txs.filter { it.type == TxType.RECU }.sumOf { it.amount }
                val sortie = txs.filter { it.type == TxType.SORTIE }.sumOf { it.amount }
                binding.txtSummary.text = getString(
                    R.string.summary_format,
                    txs.size, recu, sortie, recu - sortie
                )
            }
        }
    }

    private fun syncToRailway() {
        if (!Settings.isConfigured(this)) {
            Toast.makeText(this, R.string.sync_not_configured, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (current.isEmpty()) {
            Toast.makeText(this, R.string.no_data, Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnSync.isEnabled = false
        binding.txtSummary.text = getString(R.string.syncing)
        CoroutineScope(Dispatchers.IO).launch {
            val url = Settings.getUrl(this@MainActivity)
            val token = Settings.getToken(this@MainActivity)
            val res = RailwayClient.syncTransactions(url, token, current)
            withContext(Dispatchers.Main) {
                binding.btnSync.isEnabled = true
                if (res.ok) Settings.setLastSync(this@MainActivity, System.currentTimeMillis())
                Toast.makeText(
                    this@MainActivity,
                    (if (res.ok) "✅ " else "❌ ") + res.message,
                    Toast.LENGTH_LONG
                ).show()
                loadData()
            }
        }
    }

    private fun generatePdf() {
        if (current.isEmpty()) {
            Toast.makeText(this, R.string.no_data, Toast.LENGTH_SHORT).show()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val file = PdfGenerator.generateDailyReport(this@MainActivity, current, FolderStore(this@MainActivity))
            val uri: Uri = FileProvider.getUriForFile(
                this@MainActivity,
                "${applicationContext.packageName}.fileprovider",
                file
            )
            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(Intent.createChooser(intent, getString(R.string.open_pdf)))
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, R.string.no_pdf_viewer, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
