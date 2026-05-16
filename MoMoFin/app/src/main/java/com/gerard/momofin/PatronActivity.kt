package com.gerard.momofin

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.gerard.momofin.databinding.ActivityPatronBinding
import java.text.NumberFormat
import java.util.Locale

/**
 * Section PATRON :
 *  - Bouton "Reçu" : ouvre une boîte de dialogue pour saisir un montant reçu
 *  - Bouton "Sortie" : ouvre une boîte de dialogue pour saisir un montant sorti
 *  - Total : différence entre Reçu et Sortie (mise à jour en temps réel)
 */
class PatronActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPatronBinding
    private lateinit var store: PatronStore
    private lateinit var adapter: PatronAdapter
    private val nf = NumberFormat.getNumberInstance(Locale.FRENCH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPatronBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = PatronStore(this)
        adapter = PatronAdapter { entry ->
            // Suppression d'une entrée (long press)
            AlertDialog.Builder(this)
                .setTitle(R.string.delete_entry)
                .setMessage(getString(R.string.delete_entry_msg, nf.format(entry.amount)))
                .setPositiveButton(R.string.delete) { _, _ ->
                    store.delete(entry.id); refresh()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnRecu.setOnClickListener { askAmount(TxType.RECU) }
        binding.btnSortie.setOnClickListener { askAmount(TxType.SORTIE) }
        binding.btnTotal.setOnClickListener { showTotalsDialog() }

        refresh()
    }

    private fun askAmount(type: TxType) {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_patron_entry, null)
        val edtAmount = v.findViewById<EditText>(R.id.edtAmount)
        val edtNote = v.findViewById<EditText>(R.id.edtNote)
        val title = if (type == TxType.RECU) R.string.add_recu else R.string.add_sortie
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(v)
            .setPositiveButton(R.string.save) { _, _ ->
                val amount = edtAmount.text.toString()
                    .replace(" ", "").replace(",", ".").toDoubleOrNull()
                if (amount == null || amount <= 0) {
                    Toast.makeText(this, R.string.invalid_amount, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                store.add(type, amount, edtNote.text.toString().trim())
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showTotalsDialog() {
        val (recu, sortie) = store.totals()
        val total = recu - sortie
        AlertDialog.Builder(this)
            .setTitle(R.string.total_patron)
            .setMessage(
                getString(R.string.total_patron_msg, nf.format(recu), nf.format(sortie), nf.format(total))
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun refresh() {
        val list = store.all()
        adapter.submit(list)
        val (recu, sortie) = store.totals()
        val total = recu - sortie
        binding.txtTotalRecu.text = getString(R.string.txt_total_recu, nf.format(recu))
        binding.txtTotalSortie.text = getString(R.string.txt_total_sortie, nf.format(sortie))
        binding.txtTotalFinal.text = getString(R.string.txt_total_final, nf.format(total))
    }
}
