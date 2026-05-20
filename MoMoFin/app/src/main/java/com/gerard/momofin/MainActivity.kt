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
    private var filterFromMillis: Long? = null
    private var filterToMillis: Long? = null
    private var searchQuery: String = ""
    private var subtypeFilter: TxSubtype? = null  // null = Tout, ou subtype precis
    private var groupFilter: String? = null        // null, 'CAISSE' ou 'TRANSFERTS'

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

        adapter = DailyAdapter { tx ->
            val i = Intent(this, TransactionDetailActivity::class.java)
            i.putExtra("tx_amount", tx.amount)
            i.putExtra("tx_currency", tx.currency)
            i.putExtra("tx_type", tx.type.name)
            i.putExtra("tx_subtype", tx.subtype.name)
            i.putExtra("tx_operator", tx.operator)
            i.putExtra("tx_phone", tx.phoneNumber)
            i.putExtra("tx_reference", tx.reference)
            i.putExtra("tx_timestamp", tx.timestamp)
            i.putExtra("tx_sender", tx.rawSender)
            i.putExtra("tx_body", tx.rawBody)
            startActivity(i)
        }
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
        binding.btnOpenWeb.setOnClickListener {
            val url = Settings.getUrl(this).ifBlank {
                Toast.makeText(this, "⚠️ Configurez d'abord l'URL dans Parametres", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, SettingsActivity::class.java))
                return@setOnClickListener
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, "URL invalide : $url", Toast.LENGTH_LONG).show()
            }
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
            filterFromMillis = null
            filterToMillis = null
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

        // === Chips de filtre ===
        // chipAll -> null/null, chipCaisse -> group CAISSE, chipTransferts -> group TRANSFERTS
        // chipDepot/Retrait -> subtype precis (DEPOT/RETRAIT)
        // chipTEnvoye/TRecu -> subtype precis (TRANSFERT_ENVOYE/TRANSFERT_RECU)
        val allChips = listOf(
            binding.chipAll, binding.chipCaisse, binding.chipTransferts,
            binding.chipDepot, binding.chipRetrait, binding.chipTEnvoye, binding.chipTRecu
        )
        fun updateChips() {
            for (c in allChips) {
                val isActive = when (c.id) {
                    R.id.chipAll -> subtypeFilter == null && groupFilter == null
                    R.id.chipCaisse -> groupFilter == "CAISSE"
                    R.id.chipTransferts -> groupFilter == "TRANSFERTS"
                    R.id.chipDepot -> subtypeFilter == TxSubtype.DEPOT
                    R.id.chipRetrait -> subtypeFilter == TxSubtype.RETRAIT
                    R.id.chipTEnvoye -> subtypeFilter == TxSubtype.TRANSFERT_ENVOYE
                    R.id.chipTRecu -> subtypeFilter == TxSubtype.TRANSFERT_RECU
                    else -> false
                }
                c.setBackgroundResource(if (isActive) R.drawable.chip_active else R.drawable.chip_inactive)
                c.setTextColor(if (isActive) 0xFFFFFFFF.toInt() else 0xFF1565C0.toInt())
            }
        }
        binding.chipAll.setOnClickListener { subtypeFilter = null; groupFilter = null; updateChips(); renderList() }
        binding.chipCaisse.setOnClickListener { subtypeFilter = null; groupFilter = "CAISSE"; updateChips(); renderList() }
        binding.chipTransferts.setOnClickListener { subtypeFilter = null; groupFilter = "TRANSFERTS"; updateChips(); renderList() }
        binding.chipDepot.setOnClickListener { subtypeFilter = TxSubtype.DEPOT; groupFilter = null; updateChips(); renderList() }
        binding.chipRetrait.setOnClickListener { subtypeFilter = TxSubtype.RETRAIT; groupFilter = null; updateChips(); renderList() }
        binding.chipTEnvoye.setOnClickListener { subtypeFilter = TxSubtype.TRANSFERT_ENVOYE; groupFilter = null; updateChips(); renderList() }
        binding.chipTRecu.setOnClickListener { subtypeFilter = TxSubtype.TRANSFERT_RECU; groupFilter = null; updateChips(); renderList() }
        updateChips()

        binding.btnNotifAccess.setOnClickListener {
            startActivity(Intent(this, PermissionWizardActivity::class.java))
        }
        binding.btnOpenAppSettings.setOnClickListener {
            PermissionHelper.openAppSettings(this)
        }
        binding.btnRequestPerms.setOnClickListener {
            startActivity(Intent(this, PermissionWizardActivity::class.java))
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
        checkRestrictions()
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
        val c1 = Calendar.getInstance()
        filterFromMillis?.let { c1.timeInMillis = it }
        val startPicker = DatePickerDialog(this, { _, y1, m1, d1 ->
            val from = Calendar.getInstance().apply { set(y1, m1, d1, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
            val c2 = Calendar.getInstance().apply { timeInMillis = from.timeInMillis }
            val endPicker = DatePickerDialog(this, { _, y2, m2, d2 ->
                val to = Calendar.getInstance().apply { set(y2, m2, d2, 23, 59, 59); set(Calendar.MILLISECOND, 999) }
                filterFromMillis = from.timeInMillis
                filterToMillis = to.timeInMillis
                refreshDateUi()
                renderList()
            }, c2.get(Calendar.YEAR), c2.get(Calendar.MONTH), c2.get(Calendar.DAY_OF_MONTH))
            endPicker.setTitle("Date de fin")
            endPicker.show()
        }, c1.get(Calendar.YEAR), c1.get(Calendar.MONTH), c1.get(Calendar.DAY_OF_MONTH))
        startPicker.setTitle("Date de debut")
        startPicker.show()
    }

    private fun refreshDateUi() {
        val dfShort = SimpleDateFormat("dd/MM/yy", Locale.FRENCH)
        val txt = if (filterFromMillis != null && filterToMillis != null) {
            val sameDay = TransactionParser.dayKey(filterFromMillis!!) == TransactionParser.dayKey(filterToMillis!!)
            if (sameDay) "📅 " + dfShort.format(Date(filterFromMillis!!))
            else "📅 " + dfShort.format(Date(filterFromMillis!!)) + " → " + dfShort.format(Date(filterToMillis!!))
        } else "📅 Période"
        binding.btnPickDate.text = txt
        binding.btnClearDate.visibility = if (filterFromMillis != null) View.VISIBLE else View.GONE
        binding.txtSelectedDate.text = txt
    }

    private fun renderList() {
        var list = if (filterFromMillis == null) current
                   else current.filter { it.timestamp >= filterFromMillis!! && (filterToMillis == null || it.timestamp <= filterToMillis!!) }
        if (subtypeFilter != null) {
            list = list.filter { it.subtype == subtypeFilter }
        } else if (groupFilter == "CAISSE") {
            list = list.filter { it.subtype == TxSubtype.DEPOT || it.subtype == TxSubtype.RETRAIT }
        } else if (groupFilter == "TRANSFERTS") {
            list = list.filter { it.subtype == TxSubtype.TRANSFERT_ENVOYE || it.subtype == TxSubtype.TRANSFERT_RECU }
        }
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
            val txs: List<Transaction> = if (Settings.isPrimaryDevice(this@MainActivity)) {
                val raws = SmsSource.loadAll(this@MainActivity)
                // Parse strict : ignore les SMS qui ne matchent aucun des 6 patterns canoniques
                raws.mapNotNull {
                    TransactionParser.parse(
                        rawId = it.id,
                        sender = it.sender,
                        body = it.body,
                        smsTimestamp = it.timestamp,
                        operator = it.operator
                    )
                }
            } else {
                // Sous-compte : on recupere les Transaction deja parsees cote serveur
                RailwayClient.pullTransactionsAsTx(
                    Settings.getUrl(this@MainActivity),
                    Settings.getToken(this@MainActivity)
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
            // Pousser aussi les dossiers Mes Comptes vers le serveur
            val folderStore = FolderStore(this@MainActivity)
            val resF = RailwayClient.syncFolders(url, token, folderStore)
            withContext(Dispatchers.Main) {
                binding.btnSync.isEnabled = true
                if (res.ok) Settings.setLastSync(this@MainActivity, System.currentTimeMillis())
                val combined = (if (res.ok) "✅ " else "❌ ") + res.message +
                               "\n" + (if (resF.ok) "✅ " else "⚠️ ") + resF.message
                Toast.makeText(this@MainActivity, combined, Toast.LENGTH_LONG).show()
                loadData()
            }
        }
    }

    private fun generatePdf() {
        if (current.isEmpty()) {
            Toast.makeText(this, R.string.no_data, Toast.LENGTH_SHORT).show()
            return
        }
        val filtered = if (filterFromMillis == null) current
                       else current.filter { it.timestamp >= filterFromMillis!! && (filterToMillis == null || it.timestamp <= filterToMillis!!) }
        if (filtered.isEmpty()) {
            Toast.makeText(this, R.string.no_data_for_date, Toast.LENGTH_SHORT).show()
            return
        }
        val singleDay = if (filterFromMillis != null && filterToMillis != null
            && TransactionParser.dayKey(filterFromMillis!!) == TransactionParser.dayKey(filterToMillis!!)) filterFromMillis else null
        CoroutineScope(Dispatchers.IO).launch {
            val file = PdfGenerator.generateDailyReport(
                this@MainActivity, filtered, FolderStore(this@MainActivity),
                singleDayMillis = singleDay
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
    private fun checkRestrictions() {
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        val needsWizard = !pm.isIgnoringBatteryOptimizations(packageName)
                || !PermissionHelper.hasSmsPermission(this)
                || !PermissionHelper.hasNotificationAccess(this)
        if (needsWizard) {
            val prefs = getSharedPreferences("restrictions", android.content.Context.MODE_PRIVATE)
            if (!prefs.getBoolean("wizard_shown", false)) {
                AlertDialog.Builder(this)
                    .setTitle("⚙️ Activer les SMS")
                    .setMessage("MoMo Fin a besoin de quelques autorisations pour lire les SMS Orange/MTN/MOOV. Ouvrir l\'assistant ?")
                    .setPositiveButton("Ouvrir l\'assistant") { _, _ ->
                        prefs.edit().putBoolean("wizard_shown", true).apply()
                        startActivity(Intent(this, PermissionWizardActivity::class.java))
                    }
                    .setNegativeButton("Plus tard", null)
                    .show()
            }
        }
    }

}
