package com.gerard.momofin

enum class TxType { RECU, SORTIE, INCONNU }

data class Transaction(
    val rawId: Long,
    val operator: String,
    val type: TxType,
    val amount: Double,
    val currency: String,
    val reference: String,        // ID / Référence
    val phoneNumber: String,      // Numéro de l'autre partie
    val timestamp: Long,
    val rawSender: String,
    val rawBody: String
)
