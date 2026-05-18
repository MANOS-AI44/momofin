package com.gerard.momofin

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
    private var filterDayMillis: Long? = null
    private var searchQuery: String = ""

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
        binding.btnPoints.setOnClickListener {
            startActivity(Intent(this, PointsActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnDevices.setOnClickListener {
            if (Settings.isAdmin(this)) {
                startActivity(Intent(this, DevicesActivity::class.java))
            } else {
                Toast.makeText(this, "🔒 Réservé à l'administrateur", Toast.LENGTH_LONG).show()
            }
        }
        // Cacher btnDevices si pas admin (sous-compte)
        if (!Settings.isAdmin(this)) {
            binding.btnDevices.visibility = View.GONE
        }
        binding.btnSync.setOnClickListener { syncToRailway() }

        binding.btnPickDate.setOnClickListener { pickDate() }
        binding.btnClearDate.setOnClickListener {
            filterDayMillis = null
            refreshDateUi()
            renderList()
        }

        // Recherche par numero / reference
        binding.edtSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = (s?.toString() ?: "").trim()
                binding.btnClearSearch.visibility = if (searchQuery.isEmpty()) View.GONE else View.VISIBLE
                renderList()
            }
        })
        binding.btnClearSearch.setOnClickListener {
            binding.edtSearch.setText("")
        }

        binding.btnNotifAccess.setOnClickListener {
            showNotificationAccessGuide()
        }
        binding.btnOpenAppSettings.setOnClickListener {
            PermissionHelper.openAppSettings(this)
        }
        binding.btnRequestPerms.setOnClickListener {
            askSmsPermissions()
        }
        binding.btnDismissNotif.setOnClickListener {
            Settings.setBannerDismissed(this, "notif")
            updateBanners()
        }
        binding.btnDismissPerms.setOnClickListener {
            Settings.setBannerDismissed(this, "sms")
            updateBanners()
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
        val notifDismissed = Settings.isBannerDismissed(this, "notif")
        val smsDismissed = Settings.isBannerDismissed(this, "sms")

        binding.bannerNotifAccess.visibility =
            if (notifOk || notifDismissed) View.GONE else View.VISIBLE
        binding.bannerPerms.visibility =
            if (smsOk || notifOk || smsDismissed) View.GONE else View.VISIBLE
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

    
    private fun pickDate() {
        val c = Calendar.getInstance()
        filterDayMillis?.let { c.timeInMillis = it }
        DatePickerDialog(this, { _, y, m, d ->
            val cal = Calendar.getInstance().apply {
                set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0)
            }
            filterDayMillis = cal.timeInMillis
            refreshDateUi()
            renderList()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun refreshDateUi() {
        val df = SimpleDateFormat("EEEE dd MMMM yyyy", Locale.FRENCH)
        if (filterDayMillis != null) {
            binding.txtSelectedDate.text = "Date : " + df.format(Date(filterDayMillis!!))
                .replaceFirstChar { it.uppercase() }
            binding.btnClearDate.visibility = View.VISIBLE
        } else {
            binding.txtSelectedDate.text = getString(R.string.date_all)
            binding.btnClearDate.visibility = View.GONE
        }
    }

    private fun renderList() {
        var list = if (filterDayMillis == null) current
                   else current.filter { TransactionParser.dayKey(it.timestamp) == filterDayMillis }
        if (searchQuery.isNotEmpty()) {
            val q = searchQuery.lowercase()
            list = list.filter {
                it.phoneNumber.lowercase().contains(q) ||
                it.reference.lowercase().contains(q) ||
                it.operator.lowercase().contains(q)
            }
        }
        adapter.submit(list)
        val recu = list.filter { it.type == TxType.RECU }.sumOf { it.amount }
        val sortie = list.filter { it.type == TxType.SORTIE }.sumOf { it.amount }
        binding.txtSummary.text = getString(
            R.string.summary_format, list.size, recu, sortie, recu - sortie
        )
    }

    private fun loadData() {
        CoroutineScope(Dispatchers.IO).launch {
            val raws = if (Settings.isPrimaryDevice(this@MainActivity))
                SmsSource.loadAll(this@MainActivity)
            else
                RailwayClient.pullTransactions(Settings.getUrl(this@MainActivity), Settings.getToken(this@MainActivity))
            // Parse strict : ignore les SMS qui ne matchent aucun des 6 patterns canoniques
            val txs = raws.mapNotNull {
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
                refreshDateUi()
                renderList()
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
        val filtered = if (filterDayMillis == null) current
                       else current.filter { TransactionParser.dayKey(it.timestamp) == filterDayMillis }
        if (filtered.isEmpty()) {
            Toast.makeText(this, R.string.no_data_for_date, Toast.LENGTH_SHORT).show()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val file = PdfGenerator.generateDailyReport(
                this@MainActivity, filtered, FolderStore(this@MainActivity),
                singleDayMillis = filterDayMillis
            )
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
