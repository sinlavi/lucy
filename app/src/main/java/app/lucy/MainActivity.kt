package app.lucy

import android.Manifest
import android.app.AlertDialog
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.Executors

data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val lyrics: String
)

private sealed class Page {
    data object Library : Page()
    data object Artists : Page()
    data object Albums : Page()
    data object Playlists : Page()
    data class Artist(val name: String) : Page()
    data class Album(val name: String, val artist: String) : Page()
    data class TrackDetails(val id: Long) : Page()
    data class PlaylistDetails(val id: Long) : Page()
    data class Lyrics(val id: Long) : Page()
    data object NowPlaying : Page()
}

class MainActivity : ComponentActivity() {
    private val background = Color.rgb(17, 20, 17)
    private val surface = Color.rgb(29, 34, 29)
    private val muted = Color.rgb(161, 170, 157)
    private val primary = Color.rgb(190, 232, 111)
    private val textColor = Color.rgb(241, 244, 237)

    private lateinit var database: LibraryDatabase
    private lateinit var player: ExoPlayer
    private lateinit var screen: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlay: TextView
    private lateinit var search: EditText
    private val tabViews = mutableListOf<TextView>()
    private val libraryExecutor = Executors.newSingleThreadExecutor()
    private val audioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) loadTracks()
        else {
            Toast.makeText(this, "Allow audio access to browse your music.", Toast.LENGTH_LONG).show()
            renderContent()
        }
    }
    private var tracks: List<Track> = emptyList()
    private var queue: List<Track> = emptyList()
    private var currentPage: Page = Page.Library

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        database = LibraryDatabase(this)
        player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = updateMiniPlayer()

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateMiniPlayer()
                if (currentPage is Page.NowPlaying) renderContent()
            }
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentPage == Page.Library) finish() else goBack()
            }
        })
        createLayout()
        if (hasAudioPermission()) loadTracks() else requestAudioPermission()
    }

    override fun onDestroy() {
        libraryExecutor.execute { database.close() }
        libraryExecutor.shutdown()
        player.release()
        super.onDestroy()
    }

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        audioPermissionLauncher.launch(permission)
    }

    private fun createLayout() {
        screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@MainActivity.background)
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val logo = TextView(this).apply {
            text = "LUCY"
            textSize = 22f
            letterSpacing = 0.16f
            setTextColor(primary)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        toolbar.addView(logo, LinearLayout.LayoutParams(dp(78), dp(48)))
        search = EditText(this).apply {
            hint = "Search your music"
            textSize = 14f
            setSingleLine(true)
            setTextColor(textColor)
            setHintTextColor(muted)
            setPadding(dp(12), 0, dp(12), 0)
            background = roundedBackground(surface, dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderContent()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        toolbar.addView(search, LinearLayout.LayoutParams(0, dp(44), 1f))
        screen.addView(toolbar)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        listOf("Home", "Artists", "Albums", "Playlists").forEachIndexed { index, name ->
            val tab = TextView(this).apply {
                text = name
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (index == 0) primary else muted)
                setOnClickListener {
                    currentPage = when (index) {
                        1 -> Page.Artists
                        2 -> Page.Albums
                        3 -> Page.Playlists
                        else -> Page.Library
                    }
                    renderContent()
                }
            }
            tabViews += tab
            tabs.addView(tab, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        screen.addView(tabs)

        content = FrameLayout(this)
        screen.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        createMiniPlayer()
        setContentView(screen)
        renderContent()
    }

    private fun createMiniPlayer() {
        val mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(10), dp(8))
            background = roundedBackground(surface, dp(16))
            setOnClickListener {
                if (currentTrack() != null) {
                    currentPage = Page.NowPlaying
                    renderContent()
                }
            }
        }
        val cover = TextView(this).apply {
            text = "♫"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(this@MainActivity.background)
            setBackgroundColor(primary)
        }
        mini.addView(cover, LinearLayout.LayoutParams(dp(44), dp(44)))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        miniTitle = TextView(this).apply {
            text = "Nothing playing"
            textSize = 14f
            setTextColor(textColor)
            maxLines = 1
        }
        miniArtist = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
            maxLines = 1
        }
        labels.addView(miniTitle)
        labels.addView(miniArtist)
        mini.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        miniPlay = TextView(this).apply {
            text = "▶"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(primary)
            setOnClickListener { togglePlayback() }
        }
        mini.addView(miniPlay, LinearLayout.LayoutParams(dp(48), dp(48)))
        screen.addView(mini, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply {
            topMargin = dp(10)
        })
    }

    private fun loadTracks() {
        if (!hasAudioPermission()) return
        libraryExecutor.execute {
            val loaded = mutableListOf<Track>()
            try {
                val overrides = database.allTags()
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION
                )
                val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    null,
                    "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
                )?.use { cursor: Cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val tags = overrides[id]
                        loaded += Track(
                            id = id,
                            uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                            title = tags?.title ?: cursor.getString(titleColumn).orEmpty().ifBlank { "Unknown title" },
                            artist = tags?.artist ?: cursor.getString(artistColumn).orEmpty().ifBlank { "Unknown artist" },
                            album = tags?.album ?: cursor.getString(albumColumn).orEmpty().ifBlank { "Unknown album" },
                            duration = cursor.getLong(durationColumn),
                            lyrics = tags?.lyrics.orEmpty()
                        )
                    }
                }
            } catch (error: SecurityException) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(this, "Audio access was revoked. Allow access to reload your music.", Toast.LENGTH_LONG).show()
                    renderContent()
                }
                return@execute
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                tracks = loaded
                updateMiniPlayer()
                renderContent()
            }
        }
    }

    private fun renderContent() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        val selectedTab = when (currentPage) {
            Page.Artists, is Page.Artist -> 1
            Page.Albums, is Page.Album -> 2
            Page.Playlists, is Page.PlaylistDetails -> 3
            else -> 0
        }
        tabViews.forEachIndexed { index, tab -> tab.setTextColor(if (index == selectedTab) primary else muted) }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(24))
        }
        scroll.addView(body)
        content.addView(scroll)
        when (val page = currentPage) {
            Page.Library -> renderLibrary(body)
            Page.Artists -> renderArtists(body)
            Page.Albums -> renderAlbums(body)
            Page.Playlists -> renderPlaylists(body)
            is Page.Artist -> renderArtist(body, page.name)
            is Page.Album -> renderAlbum(body, page.name, page.artist)
            is Page.TrackDetails -> renderTrackDetails(body, page.id)
            is Page.PlaylistDetails -> renderPlaylist(body, page.id)
            is Page.Lyrics -> renderLyrics(body, page.id)
            Page.NowPlaying -> renderNowPlaying(body)
        }
    }

    private fun renderLibrary(body: LinearLayout) {
        addHeading(body, "YOUR LIBRARY")
        if (!hasAudioPermission()) {
            addEmpty(body, "Lucy needs audio access to scan music stored on this device.")
            addActionButton(body, "Allow audio access") { requestAudioPermission() }
            return
        }
        TextView(this).apply {
            text = "${tracks.size} songs on this device"
            textSize = 14f
            setTextColor(muted)
        }.also(body::addView)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Artists" to Page.Artists, "Albums" to Page.Albums, "Playlists" to Page.Playlists).forEach { (label, page) ->
            val tile = TextView(this).apply {
                text = "$label\n›"
                textSize = 14f
                setTextColor(textColor)
                gravity = Gravity.CENTER
                background = roundedBackground(surface, dp(14))
                setOnClickListener { currentPage = page; renderContent() }
            }
            val params = LinearLayout.LayoutParams(0, dp(76), 1f)
            params.setMargins(0, dp(16), dp(8), dp(14))
            actions.addView(tile, params)
        }
        body.addView(actions)
        val matching = filtered(tracks)
        addHeading(body, "ALL TRACKS", small = true)
        if (matching.isEmpty()) {
            addEmpty(body, if (tracks.isEmpty()) "Your music will appear here once it is on this device." else "No tracks match your search.")
        } else {
            matching.forEach { addTrackRow(body, it) }
        }
    }

    private fun renderArtists(body: LinearLayout) {
        addBackHeader(body, "Artists")
        val artists = filtered(tracks).map { it.artist }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
        if (artists.isEmpty()) addEmpty(body, "No artists found.")
        artists.forEach { artist ->
            addSimpleRow(body, artist, "${tracks.count { it.artist == artist }} tracks") {
                currentPage = Page.Artist(artist)
                renderContent()
            }
        }
    }

    private fun renderAlbums(body: LinearLayout) {
        addBackHeader(body, "Albums")
        val albums = filtered(tracks).distinctBy { it.album.lowercase() to it.artist.lowercase() }
        if (albums.isEmpty()) addEmpty(body, "No albums found.")
        albums.sortedBy { it.album.lowercase() }.forEach { track ->
            addSimpleRow(body, track.album, track.artist) {
                currentPage = Page.Album(track.album, track.artist)
                renderContent()
            }
        }
    }

    private fun renderPlaylists(body: LinearLayout) {
        addBackHeader(body, "Playlists")
        addActionButton(body, "+  New playlist") { createPlaylist() }
        database.playlists().forEach { playlist ->
            addSimpleRow(body, playlist.name, "${database.trackIdsInPlaylist(playlist.id).size} tracks", "⋯") {
                playlistActions(playlist)
            }.also { row ->
                row.setOnClickListener {
                    currentPage = Page.PlaylistDetails(playlist.id)
                    renderContent()
                }
            }
        }
        if (database.playlists().isEmpty()) addEmpty(body, "Create a playlist to collect your favorite tracks.")
    }

    private fun renderArtist(body: LinearLayout, artist: String) {
        addBackHeader(body, artist)
        addHeading(body, "ARTIST", small = true)
        addDetailTitle(body, artist)
        val artistTracks = filtered(tracks.filter { it.artist == artist })
        addHeading(body, "TRACKS  ·  ${artistTracks.size}", small = true)
        if (artistTracks.isEmpty()) addEmpty(body, "No tracks by this artist.")
        artistTracks.forEach { addTrackRow(body, it) }
    }

    private fun renderAlbum(body: LinearLayout, album: String, artist: String) {
        addBackHeader(body, "Album")
        addCover(body, "♫", 128)
        addDetailTitle(body, album)
        addSimpleRow(body, artist, "Artist") {
            currentPage = Page.Artist(artist)
            renderContent()
        }
        val albumTracks = filtered(tracks.filter { it.album == album && it.artist == artist })
        addActionButton(body, "▶  Play album") { playTracks(albumTracks, 0) }
        addHeading(body, "TRACKS  ·  ${albumTracks.size}", small = true)
        albumTracks.forEach { addTrackRow(body, it) }
    }

    private fun renderTrackDetails(body: LinearLayout, id: Long) {
        val track = trackById(id) ?: return addEmpty(body, "This track is no longer available.")
        addBackHeader(body, "Track")
        addCover(body, "♫", 144)
        addDetailTitle(body, track.title)
        addSimpleRow(body, track.artist, "Artist") {
            currentPage = Page.Artist(track.artist)
            renderContent()
        }
        addSimpleRow(body, track.album, "Album") {
            currentPage = Page.Album(track.album, track.artist)
            renderContent()
        }
        addActionButton(body, "▶  Play track") { playTracks(tracks, tracks.indexOf(track)) }
        addActionButton(body, "✎  Edit tags") { editTags(track) }
        addActionButton(body, "♫  View lyrics") {
            currentPage = Page.Lyrics(track.id)
            renderContent()
        }
        addActionButton(body, "＋  Add to playlist") { choosePlaylist(track) }
    }

    private fun renderPlaylist(body: LinearLayout, id: Long) {
        val playlist = database.playlists().firstOrNull { it.id == id }
        if (playlist == null) {
            currentPage = Page.Playlists
            return renderContent()
        }
        addBackHeader(body, playlist.name)
        val playlistTracks = database.trackIdsInPlaylist(id).mapNotNull(::trackById).let(::filtered)
        addActionButton(body, "▶  Play playlist") { playTracks(playlistTracks, 0) }
        addActionButton(body, "+  Add tracks") { chooseTracksForPlaylist(id) }
        if (playlistTracks.isEmpty()) addEmpty(body, "This playlist is empty.")
        playlistTracks.forEach { track ->
            addTrackRow(
                body,
                track,
                onClick = { playTracks(playlistTracks, playlistTracks.indexOf(track)) },
                onMore = {
                    database.removeFromPlaylist(id, track.id)
                    renderContent()
                }
            )
        }
    }

    private fun renderLyrics(body: LinearLayout, id: Long) {
        val track = trackById(id) ?: return addEmpty(body, "This track is no longer available.")
        addBackHeader(body, "Lyrics")
        addDetailTitle(body, track.title)
        TextView(this).apply {
            text = track.artist
            textSize = 14f
            setTextColor(muted)
        }.also(body::addView)
        if (track.lyrics.isBlank()) {
            addEmpty(body, "No lyrics saved for this track yet.")
        } else {
            TextView(this).apply {
                text = track.lyrics
                textSize = 18f
                setTextColor(textColor)
                setLineSpacing(dp(5).toFloat(), 1f)
                setPadding(0, dp(28), 0, dp(20))
            }.also(body::addView)
        }
        addActionButton(body, "✎  Edit tags & lyrics") { editTags(track) }
    }

    private fun renderNowPlaying(body: LinearLayout) {
        val track = currentTrack() ?: return addEmpty(body, "Choose a track to start listening.")
        addBackHeader(body, "Now playing")
        addCover(body, "♫", 224)
        addDetailTitle(body, track.title)
        TextView(this).apply {
            text = "${track.artist}  ·  ${track.album}"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setOnClickListener { currentPage = Page.TrackDetails(track.id); renderContent() }
        }.also(body::addView)
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf("|◀" to { player.seekToPrevious() }, (if (player.isPlaying) "Ⅱ" else "▶") to { togglePlayback() }, "▶|" to { player.seekToNext() }).forEach { (label, action) ->
            val control = TextView(this).apply {
                text = label
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(primary)
                setOnClickListener { action() }
            }
            controls.addView(control, LinearLayout.LayoutParams(dp(72), dp(72)))
        }
        body.addView(controls)
        addActionButton(body, "♫  View lyrics") { currentPage = Page.Lyrics(track.id); renderContent() }
        addActionButton(body, "✎  Edit tags") { editTags(track) }
    }

    private fun addTrackRow(
        body: LinearLayout,
        track: Track,
        onClick: (() -> Unit)? = null,
        onMore: (() -> Unit)? = null
    ) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(8), dp(4), dp(8))
            setOnClickListener {
                if (onClick != null) {
                    onClick()
                } else {
                    currentPage = Page.TrackDetails(track.id)
                    renderContent()
                }
            }
        }
        val cover = TextView(this).apply {
            text = "♫"
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(this@MainActivity.background)
            setBackgroundColor(primary)
        }
        row.addView(cover, LinearLayout.LayoutParams(dp(44), dp(44)))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        labels.addView(TextView(this).apply {
            text = track.title
            textSize = 15f
            setTextColor(textColor)
            maxLines = 1
        })
        labels.addView(TextView(this).apply {
            text = "${track.artist}  ·  ${track.album}"
            textSize = 12f
            setTextColor(muted)
            maxLines = 1
        })
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val more = TextView(this).apply {
            text = "⋮"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setOnClickListener {
                if (onMore != null) onMore()
                else trackActions(track)
            }
        }
        row.addView(more, LinearLayout.LayoutParams(dp(42), dp(48)))
        body.addView(row)
        body.addView(View(this).apply { setBackgroundColor(Color.rgb(40, 45, 40)) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ))
    }

    private fun addSimpleRow(
        body: LinearLayout,
        title: String,
        subtitle: String,
        trailing: String? = null,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener { onClick() }
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(textColor)
        })
        labels.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(muted)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) {
            row.addView(TextView(this).apply {
                text = trailing
                textSize = 22f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
        }
        body.addView(row)
        return row
    }

    private fun addBackHeader(body: LinearLayout, title: String) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        row.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            setTextColor(primary)
            gravity = Gravity.CENTER
            setOnClickListener { goBack() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        row.addView(TextView(this).apply {
            text = title
            textSize = 19f
            setTextColor(textColor)
            maxLines = 1
        })
        body.addView(row)
    }

    private fun addHeading(body: LinearLayout, title: String, small: Boolean = false) {
        body.addView(TextView(this).apply {
            text = title
            textSize = if (small) 12f else 14f
            letterSpacing = if (small) 0.1f else 0f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (small) muted else textColor)
            setPadding(0, dp(12), 0, dp(8))
        })
    }

    private fun addDetailTitle(body: LinearLayout, title: String) {
        body.addView(TextView(this).apply {
            text = title
            textSize = 25f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(14), dp(4), dp(6))
        })
    }

    private fun addCover(body: LinearLayout, symbol: String, size: Int) {
        val cover = TextView(this).apply {
            text = symbol
            textSize = 52f
            gravity = Gravity.CENTER
            setTextColor(this@MainActivity.background)
            background = roundedBackground(primary, dp(22))
        }
        body.addView(cover, LinearLayout.LayoutParams(dp(size), dp(size)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(22)
            bottomMargin = dp(10)
        })
    }

    private fun addActionButton(body: LinearLayout, title: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = title
            textSize = 14f
            setTextColor(textColor)
            isAllCaps = false
            background = roundedBackground(surface, dp(12))
            setOnClickListener { action() }
        }
        body.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(7)
        })
    }

    private fun addEmpty(body: LinearLayout, message: String) {
        body.addView(TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(muted)
            setPadding(dp(8), dp(18), dp(8), dp(18))
        })
    }

    private fun filtered(source: List<Track>): List<Track> {
        val needle = search.text?.toString()?.trim().orEmpty()
        if (needle.isEmpty()) return source
        return source.filter {
            it.title.contains(needle, true) || it.artist.contains(needle, true) || it.album.contains(needle, true)
        }
    }

    private fun trackById(id: Long): Track? = tracks.firstOrNull { it.id == id }

    private fun goBack() {
        currentPage = when (val page = currentPage) {
            Page.Artists, Page.Albums, Page.Playlists -> Page.Library
            is Page.Artist -> Page.Artists
            is Page.Album -> Page.Albums
            is Page.TrackDetails -> Page.Library
            is Page.PlaylistDetails -> Page.Playlists
            is Page.Lyrics -> if (player.mediaItemCount > 0 && currentTrack()?.id == page.id) Page.TrackDetails(page.id) else Page.Library
            Page.NowPlaying -> currentTrack()?.let { Page.TrackDetails(it.id) } ?: Page.Library
            Page.Library -> Page.Library
        }
        renderContent()
    }

    private fun currentTrack(): Track? {
        val index = player.currentMediaItemIndex
        return if (player.mediaItemCount > 0 && index in queue.indices) queue[index] else null
    }

    private fun playTracks(items: List<Track>, startIndex: Int) {
        if (items.isEmpty()) {
            Toast.makeText(this, "There are no tracks to play.", Toast.LENGTH_SHORT).show()
            return
        }
        val safeIndex = startIndex.coerceIn(0, items.lastIndex)
        queue = items
        player.setMediaItems(items.map { MediaItem.fromUri(it.uri) }, safeIndex, 0L)
        player.prepare()
        player.play()
        updateMiniPlayer()
        currentPage = Page.NowPlaying
        renderContent()
    }

    private fun togglePlayback() {
        if (player.mediaItemCount == 0) {
            if (tracks.isNotEmpty()) playTracks(tracks, 0)
            else Toast.makeText(this, "No music found on this device.", Toast.LENGTH_SHORT).show()
        } else if (player.isPlaying) player.pause() else player.play()
        updateMiniPlayer()
    }

    private fun updateMiniPlayer() {
        if (!::miniTitle.isInitialized) return
        val track = currentTrack()
        miniTitle.text = track?.title ?: "Nothing playing"
        miniArtist.text = track?.artist ?: "Choose a song to begin"
        miniPlay.text = if (player.isPlaying) "Ⅱ" else "▶"
    }

    private fun editTags(track: Track) {
        val title = EditText(this).apply { setText(track.title); hint = "Title" }
        val artist = EditText(this).apply { setText(track.artist); hint = "Artist" }
        val album = EditText(this).apply { setText(track.album); hint = "Album" }
        val lyrics = EditText(this).apply {
            setText(track.lyrics)
            hint = "Lyrics (plain text or .lrc)"
            minLines = 4
            gravity = Gravity.TOP
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
            addView(title)
            addView(artist)
            addView(album)
            addView(lyrics)
        }
        val dialogContent = ScrollView(this).apply { addView(fields) }
        AlertDialog.Builder(this)
            .setTitle("Edit local tags")
            .setMessage("Changes are saved in Lucy's library on this device.")
            .setView(dialogContent)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                database.saveTags(
                    track.id,
                    TrackTags(title.text.toString().trim(), artist.text.toString().trim(), album.text.toString().trim(), lyrics.text.toString())
                )
                loadTracks()
            }
            .show()
    }

    private fun trackActions(track: Track) {
        AlertDialog.Builder(this)
            .setTitle(track.title)
            .setItems(arrayOf("Play next", "Add to playlist", "Edit tags", "View lyrics")) { _, which ->
                when (which) {
                    0 -> playNext(track)
                    1 -> choosePlaylist(track)
                    2 -> editTags(track)
                    3 -> { currentPage = Page.Lyrics(track.id); renderContent() }
                }
            }
            .show()
    }

    private fun playNext(track: Track) {
        val insertionIndex = (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount)
        if (player.mediaItemCount == 0) return playTracks(listOf(track), 0)
        queue = queue.toMutableList().apply { add(insertionIndex, track) }
        player.addMediaItem(insertionIndex, MediaItem.fromUri(track.uri))
        Toast.makeText(this, "Added to play next.", Toast.LENGTH_SHORT).show()
    }

    private fun choosePlaylist(track: Track) {
        val playlists = database.playlists()
        if (playlists.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage("Create a playlist first.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create playlist") { _, _ ->
                    createPlaylist { id -> database.addToPlaylist(id, track.id) }
                }
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Add to playlist")
            .setItems(playlists.map { it.name }.toTypedArray()) { _, which ->
                database.addToPlaylist(playlists[which].id, track.id)
                Toast.makeText(this, "Added to ${playlists[which].name}.", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("New playlist") { _, _ ->
                createPlaylist { id -> database.addToPlaylist(id, track.id) }
            }
            .show()
    }

    private fun createPlaylist(afterCreate: ((Long) -> Unit)? = null) {
        val input = EditText(this).apply { hint = "Playlist name" }
        AlertDialog.Builder(this)
            .setTitle("New playlist")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "Enter a playlist name.", Toast.LENGTH_SHORT).show()
                } else {
                    val id = database.createPlaylist(name)
                    afterCreate?.invoke(id)
                    renderContent()
                }
            }
            .show()
    }

    private fun chooseTracksForPlaylist(playlistId: Long) {
        if (tracks.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Add tracks")
            .setMultiChoiceItems(tracks.map { "${it.title} — ${it.artist}" }.toTypedArray(), null) { _, which, checked ->
                if (checked) database.addToPlaylist(playlistId, tracks[which].id)
                else database.removeFromPlaylist(playlistId, tracks[which].id)
            }
            .setPositiveButton("Done") { _, _ -> renderContent() }
            .show()
    }

    private fun playlistActions(playlist: Playlist) {
        AlertDialog.Builder(this)
            .setTitle(playlist.name)
            .setItems(arrayOf("Open", "Delete playlist")) { _, which ->
                if (which == 0) {
                    currentPage = Page.PlaylistDetails(playlist.id)
                } else {
                    AlertDialog.Builder(this)
                        .setMessage("Delete “${playlist.name}”?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ ->
                            database.deletePlaylist(playlist.id)
                            renderContent()
                        }
                        .show()
                    return@setItems
                }
                renderContent()
            }
            .show()
    }

    private fun roundedBackground(color: Int, radius: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

}
