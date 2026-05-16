package com.gerard.momofin

data class PatronEntry(
    val id: Long,
    val type: TxType,           // RECU ou SORTIE (uniquement)
    val amount: Double,
    val note: String,
    val timestamp: Long
)
