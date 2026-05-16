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

/**
 * Génère un PDF imprimable :
 *  - Une section par jour
 *  - Pour chaque jour : tableau (heure, type, montant, référence, opérateur)
 *  - Sous-totaux par jour (Reçu / Sortie / Solde) + total général
 */
object PdfGenerator {

    private const val PAGE_W = 595   // A4 portrait à 72 dpi
    private const val PAGE_H = 842
    private const val MARGIN = 36

    /** Retourne le File du PDF généré. */
    fun generateDailyReport(
        context: Context,
        transactions: List<Transaction>,
        patronEntries: List<PatronEntry> = emptyList()
    ): File {
        val pdf = PdfDocument()
        val title = Paint().apply { textSize = 18f; isFakeBoldText = true }
        val h2 = Paint().apply { textSize = 14f; isFakeBoldText = true }
        val text = Paint().apply { textSize = 10f }
        val small = Paint().apply { textSize = 9f; color = 0xFF555555.toInt() }
        val rule = Paint().apply { strokeWidth = 0.6f; color = 0xFFBBBBBB.toInt() }

        val nf = NumberFormat.getNumberInstance(Locale.FRENCH)
        val dfDay = SimpleDateFormat("EEEE dd MMMM yyyy", Locale.FRENCH)
        val dfTime = SimpleDateFormat("HH:mm:ss", Locale.FRENCH)
        val dfNow = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH)

        val grouped = transactions
            .sortedByDescending { it.timestamp }
            .groupBy { TransactionParser.dayKey(it.timestamp) }
            .toSortedMap(compareByDescending { it })

        var pageNum = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
        var canvas = page.canvas
        var y = MARGIN.toFloat()

        // Entête
        canvas.drawText("MoMo Fin — Rapport des transactions", MARGIN.toFloat(), y, title)
        y += 18f
        canvas.drawText("Généré le ${dfNow.format(Date())}", MARGIN.toFloat(), y, small)
        y += 16f
        canvas.drawLine(MARGIN.toFloat(), y, (PAGE_W - MARGIN).toFloat(), y, rule)
        y += 14f

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

        for ((dayMillis, txs) in grouped) {
            newPageIfNeeded(80f)
            canvas.drawText(dfDay.format(Date(dayMillis)).replaceFirstChar { it.uppercase() },
                MARGIN.toFloat(), y, h2)
            y += 16f

            // Entêtes de colonnes
            val xHeure = MARGIN.toFloat()
            val xType = xHeure + 60
            val xMontant = xType + 70
            val xRef = xMontant + 110
            val xOp = xRef + 150

            val header = Paint().apply { textSize = 10f; isFakeBoldText = true }
            canvas.drawText("Heure", xHeure, y, header)
            canvas.drawText("Type", xType, y, header)
            canvas.drawText("Montant", xMontant, y, header)
            canvas.drawText("Référence", xRef, y, header)
            canvas.drawText("Opérateur", xOp, y, header)
            y += 4f
            canvas.drawLine(MARGIN.toFloat(), y, (PAGE_W - MARGIN).toFloat(), y, rule)
            y += 12f

            var dayRecu = 0.0
            var daySortie = 0.0

            for (tx in txs) {
                newPageIfNeeded(14f)
                canvas.drawText(dfTime.format(Date(tx.timestamp)), xHeure, y, text)
                canvas.drawText(typeLabel(tx.type), xType, y, text)
                canvas.drawText("${nf.format(tx.amount)} ${tx.currency}", xMontant, y, text)
                canvas.drawText(truncate(tx.reference, 22), xRef, y, text)
                canvas.drawText(tx.operator, xOp, y, text)
                y += 13f
                when (tx.type) {
                    TxType.RECU -> dayRecu += tx.amount
                    TxType.SORTIE -> daySortie += tx.amount
                    else -> {}
                }
            }

            // Sous-total du jour
            y += 4f
            canvas.drawLine(MARGIN.toFloat(), y, (PAGE_W - MARGIN).toFloat(), y, rule)
            y += 12f
            val sumPaint = Paint().apply { textSize = 10f; isFakeBoldText = true }
            canvas.drawText(
                "Reçu : ${nf.format(dayRecu)}   |   Sortie : ${nf.format(daySortie)}   |   Solde : ${nf.format(dayRecu - daySortie)}",
                MARGIN.toFloat(), y, sumPaint
            )
            y += 22f

            totalRecuGlobal += dayRecu
            totalSortieGlobal += daySortie
        }

        // Section PATRON (saisies manuelles)
        if (patronEntries.isNotEmpty()) {
            newPageIfNeeded(60f)
            canvas.drawText("Section PATRON (saisies manuelles)", MARGIN.toFloat(), y, h2)
            y += 16f
            var pRecu = 0.0
            var pSortie = 0.0
            for (e in patronEntries) {
                newPageIfNeeded(14f)
                val label = if (e.type == TxType.RECU) "Reçu" else "Sortie"
                canvas.drawText(
                    "${dfNow.format(Date(e.timestamp))}   $label   ${nf.format(e.amount)}   ${e.note}",
                    MARGIN.toFloat(), y, text
                )
                y += 13f
                if (e.type == TxType.RECU) pRecu += e.amount else pSortie += e.amount
            }
            y += 8f
            canvas.drawLine(MARGIN.toFloat(), y, (PAGE_W - MARGIN).toFloat(), y, rule)
            y += 14f
            canvas.drawText(
                "Patron — Reçu : ${nf.format(pRecu)}   |   Sortie : ${nf.format(pSortie)}   |   Total : ${nf.format(pRecu - pSortie)}",
                MARGIN.toFloat(), y, Paint().apply { textSize = 11f; isFakeBoldText = true }
            )
            y += 22f
        }

        // Total général
        newPageIfNeeded(40f)
        canvas.drawLine(MARGIN.toFloat(), y, (PAGE_W - MARGIN).toFloat(), y, rule)
        y += 16f
        canvas.drawText(
            "TOTAL GÉNÉRAL — Reçu : ${nf.format(totalRecuGlobal)}   |   Sortie : ${nf.format(totalSortieGlobal)}   |   Solde : ${nf.format(totalRecuGlobal - totalSortieGlobal)}",
            MARGIN.toFloat(), y, Paint().apply { textSize = 12f; isFakeBoldText = true }
        )

        pdf.finishPage(page)

        // Écriture du fichier
        val fileName = "MoMoFin_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf"
        val outFile = writePdf(context, pdf, fileName)
        pdf.close()
        return outFile
    }

    private fun writePdf(context: Context, pdf: PdfDocument, fileName: String): File {
        // 1. Toujours écrire une copie interne pour FileProvider (Aperçu / Partage)
        val internalDir = File(context.filesDir, "pdfs").apply { mkdirs() }
        val internal = File(internalDir, fileName)
        FileOutputStream(internal).use { pdf.writeTo(it) }

        // 2. Sur API 29+, on enregistre aussi dans Téléchargements via MediaStore
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
        TxType.RECU -> "Reçu"
        TxType.SORTIE -> "Sortie"
        TxType.INCONNU -> "—"
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max - 1) + "…"
}
