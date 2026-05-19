package com.gerard.momofin

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.gerard.momofin.databinding.ActivityTransactionDetailBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TransactionDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTransactionDetailBinding
    private val nf = NumberFormat.getNumberInstance(Locale.FRENCH)
    private val dfFull = SimpleDateFormat("EEEE dd MMMM yyyy 'à' HH:mm:ss", Locale.FRENCH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransactionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val amount = intent.getDoubleExtra("tx_amount", 0.0)
        val currency = intent.getStringExtra("tx_currency") ?: "FCFA"
        val typeStr = intent.getStringExtra("tx_type") ?: "INCONNU"
        val subtypeStr = intent.getStringExtra("tx_subtype") ?: "INCONNU"
        val operator = intent.getStringExtra("tx_operator") ?: "—"
        val phone = intent.getStringExtra("tx_phone") ?: ""
        val ref = intent.getStringExtra("tx_reference") ?: ""
        val ts = intent.getLongExtra("tx_timestamp", System.currentTimeMillis())
        val sender = intent.getStringExtra("tx_sender") ?: "—"
        val body = intent.getStringExtra("tx_body") ?: ""

        val subtype = try { TxSubtype.valueOf(subtypeStr) } catch (_: Exception) { TxSubtype.INCONNU }
        val type = try { TxType.valueOf(typeStr) } catch (_: Exception) { TxType.INCONNU }

        // Bandeau type
        binding.txtTypeBig.text = subtype.label().uppercase()
        binding.txtTypeBig.setBackgroundResource(
            if (type == TxType.RECU) R.drawable.badge_retrait else R.drawable.badge_depot
        )
        binding.txtTypeBig.setTextColor(getColor(
            if (type == TxType.RECU) R.color.badge_recu_text else R.color.badge_sortie_text
        ))

        // Montant
        binding.txtAmountBig.text = "${nf.format(amount)} $currency"
        binding.txtAmountBig.setTextColor(getColor(
            if (type == TxType.RECU) R.color.success else R.color.danger
        ))

        // Champs
        binding.txtDateValue.text = dfFull.format(Date(ts)).replaceFirstChar { it.uppercase() }

        val phoneOp = TransactionParser.phoneOperator(phone)
        binding.txtPhoneValue.text = if (phone.isBlank()) "—"
            else if (phoneOp.isNotEmpty()) "$phone   ($phoneOp)" else phone

        binding.txtOperatorValue.text = operator
        binding.txtRefValue.text = if (ref.isBlank()) "—" else ref
        binding.txtSenderValue.text = sender

        // Extraire "Frais" et "Nouveau solde" du body si presents
        val frais = Regex("(?i)frais[^\\d]{0,20}([\\d\\s.,]+)\\s*(?:FCFA|CFA|F)").find(body)?.groupValues?.get(1)?.trim()
        val solde = Regex("(?i)(?:nouveau\\s+)?solde[^\\d]{0,30}([\\d\\s.,]+)\\s*(?:FCFA|CFA|F)").find(body)?.groupValues?.get(1)?.trim()
        binding.txtFraisValue.text = if (frais != null) "$frais $currency" else "—"
        binding.txtSoldeValue.text = if (solde != null) "$solde $currency" else "—"

        // SMS original
        binding.txtBodyValue.text = if (body.isBlank()) "(non disponible — recu via le serveur)" else body

        binding.btnClose.setOnClickListener { finish() }
    }
}
