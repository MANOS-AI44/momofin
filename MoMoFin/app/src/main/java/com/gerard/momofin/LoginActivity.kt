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
    private enum class State { CHOICE, SIGNUP, LOGIN, CODE }
    private var state = State.CHOICE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Settings.isConfigured(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // URL serveur par défaut — invisible
        if (Settings.getUrl(this).isBlank()) {
            Settings.save(this, AuthClient.defaultUrl(), "")
        }

        binding.btnChoiceSignup.setOnClickListener { showSignup() }
        binding.btnChoiceLogin.setOnClickListener { showLogin() }
        binding.btnBackSignup.setOnClickListener { showChoice() }
        binding.btnBackLogin.setOnClickListener { showChoice() }
        binding.btnSignupSubmit.setOnClickListener { doSignup() }
        binding.btnLoginSubmit.setOnClickListener { doLogin() }
        binding.btnGotoLogin.setOnClickListener { showLogin() }
        binding.btnGotoSignup.setOnClickListener { showSignup() }
        binding.btnChoiceCode.setOnClickListener { showCodeEntry() }
        binding.btnBackCode.setOnClickListener { showChoice() }
        binding.btnCodeSubmit.setOnClickListener { doClaimCode() }

        showChoice()
    }

    private fun showChoice() {
        state = State.CHOICE
        binding.layoutChoice.visibility = View.VISIBLE
        binding.layoutSignup.visibility = View.GONE
        binding.layoutLogin.visibility = View.GONE
        binding.layoutCode.visibility = View.GONE
    }

    private fun showSignup() {
        state = State.SIGNUP
        binding.layoutChoice.visibility = View.GONE
        binding.layoutSignup.visibility = View.VISIBLE
        binding.layoutLogin.visibility = View.GONE
        binding.layoutCode.visibility = View.GONE
    }

    private fun showCodeEntry() {
        state = State.CODE
        binding.layoutChoice.visibility = View.GONE
        binding.layoutSignup.visibility = View.GONE
        binding.layoutLogin.visibility = View.GONE
        binding.layoutCode.visibility = View.VISIBLE
    }

    private fun showLogin() {
        state = State.LOGIN
        binding.layoutChoice.visibility = View.GONE
        binding.layoutSignup.visibility = View.GONE
        binding.layoutLogin.visibility = View.VISIBLE
        binding.layoutCode.visibility = View.GONE
    }

    override fun onBackPressed() {
        if (state != State.CHOICE) showChoice() else super.onBackPressed()
    }

    private fun doSignup() {
        val email = binding.edtSignupEmail.text.toString().trim()
        val password = binding.edtSignupPassword.text.toString()
        val name = binding.edtSignupName.text.toString().trim()

        if (email.isBlank() || !email.contains("@")) {
            Toast.makeText(this, R.string.login_email_invalid, Toast.LENGTH_SHORT).show(); return
        }
        if (password.length < 6) {
            Toast.makeText(this, R.string.login_password_short, Toast.LENGTH_SHORT).show(); return
        }

        binding.btnSignupSubmit.isEnabled = false
        binding.txtSignupStatus.text = getString(R.string.login_in_progress)

        val url = Settings.getUrl(this).ifBlank { AuthClient.defaultUrl() }
        CoroutineScope(Dispatchers.IO).launch {
            val r = AuthClient.signup(url, email, password, name)
            withContext(Dispatchers.Main) {
                binding.btnSignupSubmit.isEnabled = true
                if (r.ok && !r.token.isNullOrBlank()) {
                    Settings.save(this@LoginActivity, url, r.token)
                    Settings.setAdmin(this@LoginActivity, true)
                    Toast.makeText(this@LoginActivity, R.string.login_success, Toast.LENGTH_LONG).show()
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    binding.txtSignupStatus.text = "❌ ${r.message}"
                }
            }
        }
    }

    private fun doClaimCode() {
        val code = binding.edtCode.text.toString().trim().uppercase()
        if (code.length < 4) {
            Toast.makeText(this, "Code invalide", Toast.LENGTH_SHORT).show(); return
        }
        binding.btnCodeSubmit.isEnabled = false
        binding.txtCodeStatus.text = getString(R.string.login_in_progress)
        val url = Settings.getUrl(this).ifBlank { AuthClient.defaultUrl() }
        CoroutineScope(Dispatchers.IO).launch {
            val r = AuthClient.claimCode(url, code)
            withContext(Dispatchers.Main) {
                binding.btnCodeSubmit.isEnabled = true
                if (r.ok && !r.token.isNullOrBlank()) {
                    Settings.save(this@LoginActivity, url, r.token)
                    Settings.setAdmin(this@LoginActivity, false)
                    Toast.makeText(this@LoginActivity, R.string.login_success, Toast.LENGTH_LONG).show()
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    binding.txtCodeStatus.text = "❌ ${r.message}"
                }
            }
        }
    }

    private fun doLogin() {
        val email = binding.edtLoginEmail.text.toString().trim()
        val password = binding.edtLoginPassword.text.toString()

        if (email.isBlank() || !email.contains("@")) {
            Toast.makeText(this, R.string.login_email_invalid, Toast.LENGTH_SHORT).show(); return
        }
        if (password.isBlank()) {
            Toast.makeText(this, R.string.login_password_short, Toast.LENGTH_SHORT).show(); return
        }

        binding.btnLoginSubmit.isEnabled = false
        binding.txtLoginStatus.text = getString(R.string.login_in_progress)

        val url = Settings.getUrl(this).ifBlank { AuthClient.defaultUrl() }
        CoroutineScope(Dispatchers.IO).launch {
            val r = AuthClient.login(url, email, password)
            withContext(Dispatchers.Main) {
                binding.btnLoginSubmit.isEnabled = true
                if (r.ok && !r.token.isNullOrBlank()) {
                    Settings.save(this@LoginActivity, url, r.token)
                    Settings.setAdmin(this@LoginActivity, true)
                    Toast.makeText(this@LoginActivity, R.string.login_success, Toast.LENGTH_LONG).show()
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    binding.txtLoginStatus.text = "❌ ${r.message}"
                }
            }
        }
    }
}
