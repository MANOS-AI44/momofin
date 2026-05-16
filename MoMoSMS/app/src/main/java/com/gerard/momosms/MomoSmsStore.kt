package com.gerard.momosms

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Stockage local SQLite des SMS Mobile Money capturés.
 * Cette base est partagée avec MoMo Fin via le ContentProvider.
 */
class MomoSmsStore(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "momo_sms.db"
        const val DB_VERSION = 1
        const val TABLE = "sms"
        const val COL_ID = "_id"
        const val COL_SENDER = "sender"
        const val COL_BODY = "body"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_OPERATOR = "operator"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SENDER TEXT,
                $COL_BODY TEXT,
                $COL_TIMESTAMP INTEGER,
                $COL_OPERATOR TEXT,
                UNIQUE($COL_SENDER, $COL_BODY, $COL_TIMESTAMP)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insert(sender: String, body: String, timestamp: Long, operator: String): Long {
        val cv = ContentValues().apply {
            put(COL_SENDER, sender)
            put(COL_BODY, body)
            put(COL_TIMESTAMP, timestamp)
            put(COL_OPERATOR, operator)
        }
        return writableDatabase.insertWithOnConflict(
            TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE
        )
    }
}
