package com.gerard.momosms

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.gerard.momosms.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SmsAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            importExistingSms()
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = SmsAdapter(emptyList())
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnRefresh.setOnClickListener { refresh() }
        binding.btnImport.setOnClickListener { importExistingSms() }

        ensurePermissions()
    }

    private fun ensurePermissions() {
        val needed = listOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            importExistingSms()
            refresh()
        }
    }

    /**
     * Lit la boîte SMS du téléphone et importe les SMS Mobile Money trouvés
     * dans la base locale partagée.
     */
    private fun importExistingSms() {
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
