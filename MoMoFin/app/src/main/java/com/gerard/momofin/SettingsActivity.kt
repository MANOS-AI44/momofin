package com.gerard.momofin

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gerard.momofin.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.edtUrl.setText(Settings.getUrl(this))
        binding.edtToken.setText(Settings.getToken(this))

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
}
