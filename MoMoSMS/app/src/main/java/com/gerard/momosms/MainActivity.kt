package com.gerard.momosms

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.gerard.momosms.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SmsAdapter

    private val PREF = "momosms_settings"
    private val K_ASKED = "asked_perms"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        getPrefs().edit().putBoolean(K_ASKED, true).apply()
        if (result.values.all { it }) {
            hideBanner()
            importExistingSms()
            refresh()
        } else {
            showBanner()
        }
    }

    private fun getPrefs(): SharedPreferences =
        getSharedPreferences(PREF, MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = SmsAdapter(emptyList())
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnRefresh.setOnClickListener { refresh() }
        binding.btnImport.setOnClickListener {
            if (hasSmsPermission()) importExistingSms()
            else ensurePermissions()
        }
        binding.btnOpenAppSettings.setOnClickListener { openAppSettings() }

        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        if (hasSmsPermission()) {
            hideBanner()
            refresh()
        } else if (getPrefs().getBoolean(K_ASKED, false)) {
            showBanner()
        }
    }

    private fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        val needed = listOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            hideBanner()
            importExistingSms()
            refresh()
            return
        }

        // Si déjà demandé et toujours refusé → ouvrir les paramètres
        val alreadyAsked = getPrefs().getBoolean(K_ASKED, false)
        val canPrompt = needed.any { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
        if (alreadyAsked && !canPrompt) {
            showBanner()
            AlertDialog.Builder(this)
                .setTitle(R.string.perm_required_title)
                .setMessage(R.string.perm_required_message)
                .setPositiveButton(R.string.perm_btn_settings) { _, _ -> openAppSettings() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun showBanner() { binding.bannerPerms.visibility = View.VISIBLE }
    private fun hideBanner() { binding.bannerPerms.visibility = View.GONE }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun importExistingSms() {
        if (!hasSmsPermission()) { showBanner(); return }
        val store = MomoSmsStore(this)
        val uri = android.provider.Telephony.Sms.Inbox.CONTENT_URI
        val projection = arrayOf(
            android.provider.Telephony.Sms.ADDRESS,
            android.provider.Telephony.Sms.BODY,
            android.provider.Telephony.Sms.DATE
        )
        var inserted = 0
        contentResolver.query(uri, projection, null, null, "date DESC")?.use { c ->
            val iAddr = c.getColumnIndexOrThrow(android.provider.Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(android.provider.Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(android.provider.Telephony.Sms.DATE)
            while (c.moveToNext()) {
                val addr = c.getString(iAddr) ?: continue
                val body = c.getString(iBody) ?: continue
                val date = c.getLong(iDate)
                if (MomoFilter.isMomoSms(addr, body)) {
                    val op = MomoFilter.detectOperator(addr, body)
                    if (store.insert(addr, body, date, op) > 0) inserted++
                }
            }
        }
        store.close()
        binding.txtStatus.text = getString(R.string.imported_count, inserted)
    }

    private fun refresh() {
        if (!hasSmsPermission()) { showBanner(); return }
        val list = mutableListOf<SmsModel>()
        val store = MomoSmsStore(this)
        store.readableDatabase.query(
            MomoSmsStore.TABLE, null, null, null, null, null,
            "${MomoSmsStore.COL_TIMESTAMP} DESC"
        ).use { c ->
            val iId = c.getColumnIndexOrThrow(MomoSmsStore.COL_ID)
            val iAddr = c.getColumnIndexOrThrow(MomoSmsStore.COL_SENDER)
            val iBody = c.getColumnIndexOrThrow(MomoSmsStore.COL_BODY)
            val iTs = c.getColumnIndexOrThrow(MomoSmsStore.COL_TIMESTAMP)
            val iOp = c.getColumnIndexOrThrow(MomoSmsStore.COL_OPERATOR)
            while (c.moveToNext()) {
                list.add(
                    SmsModel(
                        id = c.getLong(iId),
                        sender = c.getString(iAddr) ?: "",
                        body = c.getString(iBody) ?: "",
                        timestamp = c.getLong(iTs),
                        operator = c.getString(iOp) ?: ""
                    )
                )
            }
        }
        store.close()
        adapter.submit(list)
        binding.txtCount.text = getString(R.string.total_sms, list.size)
    }
}
