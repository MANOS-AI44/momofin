package com.gerard.momofin

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gerard.momofin.databinding.ActivityFolderDetailBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FolderDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFolderDetailBinding
    private lateinit var store: FolderStore
    private lateinit var folder: Folder
    private lateinit var adapter: EntryAdapter
    private val nf = NumberFormat.getNumberInstance(Locale.FRENCH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFolderDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val folderId = intent.getLongExtra("folder_id", -1L)
        store = FolderStore(this)
        folder = store.getFolder(folderId) ?: run { finish(); return }

        binding.txtTitle.text = folder.name
        supportActionBar?.title = folder.name

        adapter = EntryAdapter { e ->
            AlertDialog.Builder(this)
                .setTitle(R.string.delete_entry)
                .setMessage(getString(R.string.delete_entry_msg, nf.format(e.amount)))
                .setPositiveButton(R.string.delete) { _, _ -> store.deleteEntry(e.id); refresh(); autoBackup() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnEntree.setOnClickListener { askAmount(TxType.RECU) }
        binding.btnSortie.setOnClickListener { askAmount(TxType.SORTIE) }
        binding.btnTotal.setOnClickListener { showTotal() }

        refresh()
    }

    private fun askAmount(type: TxType) {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_patron_entry, null)
        val edtAmount = v.findViewById<EditText>(R.id.edtAmount)
        val edtNote = v.findViewById<EditText>(R.id.edtNote)
        AmountWatcher.attach(edtAmount)
        val title = if (type == TxType.RECU) R.string.folder_add_entree else R.string.folder_add_sortie
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(v)
            .setPositiveButton(R.string.save) { _, _ ->
                val a = AmountWatcher.getAmount(edtAmount.text.toString())
                if (a <= 0) {
                    Toast.makeText(this, R.string.invalid_amount, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                store.addEntry(folder.id, type, a, edtNote.text.toString().trim())
                autoBackup()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showTotal() {
        val (e, s) = store.totals(folder.id)
        AlertDialog.Builder(this)
            .setTitle(R.string.folder_total_title)
            .setMessage(getString(R.string.folder_total_msg, nf.format(e), nf.format(s), nf.format(e - s)))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun refresh() {
        val list = store.entries(folder.id)
        adapter.submit(list)
        val (e, s) = store.totals(folder.id)
        binding.txtEntree.text = getString(R.string.folder_total_entree, nf.format(e))
        binding.txtSortie.text = getString(R.string.folder_total_sortie, nf.format(s))
        binding.txtSolde.text = getString(R.string.folder_total_solde, nf.format(e - s))
    }

    class EntryAdapter(val onLong: (FolderEntry) -> Unit) : RecyclerView.Adapter<EntryAdapter.VH>() {
        private val items = mutableListOf<FolderEntry>()
        private val nf = NumberFormat.getNumberInstance(Locale.FRENCH)
        private val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH)

        fun submit(l: List<FolderEntry>) { items.clear(); items.addAll(l); notifyDataSetChanged() }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val type: TextView = v.findViewById(R.id.txtType)
            val amount: TextView = v.findViewById(R.id.txtAmount)
            val note: TextView = v.findViewById(R.id.txtNote)
            val date: TextView = v.findViewById(R.id.txtDate)
        }

        override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_patron, p, false))

        override fun onBindViewHolder(h: VH, pos: Int) {
            val e = items[pos]
            val isEntree = e.type == TxType.RECU
            h.type.text = if (isEntree) "ENTRÉE" else "SORTIE"
            h.type.setTextColor(if (isEntree) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
            h.amount.text = nf.format(e.amount)
            h.amount.setTextColor(if (isEntree) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
            h.note.text = e.note.ifBlank { "—" }
            h.date.text = df.format(Date(e.timestamp))
            h.itemView.setOnLongClickListener { onLong(e); true }
        }

        override fun getItemCount() = items.size
    }
    private fun autoBackup() {
        if (!Settings.isConfigured(this)) return
        val url = Settings.getUrl(this); val token = Settings.getToken(this)
        CoroutineScope(Dispatchers.IO).launch { RailwayClient.syncFolders(url, token, store) }
    }

}
