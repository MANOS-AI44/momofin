package com.gerard.momofin

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteOpenHelper

/**
 * Stockage des saisies manuelles du PATRON (Reçu / Sortie).
 */
class PatronStore(context: Context) :
    SQLiteOpenHelper(context, "patron.db", null, 1) {

    companion object {
        const val TABLE = "patron"
        const val COL_ID = "_id"
        const val COL_TYPE = "type"      // "RECU" / "SORTIE"
        const val COL_AMOUNT = "amount"
        const val COL_NOTE = "note"
        const val COL_TS = "ts"
    }

    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TYPE TEXT NOT NULL,
                $COL_AMOUNT REAL NOT NULL,
                $COL_NOTE TEXT,
                $COL_TS INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldV: Int, newV: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun add(type: TxType, amount: Double, note: String) {
        val cv = ContentValues().apply {
            put(COL_TYPE, type.name)
            put(COL_AMOUNT, amount)
            put(COL_NOTE, note)
            put(COL_TS, System.currentTimeMillis())
        }
        writableDatabase.insert(TABLE, null, cv)
    }

    fun delete(id: Long) {
        writableDatabase.delete(TABLE, "$COL_ID=?", arrayOf(id.toString()))
    }

    fun all(): List<PatronEntry> {
        val list = mutableListOf<PatronEntry>()
        readableDatabase.query(
            TABLE, null, null, null, null, null, "$COL_TS DESC"
        ).use { c ->
            val iId = c.getColumnIndexOrThrow(COL_ID)
            val iType = c.getColumnIndexOrThrow(COL_TYPE)
            val iAmt = c.getColumnIndexOrThrow(COL_AMOUNT)
            val iNote = c.getColumnIndexOrThrow(COL_NOTE)
            val iTs = c.getColumnIndexOrThrow(COL_TS)
            while (c.moveToNext()) {
                list.add(
                    PatronEntry(
                        id = c.getLong(iId),
                        type = runCatching { TxType.valueOf(c.getString(iType)) }
                            .getOrElse { TxType.INCONNU },
                        amount = c.getDouble(iAmt),
                        note = c.getString(iNote) ?: "",
                        timestamp = c.getLong(iTs)
                    )
                )
            }
        }
        return list
    }

    fun totals(): Pair<Double, Double> {
        var recu = 0.0
        var sortie = 0.0
        for (e in all()) when (e.type) {
            TxType.RECU -> recu += e.amount
            TxType.SORTIE -> sortie += e.amount
            else -> {}
        }
        return recu to sortie
    }
}
