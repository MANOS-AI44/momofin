package com.gerard.momofin

data class Receipt(
    val id: Long,
    val clientId: String,        // identifiant stable pour la sync serveur
    val partnerName: String,     // entreprise partenaire
    val clientName: String,      // nom + prenom du client
    val objet: String,           // objet (achat cryptos, Chine, vetements...)
    val conditions: String,      // condition propre a l'objet (snapshot)
    val amount: Double,
    val currency: String,
    val timestamp: Long          // date/heure de remplissage
)
