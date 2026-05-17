package com.gerard.momofin

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

object TransactionParser {

    private val CURRENCY = "(RWF|XOF|XAF|UGX|GHS|KES|TZS|FCFA|CFA|USD|EUR)"

    private val AMOUNT_PATTERN = Pattern.compile(
        "(?:^|[^A-Za-z0-9])([0-9][0-9 ,\\.]{0,15})\\s*$CURRENCY",
        Pattern.CASE_INSENSITIVE
    )
    private val AMOUNT_PATTERN_2 = Pattern.compile(
        "$CURRENCY\\s*([0-9][0-9 ,\\.]{0,15})",
        Pattern.CASE_INSENSITIVE
    )

    private val REF_PATTERNS = listOf(
        Pattern.compile("(?:Financial Transaction Id|Transaction Id|TxId|TXID|Txn Id|Trans\\.? Id)\\s*[:#]?\\s*([A-Z0-9]+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:Ref(?:erence|érence)?|Réf)\\.?\\s*[:#]?\\s*([A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bID\\s*[:#]\\s*([A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b([0-9]{8,16})\\b")
    )

    // Numéro précédé de from/to/de/vers/à : on prend en priorité
    private val PHONE_NEAR_KEYWORD = Pattern.compile(
        "(?i)(?:from|to|de|vers|à|au|chez)\\s+(?:[^()\\d\\n]{0,40}?)?\\(?\\s*(\\+?\\d[\\d\\s\\-\\.]{6,18}\\d)\\s*\\)?"
    )
    // Numéro entre parenthèses : ex. "Jean (07 12 34 56 78)"
    private val PHONE_IN_PARENS = Pattern.compile(
        "\\((\\+?\\d[\\d\\s\\-\\.]{6,18}\\d)\\)"
    )
    // Numéro avec + (international)
    private val PHONE_INTL = Pattern.compile(
        "(\\+\\d[\\d\\s\\-\\.]{6,18}\\d)"
    )
    // Fallback : suite de chiffres ressemblant à un téléphone
    private val PHONE_LOCAL = Pattern.compile(
        "(?<![\\d])(0\\d[\\d\\s\\-\\.]{6,12}\\d)(?![\\d])"
    )

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
        val phone = extractPhone(body)
        val timestamp = extractDate(body) ?: smsTimestamp

        return Transaction(
            rawId = rawId,
            operator = operator,
            type = type,
            amount = amount,
            currency = currency,
            reference = reference,
            phoneNumber = phone,
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

    private fun cleanPhone(raw: String): String {
        // garde + et chiffres, et limite à 8-15 chiffres
        val digitsOnly = raw.replace(Regex("[^\\d+]"), "")
        val justDigits = digitsOnly.replace("+", "")
        if (justDigits.length !in 8..15) return ""
        return if (digitsOnly.startsWith("+")) "+$justDigits" else justDigits
    }

    private fun extractPhone(body: String): String {
        // Priorité : numéro précédé d'un mot-clé (from, to, de, vers, à)
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
