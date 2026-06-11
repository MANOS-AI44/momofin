package com.gerard.momofin

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Cache local des transactions deja parsees.
 * Permet de gerer des milliers de SMS sans tout reparser a chaque ouverture :
 *   1) loadData affiche INSTANTANEMENT le cache (rien a parser)
 *   2) en arriere-plan, on scanne uniquement les SMS NOUVEAUX et on les ajoute au cache
 *   3) la dedoublonnage est garantie par UNIQUE(sender, body, ts)
 */
class TransactionCache(context: Context) :
    SQLiteOpenHelper(context, "tx_cache.db", null, 1) {

    companion object { const val TABLE = "tx_cache" }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                raw_id INTEGER,
                sender TEXT,
                body TEXT,
                ts INTEGER,
                type TEXT,
                subtype TEXT,
                amount REAL,
                currency TEXT,
                reference TEXT,
                phone_number TEXT,
                operator TEXT,
                UNIQUE(sender, body, ts)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_tx_ts ON $TABLE(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insert(t: Transaction): Boolean {
        val cv = ContentValues().apply {
            put("raw_id", t.rawId)
            put("sender", t.rawSender)
            put("body", t.rawBody)
            put("ts", t.timestamp)
            put("type", t.type.name)
            put("subtype", t.subtype.name)
            put("amount", t.amount)
            put("currency", t.currency)
            put("reference", t.reference)
            put("phone_number", t.phoneNumber)
            put("operator", t.operator)
        }
        return writableDatabase.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE) > 0
    }

    fun all(): List<Transaction> {
        val list = mutableListOf<Transaction>()
        readableDatabase.query(TABLE, null, null, null, null, null, "ts DESC").use { c ->
            while (c.moveToNext()) {
                list.add(Transaction(
                    rawId = c.getLong(c.getColumnIndexOrThrow("raw_id")),
                    operator = c.getString(c.getColumnIndexOrThrow("operator")) ?: "",
                    type = runCatching { TxType.valueOf(c.getString(c.getColumnIndexOrThrow("type"))) }.getOrElse { TxType.INCONNU },
                    subtype = runCatching { TxSubtype.valueOf(c.getString(c.getColumnIndexOrThrow("subtype"))) }.getOrElse { TxSubtype.INCONNU },
                    amount = c.getDouble(c.getColumnIndexOrThrow("amount")),
                    currency = c.getString(c.getColumnIndexOrThrow("currency")) ?: "FCFA",
                    reference = c.getString(c.getColumnIndexOrThrow("reference")) ?: "",
                    phoneNumber = c.getString(c.getColumnIndexOrThrow("phone_number")) ?: "",
                    timestamp = c.getLong(c.getColumnIndexOrThrow("ts")),
                    rawSender = c.getString(c.getColumnIndexOrThrow("sender")) ?: "",
                    rawBody = c.getString(c.getColumnIndexOrThrow("body")) ?: ""
                ))
            }
        }
        return list
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        return 0
    }
}
