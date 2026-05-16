package com.gerard.momosms

/**
 * Détecte si un SMS provient d'un opérateur Mobile Money (MTN MoMo, Orange Money, Airtel Money).
 * Multi-opérateur : on filtre soit par l'expéditeur, soit par le contenu.
 */
object MomoFilter {

    private val SENDERS = listOf(
        "MoMo", "MTN", "MTN MoMo", "M-Money", "MTNMoMo",
        "Orange", "OrangeMoney", "Orange Money", "OM",
        "Airtel", "AirtelMoney", "Airtel Money"
    )

    private val KEYWORDS = listOf(
        "momo", "mobile money", "transaction", "received", "sent",
        "reçu", "envoy", "transfert", "transfer", "ref",
        "rwf", "xof", "xaf", "ugx", "ghs", "kes", "tzs", "fcfa",
        "id:", "txn", "txid"
    )

    fun isMomoSms(sender: String?, body: String?): Boolean {
        val s = (sender ?: "").lowercase()
        val b = (body ?: "").lowercase()
        if (SENDERS.any { s.contains(it.lowercase()) }) return true
        // Heuristique : au moins 2 mots-clés mobile money
        val hits = KEYWORDS.count { b.contains(it) }
        return hits >= 2
    }

    fun detectOperator(sender: String?, body: String?): String {
        val s = (sender ?: "").lowercase()
        val b = (body ?: "").lowercase()
        return when {
            s.contains("mtn") || s.contains("momo") || b.contains("momo") -> "MTN"
            s.contains("orange") || b.contains("orange money") -> "Orange"
            s.contains("airtel") -> "Airtel"
            else -> "Inconnu"
        }
    }
}
