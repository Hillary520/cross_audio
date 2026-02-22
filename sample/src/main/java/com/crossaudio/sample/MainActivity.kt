package com.crossaudio.sample

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.crossaudio.engine.MediaItem
import com.crossaudio.engine.PlayerEngine
import com.crossaudio.engine.PlayerState
import com.crossaudio.engine.QueueMutableEngine
import com.crossaudio.engine.service.CrossAudioPlaybackService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var selectedUris: List<Uri> = emptyList()
    private var engine: PlayerEngine? = null
    private var service: CrossAudioPlaybackService? = null
    private var crossfadeMs: Long = 12_000L
    private val prefs by lazy { getSharedPreferences("cross_audio_sample", MODE_PRIVATE) }

    private lateinit var status: TextView

    private val openAudio =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            selectedUris = uris
            persistQueue()
            service?.setQueue(uris.map(::MediaItem))
        }

    private val addAudio =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            selectedUris = selectedUris + uris
            persistQueue()
            (engine as? QueueMutableEngine)?.addToQueue(uris.map(::MediaItem))
        }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val b = binder as CrossAudioPlaybackService.Binder
            service = b.service
            engine = b.service.player().also { it.setCrossfadeDurationMs(crossfadeMs) }
            if (engine?.state?.value is PlayerState.Idle && selectedUris.isNotEmpty()) {
                service?.setQueue(selectedUris.map(::MediaItem))
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            engine = null
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        crossfadeMs = prefs.getLong("crossfadeMs", 12_000L).coerceIn(0L, 12_000L)
        selectedUris = prefs.getStringSet("queueUris", emptySet()).orEmpty().map(Uri::parse)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        status = TextView(this).apply { text = "Pick audio files to start" }
        val pick = Button(this).apply {
            text = "Pick queue"
            setOnClickListener { openAudio.launch(arrayOf("audio/*")) }
        }
        val add = Button(this).apply {
            text = "Add to queue"
            setOnClickListener { addAudio.launch(arrayOf("audio/*")) }
        }
        val playPause = Button(this).apply {
            text = "Play/Pause"
            setOnClickListener { togglePlayPause() }
        }
        val prev = Button(this).apply {
            text = "Prev"
            setOnClickListener { CrossAudioPlaybackService.start(this@MainActivity, CrossAudioPlaybackService.ACTION_PREV) }
        }
        val next = Button(this).apply {
            text = "Next"
            setOnClickListener { CrossAudioPlaybackService.start(this@MainActivity, CrossAudioPlaybackService.ACTION_NEXT) }
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener { CrossAudioPlaybackService.start(this@MainActivity, CrossAudioPlaybackService.ACTION_STOP) }
        }
        val crossfadeLabel = TextView(this).apply { text = "Crossfade: ${crossfadeMs / 1000L}s" }
        val crossfadeSeek = SeekBar(this).apply {
            max = 12
            progress = (crossfadeMs / 1000L).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    crossfadeMs = (progress.toLong() * 1000L).coerceIn(0L, 12_000L)
                    crossfadeLabel.text = "Crossfade: ${crossfadeMs / 1000L}s"
                    prefs.edit().putLong("crossfadeMs", crossfadeMs).apply()
                    engine?.setCrossfadeDurationMs(crossfadeMs)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }

        listOf(status, pick, add, playPause, prev, next, stop, crossfadeLabel, crossfadeSeek).forEach(root::addView)
        setContentView(root)
        observeState()
    }

    override fun onStart() {
        super.onStart()
        bindService(CrossAudioPlaybackService.bindIntent(this), conn, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        runCatching { unbindService(conn) }
        engine = null
        service = null
        super.onStop()
    }

    private fun togglePlayPause() {
        val st = engine?.state?.value
        when (st) {
            is PlayerState.Playing -> CrossAudioPlaybackService.start(this, CrossAudioPlaybackService.ACTION_PAUSE)
            is PlayerState.Paused -> CrossAudioPlaybackService.start(this, CrossAudioPlaybackService.ACTION_PLAY, crossfadeMs = crossfadeMs)
            else -> {
                val shouldReplace = selectedUris.isNotEmpty()
                CrossAudioPlaybackService.start(
                    context = this,
                    action = CrossAudioPlaybackService.ACTION_PLAY,
                    uris = if (shouldReplace) selectedUris.map(Uri::toString) else emptyList(),
                    replaceQueue = shouldReplace,
                    crossfadeMs = crossfadeMs,
                )
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (engine == null) {
                    status.text = "Service not connected"
                    kotlinx.coroutines.delay(100)
                }
                engine!!.state.collect { st ->
                    status.text = buildString {
                        append("Queue items: ${selectedUris.size}\n")
                        selectedUris.firstOrNull()?.let { append("First: $it\n") }
                        append("Crossfade: ${crossfadeMs / 1000L}s\n")
                        append(st.toString())
                    }
                }
            }
        }
    }

    private fun persistQueue() {
        prefs.edit().putStringSet("queueUris", selectedUris.map(Uri::toString).toSet()).apply()
    }
}
