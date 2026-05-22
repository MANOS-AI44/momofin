package com.gerard.momofin

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ReceiptStore(context: Context) : SQLiteOpenHelper(context, "receipts.db", null, 1) {

    companion object { const val T = "receipts" }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $T (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                client_id TEXT,
                partner_name TEXT,
                client_name TEXT,
                objet TEXT,
                conditions TEXT,
                amount REAL NOT NULL DEFAULT 0,
                currency TEXT,
                ts INTEGER NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        db.execSQL("DROP TABLE IF EXISTS $T")
        onCreate(db)
    }

    fun create(partnerName: String, clientName: String, objet: String, conditions: String, amount: Double, currency: String): Long {
        val cv = ContentValues().apply {
            put("client_id", System.currentTimeMillis().toString() + "-" + (0..9999).random())
            put("partner_name", partnerName)
            put("client_name", clientName)
            put("objet", objet)
            put("conditions", conditions)
            put("amount", amount)
            put("currency", currency)
            put("ts", System.currentTimeMillis())
        }
        return writableDatabase.insert(T, null, cv)
    }

    fun all(): List<Receipt> {
        val list = mutableListOf<Receipt>()
        readableDatabase.query(T, null, null, null, null, null, "ts DESC").use { c ->
            while (c.moveToNext()) {
                list.add(Receipt(
                    id = c.getLong(c.getColumnIndexOrThrow("_id")),
                    clientId = c.getString(c.getColumnIndexOrThrow("client_id")) ?: "",
                    partnerName = c.getString(c.getColumnIndexOrThrow("partner_name")) ?: "",
                    clientName = c.getString(c.getColumnIndexOrThrow("client_name")) ?: "",
                    objet = c.getString(c.getColumnIndexOrThrow("objet")) ?: "",
                    conditions = c.getString(c.getColumnIndexOrThrow("conditions")) ?: "",
                    amount = c.getDouble(c.getColumnIndexOrThrow("amount")),
                    currency = c.getString(c.getColumnIndexOrThrow("currency")) ?: "FCFA",
                    timestamp = c.getLong(c.getColumnIndexOrThrow("ts"))
                ))
            }
        }
        return list
    }

    fun delete(id: Long) {
        writableDatabase.delete(T, "_id=?", arrayOf(id.toString()))
    }

    /** Restaure depuis le serveur : efface le local et recree */
    fun replaceAllFromRemote(receipts: List<RailwayClient.RemoteReceipt>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(T, null, null)
            for (r in receipts) {
                val cv = ContentValues().apply {
                    put("client_id", r.clientId)
                    put("partner_name", r.partnerName)
                    put("client_name", r.clientName)
                    put("objet", r.objet)
                    put("conditions", r.conditions)
                    put("amount", r.amount)
                    put("currency", r.currency)
                    put("ts", if (r.ts > 0) r.ts else System.currentTimeMillis())
                }
                db.insert(T, null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
