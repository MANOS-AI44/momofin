package com.gerard.momosms

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.gerard.momosms.databinding.ItemSmsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsAdapter(private var items: List<SmsModel>) :
    RecyclerView.Adapter<SmsAdapter.VH>() {

    private val df = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRENCH)

    fun submit(newItems: List<SmsModel>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(val b: ItemSmsBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemSmsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.b.txtSender.text = "${item.sender} • ${item.operator}"
        holder.b.txtDate.text = df.format(Date(item.timestamp))
        holder.b.txtBody.text = item.body
    }

    override fun getItemCount(): Int = items.size
}
