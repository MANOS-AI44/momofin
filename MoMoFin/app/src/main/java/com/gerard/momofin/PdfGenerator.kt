package com.gerard.momofin

import android.content.ContentValues
import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfGenerator {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 28

    fun generateDailyReport(
        context: Context,
        transactions: List<Transaction>,
        folderStore: FolderStore? = null
    ): File {
        val pdf = PdfDocument()
        val title = Paint().apply { textSize = 18f; isFakeBoldText = true }
        val h2 = Paint().apply { textSize = 13f; isFakeBoldText = true }
        val text = Paint().apply { textSize = 9f }
        val small = Paint().apply { textSize = 9f; color = 0xFF555555.toInt() }
        val rule = Paint().apply { strokeWidth = 0.6f; color = 0xFFBBBBBB.toInt() }
        val header = Paint().apply { textSize = 9f; isFakeBoldText = true }

        val nf = NumberFormat.getNumberInstance(Locale.FRENCH)
        val dfDay = SimpleDateFormat("EEEE dd MMMM yyyy", Locale.FRENCH)
        val dfDateTime = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH)
        val dfNow = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH)

        val grouped = transactions
            .sortedByDescending { it.timestamp }
            .groupBy { TransactionParser.dayKey(it.timestamp) }
            .toSortedMap(compareByDescending { it })

        var pageNum = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
        var canvas = page.canvas
        var y = MARGIN.toFloat()

        canvas.drawText("MoMo Fin — Rapport des transactions", MARGIN.toFloat(), y, title)
        y += 18f
        canvas.drawText("Généré le ${dfNow.format(Date())}", MARGIN.toFloat(), y, small)
        y += 14f
        canvas.drawLine(MARGIN.toFloat(), y, (PAGE_W - MARGIN).toFloat(), y, rule)
        y += 12f

        fun newPageIfNeeded(minRoom: Float) {
            if (y + minRoom > PAGE_H - MARGIN) {
                pdf.finishPage(page)
                pageNum++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
                canvas = page.canvas
                y = MARGIN.toFloat()
            }
        }

        var totalRecuGlobal = 0.0
        var totalSortieGlobal = 0.0

        // Colonnes : Date/heure | Type | Montant | Numéro | Référence | Opér.
        val xDate = MARGIN.toFloat()
        val xType = xDate + 95
        val xAmount = xType + 50
        val xPhone = xAmount + 90
        val xRef = xPhone + 110
        val xOp = xRef + 140

        for ((dayMillis, txs) in grouped) {
            newPageIfNeeded(80f)
            canvas.drawText(dfDay.format(Date(dayMillis)).replaceFirstChar { it.uppercase() },
                MARGIN.toFloat(), y, h2)
            y += 14f

            canvas.drawText("Date/Heure", xDate, y, header)
            canvas.drawText("Type", xType, y, header)
            canvas.drawText("Montant", xAmount, y, header)
            canvas.drawText("Numéro", xPhone, y, header)
            canvas.drawText("Référence", xRef, y, header)
            canvas.drawText("Opérateur", xOp, y, header)
            y += 4f
            canvas.drawLine(MARGIN.toFloat(), y, (PAGE_W - MARGIN).toFloat(), y, rule)
            y += 11f

            var dayRecu = 0.0
            var daySortie = 0.0

            for (tx in txs) {
                newPageIfNeeded(12f)
                canvas.drawText(dfDateTime.format(Date(tx.timestamp)), xDate, y, text)
                canvas.drawText(typeLabel(tx.type), xType, y, text)
                canvas.drawText("${nf.format(tx.amount)} ${tx.currency}", xAmount, y, text)
                canvas.drawText(truncate(tx.phoneNumber.ifBlank { "—" }, 16), xPhone, y, text)
                canvas.drawText(truncate(tx.reference.ifBlank { "—" }, 20), xRef, y, text)
                canvas.drawText(tx.operator, xOp, y, text)
                y += 12f
                when (tx.type) {
                    TxType.RECU -> dayRecu += tx.amount
                    TxType.SORTIE -> daySortie += tx.amount
                    else -> {}
                }
            }

            y += 4f
            canvas.drawLine(MARGIN.toFloat(), y, (PAGE_W - MARGIN).toFloat(), y, rule)
            y += 12f
            val sumPaint = Paint().apply { textSize = 10f; isFakeBoldText = true }
            canvas.drawText(
                "Retrait : ${nf.format(dayRecu)}   |   Dépôt : ${nf.format(daySortie)}   |   Solde : ${nf.format(dayRecu - daySortie)}",
                MARGIN.toFloat(), y, sumPaint
            )
            y += 22f

            totalRecuGlobal += dayRecu
            totalSortieGlobal += daySortie
        }

        val folders = folderStore?.allFolders() ?: emptyList()
        if (folders.isNotEmpty()) {
            newPageIfNeeded(40f)
            canvas.drawText("Mes Carnets", MARGIN.toFloat(), y, h2)
            y += 16f
            for (folder in folders) {
                newPageIfNeeded(50f)
                canvas.drawText("📓 ${folder.name}", MARGIN.toFloat(), y, Paint().apply { textSize = 12f; isFakeBoldText = true })
                y += 14f
                val entries = folderStore!!.entries(folder.id)
                var fEntree = 0.0
                var fSortie = 0.0
                for (e in entries) {
                    newPageIfNeeded(12f)
                    val label = if (e.type == TxType.RECU) "Entrée" else "Sortie"
                    canvas.drawText(
                        "  ${dfNow.format(Date(e.timestamp))}   $label   ${nf.format(e.amount)}   ${e.note}",
                        MARGIN.toFloat(), y, text
                    )
                    y += 11f
                    if (e.type == TxType.RECU) fEntree += e.amount else fSortie += e.amount
                }
                y += 4f
                canvas.drawText(
                    "  Total dossier — Entrée : ${nf.format(fEntree)}   |   Sortie : ${nf.format(fSortie)}   |   Solde : ${nf.format(fEntree - fSortie)}",
                    MARGIN.toFloat(), y, Paint().apply { textSize = 10f; isFakeBoldText = true; color = 0xFF1565C0.toInt() }
                )
                y += 18f
            }
        }

        newPageIfNeeded(40f)
        canvas.drawLine(MARGIN.toFloat(), y, (PAGE_W - MARGIN).toFloat(), y, rule)
        y += 16f
        canvas.drawText(
            "TOTAL GÉNÉRAL — Retrait : ${nf.format(totalRecuGlobal)}   |   Dépôt : ${nf.format(totalSortieGlobal)}   |   Solde : ${nf.format(totalRecuGlobal - totalSortieGlobal)}",
            MARGIN.toFloat(), y, Paint().apply { textSize = 12f; isFakeBoldText = true }
        )

        pdf.finishPage(page)

        val fileName = "MoMoFin_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf"
        val outFile = writePdf(context, pdf, fileName)
        pdf.close()
        return outFile
    }

    private fun writePdf(context: Context, pdf: PdfDocument, fileName: String): File {
        val internalDir = File(context.filesDir, "pdfs").apply { mkdirs() }
        val internal = File(internalDir, fileName)
        FileOutputStream(internal).use { pdf.writeTo(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MoMoFin")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { out ->
                    internal.inputStream().use { input -> input.copyTo(out) }
                }
            }
        }
        return internal
    }

    private fun typeLabel(t: TxType): String = when (t) {
        TxType.RECU -> "Retrait"
        TxType.SORTIE -> "Dépôt"
        TxType.INCONNU -> "—"
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max - 1) + "…"
}
