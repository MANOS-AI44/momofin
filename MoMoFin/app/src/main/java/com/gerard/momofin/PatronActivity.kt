package com.gerard.momofin

import android.app.AlertDialog
import android.content.Intent
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
import com.gerard.momofin.databinding.ActivityPatronBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Liste des dossiers (chaque dossier est une mini-comptabilité).
 */
class PatronActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPatronBinding
    private lateinit var store: FolderStore
    private lateinit var adapter: FolderAdapter
    private val nf = NumberFormat.getNumberInstance(Locale.FRENCH).apply { groupingSize = 3 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPatronBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = FolderStore(this)
        adapter = FolderAdapter(
            onOpen = { f ->
                startActivity(Intent(this, FolderDetailActivity::class.java).putExtra("folder_id", f.id))
            },
            onLongClick = { f -> confirmDeleteFolder(f) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnNewFolder.setOnClickListener { askNewFolder() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun askNewFolder() {
        val edit = EditText(this).apply {
            hint = getString(R.string.folder_hint_name)
            setPadding(50, 30, 50, 30)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.folder_new_title)
            .setView(edit)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, R.string.folder_name_required, Toast.LENGTH_SHORT).show()
                } else {
                    store.createFolder(name)
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteFolder(f: Folder) {
        AlertDialog.Builder(this)
            .setTitle(R.string.folder_delete_title)
            .setMessage(getString(R.string.folder_delete_msg, f.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                store.deleteFolder(f.id)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refresh() {
        val folders = store.allFolders()
        adapter.submit(folders, store)
        binding.txtEmpty.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---- Adapter ----
    inner class FolderAdapter(
        val onOpen: (Folder) -> Unit,
        val onLongClick: (Folder) -> Unit
    ) : RecyclerView.Adapter<FolderAdapter.VH>() {

        private val items = mutableListOf<Folder>()
        private var sto: FolderStore? = null
        private val df = SimpleDateFormat("dd/MM/yyyy", Locale.FRENCH)

        fun submit(list: List<Folder>, s: FolderStore) {
            items.clear(); items.addAll(list); sto = s; notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.txtFolderName)
            val totals: TextView = v.findViewById(R.id.txtFolderTotals)
            val date: TextView = v.findViewById(R.id.txtFolderDate)
        }

        override fun onCreateViewHolder(p: ViewGroup, vt: Int) = VH(
            LayoutInflater.from(p.context).inflate(R.layout.item_folder, p, false)
        )

        override fun onBindViewHolder(h: VH, pos: Int) {
            val f = items[pos]
            h.name.text = f.name
            h.date.text = df.format(Date(f.createdAt))
            val (e, s) = sto!!.totals(f.id)
            h.totals.text = "Entrée : ${nf.format(e)} • Sortie : ${nf.format(s)} • Solde : ${nf.format(e - s)}"
            h.itemView.setOnClickListener { onOpen(f) }
            h.itemView.setOnLongClickListener { onLongClick(f); true }
        }

        override fun getItemCount() = items.size
    }
}
