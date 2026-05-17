package com.gerard.momofin

data class Folder(
    val id: Long,
    val name: String,
    val createdAt: Long
)

data class FolderEntry(
    val id: Long,
    val folderId: Long,
    val type: TxType,    // RECU (= Entrée) ou SORTIE
    val amount: Double,
    val note: String,
    val timestamp: Long
)
