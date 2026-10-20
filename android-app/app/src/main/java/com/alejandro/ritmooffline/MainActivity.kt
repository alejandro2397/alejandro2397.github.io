package com.alejandro.ritmooffline

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private val prefs by lazy { getSharedPreferences("ritmo", Context.MODE_PRIVATE) }
    private val tracks = mutableListOf<Track>()
    private lateinit var trackList: LinearLayout
    private lateinit var status: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var search: EditText
    private lateinit var playButton: Button
    private lateinit var shuffleButton: Button
    private var shuffle = false
    data class Track(val uri: Uri, val title: String)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            if (tracks.none { it.uri == uri }) tracks.add(Track(uri, displayName(uri)))
        }
        saveTracks()
        renderTracks()
        status.text = "${tracks.size} canciones guardadas en la biblioteca"
        if (controller?.mediaItemCount == 0) loadQueue()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(18, 16, 30)
        window.navigationBarColor = Color.rgb(18, 16, 30)
        buildUi()
        restoreTracks()
        renderTracks()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            try {
                controller = future.get()
                controller?.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playButton.text = if (isPlaying) "❚❚ Pausar" else "▶ Reproducir"
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        nowPlaying.text = mediaItem?.mediaMetadata?.title ?: "Nada reproduciéndose"
                    }
                })
                if (tracks.isNotEmpty() && controller?.mediaItemCount == 0) loadQueue()
                updateNowPlaying()
            } catch (_: Exception) { status.text = "No se pudo iniciar el reproductor" }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controller?.release()
        controller = null
        super.onStop()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 22, 18, 12)
            setBackgroundColor(Color.rgb(18, 16, 30))
        }
        root.addView(TextView(this).apply {
            text = "♫  RITMO OFFLINE"
            textSize = 25f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(-1, 50))
        root.addView(TextView(this).apply {
            text = "Tu música. Sin internet."
            textSize = 14f
            setTextColor(Color.rgb(190, 180, 220))
            setPadding(0, 0, 0, 12)
        })
        root.addView(Button(this).apply {
            text = "＋ Agregar canciones"
            setOnClickListener { picker.launch(arrayOf("audio/*")) }
        }, LinearLayout.LayoutParams(-1, 50))
        search = EditText(this).apply {
            hint = "Buscar canción…"
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setBackgroundColor(Color.rgb(38, 34, 54))
            setPadding(14, 0, 14, 0)
        }
        val searchLp = LinearLayout.LayoutParams(-1, 48).apply { topMargin = 9; bottomMargin = 7 }
        root.addView(search, searchLp)
        search.addTextChangedListener(SimpleTextWatcher { renderTracks() })
        status = TextView(this).apply {
            text = "Agrega canciones guardadas en tu teléfono"
            setTextColor(Color.rgb(190, 180, 220))
            textSize = 12f
            setPadding(2, 5, 2, 8)
        }
        root.addView(status)
        val scroll = ScrollView(this)
        trackList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(trackList)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        nowPlaying = TextView(this).apply {
            text = "Nada reproduciéndose"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(4, 12, 4, 8)
            maxLines = 2
        }
        root.addView(nowPlaying)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        controls.addView(makeButton("⏮") { controller?.seekToPreviousMediaItem() })
        playButton = makeButton("▶ Reproducir") {
            val c = controller
            if (c != null) {
                if (c.isPlaying) c.pause() else {
                    if (c.mediaItemCount == 0) loadQueue()
                    c.play()
                }
            }
        }
        controls.addView(playButton)
        controls.addView(makeButton("⏭") { controller?.seekToNextMediaItem() })
        root.addView(controls)
        val extras = LinearLayout(this).apply { gravity = Gravity.CENTER }
        shuffleButton = makeButton("🔀 Aleatorio: no") {
            shuffle = !shuffle
            controller?.shuffleModeEnabled = shuffle
            shuffleButton.text = if (shuffle) "🔀 Aleatorio: sí" else "🔀 Aleatorio: no"
        }
        extras.addView(shuffleButton)
        extras.addView(makeButton("↻ Repetir") {
            val c = controller
            if (c != null) {
                c.repeatMode = when (c.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                status.text = when (c.repeatMode) {
                    Player.REPEAT_MODE_ALL -> "Repitiendo toda la lista"
                    Player.REPEAT_MODE_ONE -> "Repitiendo esta canción"
                    else -> "Repetición desactivada"
                }
            }
        })
        root.addView(extras)
        setContentView(root)
    }

    private fun makeButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "Audio"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
        }
        return name.substringBeforeLast('.', name)
    }

    private fun restoreTracks() {
        prefs.getStringSet("uris", emptySet()).orEmpty().forEach { raw ->
            try {
                val uri = Uri.parse(raw)
                contentResolver.openFileDescriptor(uri, "r")?.close()
                tracks.add(Track(uri, displayName(uri)))
            } catch (_: Exception) {}
        }
    }

    private fun saveTracks() {
        prefs.edit().putStringSet("uris", tracks.map { it.uri.toString() }.toSet()).apply()
    }

    private fun loadQueue() {
        val c = controller ?: return
        if (tracks.isEmpty()) {
            status.text = "Pulsa Agregar canciones para comenzar"
            return
        }
        val items = tracks.map { track ->
            MediaItem.Builder().setUri(track.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist("Biblioteca local").build())
                .build()
        }
        c.setMediaItems(items)
        c.prepare()
        updateNowPlaying()
    }

    private fun updateNowPlaying() {
        nowPlaying.text = controller?.currentMediaItem?.mediaMetadata?.title ?: "Elige una canción"
        playButton.text = if (controller?.isPlaying == true) "❚❚ Pausar" else "▶ Reproducir"
    }

    private fun renderTracks() {
        if (!::trackList.isInitialized) return
        trackList.removeAllViews()
        val query = if (::search.isInitialized) search.text.toString().trim().lowercase() else ""
        val shown = tracks.filter { it.title.lowercase().contains(query) }
        shown.forEach { track ->
            val row = TextView(this).apply {
                text = "♫   ${track.title}"
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(14, 16, 10, 16)
                setBackgroundColor(Color.rgb(30, 27, 44))
                setOnClickListener {
                    val c = controller ?: return@setOnClickListener
                    val index = tracks.indexOf(track)
                    loadQueue()
                    c.seekTo(index.coerceAtLeast(0), 0L)
                    c.play()
                    nowPlaying.text = track.title
                }
            }
            trackList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 5 })
        }
        if (shown.isEmpty()) {
            trackList.addView(TextView(this).apply {
                text = if (tracks.isEmpty()) "Tu biblioteca está vacía. Agrega MP3, M4A, OGG o FLAC." else "No se encontraron canciones."
                setTextColor(Color.LTGRAY)
                setPadding(8, 20, 8, 20)
            })
        }
    }
}
