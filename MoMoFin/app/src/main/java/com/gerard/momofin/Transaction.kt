package com.gerard.momofin

enum class TxType { RECU, SORTIE, INCONNU }

// Sous-categorie plus precise — distingue depot/retrait des transferts
enum class TxSubtype {
    DEPOT,
    RETRAIT,
    TRANSFERT_ENVOYE,
    TRANSFERT_RECU,
    INCONNU;

    fun label(): String = when (this) {
        DEPOT -> "Dépôt"
        RETRAIT -> "Retrait"
        TRANSFERT_ENVOYE -> "Transfert envoyé"
        TRANSFERT_RECU -> "Transfert reçu"
        INCONNU -> "—"
    }
}

data class Transaction(
    val rawId: Long,
    val operator: String,
    val type: TxType,
    val subtype: TxSubtype = TxSubtype.INCONNU,
    val amount: Double,
    val currency: String,
    val reference: String,
    val phoneNumber: String,
    val timestamp: Long,
    val rawSender: String,
    val rawBody: String
)
