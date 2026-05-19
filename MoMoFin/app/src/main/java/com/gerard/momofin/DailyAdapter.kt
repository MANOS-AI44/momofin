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

class DailyAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class DayHeader(val dayMillis: Long, val recu: Double, val sortie: Double) : Row()
        data class Tx(val tx: Transaction) : Row()
    }

    private val rows = mutableListOf<Row>()
    private val nf = NumberFormat.getNumberInstance(Locale.FRENCH)
    private val dfDay = SimpleDateFormat("EEEE dd MMMM yyyy", Locale.FRENCH)
    private val dfDateTime = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRENCH)

    fun submit(transactions: List<Transaction>) {
        rows.clear()
        val grouped = transactions
            .sortedByDescending { it.timestamp }
            .groupBy { TransactionParser.dayKey(it.timestamp) }
            .toSortedMap(compareByDescending { it })
        for ((day, txs) in grouped) {
            val recu = txs.filter { it.type == TxType.RECU }.sumOf { it.amount }
            val sortie = txs.filter { it.type == TxType.SORTIE }.sumOf { it.amount }
            rows.add(Row.DayHeader(day, recu, sortie))
            txs.forEach { rows.add(Row.Tx(it)) }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.DayHeader) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            DayVH(inflater.inflate(R.layout.item_day, parent, false))
        } else {
            TxVH(inflater.inflate(R.layout.item_transaction, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val r = rows[position]) {
            is Row.DayHeader -> (holder as DayVH).bind(r)
            is Row.Tx -> (holder as TxVH).bind(r.tx)
        }
    }

    override fun getItemCount(): Int = rows.size

    inner class DayVH(v: View) : RecyclerView.ViewHolder(v) {
        private val txtDay: TextView = v.findViewById(R.id.txtDay)
        private val txtTotals: TextView = v.findViewById(R.id.txtTotals)
        fun bind(r: Row.DayHeader) {
            txtDay.text = dfDay.format(Date(r.dayMillis))
                .replaceFirstChar { it.uppercase() }
            txtTotals.text = "Retrait : ${nf.format(r.recu)}   •   Dépôt : ${nf.format(r.sortie)}   •   Solde : ${nf.format(r.recu - r.sortie)}"
        }
    }

    inner class TxVH(v: View) : RecyclerView.ViewHolder(v) {
        private val txtTime: TextView = v.findViewById(R.id.txtTime)
        private val txtType: TextView = v.findViewById(R.id.txtType)
        private val txtAmount: TextView = v.findViewById(R.id.txtAmount)
        private val txtPhone: TextView = v.findViewById(R.id.txtPhone)
        private val txtRef: TextView = v.findViewById(R.id.txtRef)
        private val txtOp: TextView = v.findViewById(R.id.txtOperator)

        fun bind(tx: Transaction) {
            txtTime.text = dfDateTime.format(Date(tx.timestamp))
            txtType.text = when (tx.type) {
                TxType.RECU -> "RETRAIT"
                TxType.SORTIE -> "DÉPÔT"
                TxType.INCONNU -> "—"
            }
            // Badge stylise (rounded corners + couleurs douces)
            val ctx = itemView.context
            txtType.background = ctx.getDrawable(when (tx.type) {
                TxType.RECU -> R.drawable.badge_retrait
                TxType.SORTIE -> R.drawable.badge_depot
                TxType.INCONNU -> R.drawable.badge_depot
            })
            txtType.setTextColor(when (tx.type) {
                TxType.RECU   -> ctx.getColor(R.color.badge_recu_text)
                TxType.SORTIE -> ctx.getColor(R.color.badge_sortie_text)
                TxType.INCONNU -> ctx.getColor(R.color.text_muted)
            })
            txtAmount.text = "${nf.format(tx.amount)} ${tx.currency}"
            txtAmount.setTextColor(when (tx.type) {
                TxType.RECU -> 0xFF2E7D32.toInt()
                TxType.SORTIE -> 0xFFC62828.toInt()
                else -> 0xFF000000.toInt()
            })
            val opByNum = TransactionParser.phoneOperator(tx.phoneNumber)
            val opSuffix = if (opByNum.isNotEmpty()) " ($opByNum)" else ""
            txtPhone.text = if (tx.phoneNumber.isBlank()) "📞 —" else "📞 ${tx.phoneNumber}$opSuffix"
            txtRef.text = if (tx.reference.isBlank()) "Réf. —" else "Réf. ${tx.reference}"
            txtOp.text = if (TransactionParser.phoneOperator(tx.phoneNumber).isNotEmpty()) TransactionParser.phoneOperator(tx.phoneNumber) else tx.operator
        }
    }
}
