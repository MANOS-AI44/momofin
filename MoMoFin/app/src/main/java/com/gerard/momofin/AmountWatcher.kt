package com.gerard.momofin

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Formate un EditText numérique avec des espaces des milliers en temps réel.
 * Ex. saisie "1000000" → affiché "1 000 000".
 * Récupérer la valeur via [getAmount] qui ignore les espaces.
 */
class AmountWatcher(private val edit: EditText) : TextWatcher {

    private val symbols = DecimalFormatSymbols(Locale.FRENCH).apply { groupingSeparator = ' ' }
    private val df = DecimalFormat("#,###", symbols)
    private var formatting = false

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

    override fun afterTextChanged(s: Editable?) {
        if (formatting || s == null) return
        formatting = true
        try {
            val raw = s.toString().replace(" ", "").replace(",", ".")
            if (raw.isEmpty()) { formatting = false; return }

            // Sépare partie entière et décimale
            val dotIdx = raw.indexOf('.')
            val intPart = if (dotIdx >= 0) raw.substring(0, dotIdx) else raw
            val decPart = if (dotIdx >= 0) raw.substring(dotIdx) else ""

            val intValue = intPart.toLongOrNull() ?: 0L
            val formatted = df.format(intValue) + decPart

            if (formatted != s.toString()) {
                edit.setText(formatted)
                edit.setSelection(formatted.length)
            }
        } catch (_: Exception) {
        } finally {
            formatting = false
        }
    }

    companion object {
        /** Récupère la valeur numérique à partir d'un texte formaté avec espaces. */
        fun getAmount(text: String): Double {
            val clean = text.replace(" ", "").replace(",", ".")
            return clean.toDoubleOrNull() ?: 0.0
        }

        /** Attache un watcher à un EditText. */
        fun attach(edit: EditText) {
            edit.addTextChangedListener(AmountWatcher(edit))
        }
    }
}
