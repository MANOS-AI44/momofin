package com.gerard.momofin

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

object TransactionParser {

    /** Normalise un numero CI : strip prefixe 225 (+225, 225, 00225) -> 0XXX */
    fun normalizePhone(phone: String?): String {
        if (phone.isNullOrBlank()) return ""
        var digits = phone.replace(Regex("[^\\d]"), "")
        if (digits.startsWith("00225")) digits = digits.substring(5)
        else if (digits.startsWith("225") && digits.length > 10) digits = digits.substring(3)
        if (digits.length == 8) digits = "0$digits"
        if (digits.length == 9 && !digits.startsWith("0")) digits = "0$digits"
        return digits
    }

    /** Operateur deduit du prefixe local : 07/08/09 = Orange, 05/04/06 = MTN, 01/02/03 = MOOV */
    fun phoneOperator(phone: String?): String {
        val n = normalizePhone(phone)
        if (n.length != 10) return ""
        return when (n.substring(0, 2)) {
            "07", "08", "09" -> "Orange"
            "05", "04", "06" -> "MTN"
            "01", "02", "03" -> "MOOV"
            else -> ""
        }
    }


    private val CURRENCY = "(RWF|XOF|XAF|UGX|GHS|KES|TZS|FCFA|CFA|USD|EUR|F(?![a-zA-Z]))"

    private val AMOUNT_PATTERN = Pattern.compile(
        "(?:^|[^A-Za-z0-9])([0-9][0-9 ,\\.]{0,15})\\s*$CURRENCY",
        Pattern.CASE_INSENSITIVE
    )

    // === PATTERNS CANONIQUES STRICTS — base sur les 6 SMS reels ===
    private val PAT_SORTIE_GENERIC = Pattern.compile(
        "(?i)vous\\s+avez\\s+envoy[eé][\\s\\S]{0,250}?(?:FCFA|CFA|XOF|XAF|RWF|\\bF\\b)"
    )
    private val PAT_SORTIE_ORANGE = Pattern.compile(
        "(?i)(?:^|[\\s.])(?:le\\s+)?d[eé]p[oô]?t\\s+vers\\s+(?:le\\s+)?\\+?\\d[\\d\\s\\-\\.]{6,18}\\d[\\s\\S]{0,80}?(?:est\\s+r[eé]ussi|reussi)"
    )
    private val PAT_RECU_DIRECT = Pattern.compile(
        "(?i)vous\\s+avez\\s+re[çc]u[\\s\\S]{0,250}?(?:FCFA|CFA|XOF|XAF|RWF|\\bF\\b)"
    )
    private val PAT_RECU_INDIRECT = Pattern.compile(
        "(?i)(?:le\\s+)?num[eé]ro\\s+\\+?\\d[\\d\\s\\-\\.]{6,18}\\d\\s+a\\s+envoy[eé][\\s\\S]{0,250}?sur\\s+votre\\s+num[eé]ro"
    )
    private val PAT_RECU_ORANGE = Pattern.compile(
        "(?i)retrait\\s+de\\s+\\+?\\d[\\d\\s\\-\\.]{6,18}\\d[\\s\\S]{0,40}?effectu[eé]"
    )
    private val PAT_RECU_MTN = Pattern.compile(
        "(?i)retrait\\s+initi[eé][\\s\\S]{0,250}?(?:a\\s+[eé]t[eé]\\s+effectu[eé]|payer\\s+le\\s+montant)"
    )

