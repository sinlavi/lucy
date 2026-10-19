package app.lucy

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class TrackTags(val title: String, val artist: String, val album: String, val lyrics: String)
data class Playlist(val id: Long, val name: String)

class LibraryDatabase(context: Context) : SQLiteOpenHelper(context, "lucy.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE track_tags (
                track_id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                lyrics TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE TABLE playlists (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
        db.execSQL(
            """
            CREATE TABLE playlist_tracks (
                playlist_id INTEGER NOT NULL REFERENCES playlists(_id) ON DELETE CASCADE,
                track_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY (playlist_id, track_id)
            )
            """.trimIndent()
        )
        db.execSQL("PRAGMA foreign_keys=ON")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun tagsFor(trackId: Long): TrackTags? =
        readableDatabase.query(
            "track_tags",
            arrayOf("title", "artist", "album", "lyrics"),
            "track_id=?",
            arrayOf(trackId.toString()),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) null
            else TrackTags(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3))
        }

    fun allTags(): Map<Long, TrackTags> =
        readableDatabase.query(
            "track_tags",
            arrayOf("track_id", "title", "artist", "album", "lyrics"),
            null,
            null,
            null,
            null,
            null
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(
                        cursor.getLong(0),
                        TrackTags(cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4))
                    )
                }
            }
        }

    fun saveTags(trackId: Long, tags: TrackTags) {
        val values = ContentValues().apply {
            put("track_id", trackId)
            put("title", tags.title)
            put("artist", tags.artist)
            put("album", tags.album)
            put("lyrics", tags.lyrics)
        }
        writableDatabase.insertWithOnConflict("track_tags", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun playlists(): List<Playlist> =
        readableDatabase.query("playlists", arrayOf("_id", "name"), null, null, null, null, "name COLLATE NOCASE")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(Playlist(cursor.getLong(0), cursor.getString(1)))
                }
            }

    fun createPlaylist(name: String): Long =
        writableDatabase.insertOrThrow("playlists", null, ContentValues().apply { put("name", name) })

    fun deletePlaylist(id: Long) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("playlist_tracks", "playlist_id=?", arrayOf(id.toString()))
            writableDatabase.delete("playlists", "_id=?", arrayOf(id.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun addToPlaylist(playlistId: Long, trackId: Long) {
        val nextPosition = DatabaseUtilsCompat.nextPosition(writableDatabase, playlistId)
        writableDatabase.insertWithOnConflict(
            "playlist_tracks",
            null,
            ContentValues().apply {
                put("playlist_id", playlistId)
                put("track_id", trackId)
                put("position", nextPosition)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun removeFromPlaylist(playlistId: Long, trackId: Long) {
        writableDatabase.delete(
            "playlist_tracks",
            "playlist_id=? AND track_id=?",
            arrayOf(playlistId.toString(), trackId.toString())
        )
    }

    fun trackIdsInPlaylist(playlistId: Long): List<Long> =
        readableDatabase.query(
            "playlist_tracks",
            arrayOf("track_id"),
            "playlist_id=?",
            arrayOf(playlistId.toString()),
            null,
            null,
            "position"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }
}

private object DatabaseUtilsCompat {
    fun nextPosition(db: SQLiteDatabase, playlistId: Long): Int =
        db.rawQuery(
            "SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlist_id=?",
            arrayOf(playlistId.toString())
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
