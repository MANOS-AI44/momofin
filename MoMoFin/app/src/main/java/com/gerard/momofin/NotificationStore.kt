package com.gerard.momofin

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Stockage local des SMS captés via NotificationListener.
 * Indépendant de MoMoSmsStore (qui repose sur READ_SMS).
 */
class NotificationStore(context: Context) :
    SQLiteOpenHelper(context, "notif_sms.db", null, 1) {

    companion object {
        const val TABLE = "notif_sms"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                sender TEXT,
                body TEXT,
                timestamp INTEGER,
                operator TEXT,
                UNIQUE(sender, body, timestamp)
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insert(sender: String, body: String, timestamp: Long, operator: String): Long {
        val cv = ContentValues().apply {
            put("sender", sender)
            put("body", body)
            put("timestamp", timestamp)
            put("operator", operator)
        }
        return writableDatabase.insertWithOnConflict(
            TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun all(): List<SmsSource.Raw> {
        val list = mutableListOf<SmsSource.Raw>()
        readableDatabase.query(TABLE, null, null, null, null, null, "timestamp DESC").use { c ->
            while (c.moveToNext()) {
                list.add(SmsSource.Raw(
                    id = c.getLong(c.getColumnIndexOrThrow("_id")),
                    sender = c.getString(c.getColumnIndexOrThrow("sender")) ?: "",
                    body = c.getString(c.getColumnIndexOrThrow("body")) ?: "",
                    timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp")),
                    operator = c.getString(c.getColumnIndexOrThrow("operator")) ?: ""
                ))
            }
        }
        return list
    }
}
