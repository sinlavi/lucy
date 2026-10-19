package app.lucy

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import java.util.concurrent.Executors

data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val lyrics: String,
    val displayName: String,
    val dateAdded: Long
)

private data class PendingTagEdit(val track: Track, val tags: TrackTags, val displayName: String)
private data class TrackEditorDraft(
    val trackId: Long,
    var displayName: String,
    var title: String,
    var artist: String,
    var album: String,
    var lyrics: String,
    var lyricsRead: Boolean = false,
    var lyricsEdited: Boolean = false
)

private sealed class Page {
    data object Library : Page()
    data object Queue : Page()
    data object Artists : Page()
    data object Albums : Page()
    data object Playlists : Page()
    data object Settings : Page()
    data class Artist(val name: String) : Page()
    data class Album(val name: String, val artist: String) : Page()
    data class TrackDetails(val id: Long) : Page()
    data class PlaylistDetails(val id: Long) : Page()
    data class SmartPlaylist(val kind: String, val name: String) : Page()
    data class TrackEditor(val id: Long) : Page()
    data object NowPlaying : Page()
}

class MainActivity : ComponentActivity() {
    private var background = Color.rgb(17, 20, 17)
    private var surface = Color.rgb(29, 34, 29)
    private var muted = Color.rgb(161, 170, 157)
    private var primary = Color.rgb(190, 232, 111)
    private var textColor = Color.rgb(241, 244, 237)

