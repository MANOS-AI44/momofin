package com.gerard.momofin

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class FolderStore(context: Context) : SQLiteOpenHelper(context, "patron_folders.db", null, 1) {

    companion object {
        const val T_FOLDER = "folders"
        const val T_ENTRY = "folder_entries"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $T_FOLDER (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE $T_ENTRY (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                folder_id INTEGER NOT NULL,
                type TEXT NOT NULL,
                amount REAL NOT NULL,
                note TEXT,
                ts INTEGER NOT NULL,
                FOREIGN KEY(folder_id) REFERENCES $T_FOLDER(_id) ON DELETE CASCADE
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        db.execSQL("DROP TABLE IF EXISTS $T_ENTRY")
        db.execSQL("DROP TABLE IF EXISTS $T_FOLDER")
        onCreate(db)
    }

    // ----- Folders -----
    fun createFolder(name: String): Long {
        val cv = ContentValues().apply {
            put("name", name)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert(T_FOLDER, null, cv)
    }

    fun renameFolder(id: Long, newName: String) {
        val cv = ContentValues().apply { put("name", newName) }
        writableDatabase.update(T_FOLDER, cv, "_id=?", arrayOf(id.toString()))
    }

    fun deleteFolder(id: Long) {
        writableDatabase.delete(T_ENTRY, "folder_id=?", arrayOf(id.toString()))
        writableDatabase.delete(T_FOLDER, "_id=?", arrayOf(id.toString()))
    }

    fun allFolders(): List<Folder> {
        val list = mutableListOf<Folder>()
        readableDatabase.query(T_FOLDER, null, null, null, null, null, "created_at DESC").use { c ->
            val iId = c.getColumnIndexOrThrow("_id")
            val iName = c.getColumnIndexOrThrow("name")
            val iTs = c.getColumnIndexOrThrow("created_at")
            while (c.moveToNext()) {
                list.add(Folder(c.getLong(iId), c.getString(iName) ?: "", c.getLong(iTs)))
            }
        }
        return list
    }

    fun getFolder(id: Long): Folder? {
        readableDatabase.query(T_FOLDER, null, "_id=?", arrayOf(id.toString()), null, null, null).use { c ->
            if (!c.moveToFirst()) return null
            return Folder(
                c.getLong(c.getColumnIndexOrThrow("_id")),
                c.getString(c.getColumnIndexOrThrow("name")) ?: "",
                c.getLong(c.getColumnIndexOrThrow("created_at"))
            )
        }
    }

    /** Restaure les comptes depuis le serveur : efface le local et recree
     *  folders + entries avec leurs timestamps d'origine. */
    fun replaceAllFromRemote(folders: List<RailwayClient.RemoteFolder>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(T_ENTRY, null, null)
            db.delete(T_FOLDER, null, null)
            for (f in folders) {
                val fcv = ContentValues().apply {
                    put("name", f.name)
                    put("created_at", if (f.createdAt > 0) f.createdAt else System.currentTimeMillis())
                }
                val fid = db.insert(T_FOLDER, null, fcv)
                for (e in f.entries) {
                    val ecv = ContentValues().apply {
                        put("folder_id", fid)
                        put("type", if (e.type == "RECU") "RECU" else "SORTIE")
                        put("amount", e.amount)
                        put("note", e.note)
                        put("ts", if (e.ts > 0) e.ts else System.currentTimeMillis())
                    }
                    db.insert(T_ENTRY, null, ecv)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ----- Entries -----
    fun addEntry(folderId: Long, type: TxType, amount: Double, note: String): Long {
        val cv = ContentValues().apply {
            put("folder_id", folderId)
            put("type", type.name)
            put("amount", amount)
            put("note", note)
            put("ts", System.currentTimeMillis())
        }
        return writableDatabase.insert(T_ENTRY, null, cv)
    }

    fun deleteEntry(id: Long) {
        writableDatabase.delete(T_ENTRY, "_id=?", arrayOf(id.toString()))
    }

    fun entries(folderId: Long): List<FolderEntry> {
        val list = mutableListOf<FolderEntry>()
        readableDatabase.query(T_ENTRY, null, "folder_id=?", arrayOf(folderId.toString()), null, null, "ts DESC").use { c ->
            while (c.moveToNext()) {
                list.add(FolderEntry(
                    id = c.getLong(c.getColumnIndexOrThrow("_id")),
                    folderId = c.getLong(c.getColumnIndexOrThrow("folder_id")),
                    type = runCatching { TxType.valueOf(c.getString(c.getColumnIndexOrThrow("type"))) }.getOrElse { TxType.INCONNU },
                    amount = c.getDouble(c.getColumnIndexOrThrow("amount")),
                    note = c.getString(c.getColumnIndexOrThrow("note")) ?: "",
                    timestamp = c.getLong(c.getColumnIndexOrThrow("ts"))
                ))
            }
        }
        return list
    }

    fun totals(folderId: Long): Pair<Double, Double> {
        var entree = 0.0; var sortie = 0.0
        for (e in entries(folderId)) when (e.type) {
            TxType.RECU -> entree += e.amount
            TxType.SORTIE -> sortie += e.amount
            else -> {}
        }
        return entree to sortie
    }
}
