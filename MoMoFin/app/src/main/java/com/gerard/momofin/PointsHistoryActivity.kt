package com.gerard.momofin

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gerard.momofin.databinding.ActivityPointsHistoryBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PointsHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPointsHistoryBinding
    private lateinit var store: PointsStore
    private lateinit var adapter: Adapter
    private val nf = NumberFormat.getNumberInstance(Locale.FRENCH)
    private val dfDay = SimpleDateFormat("EEEE dd MMMM yyyy", Locale.FRENCH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPointsHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = PointsStore(this)
        adapter = Adapter { p -> showDetail(p) }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnSyncCloud.setOnClickListener { syncFromCloud() }
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val days = store.allDays()
        adapter.submit(days)
        binding.txtEmpty.visibility = if (days.isEmpty()) View.VISIBLE else View.GONE
        binding.txtCount.text = "${days.size} rapport(s) enregistré(s)"
    }

    private fun syncFromCloud() {
        if (!Settings.isConfigured(this)) {
            Toast.makeText(this, "Connectez-vous d'abord (Paramètres)", Toast.LENGTH_LONG).show()
            return
        }
        binding.btnSyncCloud.isEnabled = false
        Toast.makeText(this, "Récupération depuis le serveur…", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            val cloud = PointsClient.pullAll(Settings.getUrl(this@PointsHistoryActivity), Settings.getToken(this@PointsHistoryActivity))
            for (p in cloud) store.save(p)
            withContext(Dispatchers.Main) {
                binding.btnSyncCloud.isEnabled = true
                Toast.makeText(this@PointsHistoryActivity, "✅ ${cloud.size} rapport(s) récupéré(s) du cloud", Toast.LENGTH_LONG).show()
                refresh()
            }
        }
    }

    private fun showDetail(p: DailyPoints) {
        val msg = buildString {
            append("Date : ${dfDay.format(Date(p.dayKey)).replaceFirstChar { it.uppercase() }}\n\n")
            append("OM         : ${nf.format(p.om)}\n")
            append("MoMo       : ${nf.format(p.momo)}\n")
            append("MOOV       : ${nf.format(p.moov)}\n")
            append("WAVE       : ${nf.format(p.wave)}\n")
            append("djamo      : ${nf.format(p.djamo)}\n")
            append("CFA        : ${nf.format(p.cfa)}\n\n")
            append("Entrée     : ${nf.format(p.entree)}\n")
            append("Sortie     : ${nf.format(p.sortie)}\n\n")
            append("───────────────────\n")
            append("TOTAL POINTS : ${nf.format(p.total)}\n")
            if (p.note.isNotBlank()) append("\nNote : ${p.note}")
        }
        AlertDialog.Builder(this)
            .setTitle("📊 Rapport du jour")
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    inner class Adapter(val onClick: (DailyPoints) -> Unit) : RecyclerView.Adapter<Adapter.VH>() {
        private val items = mutableListOf<DailyPoints>()
        fun submit(list: List<DailyPoints>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val date: TextView = v.findViewById(R.id.txtDay)
            val totals: TextView = v.findViewById(R.id.txtTotals)
        }
        override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_day, p, false))
        override fun onBindViewHolder(h: VH, pos: Int) {
            val p = items[pos]
            h.date.text = dfDay.format(Date(p.dayKey)).replaceFirstChar { it.uppercase() }
            h.totals.text = "TOTAL POINTS : ${nf.format(p.total)}"
            h.itemView.setOnClickListener { onClick(p) }
        }
        override fun getItemCount() = items.size
    }
}
