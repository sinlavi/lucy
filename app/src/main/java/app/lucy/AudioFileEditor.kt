package app.lucy

import android.content.ContentValues
import android.content.ContentResolver
import android.provider.MediaStore
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.IOException

object AudioFileEditor {
    fun readLyrics(contentResolver: ContentResolver, cacheDir: File, track: Track): String {
        val stagedFile = stage(contentResolver, cacheDir, track)
        try {
            return AudioFileIO.read(stagedFile).tag?.getFirst(FieldKey.LYRICS).orEmpty()
        } finally {
            stagedFile.delete()
        }
    }

    fun save(
        contentResolver: ContentResolver,
        cacheDir: File,
        track: Track,
        tags: TrackTags,
        newDisplayName: String
    ) {
        val stagedFile = stage(contentResolver, cacheDir, track)
        try {
            val audioFile = AudioFileIO.read(stagedFile)
            val tag = audioFile.tagOrCreateAndSetDefault
            setField(tag, FieldKey.TITLE, tags.title)
            setField(tag, FieldKey.ARTIST, tags.artist)
            setField(tag, FieldKey.ALBUM, tags.album)
            setField(tag, FieldKey.LYRICS, tags.lyrics)
            audioFile.commit()

            val output = contentResolver.openOutputStream(track.uri, "wt")
                ?: throw IOException("Could not open the audio file for writing.")
            output.use { destination -> stagedFile.inputStream().buffered().use { it.copyTo(destination) } }

            if (newDisplayName != track.displayName) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, newDisplayName)
                }
                if (contentResolver.update(track.uri, values, null, null) == 0) {
                    throw IOException("Tags were written, but Android could not update the file name in the library.")
                }
            }
            contentResolver.notifyChange(track.uri, null)
        } finally {
            stagedFile.delete()
        }
    }

    private fun stage(contentResolver: ContentResolver, cacheDir: File, track: Track): File {
        val extension = track.displayName.substringAfterLast('.', "").lowercase()
        if (extension.isBlank() || !extension.matches(Regex("[a-z0-9]{1,10}"))) {
            throw IOException("Lucy cannot identify this audio file's format.")
        }
        val stagingDirectory = File(cacheDir, "tag-edits")
        if (!stagingDirectory.exists() && !stagingDirectory.mkdirs()) {
            throw IOException("Could not create a temporary file for editing.")
        }
        val stagedFile = File.createTempFile("track-${track.id}-", ".$extension", stagingDirectory)
        try {
            val input = contentResolver.openInputStream(track.uri)
                ?: throw IOException("Could not open the selected audio file.")
            input.use { source ->
                stagedFile.outputStream().buffered().use { destination -> source.copyTo(destination) }
            }
            return stagedFile
        } catch (error: IOException) {
            stagedFile.delete()
            throw error
        } catch (error: SecurityException) {
            stagedFile.delete()
            throw error
        }
    }

    private fun setField(tag: org.jaudiotagger.tag.Tag, field: FieldKey, value: String) {
        if (value.isBlank()) tag.deleteField(field) else tag.setField(field, value)
    }
}
