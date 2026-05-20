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
import android.widget.LinearLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val nf = NumberFormat.getNumberInstance(Locale.FRENCH)

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
        pullOthers()
    }

    private fun pullOthers() {
        if (!Settings.isConfigured(this)) return
        val url = Settings.getUrl(this); val token = Settings.getToken(this)
        CoroutineScope(Dispatchers.IO).launch {
            val list = RailwayClient.pullAllFolders(url, token)
            val others = list.filter { !it.isOwn }
            withContext(Dispatchers.Main) { renderOthers(others) }
        }
    }

    private fun renderOthers(others: List<RailwayClient.RemoteFolder>) {
        val section = findViewById<LinearLayout>(R.id.sectionOthers)
        val container = findViewById<LinearLayout>(R.id.listOthers)
        container.removeAllViews()
        if (others.isEmpty()) { section.visibility = View.GONE; return }
        section.visibility = View.VISIBLE
        // Regrouper par boutique
        val byShop = others.groupBy { it.deviceLabel }
        for ((shop, list) in byShop) {
            val shopHeader = TextView(this).apply {
                text = "🏪 $shop"
                setTextColor(0xFF1565C0.toInt())
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(12, 12, 12, 4)
            }
            container.addView(shopHeader)
            for (f in list) {
                val row = TextView(this).apply {
                    val solde = f.totalEntree - f.totalSortie
                    val soldeColor = if (solde >= 0) "#059669" else "#DC2626"
                    text = android.text.Html.fromHtml(
                        "<b>${f.name}</b><br/>" +
                        "<small>${f.nbEntries} saisies • " +
                        "<font color='#059669'>Entrée ${nf.format(f.totalEntree)}</font> • " +
                        "<font color='#DC2626'>Sortie ${nf.format(f.totalSortie)}</font> • " +
                        "<font color='$soldeColor'><b>Solde ${nf.format(solde)}</b></font></small>",
                        android.text.Html.FROM_HTML_MODE_COMPACT
                    )
                    textSize = 13f
                    setPadding(20, 8, 12, 8)
                    setBackgroundColor(0xFFF9FAFB.toInt())
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, 6)
                container.addView(row, lp)
            }
        }
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
