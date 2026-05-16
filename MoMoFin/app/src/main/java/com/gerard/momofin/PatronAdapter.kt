package com.gerard.momofin

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PatronAdapter(
    private val onLongClick: (PatronEntry) -> Unit
) : RecyclerView.Adapter<PatronAdapter.VH>() {

    private val items = mutableListOf<PatronEntry>()
    private val nf = NumberFormat.getNumberInstance(Locale.FRENCH)
    private val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH)

    fun submit(list: List<PatronEntry>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val txtType: TextView = v.findViewById(R.id.txtType)
        val txtAmount: TextView = v.findViewById(R.id.txtAmount)
        val txtNote: TextView = v.findViewById(R.id.txtNote)
        val txtDate: TextView = v.findViewById(R.id.txtDate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_patron, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        holder.txtType.text = if (e.type == TxType.RECU) "Reçu" else "Sortie"
        holder.txtAmount.text = nf.format(e.amount)
        holder.txtNote.text = e.note.ifBlank { "—" }
        holder.txtDate.text = df.format(Date(e.timestamp))
        holder.itemView.setOnLongClickListener {
            onLongClick(e); true
        }
    }

    override fun getItemCount(): Int = items.size
}