    private lateinit var database: LibraryDatabase
    private lateinit var player: ExoPlayer
    private lateinit var screen: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlay: TextView
    private lateinit var miniProgress: ProgressBar
    private lateinit var search: EditText
    private var nowPlayingProgress: Slider? = null
    private var nowPlayingPosition: TextView? = null
    private var nowPlayingDuration: TextView? = null
    private var sleepTimerButton: MaterialButton? = null
    private val tabViews = mutableListOf<TextView>()
    private val libraryExecutor = Executors.newSingleThreadExecutor()
    private val metadataExecutor = Executors.newSingleThreadExecutor()
    private val progressHandler = Handler(Looper.getMainLooper())
    private var sleepTimerEndsAt = 0L
    private val progressTicker = object : Runnable {
        override fun run() {
            updatePlaybackProgress()
            progressHandler.postDelayed(this, 1_000L)
        }
    }
    private val audioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) loadTracks()
        else {
            Toast.makeText(this, "Allow audio access to browse your music.", Toast.LENGTH_LONG).show()
            renderContent()
        }
    }
    private val writePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) savePendingTagEdit()
        else {
            pendingTagEdit = null
            Toast.makeText(this, "Storage write permission is required to edit this file.", Toast.LENGTH_LONG).show()
        }
    }
    private val writeRequestLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) savePendingTagEdit()
            else {
                pendingTagEdit = null
                Toast.makeText(this, "File changes were not approved.", Toast.LENGTH_SHORT).show()
            }
        }
    private var tracks: List<Track> = emptyList()
    private var queue: List<Track> = emptyList()
    private var favoriteIds: Set<Long> = emptySet()
    private var currentPage: Page = Page.Library
    private var lastRecordedTrackId = -1L
    private var pendingTagEdit: PendingTagEdit? = null
    private var editorDraft: TrackEditorDraft? = null
    private lateinit var editorTitle: EditText
    private lateinit var editorArtist: EditText
    private lateinit var editorAlbum: EditText
    private lateinit var editorLyrics: EditText
    private lateinit var editorFileName: EditText
    private val preferences by lazy { getSharedPreferences("lucy_settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(
            when (preferences.getString("theme", "Dark")) {
                "Light" -> R.style.AppThemeLight
                "System" -> if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                ) R.style.AppTheme else R.style.AppThemeLight
                else -> R.style.AppTheme
            }
        )
        applyTheme()
        window.statusBarColor = background
        window.navigationBarColor = background
        database = LibraryDatabase(this)
        favoriteIds = database.favoriteTrackIds().toSet()
        player = ExoPlayer.Builder(this).build()
        player.shuffleModeEnabled = preferences.getBoolean("shuffle", false)
        player.repeatMode = when (preferences.getString("repeat", "Off")) {
            "One" -> Player.REPEAT_MODE_ONE
            "All" -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateMiniPlayer()
                if (currentPage is Page.NowPlaying) renderContent()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateMiniPlayer()
                currentTrack()?.let { track ->
                    if (track.id != lastRecordedTrackId) {
                        lastRecordedTrackId = track.id
                        libraryExecutor.execute { database.recordPlay(track.id) }
                    }
                }
                if (currentPage is Page.NowPlaying) renderContent()
            }
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentPage == Page.Library) finish() else goBack()
            }
        })
        createLayout()
        progressHandler.post(progressTicker)
        if (hasAudioPermission()) loadTracks() else requestAudioPermission()
    }

    override fun onDestroy() {
        progressHandler.removeCallbacks(progressTicker)
        libraryExecutor.execute { database.close() }
        libraryExecutor.shutdown()
        metadataExecutor.shutdownNow()
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
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val logo = TextView(this).apply {
            text = "lucy"
            textSize = 25f
            setTextColor(primary)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        toolbar.addView(logo, LinearLayout.LayoutParams(dp(68), dp(48)))
        search = EditText(this).apply {
            hint = "Search songs, artists, albums"
            textSize = 14f
            setSingleLine(true)
            setTextColor(textColor)
            setHintTextColor(muted)
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedBackground(surface, dp(24))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderContent()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        toolbar.addView(search, LinearLayout.LayoutParams(0, dp(46), 1f))
        screen.addView(toolbar)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val tabsScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        }
        listOf("Home", "Queue", "Artists", "Albums", "Playlists", "Settings").forEachIndexed { index, name ->
            val tab = TextView(this).apply {
                text = name
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                background = roundedBackground(surface, dp(24))
                setTextColor(muted)
                setOnClickListener {
                    currentPage = when (index) {
                        1 -> Page.Queue
                        2 -> Page.Artists
                        3 -> Page.Albums
                        4 -> Page.Playlists
                        5 -> Page.Settings
                        else -> Page.Library
                    }
                    renderContent()
                }
            }
            tabViews += tab
            tabs.addView(tab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                marginEnd = dp(8)
            })
        }
        screen.addView(tabsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        content = FrameLayout(this)
        screen.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        createMiniPlayer()
        setContentView(screen)
        renderContent()
    }

    private fun createMiniPlayer() {
        val mini = FrameLayout(this)
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(surface)
            strokeWidth = dp(1)
            strokeColor = if (this@MainActivity.background == Color.rgb(17, 20, 17)) Color.rgb(55, 61, 54) else Color.rgb(225, 228, 220)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(10), dp(8))
        }
        card.setOnClickListener {
            if (currentTrack() != null) {
                currentPage = Page.NowPlaying
                renderContent()
            }
        }
        card.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)))
        val cover = TextView(this).apply {
            text = "♪"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(primary)
            background = roundedBackground(
                if (this@MainActivity.background == Color.rgb(17, 20, 17)) Color.rgb(54, 67, 43) else Color.rgb(235, 242, 224),
                dp(12)
            )
        }
        row.addView(cover, LinearLayout.LayoutParams(dp(48), dp(48)))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        miniTitle = TextView(this).apply {
            text = "Nothing playing"
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
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
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        miniPlay = TextView(this).apply {
            text = "▶"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(primary)
            setOnClickListener { togglePlayback() }
        }
        row.addView(miniPlay, LinearLayout.LayoutParams(dp(48), dp(48)))
        mini.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)))
        miniProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1_000
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(primary)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                if (this@MainActivity.background == Color.rgb(17, 20, 17)) Color.rgb(58, 64, 57) else Color.rgb(226, 229, 220)
            )
        }
        mini.addView(miniProgress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.BOTTOM))
        screen.addView(mini, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)).apply {
            topMargin = dp(8)
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
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DATE_ADDED
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
                    val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
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
                            lyrics = tags?.lyrics.orEmpty(),
                            displayName = cursor.getString(displayNameColumn).orEmpty().ifBlank { "Unknown file" },
                            dateAdded = cursor.getLong(dateAddedColumn)
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
        nowPlayingProgress = null
        nowPlayingPosition = null
        nowPlayingDuration = null
        sleepTimerButton = null
        content.removeAllViews()
        val selectedTab = when (currentPage) {
            Page.Queue -> 1
            Page.Artists, is Page.Artist -> 2
            Page.Albums, is Page.Album -> 3
            Page.Playlists, is Page.PlaylistDetails, is Page.SmartPlaylist -> 4
            Page.Settings -> 5
            else -> 0
        }
        tabViews.forEachIndexed { index, tab ->
            val selected = index == selectedTab
            tab.setTextColor(if (selected) primary else muted)
            tab.background = roundedBackground(if (selected) surface else background, dp(24))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(24))
        }
        scroll.addView(body)
        content.addView(scroll)
        when (val page = currentPage) {
            Page.Library -> renderLibrary(body)
            Page.Queue -> renderQueue(body)
            Page.Artists -> renderArtists(body)
            Page.Albums -> renderAlbums(body)
            Page.Playlists -> renderPlaylists(body)
            Page.Settings -> renderSettings(body)
            is Page.Artist -> renderArtist(body, page.name)
            is Page.Album -> renderAlbum(body, page.name, page.artist)
            is Page.TrackDetails -> renderTrackDetails(body, page.id)
            is Page.PlaylistDetails -> renderPlaylist(body, page.id)
            is Page.SmartPlaylist -> renderSmartPlaylist(body, page.kind, page.name)
            is Page.TrackEditor -> renderTrackEditor(body, page.id)
            Page.NowPlaying -> renderNowPlaying(body)
        }
    }

    private fun renderLibrary(body: LinearLayout) {
        if (!hasAudioPermission()) {
            addHeading(body, "Welcome to Lucy")
            addEmpty(body, "Lucy needs audio access to scan music stored on this device.")
            addActionButton(body, "Allow audio access") { requestAudioPermission() }
            return
        }
        TextView(this).apply {
            text = "YOUR MUSIC"
            textSize = 12f
            letterSpacing = 0.14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(muted)
            setPadding(0, dp(10), 0, dp(4))
        }.also(body::addView)
        val isDark = background == Color.rgb(17, 20, 17)
        val hero = MaterialCardView(this).apply {
            radius = dp(22).toFloat()
            cardElevation = dp(3).toFloat()
            setCardBackgroundColor(if (isDark) Color.rgb(34, 43, 29) else Color.rgb(232, 240, 219))
            strokeWidth = 0
        }
        val heroContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
        }
        heroContent.addView(TextView(this).apply {
            text = "A soundtrack for\nevery moment."
            textSize = 25f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        heroContent.addView(TextView(this).apply {
            text = "${tracks.size} songs ready when you are"
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(8), 0, dp(12))
        })
        heroContent.addView(MaterialButton(this).apply {
            text = "↻   Shuffle play"
            isAllCaps = false
            textSize = 14f
            setTextColor(if (isDark) Color.rgb(23, 32, 14) else Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(primary)
            cornerRadius = dp(24)
            setOnClickListener {
                if (tracks.isNotEmpty()) {
                    player.shuffleModeEnabled = true
                    preferences.edit().putBoolean("shuffle", true).apply()
                    playTracks(sortTracks(tracks), 0)
                } else Toast.makeText(this@MainActivity, "No music found on this device.", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        hero.addView(heroContent)
        body.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(12)
        })

        addHeading(body, "PICK UP THE MOOD", small = true)
        data class QuickAction(val icon: String, val title: String, val subtitle: String, val action: () -> Unit)
        val quickActions = listOf(
            QuickAction("♥", "Liked songs", "${favoriteIds.size} tracks") {
                currentPage = Page.SmartPlaylist("liked", "Liked songs"); renderContent()
            },
            QuickAction("◷", "Recently played", "Your listening history") {
                currentPage = Page.SmartPlaylist("recent", "Recently played"); renderContent()
            },
            QuickAction("✦", "Recently added", "Fresh to your library") {
                currentPage = Page.SmartPlaylist("added", "Recently added"); renderContent()
            },
            QuickAction("☷", "Your queue", "${queue.size} tracks up next") {
                currentPage = Page.Queue; renderContent()
            }
        )
        quickActions.chunked(2).forEach { actions ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.forEach { action -> addHomeTile(row, action.icon, action.title, action.subtitle, action.action) }
            if (actions.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(100), 1f))
            body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val recentlyPlayed = tracksByIds(database.recentlyPlayedTrackIds()).take(4)
        if (recentlyPlayed.isNotEmpty()) {
            addHeading(body, "PICK UP WHERE YOU LEFT OFF", small = true)
            recentlyPlayed.forEach { addTrackRow(body, it) }
        }
        val matching = filtered(tracks)
        addHeading(body, "ALL SONGS  ·  ${matching.size}", small = true)
        if (matching.isEmpty()) {
            addEmpty(body, if (tracks.isEmpty()) "Your music will appear here once it is on this device." else "No tracks match your search.")
        } else {
            sortTracks(matching).forEach { addTrackRow(body, it) }
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
        addHeading(body, "YOUR COLLECTIONS", small = true)
        addSimpleRow(body, "♥  Liked songs", "${favoriteIds.size} tracks") {
            currentPage = Page.SmartPlaylist("liked", "Liked songs")
            renderContent()
        }
        addHeading(body, "SMART PLAYLISTS", small = true)
        listOf(
            "recent" to ("Recently played" to "Last played first"),
            "top" to ("Most played" to "Your most-played tracks"),
            "added" to ("Recently added" to "Newest in your library")
        ).forEach { (kind, details) ->
            addSimpleRow(body, details.first, details.second) {
                currentPage = Page.SmartPlaylist(kind, details.first)
                renderContent()
            }
        }
        addHeading(body, "YOUR PLAYLISTS", small = true)
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

    private fun renderQueue(body: LinearLayout) {
        addHeading(body, "UP NEXT")
        addText(body, if (queue.isEmpty()) "Your queue is empty." else "${queue.size} tracks in your queue")
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addCompactButton(controls, if (player.shuffleModeEnabled) "⤨ Shuffle on" else "⤨ Shuffle") {
            player.shuffleModeEnabled = !player.shuffleModeEnabled
            preferences.edit().putBoolean("shuffle", player.shuffleModeEnabled).apply()
            renderContent()
        }
        addCompactButton(controls, repeatLabel()) {
            cycleRepeatMode()
            renderContent()
        }
        addCompactButton(controls, "Clear") {
            AlertDialog.Builder(this)
                .setMessage("Clear the playback queue?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    player.clearMediaItems()
                    queue = emptyList()
                    updateMiniPlayer()
                    renderContent()
                }
                .show()
        }
        body.addView(controls)
        val activeIndex = player.currentMediaItemIndex
        if (queue.isEmpty()) {
            addActionButton(body, "▶  Play all songs") { playTracks(sortTracks(tracks), 0) }
            addActionButton(body, "+  Choose songs") { chooseTracksForQueue() }
            return
        }
        queue.forEachIndexed { index, track ->
            addTrackRow(
                body,
                track,
                onClick = {
                    player.seekTo(index, 0L)
                    player.play()
                    currentPage = Page.NowPlaying
                    renderContent()
                },
                onMore = {
                    AlertDialog.Builder(this)
                        .setMessage("Remove “${track.title}” from the queue?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove") { _, _ ->
                            queue = queue.toMutableList().also { it.removeAt(index) }
                            if (index < player.mediaItemCount) player.removeMediaItem(index)
                            updateMiniPlayer()
                            renderContent()
                        }
                        .show()
                },
                playing = index == activeIndex
            )
        }
        addActionButton(body, "+  Add songs to queue") { chooseTracksForQueue() }
    }

    private fun renderSmartPlaylist(body: LinearLayout, kind: String, name: String) {
        addBackHeader(body, name)
        val smartTracks = when (kind) {
            "liked" -> tracksByIds(favoriteIds.toList())
            "recent" -> tracksByIds(database.recentlyPlayedTrackIds())
            "top" -> tracksByIds(database.mostPlayedTrackIds())
            "added" -> tracks.sortedByDescending { it.dateAdded }
            else -> emptyList()
        }.let(::filtered)
        addActionButton(body, "▶  Play all") { playTracks(smartTracks, 0) }
        if (smartTracks.isEmpty()) {
            addEmpty(body, when (kind) {
                "liked" -> "Tap the heart beside any song to save it here."
                "recent" -> "Your recently played tracks will show up here."
                "top" -> "Your most-played tracks will show up here."
                "added" -> "No tracks found."
                else -> "No tracks found."
            })
        }
        smartTracks.forEach { track ->
            addTrackRow(
                body,
                track,
                onMore = {
                    if (kind == "liked") toggleFavorite(track.id)
                    else trackActions(track)
                    renderContent()
                }
            )
        }
    }

    private fun renderSettings(body: LinearLayout) {
        addHeading(body, "SETTINGS")
        addText(body, "Make Lucy feel like yours.")
        addHeading(body, "APPEARANCE", small = true)
        addSimpleRow(body, "Theme", preferences.getString("theme", "Dark").orEmpty()) {
            chooseSetting("Theme", arrayOf("Dark", "Light", "System"), preferences.getString("theme", "Dark")) {
                preferences.edit().putString("theme", it).apply()
                recreate()
            }
        }
        addSimpleRow(body, "Accent color", preferences.getString("accent", "Lime").orEmpty()) {
            chooseSetting("Accent color", arrayOf("Lime", "Ocean", "Rose"), preferences.getString("accent", "Lime")) {
                preferences.edit().putString("accent", it).apply()
                recreate()
            }
        }
        addHeading(body, "LIBRARY", small = true)
        addSimpleRow(body, "Sort songs", preferences.getString("sort", "Title").orEmpty()) {
            chooseSetting("Sort songs by", arrayOf("Title", "Artist", "Recently added"), preferences.getString("sort", "Title")) {
                preferences.edit().putString("sort", it).apply()
                renderContent()
            }
        }
        addSimpleRow(body, "Rescan music library", "Refresh tracks from this device") { loadTracks() }
        addHeading(body, "PLAYBACK", small = true)
        addSimpleRow(body, "Shuffle", if (player.shuffleModeEnabled) "On" else "Off") {
            player.shuffleModeEnabled = !player.shuffleModeEnabled
            preferences.edit().putBoolean("shuffle", player.shuffleModeEnabled).apply()
            renderContent()
        }
        addSimpleRow(body, "Repeat", repeatLabel()) {
            cycleRepeatMode()
            renderContent()
        }
        addHeading(body, "PRIVACY & STORAGE", small = true)
        addText(body, "Music playback and tag editing happen on this device. Only search terms are sent to iTunes or LRCLIB when you request a lookup.")
        addSimpleRow(body, "Clear listening history", "Resets Recently played and Most played") {
            AlertDialog.Builder(this)
                .setMessage("Clear your listening history?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    database.clearPlayHistory()
                    renderContent()
                }
                .show()
        }
        addHeading(body, "ABOUT", small = true)
        addText(body, "Lucy  ·  Version 1.0  ·  Android 6.0 and newer")
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
        addCover(body, "♫", 128, album)
        addDetailTitle(body, album)
        addSimpleRow(body, artist, "Artist") {
            currentPage = Page.Artist(artist)
            renderContent()
        }
        val albumTracks = filtered(tracks.filter { it.album == album && it.artist == artist })
        addActionButton(body, "▶  Play album") { playTracks(albumTracks, 0) }
        addHeading(body, "TRACKS  ·  ${albumTracks.size}", small = true)
        sortTracks(albumTracks).forEach { addTrackRow(body, it) }
    }

    private fun renderTrackDetails(body: LinearLayout, id: Long) {
        val track = trackById(id) ?: return addEmpty(body, "This track is no longer available.")
        addBackHeader(body, "Track")
        addCover(body, "♫", 144, track.album)
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
        addActionButton(body, if (track.id in favoriteIds) "♥  Liked" else "♡  Add to liked songs") {
            toggleFavorite(track.id)
            renderContent()
        }
        addActionButton(body, "+  Add to queue") { addToQueue(track) }
        addActionButton(body, "✎  Edit tags & lyrics") { editTags(track) }
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

    private fun renderTrackEditor(body: LinearLayout, id: Long) {
        val track = trackById(id) ?: return addEmpty(body, "This track is no longer available.")
        if (editorDraft?.trackId != id) {
            editorDraft = TrackEditorDraft(id, track.displayName, track.title, track.artist, track.album, track.lyrics)
        }
        val draft = requireNotNull(editorDraft)
        addBackHeader(body, "Edit track")
        addEmpty(body, "Changes are written to the audio file. Lucy may ask you to approve file access.")
        editorFileName = addEditorField(body, "File name", draft.displayName)
        editorTitle = addEditorField(body, "Title", draft.title)
        editorArtist = addEditorField(body, "Artist", draft.artist)
        editorAlbum = addEditorField(body, "Album", draft.album)
        editorLyrics = addEditorField(body, "Lyrics (plain text or LRC)", draft.lyrics, multiline = true)
        bindEditorField(editorFileName) { draft.displayName = it }
        bindEditorField(editorTitle) { draft.title = it }
        bindEditorField(editorArtist) { draft.artist = it }
        bindEditorField(editorAlbum) { draft.album = it }
        bindEditorField(editorLyrics) {
            draft.lyrics = it
            draft.lyricsEdited = true
        }
        if (draft.lyrics.isBlank() && !draft.lyricsRead) {
            draft.lyricsRead = true
            readEmbeddedLyrics(track)
        }
        addActionButton(body, "Find metadata by file name · iTunes") {
            lookupItunes(editorFileName.text.toString().substringBeforeLast('.', editorFileName.text.toString()))
        }
        addActionButton(body, "Find metadata by current tags · iTunes") {
            val query = listOf(editorTitle.text, editorArtist.text, editorAlbum.text)
                .map { it.toString().trim() }
                .filter(String::isNotBlank)
                .joinToString(" ")
            lookupItunes(query)
        }
        addActionButton(body, "Fetch lyrics · LRCLIB") { lookupLyrics(track) }
        addActionButton(body, "Save tags, lyrics & file name") { saveEditor(track) }
    }

    private fun renderNowPlaying(body: LinearLayout) {
        val track = currentTrack() ?: return addEmpty(body, "Choose a track to start listening.")
        val duration = playbackDuration(track)
        nowPlayingProgress = null
        nowPlayingPosition = null
        nowPlayingDuration = null
        addBackHeader(body, "Now playing")
        addCover(body, "♫", 248, track.album)
        TextView(this).apply {
            text = "NOW PLAYING"
            textSize = 11f
            letterSpacing = 0.18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(0, dp(8), 0, dp(4))
        }.also(body::addView)
        addDetailTitle(body, track.title)
        TextView(this).apply {
            text = "${track.artist}  ·  ${track.album}"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setOnClickListener { currentPage = Page.TrackDetails(track.id); renderContent() }
        }.also(body::addView)

        val seek = Slider(this).apply {
            valueFrom = 0f
            valueTo = duration.coerceAtLeast(1L).toFloat()
            value = player.currentPosition.coerceIn(0L, duration).toFloat()
            stepSize = 0f
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    player.seekTo(value.toLong())
                    nowPlayingPosition?.text = formatTime(value.toLong())
                }
            }
        }
        nowPlayingProgress = seek
        body.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
            topMargin = dp(12)
        })
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        nowPlayingPosition = TextView(this).apply {
            text = formatTime(player.currentPosition)
            textSize = 12f
            setTextColor(muted)
        }
        times.addView(nowPlayingPosition, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nowPlayingDuration = TextView(this).apply {
            text = formatTime(duration)
            textSize = 12f
            gravity = Gravity.END
            setTextColor(muted)
        }
        times.addView(nowPlayingDuration)
        body.addView(times)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(8))
        }
        addPlayerControl(controls, "⤨", "Shuffle") {
            player.shuffleModeEnabled = !player.shuffleModeEnabled
            preferences.edit().putBoolean("shuffle", player.shuffleModeEnabled).apply()
            renderContent()
        }.setTextColor(if (player.shuffleModeEnabled) primary else muted)
        addPlayerControl(controls, "|◀", "Previous track") {
            player.seekToPrevious()
            updatePlaybackProgress()
        }
        val play = MaterialButton(this).apply {
            text = if (player.isPlaying) "Ⅱ" else "▶"
            textSize = 22f
            isAllCaps = false
            setTextColor(if (this@MainActivity.background == Color.rgb(17, 20, 17)) Color.rgb(23, 32, 14) else Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(primary)
            cornerRadius = dp(36)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { togglePlayback(); text = if (player.isPlaying) "Ⅱ" else "▶" }
        }
        controls.addView(play, LinearLayout.LayoutParams(dp(68), dp(68)).apply {
            setMargins(dp(8), 0, dp(8), 0)
        })
        addPlayerControl(controls, "▶|", "Next track") {
            player.seekToNext()
            updatePlaybackProgress()
        }
        addPlayerControl(controls, if (player.repeatMode == Player.REPEAT_MODE_ONE) "↻¹" else "↻", "Repeat") {
            cycleRepeatMode()
            renderContent()
        }.setTextColor(if (player.repeatMode != Player.REPEAT_MODE_OFF) primary else muted)
        body.addView(controls)

        sleepTimerButton = addActionButton(
            body,
            if (sleepTimerEndsAt > System.currentTimeMillis()) "◷  Sleep timer · ${remainingSleepTime()}" else "◷  Sleep timer"
        ) {
            chooseSleepTimer()
        }
        addActionButton(body, "☷  Play queue · ${queue.size} tracks") {
            currentPage = Page.Queue
            renderContent()
        }
        addActionButton(body, if (track.id in favoriteIds) "♥  Liked" else "♡  Add to liked songs") {
            toggleFavorite(track.id)
            renderContent()
        }
        addActionButton(body, "♫  Read lyrics") { showLyrics(track) }
        addActionButton(body, "✎  Edit tags & lyrics") { editTags(track) }
        updatePlaybackProgress()
    }

    private fun addPlayerControl(
        parent: LinearLayout,
        label: String,
        description: String,
        action: () -> Unit
    ): TextView {
        val control = TextView(this).apply {
            text = label
            contentDescription = description
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(textColor)
            setOnClickListener { action() }
        }
        parent.addView(control, LinearLayout.LayoutParams(dp(48), dp(56)))
        return control
    }

    private fun addTrackRow(
        body: LinearLayout,
        track: Track,
        onClick: (() -> Unit)? = null,
        onMore: (() -> Unit)? = null,
        playing: Boolean = false
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
            setTextColor(Color.WHITE)
            background = artworkBackground(track.album)
        }
        row.addView(cover, LinearLayout.LayoutParams(dp(44), dp(44)))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        labels.addView(TextView(this).apply {
            text = (if (playing) "♫  " else "") + track.title
            textSize = 15f
            setTextColor(if (playing) primary else textColor)
            maxLines = 1
        })
        labels.addView(TextView(this).apply {
            text = "${track.artist}  ·  ${track.album}"
            textSize = 12f
            setTextColor(muted)
            maxLines = 1
        })
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val favorite = TextView(this).apply {
            text = if (track.id in favoriteIds) "♥" else "♡"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(if (track.id in favoriteIds) primary else muted)
            setOnClickListener {
                val liked = toggleFavorite(track.id)
                text = if (liked) "♥" else "♡"
                setTextColor(if (liked) primary else muted)
                if ((currentPage as? Page.SmartPlaylist)?.kind == "liked" && !liked) {
                    renderContent()
                }
            }
        }
        row.addView(favorite, LinearLayout.LayoutParams(dp(40), dp(48)))
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
        body.addView(View(this).apply {
            setBackgroundColor(if (this@MainActivity.background == Color.rgb(17, 20, 17)) Color.rgb(43, 48, 42) else Color.rgb(230, 232, 225))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
    }

    private fun addHomeTile(
        parent: LinearLayout,
        icon: String,
        title: String,
        subtitle: String,
        action: () -> Unit
    ) {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(1).toFloat()
            setCardBackgroundColor(surface)
            strokeWidth = dp(1)
            strokeColor = if (this@MainActivity.background == Color.rgb(17, 20, 17)) Color.rgb(48, 54, 47) else Color.rgb(226, 229, 220)
            setOnClickListener { action() }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
        }
        content.addView(TextView(this).apply {
            text = icon
            textSize = 19f
            setTextColor(primary)
            gravity = Gravity.CENTER
            background = roundedBackground(
                if (this@MainActivity.background == Color.rgb(17, 20, 17)) Color.rgb(49, 60, 39) else Color.rgb(235, 242, 224),
                dp(12)
            )
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        content.addView(TextView(this).apply {
            text = title
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            maxLines = 1
            setPadding(0, dp(8), 0, dp(2))
        })
        content.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(muted)
            maxLines = 1
        })
        card.addView(content)
        parent.addView(card, LinearLayout.LayoutParams(0, dp(112), 1f).apply {
            setMargins(0, dp(4), dp(8), dp(4))
        })
    }

    private fun artworkBackground(seed: String): android.graphics.drawable.GradientDrawable {
        val colors = listOf(
            primary,
            Color.rgb(110, 83, 156),
            Color.rgb(45, 128, 134),
            Color.rgb(192, 105, 76),
            Color.rgb(74, 105, 160),
            Color.rgb(169, 78, 104)
        )
        val index = (seed.lowercase().hashCode() and Int.MAX_VALUE) % colors.size
        val first = colors[index]
        val second = colors[(index + 2) % colors.size]
        return android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(first, second)
        ).apply {
            cornerRadius = dp(16).toFloat()
        }
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

    private fun addCover(body: LinearLayout, symbol: String, size: Int, seed: String = symbol) {
        val cover = TextView(this).apply {
            text = symbol
            textSize = 52f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = artworkBackground(seed)
        }
        body.addView(cover, LinearLayout.LayoutParams(dp(size), dp(size)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(22)
            bottomMargin = dp(10)
        })
    }

    private fun addActionButton(body: LinearLayout, title: String, action: () -> Unit): MaterialButton {
        val button = MaterialButton(this).apply {
            text = title
            textSize = 14f
            setTextColor(textColor)
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(surface)
            strokeWidth = dp(1)
            strokeColor = android.content.res.ColorStateList.valueOf(
                if (this@MainActivity.background == Color.rgb(17, 20, 17)) Color.rgb(49, 55, 48) else Color.rgb(226, 229, 220)
            )
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { action() }
        }
        body.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(7)
        })
        return button
    }

    private fun addEditorField(
        body: LinearLayout,
        label: String,
        value: String,
        multiline: Boolean = false
    ): EditText {
        body.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(12), 0, dp(5))
        })
        return EditText(this).apply {
            setText(value)
            hint = label
            textSize = 15f
            setTextColor(textColor)
            setHintTextColor(muted)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(surface, dp(10))
            if (multiline) {
                minLines = 5
                gravity = Gravity.TOP
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else {
                setSingleLine(true)
            }
            body.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun bindEditorField(field: EditText, onChange: (String) -> Unit) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onChange(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
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

    private fun addText(body: LinearLayout, message: String) {
        body.addView(TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(muted)
            setPadding(dp(8), dp(6), dp(8), dp(10))
        })
    }

    private fun addCompactButton(parent: LinearLayout, label: String, action: () -> Unit) {
        val button = TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(textColor)
            background = roundedBackground(surface, dp(12))
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            setMargins(0, dp(8), dp(6), dp(8))
        })
    }

    private fun chooseSetting(
        title: String,
        options: Array<String>,
        selected: String?,
        onSelected: (String) -> Unit
    ) {
        val checked = options.indexOf(selected).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options, checked) { dialog, index ->
                onSelected(options[index])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun filtered(source: List<Track>): List<Track> {
        val needle = search.text?.toString()?.trim().orEmpty()
        if (needle.isEmpty()) return source
        return source.filter {
            it.title.contains(needle, true) || it.artist.contains(needle, true) || it.album.contains(needle, true)
        }
    }

    private fun sortTracks(source: List<Track>): List<Track> =
        when (preferences.getString("sort", "Title")) {
            "Artist" -> source.sortedWith(compareBy<Track> { it.artist.lowercase() }.thenBy { it.title.lowercase() })
            "Recently added" -> source.sortedByDescending { it.dateAdded }
            else -> source.sortedBy { it.title.lowercase() }
        }

    private fun tracksByIds(ids: List<Long>): List<Track> {
        val byId = tracks.associateBy { it.id }
        return ids.mapNotNull(byId::get)
    }

    private fun repeatLabel(): String = when (player.repeatMode) {
        Player.REPEAT_MODE_ONE -> "↻  Repeat one"
        Player.REPEAT_MODE_ALL -> "↻  Repeat all"
        else -> "↻  Repeat off"
    }

    private fun cycleRepeatMode() {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        preferences.edit().putString(
            "repeat",
            when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> "One"
                Player.REPEAT_MODE_ALL -> "All"
                else -> "Off"
            }
        ).apply()
    }

    private fun applyTheme() {
        val isDark = when (preferences.getString("theme", "Dark")) {
            "Light" -> false
            "System" -> resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> true
        }
        background = if (isDark) Color.rgb(17, 20, 17) else Color.rgb(246, 247, 242)
        surface = if (isDark) Color.rgb(29, 34, 29) else Color.WHITE
        muted = if (isDark) Color.rgb(161, 170, 157) else Color.rgb(104, 111, 101)
        textColor = if (isDark) Color.rgb(241, 244, 237) else Color.rgb(29, 34, 29)
        primary = when (preferences.getString("accent", "Lime")) {
            "Ocean" -> Color.rgb(101, 190, 238)
            "Rose" -> Color.rgb(241, 132, 161)
            else -> if (isDark) Color.rgb(190, 232, 111) else Color.rgb(92, 132, 38)
        }
    }

    private fun trackById(id: Long): Track? = tracks.firstOrNull { it.id == id }

    private fun goBack() {
        currentPage = when (val page = currentPage) {
            Page.Queue, Page.Artists, Page.Albums, Page.Playlists, Page.Settings -> Page.Library
            is Page.Artist -> Page.Artists
            is Page.Album -> Page.Albums
            is Page.TrackDetails -> Page.Library
            is Page.PlaylistDetails -> Page.Playlists
            is Page.SmartPlaylist -> Page.Playlists
            is Page.TrackEditor -> Page.TrackDetails(page.id)
            Page.NowPlaying -> currentTrack()?.let { Page.TrackDetails(it.id) } ?: Page.Library
            Page.Library -> Page.Library
        }
        renderContent()
    }

    private fun lookupItunes(term: String) {
        val trackId = (currentPage as? Page.TrackEditor)?.id ?: return
        val query = term.trim()
        if (query.isBlank()) {
            Toast.makeText(this, "Enter a file name or tags to search.", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Searching iTunes…", Toast.LENGTH_SHORT).show()
        metadataExecutor.execute {
            try {
                val results = MetadataLookup.searchItunes(query)
                runOnUiThread {
                    if (isFinishing || isDestroyed || currentPage != Page.TrackEditor(trackId)) return@runOnUiThread
                    if (results.isEmpty()) {
                        Toast.makeText(this, "iTunes found no matching tracks.", Toast.LENGTH_LONG).show()
                    } else {
                        val labels = results.map { candidate ->
                            listOf(candidate.title, candidate.artist, candidate.album)
                                .filter(String::isNotBlank)
                                .joinToString(" — ")
                        }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("Choose iTunes metadata")
                            .setItems(labels) { _, index ->
                                val candidate = results[index]
                                editorTitle.setText(candidate.title)
                                editorArtist.setText(candidate.artist)
                                editorAlbum.setText(candidate.album)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            } catch (error: java.io.IOException) {
                showLookupError("iTunes", error)
            } catch (error: org.json.JSONException) {
                showLookupError("iTunes", error)
            }
        }
    }

    private fun lookupLyrics(track: Track) {
        if (currentPage != Page.TrackEditor(track.id)) return
        val title = editorTitle.text.toString().trim()
        val artist = editorArtist.text.toString().trim()
        val album = editorAlbum.text.toString().trim()
        if (title.isBlank() || artist.isBlank()) {
            Toast.makeText(this, "Enter a track title and artist before searching lyrics.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Searching LRCLIB…", Toast.LENGTH_SHORT).show()
        metadataExecutor.execute {
            try {
                val results = MetadataLookup.searchLyrics(title, artist, album, track.duration)
                runOnUiThread {
                    if (isFinishing || isDestroyed || currentPage != Page.TrackEditor(track.id)) return@runOnUiThread
                    when (results.size) {
                        0 -> Toast.makeText(this, "LRCLIB has no matching lyrics.", Toast.LENGTH_LONG).show()
                        1 -> applyLyrics(results[0])
                        else -> {
                            val labels = results.map { "${it.title} — ${it.artist}${if (it.album.isBlank()) "" else " · ${it.album}"}" }.toTypedArray()
                            AlertDialog.Builder(this)
                                .setTitle("Choose lyrics")
                                .setItems(labels) { _, index -> applyLyrics(results[index]) }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                }
            } catch (error: java.io.IOException) {
                showLookupError("LRCLIB", error)
            } catch (error: org.json.JSONException) {
                showLookupError("LRCLIB", error)
            }
        }
    }

    private fun applyLyrics(candidate: LyricsCandidate) {
        editorLyrics.setText(candidate.lyrics)
        Toast.makeText(this, "Lyrics loaded. Save to write them into the audio file.", Toast.LENGTH_LONG).show()
    }

    private fun readEmbeddedLyrics(track: Track) {
        libraryExecutor.execute {
            try {
                val lyrics = AudioFileEditor.readLyrics(contentResolver, cacheDir, track)
                if (lyrics.isBlank()) return@execute
                runOnUiThread {
                    val page = currentPage as? Page.TrackEditor
                    if (!isFinishing && !isDestroyed && page?.id == track.id &&
                        editorLyrics.text.isBlank() && editorDraft?.lyricsEdited == false
                    ) {
                        editorLyrics.setText(lyrics)
                    }
                }
            } catch (error: java.io.IOException) {
                showLookupError("Audio tag read", error)
            } catch (error: org.jaudiotagger.audio.exceptions.CannotReadException) {
                showLookupError("Audio tag read", error)
            } catch (error: org.jaudiotagger.audio.exceptions.InvalidAudioFrameException) {
                showLookupError("Audio tag read", error)
            } catch (error: org.jaudiotagger.audio.exceptions.ReadOnlyFileException) {
                showLookupError("Audio tag read", error)
            } catch (error: org.jaudiotagger.tag.TagException) {
                showLookupError("Audio tag read", error)
            }
        }
    }

    private fun showLookupError(provider: String, error: Exception) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val detail = error.message?.takeIf(String::isNotBlank) ?: "Please try again."
            Toast.makeText(this, "$provider failed: $detail", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveEditor(track: Track) {
        if (pendingTagEdit != null) {
            Toast.makeText(this, "A file edit is already in progress.", Toast.LENGTH_SHORT).show()
            return
        }
        val newName = editorFileName.text.toString().trim()
        val oldExtension = track.displayName.substringAfterLast('.', "").lowercase()
        val newExtension = newName.substringAfterLast('.', "").lowercase()
        if (newName.isBlank() || newName.contains('/') || newName.contains('\\') ||
            newName.any(Char::isISOControl) || oldExtension.isBlank() || newExtension != oldExtension
        ) {
            Toast.makeText(this, "Use a file name without a path and keep the original .$oldExtension extension.", Toast.LENGTH_LONG).show()
            return
        }
        pendingTagEdit = PendingTagEdit(
            track = track,
            tags = TrackTags(
                editorTitle.text.toString().trim(),
                editorArtist.text.toString().trim(),
                editorAlbum.text.toString().trim(),
                editorLyrics.text.toString()
            ),
            displayName = newName
        )
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                try {
                    val request = MediaStore.createWriteRequest(contentResolver, listOf(track.uri))
                    writeRequestLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                } catch (error: SecurityException) {
                    pendingTagEdit = null
                    Toast.makeText(this, "Android could not request permission to edit this file.", Toast.LENGTH_LONG).show()
                }
            }
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ->
                writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else -> savePendingTagEdit()
        }
    }

    private fun savePendingTagEdit() {
        val edit = pendingTagEdit ?: return
        libraryExecutor.execute {
            try {
                AudioFileEditor.save(contentResolver, cacheDir, edit.track, edit.tags, edit.displayName)
                database.saveTags(edit.track.id, edit.tags)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    pendingTagEdit = null
                    editorDraft?.displayName = edit.displayName
                    editorFileName.setText(edit.displayName)
                    Toast.makeText(this, "Tags, lyrics, and file name saved to the audio file.", Toast.LENGTH_LONG).show()
                    loadTracks()
                }
            } catch (error: java.io.IOException) {
                showFileEditError(error)
            } catch (error: org.jaudiotagger.audio.exceptions.CannotReadException) {
                showFileEditError(error)
            } catch (error: org.jaudiotagger.audio.exceptions.CannotWriteException) {
                showFileEditError(error)
            } catch (error: org.jaudiotagger.audio.exceptions.InvalidAudioFrameException) {
                showFileEditError(error)
            } catch (error: org.jaudiotagger.audio.exceptions.ReadOnlyFileException) {
                showFileEditError(error)
            } catch (error: org.jaudiotagger.tag.TagException) {
                showFileEditError(error)
            } catch (error: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && error is RecoverableSecurityException) {
                    requestRecoverableWrite(error)
                } else {
                    showFileEditError(error)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestRecoverableWrite(error: RecoverableSecurityException) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val request = IntentSenderRequest.Builder(error.userAction.actionIntent.intentSender).build()
            writeRequestLauncher.launch(request)
        }
    }

    private fun showFileEditError(error: Exception) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            pendingTagEdit = null
            val detail = error.message?.takeIf(String::isNotBlank) ?: "The audio format may not support tag editing."
            AlertDialog.Builder(this)
                .setTitle("Could not save audio file")
                .setMessage(detail)
                .setPositiveButton("OK", null)
                .show()
        }
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
        updatePlaybackProgress()
    }

    private fun updatePlaybackProgress() {
        if (!::miniProgress.isInitialized) return
        val track = currentTrack()
        val duration = track?.let(::playbackDuration) ?: 0L
        val position = player.currentPosition.coerceAtLeast(0L)
        miniProgress.progress = if (duration > 0L) {
            ((position.toDouble() / duration) * miniProgress.max).toInt().coerceIn(0, miniProgress.max)
        } else 0
        nowPlayingProgress?.let { slider ->
            if (!slider.isPressed) slider.value = position.coerceIn(0L, duration.coerceAtLeast(1L)).toFloat()
        }
        nowPlayingPosition?.text = formatTime(position)
        nowPlayingDuration?.text = formatTime(duration)
        if (sleepTimerEndsAt > 0L) {
            if (System.currentTimeMillis() >= sleepTimerEndsAt) {
                sleepTimerEndsAt = 0L
                player.pause()
                Toast.makeText(this, "Sleep timer ended. Playback paused.", Toast.LENGTH_LONG).show()
                if (currentPage is Page.NowPlaying) renderContent()
            } else {
                sleepTimerButton?.text = "◷  Sleep timer · ${remainingSleepTime()}"
            }
        }
    }

    private fun playbackDuration(track: Track): Long =
        player.duration.takeIf { it > 0L } ?: track.duration.coerceAtLeast(1L)

    private fun formatTime(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0L) / 1_000L
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun remainingSleepTime(): String {
        val seconds = ((sleepTimerEndsAt - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1_000L
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun chooseSleepTimer() {
        val options = arrayOf("Turn off", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "90 minutes")
        val minutes = listOf(0, 15, 30, 45, 60, 90)
        AlertDialog.Builder(this)
            .setTitle("Sleep timer")
            .setItems(options) { _, index ->
                sleepTimerEndsAt = if (minutes[index] == 0) 0L
                else System.currentTimeMillis() + minutes[index] * 60_000L
                Toast.makeText(
                    this,
                    if (minutes[index] == 0) "Sleep timer turned off."
                    else "Playback will pause in ${minutes[index]} minutes.",
                    Toast.LENGTH_SHORT
                ).show()
                renderContent()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLyrics(track: Track) {
        val lyrics = track.lyrics.trim()
        val text = TextView(this).apply {
            this.text = lyrics.ifBlank { "No lyrics saved for this track yet. Search for lyrics from the tag editor." }
            textSize = 16f
            setTextColor(textColor)
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }
        val scroll = ScrollView(this).apply {
            addView(text)
            isFillViewport = true
        }
        AlertDialog.Builder(this)
            .setTitle("${track.title} · Lyrics")
            .setView(scroll)
            .setPositiveButton(if (lyrics.isBlank()) "Find lyrics" else "Done") { _, _ ->
                if (lyrics.isBlank()) editTags(track)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun editTags(track: Track) {
        currentPage = Page.TrackEditor(track.id)
        renderContent()
    }

    private fun toggleFavorite(trackId: Long): Boolean {
        val liked = database.toggleFavorite(trackId)
        favoriteIds = if (liked) favoriteIds + trackId else favoriteIds - trackId
        return liked
    }

    private fun trackActions(track: Track) {
        val liked = track.id in favoriteIds
        AlertDialog.Builder(this)
            .setTitle(track.title)
            .setItems(arrayOf(
                "Play next",
                "Add to queue",
                if (liked) "Remove from liked songs" else "Add to liked songs",
                "Add to playlist",
                "Edit tags & lyrics"
            )) { _, which ->
                when (which) {
                    0 -> playNext(track)
                    1 -> addToQueue(track)
                    2 -> toggleFavorite(track.id).also { renderContent() }
                    3 -> choosePlaylist(track)
                    4 -> editTags(track)
                }
            }
            .show()
    }

    private fun addToQueue(track: Track) {
        if (player.mediaItemCount == 0) {
            playTracks(listOf(track), 0)
            return
        }
        queue = queue + track
        player.addMediaItem(MediaItem.fromUri(track.uri))
        Toast.makeText(this, "Added to queue.", Toast.LENGTH_SHORT).show()
    }

    private fun chooseTracksForQueue() {
        if (tracks.isEmpty()) {
            Toast.makeText(this, "No tracks are available.", Toast.LENGTH_SHORT).show()
            return
        }
        val checked = BooleanArray(tracks.size)
        AlertDialog.Builder(this)
            .setTitle("Add to queue")
            .setMultiChoiceItems(tracks.map { "${it.title} — ${it.artist}" }.toTypedArray(), checked) { _, index, selected ->
                checked[index] = selected
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add selected") { _, _ ->
                val selectedTracks = tracks.filterIndexed { index, _ -> checked[index] }
                selectedTracks.forEach(::addToQueue)
                currentPage = Page.Queue
                renderContent()
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
