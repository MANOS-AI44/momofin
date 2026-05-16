package com.gerard.momofin

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gerard.momofin.databinding.ActivityLoginBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var isSignup = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Si déjà connecté → bascule directement vers MainActivity
        if (Settings.isConfigured(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.edtUrl.setText(
            Settings.getUrl(this).ifBlank { AuthClient.defaultUrl() }
        )

        binding.btnSwitchMode.setOnClickListener { toggleMode() }
        binding.btnSubmit.setOnClickListener { submit() }
        binding.btnSkip.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        updateUi()
    }

    private fun toggleMode() {
        isSignup = !isSignup
        updateUi()
    }

    private fun updateUi() {
        if (isSignup) {
            binding.txtTitle.text = getString(R.string.signup_title)
            binding.btnSubmit.text = getString(R.string.signup_submit)
            binding.btnSwitchMode.text = getString(R.string.signup_switch_to_login)
            binding.edtName.visibility = View.VISIBLE
            binding.lblName.visibility = View.VISIBLE
        } else {
            binding.txtTitle.text = getString(R.string.login_title)
            binding.btnSubmit.text = getString(R.string.login_submit)
            binding.btnSwitchMode.text = getString(R.string.login_switch_to_signup)
            binding.edtName.visibility = View.GONE
            binding.lblName.visibility = View.GONE
        }
    }

    private fun submit() {
        val url = binding.edtUrl.text.toString().trim().removeSuffix("/")
        val email = binding.edtEmail.text.toString().trim()
        val password = binding.edtPassword.text.toString()
        val name = binding.edtName.text.toString().trim()

        if (url.isBlank() || !url.startsWith("http")) {
            Toast.makeText(this, R.string.settings_url_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        if (email.isBlank() || !email.contains("@")) {
            Toast.makeText(this, R.string.login_email_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        if (password.length < 6) {
            Toast.makeText(this, R.string.login_password_short, Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnSubmit.isEnabled = false
        binding.txtStatus.text = getString(R.string.login_in_progress)

        CoroutineScope(Dispatchers.IO).launch {
            val result = if (isSignup) AuthClient.signup(url, email, password, name)
                         else AuthClient.login(url, email, password)
            withContext(Dispatchers.Main) {
                binding.btnSubmit.isEnabled = true
                if (result.ok && !result.token.isNullOrBlank()) {
                    Settings.save(this@LoginActivity, url, result.token)
                    binding.txtStatus.text = "✅ ${result.message}"
                    Toast.makeText(this@LoginActivity, R.string.login_success, Toast.LENGTH_LONG).show()
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    binding.txtStatus.text = "❌ ${result.message}"
                    Toast.makeText(this@LoginActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
