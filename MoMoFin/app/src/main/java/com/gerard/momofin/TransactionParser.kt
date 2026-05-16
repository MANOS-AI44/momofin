package com.gerard.momofin

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Parser multi-opérateur pour SMS Mobile Money.
 *
 * Extrait :
 *  - Type (Reçu / Sortie)
 *  - Montant + Devise
 *  - Référence / ID de transaction
 *  - Date + heure (depuis le corps si présent, sinon depuis le timestamp du SMS)
 */
object TransactionParser {

    // Devises courantes en Afrique francophone et anglophone
    private val CURRENCY = "(RWF|XOF|XAF|UGX|GHS|KES|TZS|FCFA|CFA|USD|EUR)"

    // Montant : 1,000.50 / 1.000,50 / 100000 / 100 000
    private val AMOUNT_PATTERN = Pattern.compile(
        "(?:^|[^A-Za-z0-9])([0-9][0-9 ,\\.]{0,15})\\s*$CURRENCY",
        Pattern.CASE_INSENSITIVE
    )
    private val AMOUNT_PATTERN_2 = Pattern.compile(
        "$CURRENCY\\s*([0-9][0-9 ,\\.]{0,15})",
        Pattern.CASE_INSENSITIVE
    )

    // Référence / ID — formats courants
    private val REF_PATTERNS = listOf(
        Pattern.compile("(?:Financial Transaction Id|Transaction Id|TxId|TXID|Txn Id|Trans\\.? Id)\\s*[:#]?\\s*([A-Z0-9]+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:Ref(?:erence|érence)?|Réf)\\.?\\s*[:#]?\\s*([A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bID\\s*[:#]\\s*([A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b([0-9]{8,16})\\b") // suite numérique longue (fallback)
    )

    // Date/heure dans le corps : 16/05/2026 14:32 / 2026-05-16 14:32:01 / 16-05-2026 14:32
    private val DATE_PATTERNS = listOf(
        "dd/MM/yyyy HH:mm:ss",
        "dd/MM/yyyy HH:mm",
        "dd-MM-yyyy HH:mm:ss",
        "dd-MM-yyyy HH:mm",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm"
    )
    private val DATE_REGEX = Pattern.compile(
        "(\\d{2,4}[/-]\\d{2}[/-]\\d{2,4}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)"
    )

    private val RECU_KEYWORDS = listOf(
        "received", "you have received", "credited", "credit",
        "reçu", "vous avez reçu", "crédité"
    )
    private val SORTIE_KEYWORDS = listOf(
        "sent", "you have sent", "payment of", "paid", "debited", "withdrawn",
        "envoyé", "vous avez envoyé", "paiement", "retrait", "débité", "transfert vers"
    )

    fun parse(rawId: Long, sender: String, body: String, smsTimestamp: Long, operator: String): Transaction {
        val low = body.lowercase()

        val type = when {
            RECU_KEYWORDS.any { low.contains(it) } -> TxType.RECU
            SORTIE_KEYWORDS.any { low.contains(it) } -> TxType.SORTIE
            else -> TxType.INCONNU
        }

        val (amount, currency) = extractAmount(body)
        val reference = extractReference(body)
        val timestamp = extractDate(body) ?: smsTimestamp

        return Transaction(
            rawId = rawId,
            operator = operator,
            type = type,
            amount = amount,
            currency = currency,
            reference = reference,
            timestamp = timestamp,
            rawSender = sender,
            rawBody = body
        )
    }

    private fun extractAmount(body: String): Pair<Double, String> {
        AMOUNT_PATTERN.matcher(body).let { m ->
            if (m.find()) {
                val raw = m.group(1)?.trim() ?: ""
                val cur = m.group(2)?.uppercase() ?: ""
                return normalize(raw) to cur
            }
        }
        AMOUNT_PATTERN_2.matcher(body).let { m ->
            if (m.find()) {
                val cur = m.group(1)?.uppercase() ?: ""
                val raw = m.group(2)?.trim() ?: ""
                return normalize(raw) to cur
            }
        }
        return 0.0 to ""
    }

    private fun normalize(raw: String): Double {
        // "1,000.50" -> 1000.50 / "1.000,50" -> 1000.50 / "1 000" -> 1000
        val cleaned = raw.replace(" ", "")
        val withDotDecimal = when {
            cleaned.count { it == ',' } == 1 && cleaned.indexOf(',') > cleaned.indexOf('.') ->
                cleaned.replace(".", "").replace(",", ".")
            cleaned.count { it == '.' } == 1 && cleaned.indexOf('.') > cleaned.indexOf(',') ->
                cleaned.replace(",", "")
            else -> cleaned.replace(",", "").replace(" ", "")
        }
        return withDotDecimal.toDoubleOrNull() ?: 0.0
    }

    private fun extractReference(body: String): String {
        for (p in REF_PATTERNS) {
            val m = p.matcher(body)
            if (m.find()) {
                val g = m.group(1) ?: continue
                if (g.length in 4..32) return g.uppercase()
            }
        }
        return ""
    }

    private fun extractDate(body: String): Long? {
        val m = DATE_REGEX.matcher(body)
        if (!m.find()) return null
        val raw = m.group(1) ?: return null
        for (fmt in DATE_PATTERNS) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.FRENCH)
                sdf.isLenient = false
                return sdf.parse(raw)?.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** Retourne le début du jour (00:00:00) pour grouper. */
    fun dayKey(timestamp: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = timestamp
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
