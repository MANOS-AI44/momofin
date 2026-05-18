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
    private const val MARGIN = 36

    /**
     * Génère un PDF imprimable.
     * @param singleDayMillis Si fourni, le PDF cible un SEUL jour (présentation simplifiée).
     *                        Sinon, regroupement par jour comme avant.
     */
    fun generateDailyReport(
        context: Context,
        transactions: List<Transaction>,
        folderStore: FolderStore? = null,
        singleDayMillis: Long? = null
    ): File {
        val pdf = PdfDocument()

        if (singleDayMillis != null) {
            generateSingleDay(pdf, transactions, singleDayMillis)
        } else {
            generateAllDays(pdf, transactions, folderStore)
        }

        val suffix = if (singleDayMillis != null)
            SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(singleDayMillis))
        else
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "MoMoFin_$suffix.pdf"
        val outFile = writePdf(context, pdf, fileName)
        pdf.close()
        return outFile
    }

    // ----- PDF simplifié pour UN seul jour (Type, Montant, Numéro, Référence) -----
    private fun generateSingleDay(pdf: PdfDocument, txs: List<Transaction>, dayMillis: Long) {
        val title = Paint().apply { textSize = 20f; isFakeBoldText = true; color = 0xFF1565C0.toInt() }
        val subtitle = Paint().apply { textSize = 13f; color = 0xFF555555.toInt() }
        val headerP = Paint().apply { textSize = 11f; isFakeBoldText = true; color = 0xFFFFFFFF.toInt() }
        val cellP = Paint().apply { textSize = 11f; color = 0xFF000000.toInt() }
        val cellBoldP = Paint().apply { textSize = 11f; isFakeBoldText = true }
        val totalP = Paint().apply { textSize = 13f; isFakeBoldText = true }
        val recuColor = 0xFF2E7D32.toInt()
        val sortieColor = 0xFFC62828.toInt()

        val nf = NumberFormat.getNumberInstance(Locale.FRENCH)
        val dfDay = SimpleDateFormat("EEEE dd MMMM yyyy", Locale.FRENCH)

        var pageNum = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
        var canvas = page.canvas
        var y = MARGIN.toFloat()

        // En-tête
        canvas.drawText("MoMo Fin", MARGIN.toFloat(), y + 6f, title)
        y += 28f
        canvas.drawText("Rapport du " + dfDay.format(Date(dayMillis)).replaceFirstChar { it.uppercase() },
            MARGIN.toFloat(), y, subtitle)
        y += 20f

        // Colonnes : Date/Heure | Type | Montant | Numéro | Référence
        val xDate = MARGIN.toFloat()
        val xType = xDate + 105
        val xAmount = xType + 55
        val xPhone = xAmount + 95
        val xRef = xPhone + 115
        val tableEnd = (PAGE_W - MARGIN).toFloat()
        val rowH = 26f
        val dfRow = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH)

        // Bandeau d'entête
        canvas.drawRect(MARGIN.toFloat(), y, tableEnd, y + rowH,
            Paint().apply { color = 0xFF1565C0.toInt() })
        val textY = y + 17f
        canvas.drawText("Date/Heure", xDate + 8, textY, headerP)
        canvas.drawText("Type", xType + 8, textY, headerP)
        canvas.drawText("Montant", xAmount + 8, textY, headerP)
        canvas.drawText("Numéro", xPhone + 8, textY, headerP)
        canvas.drawText("Référence", xRef + 8, textY, headerP)
        y += rowH

        var totalRecu = 0.0
        var totalSortie = 0.0
        val rowBg = Paint().apply { color = 0xFFF5F5F5.toInt() }
        var altRow = false

        for ((idx, tx) in txs.sortedByDescending { it.timestamp }.withIndex()) {
            if (y + rowH > PAGE_H - 80) {
                pdf.finishPage(page); pageNum++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
                canvas = page.canvas; y = MARGIN.toFloat()
            }
            if (altRow) canvas.drawRect(MARGIN.toFloat(), y, tableEnd, y + rowH, rowBg)
            altRow = !altRow

            val ty = y + 17f
            val typeLabel = when (tx.type) {
                TxType.RECU -> "Retrait"
                TxType.SORTIE -> "Dépôt"
                else -> "—"
            }
            val typePaint = Paint(cellBoldP).apply {
                color = when (tx.type) {
                    TxType.RECU -> recuColor
                    TxType.SORTIE -> sortieColor
                    else -> 0xFF888888.toInt()
                }
            }
            canvas.drawText(dfRow.format(Date(tx.timestamp)), xDate + 8, ty, cellP)
            canvas.drawText(typeLabel, xType + 8, ty, typePaint)
            canvas.drawText("${nf.format(tx.amount)} ${tx.currency}", xAmount + 8, ty, cellBoldP)
            canvas.drawText(tx.phoneNumber.ifBlank { "—" }, xPhone + 8, ty, cellP)
            canvas.drawText(truncate(tx.reference.ifBlank { "—" }, 18), xRef + 8, ty, cellP)
            y += rowH

            when (tx.type) {
                TxType.RECU -> totalRecu += tx.amount
                TxType.SORTIE -> totalSortie += tx.amount
                else -> {}
            }
        }

        // Totaux en bas
        y += 20f
        canvas.drawLine(MARGIN.toFloat(), y, tableEnd, y, Paint().apply { strokeWidth = 1f; color = 0xFFBBBBBB.toInt() })
        y += 18f
        canvas.drawText("Total Retrait : ${nf.format(totalRecu)}", MARGIN.toFloat(), y,
            Paint(totalP).apply { color = recuColor })
        y += 18f
        canvas.drawText("Total Dépôt : ${nf.format(totalSortie)}", MARGIN.toFloat(), y,
            Paint(totalP).apply { color = sortieColor })
        y += 18f
        canvas.drawText("Solde : ${nf.format(totalRecu - totalSortie)}", MARGIN.toFloat(), y,
            Paint(totalP).apply { color = 0xFF1565C0.toInt() })

        // Pied de page
        canvas.drawText("Généré par MoMo Fin · ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH).format(Date())}",
            MARGIN.toFloat(), (PAGE_H - 24).toFloat(),
            Paint().apply { textSize = 9f; color = 0xFF999999.toInt() })

        pdf.finishPage(page)
    }

    // ----- PDF complet (tous les jours + carnets) — version précédente -----
    private fun generateAllDays(pdf: PdfDocument, transactions: List<Transaction>, folderStore: FolderStore?) {
        val title = Paint().apply { textSize = 18f; isFakeBoldText = true }
        val h2 = Paint().apply { textSize = 13f; isFakeBoldText = true }
        val text = Paint().apply { textSize = 9f }
        val small = Paint().apply { textSize = 9f; color = 0xFF555555.toInt() }
        val rule = Paint().apply { strokeWidth = 0.6f; color = 0xFFBBBBBB.toInt() }
        val header = Paint().apply { textSize = 9f; isFakeBoldText = true }

        val nf = NumberFormat.getNumberInstance(Locale.FRENCH)
        val dfDay = SimpleDateFormat("EEEE dd MMMM yyyy", Locale.FRENCH)
        val dfNow = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH)

        val grouped = transactions
            .sortedByDescending { it.timestamp }
            .groupBy { TransactionParser.dayKey(it.timestamp) }
            .toSortedMap(compareByDescending { it })

        var pageNum = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
        var canvas = page.canvas
        var y = MARGIN.toFloat()

        canvas.drawText("MoMo Fin — Rapport global", MARGIN.toFloat(), y, title); y += 18f
        canvas.drawText("Généré le ${dfNow.format(Date())}", MARGIN.toFloat(), y, small); y += 14f
        canvas.drawLine(MARGIN.toFloat(), y, (PAGE_W - MARGIN).toFloat(), y, rule); y += 12f

        fun newPageIfNeeded(minRoom: Float) {
            if (y + minRoom > PAGE_H - MARGIN) {
                pdf.finishPage(page); pageNum++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
                canvas = page.canvas; y = MARGIN.toFloat()
            }
        }

        var totalRecu = 0.0; var totalSortie = 0.0
        val xType = MARGIN.toFloat()
        val xAmount = xType + 60
        val xPhone = xAmount + 110
        val xRef = xPhone + 130

        for ((dayMillis, txs) in grouped) {
            newPageIfNeeded(60f)
            canvas.drawText(dfDay.format(Date(dayMillis)).replaceFirstChar { it.uppercase() }, MARGIN.toFloat(), y, h2)
            y += 14f
            canvas.drawText("Type", xType, y, header)
            canvas.drawText("Montant", xAmount, y, header)
            canvas.drawText("Numéro", xPhone, y, header)
            canvas.drawText("Référence", xRef, y, header)
            y += 4f; canvas.drawLine(MARGIN.toFloat(), y, (PAGE_W - MARGIN).toFloat(), y, rule); y += 11f

            var dayRecu = 0.0; var daySortie = 0.0
            for (tx in txs) {
                newPageIfNeeded(12f)
                val lbl = when (tx.type) { TxType.RECU -> "Retrait"; TxType.SORTIE -> "Dépôt"; else -> "—" }
                canvas.drawText(lbl, xType, y, text)
                canvas.drawText("${nf.format(tx.amount)} ${tx.currency}", xAmount, y, text)
                canvas.drawText(tx.phoneNumber.ifBlank { "—" }, xPhone, y, text)
                canvas.drawText(truncate(tx.reference.ifBlank { "—" }, 22), xRef, y, text)
                y += 12f
                when (tx.type) { TxType.RECU -> dayRecu += tx.amount; TxType.SORTIE -> daySortie += tx.amount; else -> {} }
            }
            y += 4f; canvas.drawLine(MARGIN.toFloat(), y, (PAGE_W - MARGIN).toFloat(), y, rule); y += 12f
            canvas.drawText("Retrait : ${nf.format(dayRecu)}   |   Dépôt : ${nf.format(daySortie)}   |   Solde : ${nf.format(dayRecu - daySortie)}",
                MARGIN.toFloat(), y, Paint().apply { textSize = 10f; isFakeBoldText = true })
            y += 22f
            totalRecu += dayRecu; totalSortie += daySortie
        }

        // Carnets
        val folders = folderStore?.allFolders() ?: emptyList()
        if (folders.isNotEmpty()) {
            newPageIfNeeded(40f)
            canvas.drawText("Mes Comptes", MARGIN.toFloat(), y, h2); y += 16f
            for (folder in folders) {
                newPageIfNeeded(50f)
                canvas.drawText("📓 ${folder.name}", MARGIN.toFloat(), y, Paint().apply { textSize = 12f; isFakeBoldText = true })
                y += 14f
                val entries = folderStore!!.entries(folder.id)
                var fE = 0.0; var fS = 0.0
                for (e in entries) {
                    newPageIfNeeded(12f)
                    val lbl = if (e.type == TxType.RECU) "Entrée" else "Sortie"
                    canvas.drawText("  ${dfNow.format(Date(e.timestamp))}   $lbl   ${nf.format(e.amount)}   ${e.note}",
                        MARGIN.toFloat(), y, text)
                    y += 11f
                    if (e.type == TxType.RECU) fE += e.amount else fS += e.amount
                }
                y += 4f
                canvas.drawText("  Total — Entrée : ${nf.format(fE)} | Sortie : ${nf.format(fS)} | Solde : ${nf.format(fE - fS)}",
                    MARGIN.toFloat(), y, Paint().apply { textSize = 10f; isFakeBoldText = true; color = 0xFF1565C0.toInt() })
                y += 18f
            }
        }

        newPageIfNeeded(40f)
        canvas.drawLine(MARGIN.toFloat(), y, (PAGE_W - MARGIN).toFloat(), y, rule); y += 16f
        canvas.drawText("TOTAL — Retrait : ${nf.format(totalRecu)} | Dépôt : ${nf.format(totalSortie)} | Solde : ${nf.format(totalRecu - totalSortie)}",
            MARGIN.toFloat(), y, Paint().apply { textSize = 12f; isFakeBoldText = true })
        pdf.finishPage(page)
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
            uri?.let { context.contentResolver.openOutputStream(it)?.use { out -> internal.inputStream().use { input -> input.copyTo(out) } } }
        }
        return internal
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max - 1) + "…"
}
