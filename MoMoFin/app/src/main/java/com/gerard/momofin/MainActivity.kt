package com.gerard.momofin

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
    ) { _ ->
        Settings.setAskedPermissions(this)
        updateBanners()
        loadData()
    }

    private val postNotifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> updateBanners() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = DailyAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.txtAndroidVersion.text = "Téléphone : ${PermissionHelper.androidVersionLabel()}"

        binding.btnRefresh.setOnClickListener { loadData() }
        binding.btnPdf.setOnClickListener { generatePdf() }
        binding.btnPatron.setOnClickListener {
            startActivity(Intent(this, PatronActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnSync.setOnClickListener { syncToRailway() }

        binding.btnNotifAccess.setOnClickListener {
            showNotificationAccessGuide()
        }
        binding.btnOpenAppSettings.setOnClickListener {
            PermissionHelper.openAppSettings(this)
        }
        binding.btnRequestPerms.setOnClickListener {
            askSmsPermissions()
        }

        // Sur Android 13+, demander POST_NOTIFICATIONS au premier lancement
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionHelper.hasPostNotificationsPermission(this)) {
            postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        askSmsPermissions()
        loadData()
    }

    override fun onResume() {
        super.onResume()
        updateBanners()
        loadData()
    }

    private fun askSmsPermissions() {
        if (PermissionHelper.hasSmsPermission(this)) {
            updateBanners()
            return
        }
        if (PermissionHelper.shouldShowSmsSettings(this)) {
            updateBanners()
        } else {
            permLauncher.launch(PermissionHelper.SMS_PERMS)
        }
    }

    /**
     * Met à jour la visibilité des bannières :
     *  - Bannière "Notifications" toujours visible si l'accès n'est PAS accordé (c'est la méthode
     *    qui marche sur Android 13+/14/15/16)
     *  - Bannière "SMS" visible uniquement si SMS refusé ET l'accès notifs n'est pas accordé non plus
     */
    private fun updateBanners() {
        val notifOk = PermissionHelper.hasNotificationAccess(this)
        val smsOk = PermissionHelper.hasSmsPermission(this)

        binding.bannerNotifAccess.visibility = if (notifOk) View.GONE else View.VISIBLE
        binding.bannerPerms.visibility = if (smsOk || notifOk) View.GONE else View.VISIBLE
    }

    private fun showNotificationAccessGuide() {
        AlertDialog.Builder(this)
            .setTitle(R.string.notif_access_guide_title)
            .setMessage(R.string.notif_access_guide_msg)
            .setPositiveButton(R.string.notif_access_btn_open) { _, _ ->
                PermissionHelper.openNotificationListenerSettings(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
