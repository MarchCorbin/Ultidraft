package com.ultidraft.data

import android.content.Context
import android.net.Uri

/**
 * Last folder, last book, and per-book position.
 *
 * Position also lives in the sidecar so the PC can pick up where the phone left off,
 * but it is mirrored here as well: the sidecar is a synced file, and writing to it on
 * every sentence would spray Syncthing conflicts across the book folder.
 */
class Prefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("ultidraft", Context.MODE_PRIVATE)

    var treeUri: Uri?
        get() = prefs.getString(KEY_TREE, null)?.let(Uri::parse)
        set(value) = prefs.edit().putString(KEY_TREE, value?.toString()).apply()

    var lastBookName: String?
        get() = prefs.getString(KEY_LAST_BOOK, null)
        set(value) = prefs.edit().putString(KEY_LAST_BOOK, value).apply()

    var speed: Float
        get() = prefs.getFloat(KEY_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SPEED, value).apply()

    var voiceName: String?
        get() = prefs.getString(KEY_VOICE, null)
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    fun positionFor(bookName: String): Int = prefs.getInt(KEY_POSITION + bookName, -1)

    fun setPositionFor(bookName: String, index: Int) {
        prefs.edit().putInt(KEY_POSITION + bookName, index).apply()
    }

    private companion object {
        const val KEY_TREE = "tree_uri"
        const val KEY_LAST_BOOK = "last_book"
        const val KEY_SPEED = "speed"
        const val KEY_VOICE = "voice"
        const val KEY_POSITION = "position:"
    }
}
