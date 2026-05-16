package com.gerard.momofin

import android.content.Context

/**
 * Préférences pour la synchronisation avec le backend Railway.
 */
object Settings {

    private const val PREF = "momofin_settings"
    private const val K_URL = "railway_url"
    private const val K_TOKEN = "railway_token"
    private const val K_LAST_SYNC = "last_sync_ts"

    fun getUrl(c: Context): String =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(K_URL, "") ?: ""

    fun getToken(c: Context): String =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(K_TOKEN, "") ?: ""

    fun save(c: Context, url: String, token: String) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(K_URL, url.trim().removeSuffix("/"))
            .putString(K_TOKEN, token.trim())
            .apply()
    }

    fun setLastSync(c: Context, ts: Long) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putLong(K_LAST_SYNC, ts).apply()
    }

    fun getLastSync(c: Context): Long =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(K_LAST_SYNC, 0L)

    fun isConfigured(c: Context): Boolean =
        getUrl(c).isNotBlank() && getToken(c).isNotBlank()
}
