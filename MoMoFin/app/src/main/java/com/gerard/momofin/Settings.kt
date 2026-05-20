package com.gerard.momofin

import android.content.Context

object Settings {

    private const val PREF = "momofin_settings"
    private const val K_URL = "railway_url"
    private const val K_TOKEN = "railway_token"
    private const val K_LAST_SYNC = "last_sync_ts"
    private const val K_ASKED_PERMS = "asked_perms"
    private const val K_IS_ADMIN = "is_admin"

    fun getUrl(c: Context): String =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(K_URL, "") ?: ""

    fun getToken(c: Context): String =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(K_TOKEN, "") ?: ""

    fun isAdmin(c: Context): Boolean =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(K_IS_ADMIN, true)

    fun setAdmin(c: Context, isAdmin: Boolean) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(K_IS_ADMIN, isAdmin).apply()
    }

    fun clearAuth(c: Context) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .remove(K_URL).remove(K_TOKEN).remove(K_IS_ADMIN).remove(K_LAST_SYNC).apply()
    }

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

    fun hasAskedPermissions(c: Context): Boolean =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(K_ASKED_PERMS, false)

    fun setAskedPermissions(c: Context) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(K_ASKED_PERMS, true).apply()
    }


    fun isBannerDismissed(c: Context, key: String): Boolean =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("dismiss_$key", false)

    fun setBannerDismissed(c: Context, key: String) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("dismiss_$key", true).apply()
    }

    fun isPrimaryDevice(c: Context): Boolean =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("primary_device", true)

    fun setPrimaryDevice(c: Context, primary: Boolean) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("primary_device", primary).apply()
    }

    fun logout(c: Context) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .remove(K_URL).remove(K_TOKEN).remove(K_LAST_SYNC).apply()
    }
}
