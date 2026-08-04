package no.mwmai.mwmcloud.data.ledger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Record of what has actually been uploaded.
 *
 * Plain SQLite rather than Room: the schema is one table with one index, and the
 * whole surface is four queries. Room's codegen would add a build-time dependency
 * for no benefit at this size.
 *
 * The ledger records intent and outcome. It is NOT the source of truth for what
 * is on the server; the reconcile pass is. An upload that returned 201 and a file
 * that is actually there are different claims, and the app must not conflate them.
 */
class UploadLedger(context: Context) {

    private val helper = object : SQLiteOpenHelper(context, DB_NAME, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    dedupe_key   TEXT PRIMARY KEY,
                    remote_path  TEXT NOT NULL,
                    size         INTEGER NOT NULL,
                    modified     INTEGER NOT NULL,
                    uploaded_at  INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_remote_path ON $TABLE (remote_path)")
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            // Dropping the ledger is safe: it causes re-verification, never data
            // loss. Nothing on the phone or the box is touched.
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    /** True when this exact file (path, size, mtime) is already recorded as uploaded. */
    suspend fun isUploaded(dedupeKey: String): Boolean = withContext(Dispatchers.IO) {
        helper.readableDatabase
            .rawQuery("SELECT 1 FROM $TABLE WHERE dedupe_key = ? LIMIT 1", arrayOf(dedupeKey))
            .use { it.moveToFirst() }
    }

    /** All recorded keys, for filtering a scan in one pass instead of N queries. */
    suspend fun uploadedKeys(): Set<String> = withContext(Dispatchers.IO) {
        buildSet {
            helper.readableDatabase
                .rawQuery("SELECT dedupe_key FROM $TABLE", null)
                .use { c -> while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    suspend fun recordUploaded(
        dedupeKey: String,
        remotePath: String,
        size: Long,
        modified: Long,
        uploadedAt: Long,
    ) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            // One remote path, one row. The key includes size and mtime, so the
            // same file re-uploaded after an edit got a second row, and the Hjem
            // count drifted above the number of files actually on the box.
            db.delete(TABLE, "remote_path = ? AND dedupe_key != ?", arrayOf(remotePath, dedupeKey))
            db.insertWithOnConflict(
                TABLE,
                null,
                ContentValues().apply {
                    put("dedupe_key", dedupeKey)
                    put("remote_path", remotePath)
                    put("size", size)
                    put("modified", modified)
                    put("uploaded_at", uploadedAt)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Unit
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery("SELECT COALESCE(SUM(size), 0) FROM $TABLE", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
    }

    /** Drops a record so the file is uploaded again. Used when reconcile finds it missing. */
    suspend fun forget(remotePath: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete(TABLE, "remote_path = ?", arrayOf(remotePath))
        Unit
    }

    private companion object {
        const val DB_NAME = "upload_ledger.db"
        const val TABLE = "uploads"
        const val VERSION = 1
    }
}
