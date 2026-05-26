package com.gerard.momofin

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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
            val typeLabel = when (tx.subtype) {
                TxSubtype.DEPOT -> "Dépôt"
                TxSubtype.RETRAIT -> "Retrait"
                TxSubtype.TRANSFERT_ENVOYE -> "T. envoyé"
                TxSubtype.TRANSFERT_RECU -> "T. reçu"
                else -> when (tx.type) {
                    TxType.RECU -> "Retrait"
                    TxType.SORTIE -> "Dépôt"
                    else -> "—"
                }
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
            val op1 = TransactionParser.phoneOperator(tx.phoneNumber)
                val ph1 = if (tx.phoneNumber.isBlank()) "—" else tx.phoneNumber + (if (op1.isNotEmpty()) " ($op1)" else "")
                canvas.drawText(ph1, xPhone + 8, ty, cellP)
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
                val lbl = when (tx.subtype) {
                    TxSubtype.DEPOT -> "Dépôt"
                    TxSubtype.RETRAIT -> "Retrait"
                    TxSubtype.TRANSFERT_ENVOYE -> "T. envoyé"
                    TxSubtype.TRANSFERT_RECU -> "T. reçu"
                    else -> when (tx.type) { TxType.RECU -> "Retrait"; TxType.SORTIE -> "Dépôt"; else -> "—" }
                }
                canvas.drawText(lbl, xType, y, text)
                canvas.drawText("${nf.format(tx.amount)} ${tx.currency}", xAmount, y, text)
                val op2 = TransactionParser.phoneOperator(tx.phoneNumber)
                val ph2 = if (tx.phoneNumber.isBlank()) "—" else tx.phoneNumber + (if (op2.isNotEmpty()) " ($op2)" else "")
                canvas.drawText(ph2, xPhone, y, text)
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

    /** Genere le PDF d'un recu (au moins une demi-page). cachet optionnel (bitmap). */
    fun generateReceipt(context: Context, r: Receipt, company: String, cachet: Bitmap?): File {
        val pdf = PdfDocument()
        val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create()
        val page = pdf.startPage(info)
        val cv = page.canvas

        // Deux exemplaires identiques sur la meme page (un pour le client, un a garder)
        drawReceiptCopy(cv, r, company, cachet, 36f, "EXEMPLAIRE CLIENT")

        val mid = 408f
        val cut = Paint().apply {
            color = 0xFF999999.toInt(); strokeWidth = 1f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
        }
        cv.drawLine(MARGIN.toFloat(), mid, (PAGE_W - MARGIN).toFloat(), mid, cut)
        cv.drawText("DECOUPER ICI - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -",
            MARGIN.toFloat(), mid - 4f, Paint().apply { textSize = 8f; color = 0xFF999999.toInt() })

        drawReceiptCopy(cv, r, company, cachet, 420f, "SOUCHE (a garder)")

        pdf.finishPage(page)
        val fileName = "Recu_${r.clientName.replace(Regex("[^A-Za-z0-9]"), "_")}_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(r.timestamp))}.pdf"
        val out = writePdf(context, pdf, fileName)
        pdf.close()
        return out
    }

    /** Dessine UNE copie compacte du recu dans une zone commencant a `top` (hauteur ~350). */
    private fun drawReceiptCopy(cv: Canvas, r: Receipt, company: String, cachet: Bitmap?, top: Float, tag: String) {
        val df = SimpleDateFormat("dd/MM/yyyy 'a' HH:mm", Locale.FRENCH)
        val nf = NumberFormat.getNumberInstance(Locale.FRENCH)
        val left = MARGIN.toFloat()
        val right = (PAGE_W - MARGIN).toFloat()

        val pHeader = Paint().apply { textSize = 13f; isFakeBoldText = true; color = 0xFF1565C0.toInt() }
        val pTitle = Paint().apply { textSize = 15f; isFakeBoldText = true; color = 0xFF0D47A1.toInt() }
        val pLabel = Paint().apply { textSize = 8f; color = 0xFF6B7280.toInt() }
        val pVal = Paint().apply { textSize = 11f; color = 0xFF111827.toInt() }
        val pValBold = Paint().apply { textSize = 12f; isFakeBoldText = true; color = 0xFF111827.toInt() }
        val pBig = Paint().apply { textSize = 16f; isFakeBoldText = true; color = 0xFF059669.toInt() }
        val pSmall = Paint().apply { textSize = 8f; color = 0xFF555555.toInt() }
        val pTag = Paint().apply { textSize = 8f; color = 0xFF9CA3AF.toInt(); textAlign = Paint.Align.RIGHT }
        val pCond = Paint().apply { textSize = 9f; color = 0xFF374151.toInt() }
        val rule = Paint().apply { color = 0xFFBBBBBB.toInt(); strokeWidth = 1f }
        val ruleStrong = Paint().apply { color = 0xFF0D47A1.toInt(); strokeWidth = 1.5f }

        var y = top + 12f
        cv.drawText(tag, right, top + 8f, pTag)
        val entete = (if (company.isNotBlank()) company.uppercase() else "MOMO FIN") +
                     (if (r.partnerName.isNotBlank()) "  -  " + r.partnerName.uppercase() else "")
        cv.drawText(entete, left, y, pHeader); y += 8f
        cv.drawLine(left, y, right, y, ruleStrong); y += 20f
        cv.drawText("RECU", left, y, pTitle)
        cv.drawText("No ${r.clientId.takeLast(8)}", right - 150f, y - 10f, pSmall)
        cv.drawText(df.format(Date(r.timestamp)), right - 150f, y, pSmall)
        y += 22f
        cv.drawText("CLIENT", left, y, pLabel); y += 13f
        cv.drawText(r.clientName, left, y, pValBold); y += 24f
        cv.drawText("OBJET", left, y, pLabel)
        cv.drawText("MONTANT", left + 270f, y, pLabel); y += 14f
        cv.drawText(r.objet, left, y, pVal)
        cv.drawText("${nf.format(r.amount)} ${r.currency}", left + 270f, y + 2f, pBig); y += 22f
        cv.drawLine(left, y, right, y, rule); y += 16f
        cv.drawText("CONDITIONS", left, y, pLabel); y += 12f
        var cond = if (r.conditions.isNotBlank()) r.conditions
                   else "Aucune reclamation ne sera acceptee passe un delai de 48 heures. Tout achat effectue est sous l'entiere responsabilite du client."
        cond = cond.replace(Regex("\\s+"), " ").trim()
        if (cond.length > 300) cond = cond.substring(0, 297) + "..."
        drawWrapped(cv, cond, left, y, right - left, 12f, pCond)
        val sigY = top + 330f
        if (cachet != null) {
            val maxW = 110; val maxH = 60
            val ratio = minOf(maxW.toFloat() / cachet.width, maxH.toFloat() / cachet.height)
            val w = (cachet.width * ratio).toInt(); val h = (cachet.height * ratio).toInt()
            val dst = Rect(right.toInt() - w, sigY.toInt() - h, right.toInt(), sigY.toInt())
            cv.drawBitmap(cachet, null, dst, null)
        }
        cv.drawText("Le client (lu et approuve)", left, sigY + 16f, pSmall)
        cv.drawText("Cachet & signature", right - 130f, sigY + 16f, pSmall)
    }

    /** Dessine un texte en l'enveloppant sur plusieurs lignes. Retourne le nouveau y. */
    private fun drawWrapped(cv: Canvas, text: String, x: Float, startY: Float, maxWidth: Float, lineH: Float, paint: Paint): Float {
        var y = startY
        val words = text.split(Regex("\\s+"))
        var line = StringBuilder()
        for (w in words) {
            val test = if (line.isEmpty()) w else "$line $w"
            if (paint.measureText(test) > maxWidth && line.isNotEmpty()) {
                cv.drawText(line.toString(), x, y, paint); y += lineH
                line = StringBuilder(w)
            } else {
                line = StringBuilder(test)
            }
        }
        if (line.isNotEmpty()) { cv.drawText(line.toString(), x, y, paint); y += lineH }
        return y
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
