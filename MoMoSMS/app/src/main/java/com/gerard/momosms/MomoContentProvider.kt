package com.gerard.momosms

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri

/**
 * ContentProvider exposant les SMS Mobile Money capturés.
 * MoMo Fin interroge l'URI suivante :
 *     content://com.gerard.momosms.provider/sms
 *
 * Lecture seule. Protégé par la permission com.gerard.momosms.READ_MOMO.
 */
class MomoContentProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.gerard.momosms.provider"
        const val PATH_SMS = "sms"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_SMS")
        private const val CODE_SMS = 1
    }

    private lateinit var store: MomoSmsStore
    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, PATH_SMS, CODE_SMS)
    }

    override fun onCreate(): Boolean {
        store = MomoSmsStore(context!!.applicationContext)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        return when (matcher.match(uri)) {
            CODE_SMS -> store.readableDatabase.query(
                MomoSmsStore.TABLE, projection, selection, selectionArgs,
                null, null, sortOrder ?: "${MomoSmsStore.COL_TIMESTAMP} DESC"
            )
            else -> null
        }
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        CODE_SMS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$PATH_SMS"
        else -> null
    }

    // Lecture seule : insert/update/delete désactivés
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, args: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, sel: String?, args: Array<out String>?): Int = 0
}
