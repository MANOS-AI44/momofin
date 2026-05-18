package com.gerard.momofin

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class DailyPoints(
    val dayKey: Long,
    val om: Double = 0.0,
    val momo: Double = 0.0,
    val moov: Double = 0.0,
    val wave: Double = 0.0,
    val djamo: Double = 0.0,
    val cfa: Double = 0.0,
    val entree: Double = 0.0,
    val sortie: Double = 0.0,
    val note: String = ""
) {
    val total: Double get() = om + momo + moov + wave + djamo + cfa + entree - sortie
}

class PointsStore(context: Context) : SQLiteOpenHelper(context, "daily_points.db", null, 1) {

    companion object { const val TABLE = "points" }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                day_key INTEGER PRIMARY KEY,
                om REAL DEFAULT 0,
                momo REAL DEFAULT 0,
                moov REAL DEFAULT 0,
                wave REAL DEFAULT 0,
                djamo REAL DEFAULT 0,
                cfa REAL DEFAULT 0,
                entree REAL DEFAULT 0,
                sortie REAL DEFAULT 0,
                note TEXT
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun get(dayKey: Long): DailyPoints {
        readableDatabase.query(TABLE, null, "day_key=?", arrayOf(dayKey.toString()), null, null, null).use { c ->
            if (!c.moveToFirst()) return DailyPoints(dayKey)
            return DailyPoints(
                dayKey = dayKey,
                om = c.getDouble(c.getColumnIndexOrThrow("om")),
                momo = c.getDouble(c.getColumnIndexOrThrow("momo")),
                moov = c.getDouble(c.getColumnIndexOrThrow("moov")),
                wave = c.getDouble(c.getColumnIndexOrThrow("wave")),
                djamo = c.getDouble(c.getColumnIndexOrThrow("djamo")),
                cfa = c.getDouble(c.getColumnIndexOrThrow("cfa")),
                entree = c.getDouble(c.getColumnIndexOrThrow("entree")),
                sortie = c.getDouble(c.getColumnIndexOrThrow("sortie")),
                note = c.getString(c.getColumnIndexOrThrow("note")) ?: ""
            )
        }
    }

    fun save(p: DailyPoints) {
        val cv = ContentValues().apply {
            put("day_key", p.dayKey)
            put("om", p.om); put("momo", p.momo); put("moov", p.moov)
            put("wave", p.wave); put("djamo", p.djamo); put("cfa", p.cfa)
            put("entree", p.entree); put("sortie", p.sortie); put("note", p.note)
        }
        writableDatabase.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun allDays(): List<DailyPoints> {
        val list = mutableListOf<DailyPoints>()
        readableDatabase.query(TABLE, null, null, null, null, null, "day_key DESC").use { c ->
            while (c.moveToNext()) {
                list.add(DailyPoints(
                    dayKey = c.getLong(c.getColumnIndexOrThrow("day_key")),
                    om = c.getDouble(c.getColumnIndexOrThrow("om")),
                    momo = c.getDouble(c.getColumnIndexOrThrow("momo")),
                    moov = c.getDouble(c.getColumnIndexOrThrow("moov")),
                    wave = c.getDouble(c.getColumnIndexOrThrow("wave")),
                    djamo = c.getDouble(c.getColumnIndexOrThrow("djamo")),
                    cfa = c.getDouble(c.getColumnIndexOrThrow("cfa")),
                    entree = c.getDouble(c.getColumnIndexOrThrow("entree")),
                    sortie = c.getDouble(c.getColumnIndexOrThrow("sortie")),
                    note = c.getString(c.getColumnIndexOrThrow("note")) ?: ""
                ))
            }
        }
        return list
    }
}
