package com.openalc.inspnative

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class NoteGroup(val id: String, val name: String)

data class NativeNote(
    val id: String,
    var title: String,
    var body: String,
    val kind: String,
    var groupId: String?,
    var private: Boolean,
    var updatedAt: Long,
)

data class NativeTodo(
    val id: String,
    var text: String,
    var completed: Boolean,
    var remindAt: Long?,
    var repeat: String,
    val createdAt: Long,
    var updatedAt: Long,
)

class NativeStore(context: Context) : SQLiteOpenHelper(context, "insp-native.db", null, 2) {
    private val vault = context.getSharedPreferences("private-vault", Context.MODE_PRIVATE)
    private var privateSessionKey: SecretKey? = null
    private val localKey by lazy { loadDeviceKey(false) }
    private val wrapKey by lazy { loadDeviceKey(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE groups (id TEXT PRIMARY KEY, name BLOB NOT NULL, position INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE notes (id TEXT PRIMARY KEY, kind TEXT NOT NULL, group_id TEXT, private INTEGER NOT NULL, title BLOB NOT NULL, body BLOB NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE attachments (id TEXT PRIMARY KEY, note_id TEXT NOT NULL, mime_type TEXT NOT NULL, payload BLOB NOT NULL)")
        db.execSQL("CREATE TABLE todos (id TEXT PRIMARY KEY, payload BLOB NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX notes_order ON notes(updated_at DESC)")
        db.execSQL("CREATE INDEX notes_group ON notes(group_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("CREATE TABLE todos (id TEXT PRIMARY KEY, payload BLOB NOT NULL, updated_at INTEGER NOT NULL)")
    }

    fun todos(): List<NativeTodo> {
        val result = mutableListOf<NativeTodo>()
        readableDatabase.rawQuery("SELECT id, payload FROM todos ORDER BY updated_at DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                val data = JSONObject(open(cursor.getBlob(1)))
                result += NativeTodo(cursor.getString(0), data.getString("text"), data.getBoolean("completed"),
                    data.optLong("remindAt").takeIf { it > 0 }, data.optString("repeat", "none"),
                    data.getLong("createdAt"), data.getLong("updatedAt"))
            }
        }
        return result
    }

    fun saveTodo(todo: NativeTodo) {
        val payload = JSONObject().put("text", todo.text).put("completed", todo.completed)
            .put("remindAt", todo.remindAt ?: 0).put("repeat", todo.repeat)
            .put("createdAt", todo.createdAt).put("updatedAt", todo.updatedAt).toString()
        writableDatabase.insertWithOnConflict("todos", null, ContentValues().apply {
            put("id", todo.id); put("payload", seal(payload)); put("updated_at", todo.updatedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteTodo(id: String) { writableDatabase.delete("todos", "id=?", arrayOf(id)) }

    fun unlockPrivate() {
        val wrapped = vault.getString("wrapped-key", null)
        privateSessionKey = if (wrapped == null) {
            val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
            val encoded = encrypt(bytes, wrapKey)
            vault.edit().putString("wrapped-key", android.util.Base64.encodeToString(encoded, android.util.Base64.NO_WRAP)).apply()
            SecretKeySpec(bytes, "AES")
        } else {
            SecretKeySpec(decrypt(android.util.Base64.decode(wrapped, android.util.Base64.NO_WRAP), wrapKey), "AES")
        }
    }

    fun lockPrivate() { privateSessionKey = null }

    private fun key(private: Boolean): SecretKey = if (private) {
        checkNotNull(privateSessionKey) { "隐私笔记尚未解锁" }
    } else localKey

    private fun loadDeviceKey(private: Boolean): SecretKey {
        val alias = if (private) "insp-native-private-wrap" else "insp-native-local"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (private) builder.setUserAuthenticationRequired(true).setUserAuthenticationParameters(
            300,
            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
        )
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(builder.build()) }.generateKey()
    }

    private fun sealBytes(value: ByteArray, private: Boolean): ByteArray {
        return encrypt(value, key(private))
    }

    private fun encrypt(value: ByteArray, secret: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        return cipher.iv + cipher.doFinal(value)
    }

    private fun openBytes(value: ByteArray, private: Boolean): ByteArray {
        return decrypt(value, key(private))
    }

    private fun decrypt(value: ByteArray, secret: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, value.copyOfRange(0, 12)))
        return cipher.doFinal(value.copyOfRange(12, value.size))
    }

    private fun seal(value: String, private: Boolean = false) = sealBytes(value.toByteArray(Charsets.UTF_8), private)
    private fun open(value: ByteArray, private: Boolean = false) = openBytes(value, private).toString(Charsets.UTF_8)

    fun groups(): List<NoteGroup> {
        val result = mutableListOf<NoteGroup>()
        readableDatabase.rawQuery("SELECT id, name FROM groups ORDER BY position, rowid", null).use { cursor ->
            while (cursor.moveToNext()) result += NoteGroup(cursor.getString(0), open(cursor.getBlob(1)))
        }
        return result
    }

    fun addGroup(name: String): NoteGroup {
        val group = NoteGroup(UUID.randomUUID().toString(), name)
        writableDatabase.insertOrThrow("groups", null, ContentValues().apply {
            put("id", group.id)
            put("name", seal(group.name))
            put("position", System.currentTimeMillis())
        })
        return group
    }

    fun renameGroup(group: NoteGroup) {
        writableDatabase.update("groups", ContentValues().apply { put("name", seal(group.name)) }, "id=?", arrayOf(group.id))
    }

    fun removeGroup(id: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.update("notes", ContentValues().apply { putNull("group_id") }, "group_id=?", arrayOf(id))
            writableDatabase.delete("groups", "id=?", arrayOf(id))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun notes(includePrivate: Boolean = false): List<NativeNote> {
        val result = mutableListOf<NativeNote>()
        val where = if (includePrivate) "private=1" else "private=0"
        readableDatabase.rawQuery(
            "SELECT id, title, body, kind, group_id, private, updated_at FROM notes WHERE $where ORDER BY updated_at DESC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val isPrivate = cursor.getInt(5) == 1
                result += NativeNote(
                    cursor.getString(0), open(cursor.getBlob(1), isPrivate), open(cursor.getBlob(2), isPrivate),
                    cursor.getString(3), cursor.getString(4), isPrivate, cursor.getLong(6),
                )
            }
        }
        return result
    }

    fun create(kind: String, groupId: String?, private: Boolean): NativeNote {
        val title = when (kind) { "mindmap" -> "未命名的灵感笔记"; "todo" -> "待办清单"; else -> "未命名笔记" }
        val body = if (kind == "mindmap") {
            JSONObject().put("type", "mindmap").put("root", JSONObject()
                .put("id", UUID.randomUUID().toString()).put("text", title).put("children", org.json.JSONArray())).toString()
        } else if (kind == "todo") JSONObject().put("version", 1).put("text", "☐ ").put("spans", org.json.JSONArray()).toString()
        else RichTextCodec.empty()
        return NativeNote(UUID.randomUUID().toString(), title, body, kind, groupId, private, System.currentTimeMillis())
            .also(::save)
    }

    fun save(note: NativeNote) {
        note.updatedAt = System.currentTimeMillis()
        writableDatabase.insertWithOnConflict("notes", null, ContentValues().apply {
            put("id", note.id)
            put("kind", note.kind)
            put("group_id", note.groupId)
            put("private", if (note.private) 1 else 0)
            put("title", seal(note.title, note.private))
            put("body", seal(note.body, note.private))
            put("updated_at", note.updatedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun addImage(note: NativeNote, bytes: ByteArray, mimeType: String): String {
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("attachments", null, ContentValues().apply {
            put("id", id)
            put("note_id", note.id)
            put("mime_type", mimeType)
            put("payload", sealBytes(bytes, note.private))
        })
        return id
    }

    fun importBundle(bundle: TransferBundle): Int {
        val db = writableDatabase
        var imported = 0
        db.beginTransaction()
        try {
            for (group in bundle.groups) db.insertWithOnConflict("groups", null, ContentValues().apply {
                put("id", group.id)
                put("name", seal(group.name))
                put("position", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_IGNORE)
            for (item in bundle.notes) {
                db.rawQuery("SELECT 1 FROM notes WHERE id=?", arrayOf(item.note.id)).use { cursor ->
                    if (cursor.moveToFirst()) continue
                }
                val timestamp = item.note.updatedAt
                save(item.note)
                db.update("notes", ContentValues().apply { put("updated_at", timestamp) }, "id=?", arrayOf(item.note.id))
                for (image in item.images) db.insertOrThrow("attachments", null, ContentValues().apply {
                    put("id", image.id)
                    put("note_id", item.note.id)
                    put("mime_type", image.mimeType)
                    put("payload", sealBytes(image.bytes, item.note.private))
                })
                imported++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return imported
    }

    fun image(note: NativeNote, id: String): ByteArray {
        readableDatabase.rawQuery("SELECT payload FROM attachments WHERE id=? AND note_id=?", arrayOf(id, note.id)).use { cursor ->
            check(cursor.moveToFirst())
            return openBytes(cursor.getBlob(0), note.private)
        }
    }

    fun setPrivacy(note: NativeNote, private: Boolean) {
        if (note.private == private) return
        val images = mutableListOf<Pair<String, ByteArray>>()
        readableDatabase.rawQuery("SELECT id, payload FROM attachments WHERE note_id=?", arrayOf(note.id)).use { cursor ->
            while (cursor.moveToNext()) images += cursor.getString(0) to openBytes(cursor.getBlob(1), note.private)
        }
        writableDatabase.beginTransaction()
        try {
            for ((id, bytes) in images) writableDatabase.update("attachments", ContentValues().apply {
                put("payload", sealBytes(bytes, private))
            }, "id=?", arrayOf(id))
            note.private = private
            save(note)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun delete(id: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("attachments", "note_id=?", arrayOf(id))
            writableDatabase.delete("notes", "id=?", arrayOf(id))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }
}
