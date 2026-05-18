package com.gerard.momofin

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gerard.momofin.databinding.ActivityPointsBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class PointsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPointsBinding
    private lateinit var store: PointsStore
    private val nf = NumberFormat.getNumberInstance(Locale.FRENCH)
    private val dfDay = SimpleDateFormat("EEEE dd MMMM yyyy", Locale.FRENCH)
    private var currentDay: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPointsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = PointsStore(this)
        currentDay = dayKey(System.currentTimeMillis())

        // Attache formatage milliers + recalcul automatique du total
        for (e in listOf(
            binding.edtOm, binding.edtMomo, binding.edtMoov, binding.edtWave,
            binding.edtDjamo, binding.edtCfa, binding.edtEntree, binding.edtSortie
        )) {
            AmountWatcher.attach(e)
            e.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { recomputeTotal() }
            })
        }

        binding.btnPickDate.setOnClickListener { pickDate() }
        binding.btnPrev.setOnClickListener { changeDay(-1) }
        binding.btnNext.setOnClickListener { changeDay(1) }
        binding.btnSave.setOnClickListener { saveCurrent() }

        loadDay()
    }

    private fun pickDate() {
        val c = Calendar.getInstance().apply { timeInMillis = currentDay }
        DatePickerDialog(this, { _, y, m, d ->
            val cal = Calendar.getInstance().apply {
                set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0)
            }
            currentDay = cal.timeInMillis
            loadDay()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun changeDay(deltaDays: Int) {
        val c = Calendar.getInstance().apply { timeInMillis = currentDay }
        c.add(Calendar.DAY_OF_MONTH, deltaDays)
        currentDay = c.timeInMillis
        loadDay()
    }

    private fun dayKey(timestamp: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    private fun loadDay() {
        binding.txtDate.text = dfDay.format(Date(currentDay)).replaceFirstChar { it.uppercase() }
        val p = store.get(currentDay)
        setEditAmount(binding.edtOm, p.om)
        setEditAmount(binding.edtMomo, p.momo)
        setEditAmount(binding.edtMoov, p.moov)
        setEditAmount(binding.edtWave, p.wave)
        setEditAmount(binding.edtDjamo, p.djamo)
        setEditAmount(binding.edtCfa, p.cfa)
        setEditAmount(binding.edtEntree, p.entree)
        setEditAmount(binding.edtSortie, p.sortie)
        binding.edtNote.setText(p.note)
        recomputeTotal()
    }

    private fun setEditAmount(e: EditText, value: Double) {
        if (value > 0) e.setText(nf.format(value)) else e.setText("")
    }

    private fun recomputeTotal() {
        val total =
            AmountWatcher.getAmount(binding.edtOm.text.toString()) +
            AmountWatcher.getAmount(binding.edtMomo.text.toString()) +
            AmountWatcher.getAmount(binding.edtMoov.text.toString()) +
            AmountWatcher.getAmount(binding.edtWave.text.toString()) +
            AmountWatcher.getAmount(binding.edtDjamo.text.toString()) +
            AmountWatcher.getAmount(binding.edtCfa.text.toString()) +
            AmountWatcher.getAmount(binding.edtEntree.text.toString()) -
            AmountWatcher.getAmount(binding.edtSortie.text.toString())
        binding.txtTotal.text = "TOTAL POINTS : ${nf.format(total)}"
    }

    private fun saveCurrent() {
        val p = DailyPoints(
            dayKey = currentDay,
            om = AmountWatcher.getAmount(binding.edtOm.text.toString()),
            momo = AmountWatcher.getAmount(binding.edtMomo.text.toString()),
            moov = AmountWatcher.getAmount(binding.edtMoov.text.toString()),
            wave = AmountWatcher.getAmount(binding.edtWave.text.toString()),
            djamo = AmountWatcher.getAmount(binding.edtDjamo.text.toString()),
            cfa = AmountWatcher.getAmount(binding.edtCfa.text.toString()),
            entree = AmountWatcher.getAmount(binding.edtEntree.text.toString()),
            sortie = AmountWatcher.getAmount(binding.edtSortie.text.toString()),
            note = binding.edtNote.text.toString().trim()
        )
        store.save(p)
        Toast.makeText(this, "✅ Points enregistrés pour ce jour", Toast.LENGTH_SHORT).show()
    }
}
