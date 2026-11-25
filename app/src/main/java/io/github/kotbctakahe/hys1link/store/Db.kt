package io.github.kotbctakahe.hys1link.store

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Db(context: Context) : SQLiteOpenHelper(context, "hys1link.db", null, 3) {

    /** Incremented on every write that the UI may care about. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version
    fun notifyChanged() { _version.value = _version.value + 1 }

    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v BLOB)")
        db.execSQL("CREATE TABLE identities (address TEXT PRIMARY KEY, key BLOB NOT NULL, ts INTEGER)")
        db.execSQL("CREATE TABLE prekeys (id INTEGER PRIMARY KEY, record BLOB NOT NULL)")
        db.execSQL("CREATE TABLE signed_prekeys (id INTEGER PRIMARY KEY, record BLOB NOT NULL)")
        db.execSQL("CREATE TABLE sessions (name TEXT NOT NULL, device INTEGER NOT NULL, record BLOB NOT NULL, PRIMARY KEY(name, device))")
        db.execSQL("CREATE TABLE sender_keys (address TEXT NOT NULL, distribution TEXT NOT NULL, record BLOB NOT NULL, PRIMARY KEY(address, distribution))")
        db.execSQL(
            """CREATE TABLE contacts (
                uuid TEXT PRIMARY KEY, e164 TEXT, system_name TEXT, profile_name TEXT, about TEXT,
                profile_key BLOB, avatar_path TEXT, blocked INTEGER DEFAULT 0, updated INTEGER DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE conversations (
                id TEXT PRIMARY KEY, kind INTEGER NOT NULL, peer TEXT, group_master_key BLOB, title TEXT,
                last_body TEXT, last_ts INTEGER DEFAULT 0, unread INTEGER DEFAULT 0, archived INTEGER DEFAULT 0, pinned INTEGER DEFAULT 0, avatar_path TEXT, expire_timer INTEGER DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT, conv TEXT NOT NULL, author TEXT, outgoing INTEGER NOT NULL,
                body TEXT, sent_ts INTEGER NOT NULL, received_ts INTEGER NOT NULL, status INTEGER DEFAULT 0,
                attachments TEXT, quote_ts INTEGER, quote_author TEXT, quote_text TEXT, reactions TEXT, deleted INTEGER DEFAULT 0,
                expires_in INTEGER DEFAULT 0, expire_started INTEGER DEFAULT 0, view_once INTEGER DEFAULT 0, mentions TEXT, preview TEXT, kind INTEGER DEFAULT 0,
                UNIQUE(conv, author, sent_ts))"""
        )
        db.execSQL("CREATE INDEX messages_conv ON messages(conv, sent_ts)")
        db.execSQL("CREATE INDEX messages_ts ON messages(sent_ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE conversations ADD COLUMN avatar_path TEXT")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN expire_timer INTEGER DEFAULT 0")
            listOf("expires_in INTEGER DEFAULT 0", "expire_started INTEGER DEFAULT 0", "view_once INTEGER DEFAULT 0",
                "mentions TEXT", "preview TEXT", "kind INTEGER DEFAULT 0").forEach { db.execSQL("ALTER TABLE messages ADD COLUMN $it") }
        }
    }

    // ---- key/value ----
    fun putBlob(k: String, v: ByteArray?) {
        writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun getBlob(k: String): ByteArray? =
        readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(k)).use { if (it.moveToFirst() && !it.isNull(0)) it.getBlob(0) else null }
    fun putString(k: String, v: String?) = putBlob(k, v?.toByteArray())
    fun getString(k: String): String? = getBlob(k)?.let { String(it) }
    fun putLong(k: String, v: Long) = putString(k, v.toString())
    fun getLong(k: String, def: Long = 0): Long = getString(k)?.toLongOrNull() ?: def

    fun wipe() {
        val db = writableDatabase
        listOf("kv", "identities", "prekeys", "signed_prekeys", "sessions", "sender_keys", "contacts", "conversations", "messages")
            .forEach { db.delete(it, null, null) }
        notifyChanged()
    }
}

fun Cursor.str(col: String): String? = getColumnIndex(col).let { if (it < 0 || isNull(it)) null else getString(it) }
fun Cursor.lng(col: String): Long = getColumnIndex(col).let { if (it < 0 || isNull(it)) 0 else getLong(it) }
fun Cursor.blob(col: String): ByteArray? = getColumnIndex(col).let { if (it < 0 || isNull(it)) null else getBlob(it) }