    private val REF_PATTERNS = listOf(
        Pattern.compile("(?:ID\\s+Transaction|Transaction\\s+ID|Transaction\\s+Id|Financial Transaction Id|TxId|TXID|Txn Id|Trans\\.? Id)\\s*[:#]?\\s*([A-Za-z0-9][A-Za-z0-9\\.\\-_]{3,40})", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(?:Ref(?:erence|érence)?|Réf)\\.?\\s*[:#]?\\s*([A-Za-z0-9][A-Za-z0-9\\.\\-_]{3,40})", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bID\\s*[:#]?\\s*([A-Za-z0-9][A-Za-z0-9\\.\\-_]{3,40})", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b([0-9]{8,16})\\b")
    )

    private val PHONE_SENDER = Pattern.compile("(?i)(?:le\\s+num[eé]ro\\s+|num[eé]ro\\s+)?(\\+?\\d[\\d\\s\\-\\.]{6,18}\\d)\\s+a\\s+envoy[eé]")
    private val PHONE_NUMERO = Pattern.compile("(?i)\\bnum[eé]ro\\s+(\\+?\\d[\\d\\s\\-\\.]{6,18}\\d)")
    private val PHONE_RETRAIT_DE = Pattern.compile("(?i)retrait\\s+de\\s+(\\+?\\d[\\d\\s\\-\\.]{6,18}\\d)")
    private val PHONE_DEPOT_VERS = Pattern.compile("(?i)d[eé]p[oô]?t\\s+vers\\s+(?:le\\s+)?(\\+?\\d[\\d\\s\\-\\.]{6,18}\\d)")
    private val PHONE_NEAR_KEYWORD = Pattern.compile("(?i)\\b(?:from|to|de|du|vers|à|a|au|chez)\\b\\s+(?:le\\s+|la\\s+|du\\s+|des\\s+|aux?\\s+)?(?:[^()\\d\\n]{0,40}?)?\\(?\\s*(\\+?\\d[\\d\\s\\.]{6,18}\\d)\\s*\\)?")
    private val PHONE_INTL = Pattern.compile("(\\+\\d[\\d\\s\\.]{6,18}\\d)")
    private val PHONE_LONG = Pattern.compile("(?<![\\d])(\\d{10,15})(?![\\d])")
    private val PHONE_LOCAL = Pattern.compile("(?<![\\d])(0\\d[\\d\\s\\.]{6,12}\\d)(?![\\d])")

    private val DATE_PATTERNS = listOf(
        "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm",
        "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm",
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"
    )
    private val DATE_REGEX = Pattern.compile(
        "(\\d{2,4}[/-]\\d{2}[/-]\\d{2,4})\\s*(?:à\\s+|a\\s+)?(\\d{1,2}:\\d{2}(?::\\d{2})?)"
    )

    private val AMOUNT_GOOD_KEYWORDS = listOf("montant", "recu", "reçu", "envoye", "envoyé", "a envoye", "a envoyé", "payer le montant")
    private val AMOUNT_BAD_KEYWORDS = listOf("solde", "frais", "commission", "nouveau solde", "balance")

    private fun detectType(body: String): TxType? {
        if (PAT_RECU_INDIRECT.matcher(body).find()) return TxType.RECU
        if (PAT_RECU_DIRECT.matcher(body).find()) return TxType.RECU
        if (PAT_RECU_ORANGE.matcher(body).find()) return TxType.RECU
        if (PAT_RECU_MTN.matcher(body).find()) return TxType.RECU
        if (PAT_SORTIE_GENERIC.matcher(body).find()) return TxType.SORTIE
        if (PAT_SORTIE_ORANGE.matcher(body).find()) return TxType.SORTIE
        return null
    }

    fun parse(rawId: Long, sender: String, body: String, smsTimestamp: Long, operator: String): Transaction? {
        val type = detectType(body) ?: return null
        val (amount, currency) = extractAmount(body)
        if (amount <= 0.0) return null
        val reference = extractReference(body)
        val phone = normalizePhone(extractPhone(body))
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
        PHONE_SENDER.matcher(body).let { if (it.find()) { val c = cleanPhone(it.group(1) ?: ""); if (c.isNotEmpty()) return c } }
        PHONE_RETRAIT_DE.matcher(body).let { if (it.find()) { val c = cleanPhone(it.group(1) ?: ""); if (c.isNotEmpty()) return c } }
        PHONE_DEPOT_VERS.matcher(body).let { if (it.find()) { val c = cleanPhone(it.group(1) ?: ""); if (c.isNotEmpty()) return c } }
        val m2 = PHONE_NUMERO.matcher(body)
        while (m2.find()) {
            val ctxStart = maxOf(0, m2.start() - 12)
            val ctx = body.substring(ctxStart, m2.start()).lowercase()
            if (ctx.contains("votre")) continue
            val c = cleanPhone(m2.group(1) ?: continue)
            if (c.isNotEmpty()) return c
        }
        for (p in listOf(PHONE_NEAR_KEYWORD, PHONE_INTL, PHONE_LONG, PHONE_LOCAL)) {
            val m = p.matcher(body)
            while (m.find()) {
                val c = cleanPhone(m.group(1) ?: continue)
                if (c.isNotEmpty()) return c
            }
        }
        return ""
    }

    private fun extractDate(body: String): Long? {
        val m = DATE_REGEX.matcher(body)
        if (!m.find()) return null
        val rawDate = (m.group(1) ?: return null).replace("-", "/")
        val rawTime = m.group(2) ?: return null
        val raw = "$rawDate $rawTime"
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
