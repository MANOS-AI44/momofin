package com.gerard.momofin

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

object TransactionParser {

    // F en fin (Côte d'Ivoire) + autres devises
    private val CURRENCY = "(RWF|XOF|XAF|UGX|GHS|KES|TZS|FCFA|CFA|USD|EUR|F(?![a-zA-Z]))"

    private val AMOUNT_PATTERN = Pattern.compile(
        "(?:^|[^A-Za-z0-9])([0-9][0-9 ,\\.]{0,15})\\s*$CURRENCY",
        Pattern.CASE_INSENSITIVE
    )

    // ID Transaction / Transaction ID / Ref... (autorise points et tirets)
    private val REF_PATTERNS = listOf(
        Pattern.compile("(?:ID\\s+Transaction|Transaction\\s+ID|Transaction\\s+Id|Financial Transaction Id|TxId|TXID|Txn Id|Trans\\.? Id)\\s*[:#]?\\s*([A-Za-z0-9][A-Za-z0-9\\.\\-_]{3,40})", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:Ref(?:erence|érence)?|Réf)\\.?\\s*[:#]?\\s*([A-Za-z0-9][A-Za-z0-9\\.\\-_]{3,40})", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bID\\s*[:#]\\s*([A-Za-z0-9][A-Za-z0-9\\.\\-_]{3,40})", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b([0-9]{8,16})\\b")
    )

    private val PHONE_NEAR_KEYWORD = Pattern.compile(
        "(?i)\\b(?:from|to|de|vers|à|au|chez)\\b\\s+(?:le\\s+|la\\s+|du\\s+|des\\s+|aux?\\s+)?(?:[^()\\d\\n]{0,40}?)?\\(?\\s*(\\+?\\d[\\d\\s\\-\\.]{6,18}\\d)\\s*\\)?"
    )
    private val PHONE_IN_PARENS = Pattern.compile("\\((\\+?\\d[\\d\\s\\-\\.]{6,18}\\d)\\)")
    private val PHONE_INTL = Pattern.compile("(\\+\\d[\\d\\s\\-\\.]{6,18}\\d)")
    private val PHONE_LOCAL = Pattern.compile("(?<![\\d])(0\\d[\\d\\s\\-\\.]{6,12}\\d)(?![\\d])")

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
        "reçu", "recu", "vous avez reçu", "vous avez recu", "crédité", "credite"
    )
    private val SORTIE_KEYWORDS = listOf(
        "sent", "you have sent", "payment of", "paid", "debited", "withdrawn",
        "envoyé", "envoye", "vous avez envoyé", "vous avez envoye",
        "paiement", "retrait", "débité", "debite",
        "transfert vers", "depot vers", "dépôt vers"
    )

    // Mots-clés indiquant que le montant qui suit est le MONTANT principal
    private val AMOUNT_GOOD_KEYWORDS = listOf(
        "montant", "recu", "reçu", "envoye", "envoyé",
        "retrait", "retrait de", "depot vers", "dépôt vers",
        "payer le montant", "payer", "payez", "transfere", "transféré"
    )
    private val AMOUNT_BAD_KEYWORDS = listOf(
        "solde", "frais", "commission", "nouveau solde", "balance"
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
        val phone = extractPhone(body)
        val timestamp = extractDate(body) ?: smsTimestamp

        return Transaction(
            rawId = rawId,
            operator = operator,
            type = type,
            amount = amount,
            currency = if (currency == "F") "FCFA" else currency,
            reference = reference,
            phoneNumber = phone,
            timestamp = timestamp,
            rawSender = sender,
            rawBody = body
        )
    }

    private fun extractAmount(body: String): Pair<Double, String> {
        // Scanne TOUS les matchs et priorise selon le contexte
        data class Cand(val amount: Double, val cur: String, val priority: Int, val pos: Int)
        val cands = mutableListOf<Cand>()
        val m = AMOUNT_PATTERN.matcher(body)
        while (m.find()) {
            val raw = m.group(1)?.trim() ?: continue
            val cur = m.group(2)?.uppercase() ?: ""
            val amount = normalize(raw)
            if (amount <= 0) continue
            val ctxStart = maxOf(0, m.start() - 30)
            val ctx = body.substring(ctxStart, m.start()).lowercase()
            val priority = when {
                AMOUNT_BAD_KEYWORDS.any { ctx.contains(it) } -> 0
                AMOUNT_GOOD_KEYWORDS.any { ctx.contains(it) } -> 3
                else -> 1
            }
            cands.add(Cand(amount, cur, priority, m.start()))
        }
        // Trier par priorité décroissante puis position croissante (premier match équivalent)
        val best = cands.sortedWith(compareByDescending<Cand> { it.priority }.thenBy { it.pos }).firstOrNull()
        return if (best != null) best.amount to best.cur else 0.0 to ""
    }

    private fun normalize(raw: String): Double {
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
                val trimmed = g.trimEnd('.', ',', ';', ':', '-', '_')
                if (trimmed.length in 4..40) return trimmed.uppercase()
            }
        }
        return ""
    }

    private fun cleanPhone(raw: String): String {
        val digitsOnly = raw.replace(Regex("[^\\d+]"), "")
        val justDigits = digitsOnly.replace("+", "")
        if (justDigits.length !in 8..15) return ""
        return if (digitsOnly.startsWith("+")) "+$justDigits" else justDigits
    }

    private fun extractPhone(body: String): String {
        for (p in listOf(PHONE_NEAR_KEYWORD, PHONE_IN_PARENS, PHONE_INTL, PHONE_LOCAL)) {
            val m = p.matcher(body)
            while (m.find()) {
                val raw = m.group(1) ?: continue
                val clean = cleanPhone(raw)
                if (clean.isNotEmpty()) return clean
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
