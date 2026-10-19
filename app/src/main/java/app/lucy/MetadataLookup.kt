package app.lucy

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class MetadataCandidate(val title: String, val artist: String, val album: String)
data class LyricsCandidate(
    val title: String,
    val artist: String,
    val album: String,
    val lyrics: String
)

object MetadataLookup {
    fun searchItunes(term: String): List<MetadataCandidate> {
        val url = "https://itunes.apple.com/search?entity=song&limit=10&term=${encode(term)}"
        val response = request(url) ?: throw IOException("iTunes returned an empty response.")
        val results = JSONObject(response).optJSONArray("results") ?: JSONArray()
        return buildList {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val title = item.optString("trackName").trim()
                val artist = item.optString("artistName").trim()
                if (title.isNotEmpty() && artist.isNotEmpty()) {
                    add(MetadataCandidate(title, artist, item.optString("collectionName").trim()))
                }
            }
        }
    }

    fun searchLyrics(title: String, artist: String, album: String, durationMillis: Long): List<LyricsCandidate> {
        val exactUrl = buildString {
            append("https://lrclib.net/api/get?track_name=${encode(title)}")
            append("&artist_name=${encode(artist)}")
            if (album.isNotBlank()) append("&album_name=${encode(album)}")
            if (durationMillis > 0) append("&duration=${durationMillis / 1000}")
        }
        val exact = request(exactUrl, allowNotFound = true)
        if (exact != null) {
            val candidate = parseLyrics(JSONObject(exact))
            return if (candidate == null) emptyList() else listOf(candidate)
        }

        val query = listOf(title, artist).filter(String::isNotBlank).joinToString(" ")
        val response = request("https://lrclib.net/api/search?q=${encode(query)}")
            ?: throw IOException("LRCLIB returned an empty response.")
        val results = JSONArray(response)
        return buildList {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                parseLyrics(item)?.let(::add)
            }
        }
    }

    private fun parseLyrics(item: JSONObject): LyricsCandidate? {
        val lyrics = item.optString("syncedLyrics").takeIf(String::isNotBlank)
            ?: item.optString("plainLyrics").takeIf(String::isNotBlank)
            ?: return null
        return LyricsCandidate(
            title = item.optString("trackName").ifBlank { item.optString("name") },
            artist = item.optString("artistName"),
            album = item.optString("albumName"),
            lyrics = lyrics
        )
    }

    private fun request(url: String, allowNotFound: Boolean = false): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "LucyAndroidMusicPlayer/1.0")
        try {
            val status = connection.responseCode
            if (allowNotFound && status == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (status !in 200..299) throw IOException("Metadata service returned HTTP $status.")
            return connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
