package com.gerard.momofin

enum class TxType { RECU, SORTIE, INCONNU }

data class Transaction(
    val rawId: Long,
    val operator: String,
    val type: TxType,
    val amount: Double,
    val currency: String,
    val reference: String,        // ID / Référence de la transaction
    val timestamp: Long,          // heure exacte (préférée : celle parsée du SMS, sinon date du SMS)
    val rawSender: String,
    val rawBody: String
)
