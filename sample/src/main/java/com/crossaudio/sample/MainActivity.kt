package com.crossaudio.sample

import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Build
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.crossaudio.engine.AdaptiveStreamingEngine
import com.crossaudio.engine.CacheEngine
import com.crossaudio.engine.CacheState
import com.crossaudio.engine.DebugControls
import com.crossaudio.engine.DrmEngine
import com.crossaudio.engine.DrmGlobalConfig
import com.crossaudio.engine.DrmRequest
import com.crossaudio.engine.DrmScheme
import com.crossaudio.engine.EngineTelemetryEvent
import com.crossaudio.engine.MediaItem
import com.crossaudio.engine.ObservableEngine
import com.crossaudio.engine.PlayerEngine
import com.crossaudio.engine.PlayerEvent
import com.crossaudio.engine.QualityCap
import com.crossaudio.engine.QueueMutableEngine
import com.crossaudio.engine.RepeatMode
import com.crossaudio.engine.ShuffleEngine
import com.crossaudio.engine.StreamingConfig
import com.crossaudio.engine.TelemetryEngine
import com.crossaudio.engine.service.CrossAudioPlaybackService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var selectedUris: List<Uri> = emptyList()
    private var engine: PlayerEngine? = null
    private var service: CrossAudioPlaybackService? = null
    private var crossfadeMs: Long = 12_000L
    private var repeatMode: RepeatMode = RepeatMode.OFF
    private var volume: Float = 1.0f
    private var debugPan: Boolean = false
    private var crossfadeHeadroom: Float = 0.9f
    private var shuffleEnabled: Boolean = false
    private var segmentPipelineEnabled: Boolean = false
    private var qualityCap: QualityCap = QualityCap.AUTO
    private var lastLicenseId: String? = null

    private val prefs by lazy { getSharedPreferences("cross_audio_sample", MODE_PRIVATE) }

    private val openAudio =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            selectedUris = uris
            uris.forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            prefs.edit().putStringSet("queueUris", uris.map { it.toString() }.toSet()).apply()
            service?.setQueue(uris.map { MediaItem(it) })
        }

    private val addAudio =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            uris.forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            selectedUris = selectedUris + uris
            prefs.edit().putStringSet("queueUris", selectedUris.map { it.toString() }.toSet()).apply()
            (engine as? QueueMutableEngine)?.addToQueue(uris.map { MediaItem(it) })
        }

    private lateinit var status: TextView

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val b = binder as CrossAudioPlaybackService.Binder
            service = b.service
            engine = b.service.player()

            // Apply latest UI config and queue.
            engine?.setCrossfadeDurationMs(crossfadeMs)
            engine?.setRepeatMode(repeatMode)
            engine?.setVolume(volume)
            (engine as? DebugControls)?.setCrossfadeDebugPanning(debugPan)
            (engine as? DebugControls)?.setCrossfadeHeadroom(crossfadeHeadroom)
            (engine as? ShuffleEngine)?.setShuffleEnabled(shuffleEnabled)
            (engine as? AdaptiveStreamingEngine)?.setStreamingConfig(
                StreamingConfig(
                    qualityCap = qualityCap,
                    segmentPipelineEnabled = segmentPipelineEnabled,
                ),
            )
            (engine as? AdaptiveStreamingEngine)?.setQualityCap(qualityCap)
            (engine as? DrmEngine)?.setDrmConfig(DrmGlobalConfig())
            // IMPORTANT: do NOT reset the service queue on every rebind; it will stop playback.
            // Only restore a saved queue if the service is idle (e.g., after process death).
            val st = engine?.state?.value
            if (st is com.crossaudio.engine.PlayerState.Idle) {
                if (selectedUris.isNotEmpty()) {
                    service?.setQueue(selectedUris.map { MediaItem(it) })
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            engine = null
            service = null
        }
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignore */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        crossfadeMs = prefs.getLong("crossfadeMs", 12_000L).coerceIn(0L, 12_000L)
        volume = prefs.getFloat("volume", 1.0f).coerceIn(0f, 1f)
        debugPan = prefs.getBoolean("debugPan", false)
        crossfadeHeadroom = prefs.getFloat("crossfadeHeadroom", 0.9f).coerceIn(0.5f, 1.0f)
        repeatMode = runCatching {
            RepeatMode.entries[prefs.getInt("repeatMode", RepeatMode.OFF.ordinal)]
        }.getOrDefault(RepeatMode.OFF)
        shuffleEnabled = prefs.getBoolean("shuffleEnabled", false)
        segmentPipelineEnabled = prefs.getBoolean("segmentPipelineEnabled", false)
        qualityCap = runCatching {
            QualityCap.entries[prefs.getInt("qualityCap", QualityCap.AUTO.ordinal)]
        }.getOrDefault(QualityCap.AUTO)

        // Restore last selected queue (for UI + service recovery after process death).
        val saved = prefs.getStringSet("queueUris", null)?.toList().orEmpty()
        if (saved.isNotEmpty()) {
            selectedUris = saved.mapNotNull { s -> runCatching { Uri.parse(s) }.getOrNull() }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            val perm = android.Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestNotifications.launch(perm)
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        status = TextView(this).apply { text = "Pick a file to start" }

        val pick = Button(this).apply {
            text = "Pick audio files (queue)"
            setOnClickListener { openAudio.launch(arrayOf("audio/*")) }
        }

        val add = Button(this).apply {
            text = "Add files to queue"
            setOnClickListener { addAudio.launch(arrayOf("audio/*")) }
        }

        var lastPosMs: Long = 0L
        var lastIsPlaying = false

        fun playViaService(replaceQueue: Boolean) {
            CrossAudioPlaybackService.start(
                this@MainActivity,
                CrossAudioPlaybackService.ACTION_PLAY,
                uris = if (replaceQueue) selectedUris.map { it.toString() } else emptyList(),
                replaceQueue = replaceQueue,
                crossfadeMs = crossfadeMs,
            )
        }

        val playPause = Button(this).apply {
            text = "Play/Pause"
            setOnClickListener {
                // Always control playback via service intents so the service is STARTED
                // (not just BOUND). Otherwise, leaving the app/unlocking can destroy
                // the bound-only service and stop playback.
                val st = engine?.state?.value
                when (st) {
                    is com.crossaudio.engine.PlayerState.Playing -> {
                        CrossAudioPlaybackService.start(this@MainActivity, CrossAudioPlaybackService.ACTION_PAUSE)
                    }
                    is com.crossaudio.engine.PlayerState.Paused -> {
                        CrossAudioPlaybackService.start(
                            this@MainActivity,
                            CrossAudioPlaybackService.ACTION_PLAY,
                            crossfadeMs = crossfadeMs,
                        )
                    }
                    else -> {
                        // Service isn't ready or has no queue; send the current queue.
                        playViaService(replaceQueue = selectedUris.isNotEmpty())
                    }
                }
            }
        }

        val prev = Button(this).apply {
            text = "Prev"
            setOnClickListener { CrossAudioPlaybackService.start(this@MainActivity, CrossAudioPlaybackService.ACTION_PREV) }
        }

        val next = Button(this).apply {
            text = "Next"
            setOnClickListener { CrossAudioPlaybackService.start(this@MainActivity, CrossAudioPlaybackService.ACTION_NEXT) }
        }

        val seekBack = Button(this).apply {
            text = "-10s"
            setOnClickListener { engine?.seekTo((lastPosMs - 10_000L).coerceAtLeast(0L)) }
        }

        val seekFwd = Button(this).apply {
            text = "+10s"
            setOnClickListener { engine?.seekTo(lastPosMs + 10_000L) }
        }

        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener { engine?.stop() ?: CrossAudioPlaybackService.start(this@MainActivity, CrossAudioPlaybackService.ACTION_STOP) }
        }

        val crossfadeLabel = TextView(this).apply {
            text = "Crossfade: ${crossfadeMs / 1000L}s"
        }

        val crossfadeSeek = SeekBar(this).apply {
            max = 12
            progress = (crossfadeMs / 1000L).toInt().coerceIn(0, 12)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    crossfadeMs = (progress.toLong() * 1000L).coerceIn(0L, 12_000L)
                    crossfadeLabel.text = "Crossfade: ${crossfadeMs / 1000L}s"
                    prefs.edit().putLong("crossfadeMs", crossfadeMs).apply()
                    engine?.setCrossfadeDurationMs(crossfadeMs)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        val xfade12 = Button(this).apply {
            text = "Crossfade 12s"
            setOnClickListener {
                crossfadeMs = 12_000L
                crossfadeLabel.text = "Crossfade: 12s"
                crossfadeSeek.progress = 12
                prefs.edit().putLong("crossfadeMs", crossfadeMs).apply()
                engine?.setCrossfadeDurationMs(crossfadeMs)
            }
        }

        val volumeLabel = TextView(this).apply {
            text = "Volume: ${(volume * 100).toInt()}%"
        }

        val volumeSeek = SeekBar(this).apply {
            max = 100
            progress = (volume * 100f).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    volume = (progress / 100f).coerceIn(0f, 1f)
                    volumeLabel.text = "Volume: ${progress}%"
                    prefs.edit().putFloat("volume", volume).apply()
                    engine?.setVolume(volume)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        val repeatButton = Button(this).apply {
            fun updateText() {
                text = "Repeat: ${repeatMode.name}"
            }
            updateText()
            setOnClickListener {
                repeatMode = when (repeatMode) {
                    RepeatMode.OFF -> RepeatMode.ONE
                    RepeatMode.ONE -> RepeatMode.ALL
                    RepeatMode.ALL -> RepeatMode.OFF
                }
                prefs.edit().putInt("repeatMode", repeatMode.ordinal).apply()
                engine?.setRepeatMode(repeatMode)
                updateText()
            }
        }

        val shuffleQueue = Button(this).apply {
            fun updateText() {
                text = if (shuffleEnabled) "Shuffle: ON" else "Shuffle: OFF"
            }
            updateText()
            setOnClickListener {
                shuffleEnabled = !shuffleEnabled
                prefs.edit().putBoolean("shuffleEnabled", shuffleEnabled).apply()
                (engine as? ShuffleEngine)?.setShuffleEnabled(shuffleEnabled)
                updateText()
            }
        }

        val removeFirst = Button(this).apply {
            text = "Remove first queue item"
            setOnClickListener {
                if (selectedUris.isEmpty()) return@setOnClickListener
                selectedUris = selectedUris.drop(1)
                prefs.edit().putStringSet("queueUris", selectedUris.map { it.toString() }.toSet()).apply()
                (engine as? QueueMutableEngine)?.removeFromQueue(intArrayOf(0))
            }
        }

        val moveFirstToLast = Button(this).apply {
            text = "Move first -> last"
            setOnClickListener {
                if (selectedUris.size < 2) return@setOnClickListener
                val first = selectedUris.first()
                selectedUris = selectedUris.drop(1) + first
                prefs.edit().putStringSet("queueUris", selectedUris.map { it.toString() }.toSet()).apply()
                (engine as? QueueMutableEngine)?.moveQueueItem(0, selectedUris.lastIndex)
            }
        }

        val pinCurrent = Button(this).apply {
            text = "Pin current offline"
            setOnClickListener {
                val item = when (val st = engine?.state?.value) {
                    is com.crossaudio.engine.PlayerState.Playing -> st.item
                    is com.crossaudio.engine.PlayerState.Paused -> st.item
                    else -> null
                } ?: return@setOnClickListener
                (engine as? CacheEngine)?.pinForOffline(item)
            }
        }

        val unpinCurrent = Button(this).apply {
            text = "Unpin current"
            setOnClickListener {
                val item = when (val st = engine?.state?.value) {
                    is com.crossaudio.engine.PlayerState.Playing -> st.item
                    is com.crossaudio.engine.PlayerState.Paused -> st.item
                    else -> null
                } ?: return@setOnClickListener
                (engine as? CacheEngine)?.unpinOffline(item)
            }
        }

        val clearCache = Button(this).apply {
            text = "Clear unpinned cache"
            setOnClickListener { (engine as? CacheEngine)?.clearUnpinnedCache() }
        }

        val segmentPipelineButton = Button(this).apply {
            fun updateText() {
                text = if (segmentPipelineEnabled) "Segment pipeline: ON" else "Segment pipeline: OFF"
            }
            updateText()
            setOnClickListener {
                segmentPipelineEnabled = !segmentPipelineEnabled
                prefs.edit().putBoolean("segmentPipelineEnabled", segmentPipelineEnabled).apply()
                (engine as? AdaptiveStreamingEngine)?.setStreamingConfig(
                    StreamingConfig(
                        qualityCap = qualityCap,
                        segmentPipelineEnabled = segmentPipelineEnabled,
                    ),
                )
                updateText()
            }
        }

        val qualityCapButton = Button(this).apply {
            fun updateText() {
                text = "Quality cap: ${qualityCap.name}"
            }
            updateText()
            setOnClickListener {
                qualityCap = when (qualityCap) {
                    QualityCap.AUTO -> QualityCap.LOW
                    QualityCap.LOW -> QualityCap.MEDIUM
                    QualityCap.MEDIUM -> QualityCap.HIGH
                    QualityCap.HIGH -> QualityCap.AUTO
                }
                prefs.edit().putInt("qualityCap", qualityCap.ordinal).apply()
                (engine as? AdaptiveStreamingEngine)?.setQualityCap(qualityCap)
                (engine as? AdaptiveStreamingEngine)?.setStreamingConfig(
                    StreamingConfig(
                        qualityCap = qualityCap,
                        segmentPipelineEnabled = segmentPipelineEnabled,
                    ),
                )
                updateText()
            }
        }

        val preloadManifest = Button(this).apply {
            text = "Preload manifest"
            setOnClickListener {
                val item = when (val st = engine?.state?.value) {
                    is com.crossaudio.engine.PlayerState.Playing -> st.item
                    is com.crossaudio.engine.PlayerState.Paused -> st.item
                    else -> selectedUris.firstOrNull()?.let { MediaItem(it, sourceType = com.crossaudio.engine.SourceType.AUTO) }
                } ?: return@setOnClickListener
                (engine as? AdaptiveStreamingEngine)?.preloadManifest(item)
            }
        }

        val drmLicenseInput = EditText(this).apply {
            hint = "DRM license URL"
            setSingleLine()
            setText(prefs.getString("drmLicenseUrl", "") ?: "")
        }

        val drmInitDataInput = EditText(this).apply {
            hint = "DRM init-data base64 (optional)"
            setSingleLine()
            setText(prefs.getString("drmInitDataB64", "") ?: "")
        }

        val cacheGroupInput = EditText(this).apply {
            hint = "Cache group key (optional)"
            setSingleLine()
            setText(prefs.getString("cacheGroupKey", "") ?: "")
        }

        val acquireOfflineLicense = Button(this).apply {
            text = "Acquire offline license"
            setOnClickListener {
                val licenseUrl = drmLicenseInput.text?.toString()?.trim().orEmpty()
                if (licenseUrl.isBlank()) return@setOnClickListener
                val initDataB64 = drmInitDataInput.text?.toString()?.trim().orEmpty()
                val groupKey = cacheGroupInput.text?.toString()?.trim().orEmpty()
                prefs.edit()
                    .putString("drmLicenseUrl", licenseUrl)
                    .putString("drmInitDataB64", initDataB64)
                    .putString("cacheGroupKey", groupKey)
                    .apply()

                val uri = when (val st = engine?.state?.value) {
                    is com.crossaudio.engine.PlayerState.Playing -> st.item.uri
                    is com.crossaudio.engine.PlayerState.Paused -> st.item.uri
                    else -> selectedUris.firstOrNull()
                } ?: return@setOnClickListener

                val item = MediaItem(
                    uri = uri,
                    cacheGroupKey = groupKey.takeIf { it.isNotBlank() },
                    drm = DrmRequest(
                        scheme = DrmScheme.WIDEVINE,
                        licenseUrl = licenseUrl,
                        initDataBase64 = initDataB64.takeIf { it.isNotBlank() },
                    ),
                )
                val result = (engine as? DrmEngine)?.acquireOfflineLicense(item)
                if (result is com.crossaudio.engine.OfflineLicenseResult.Success) {
                    lastLicenseId = result.licenseId
                }
            }
        }

        val releaseOfflineLicense = Button(this).apply {
            text = "Release offline license"
            setOnClickListener {
                lastLicenseId?.let { (engine as? DrmEngine)?.releaseOfflineLicense(it) }
            }
        }

        val pinGroup = Button(this).apply {
            text = "Pin cache group"
            setOnClickListener {
                val group = cacheGroupInput.text?.toString()?.trim().orEmpty()
                if (group.isBlank()) return@setOnClickListener
                (engine as? CacheEngine)?.pinCacheGroup(group)
            }
        }

        val unpinGroup = Button(this).apply {
            text = "Unpin cache group"
            setOnClickListener {
                val group = cacheGroupInput.text?.toString()?.trim().orEmpty()
                if (group.isBlank()) return@setOnClickListener
                (engine as? CacheEngine)?.unpinCacheGroup(group)
            }
        }

        val evictGroup = Button(this).apply {
            text = "Evict cache group"
            setOnClickListener {
                val group = cacheGroupInput.text?.toString()?.trim().orEmpty()
                if (group.isBlank()) return@setOnClickListener
                (engine as? CacheEngine)?.evictCacheGroup(group)
            }
        }

        val clearQueue = Button(this).apply {
            text = "Clear saved queue"
            setOnClickListener {
                selectedUris = emptyList()
                prefs.edit().remove("queueUris").apply()
                // Do not stop playback automatically; user might still be listening.
            }
        }

        val headroomLabel = TextView(this).apply {
            text = "Crossfade headroom: ${(crossfadeHeadroom * 100).toInt()}%"
        }

        val headroomSeek = SeekBar(this).apply {
            max = 100
            progress = (crossfadeHeadroom * 100f).toInt().coerceIn(50, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val p = progress.coerceIn(50, 100)
                    crossfadeHeadroom = (p / 100f).coerceIn(0.5f, 1.0f)
                    headroomLabel.text = "Crossfade headroom: ${p}%"
                    prefs.edit().putFloat("crossfadeHeadroom", crossfadeHeadroom).apply()
                    (engine as? DebugControls)?.setCrossfadeHeadroom(crossfadeHeadroom)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        val debugPanButton = Button(this).apply {
            fun updateText() {
                text = if (debugPan) "Crossfade debug pan: ON" else "Crossfade debug pan: OFF"
            }
            updateText()
            setOnClickListener {
                debugPan = !debugPan
                prefs.edit().putBoolean("debugPan", debugPan).apply()
                (engine as? DebugControls)?.setCrossfadeDebugPanning(debugPan)
                updateText()
            }
        }

        root.addView(status)
        root.addView(pick)
        root.addView(add)
        root.addView(playPause)
        root.addView(prev)
        root.addView(next)
        root.addView(seekBack)
        root.addView(seekFwd)
        root.addView(stop)
        root.addView(crossfadeLabel)
        root.addView(crossfadeSeek)
        root.addView(xfade12)
        root.addView(volumeLabel)
        root.addView(volumeSeek)
        root.addView(repeatButton)
        root.addView(shuffleQueue)
        root.addView(removeFirst)
        root.addView(moveFirstToLast)
        root.addView(pinCurrent)
        root.addView(unpinCurrent)
        root.addView(clearCache)
        root.addView(segmentPipelineButton)
        root.addView(qualityCapButton)
        root.addView(preloadManifest)
        root.addView(drmLicenseInput)
        root.addView(drmInitDataInput)
        root.addView(cacheGroupInput)
        root.addView(acquireOfflineLicense)
        root.addView(releaseOfflineLicense)
        root.addView(pinGroup)
        root.addView(unpinGroup)
        root.addView(evictGroup)
        root.addView(clearQueue)
        root.addView(headroomLabel)
        root.addView(headroomSeek)
        root.addView(debugPanButton)
        setContentView(root)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (engine == null) {
                    status.text = "Service not connected"
                    kotlinx.coroutines.delay(100)
                }
                var lastEventLine = "none"
                var lastTelemetryLine = "none"
                launch {
                    (engine as? ObservableEngine)?.events?.collect { ev ->
                        lastEventLine = when (ev) {
                            is PlayerEvent.CacheProgress -> "cache ${ev.downloadedBytes}/${ev.totalBytes}"
                            is PlayerEvent.CacheStateChanged -> "cache state ${ev.state}"
                            is PlayerEvent.TrackTransition -> "transition ${ev.reason} ${ev.fromIndex}->${ev.toIndex}"
                            else -> ev::class.java.simpleName
                        }
                    }
                }
                launch {
                    (engine as? TelemetryEngine)?.telemetry?.collect { ev ->
                        lastTelemetryLine = when (ev) {
                            is EngineTelemetryEvent.DrmSessionOpened -> "drm open ${ev.scheme}"
                            is EngineTelemetryEvent.DrmSessionClosed -> "drm close ${ev.scheme}"
                            is EngineTelemetryEvent.DrmSessionFailed -> "drm fail ${ev.message}"
                            is EngineTelemetryEvent.LicenseAcquired -> "license ${ev.licenseId}"
                            is EngineTelemetryEvent.CacheHit -> "cache hit ${ev.key}"
                            is EngineTelemetryEvent.CacheMiss -> "cache miss ${ev.key}"
                            else -> ev::class.java.simpleName
                        }
                    }
                }
                engine!!.state.collect { st ->
                    lastIsPlaying = st is com.crossaudio.engine.PlayerState.Playing
                    lastPosMs = when (st) {
                        is com.crossaudio.engine.PlayerState.Playing -> st.positionMs
                        is com.crossaudio.engine.PlayerState.Paused -> st.positionMs
                        else -> lastPosMs
                    }
                    val currentItem = when (st) {
                        is com.crossaudio.engine.PlayerState.Playing -> st.item
                        is com.crossaudio.engine.PlayerState.Paused -> st.item
                        else -> null
                    }
                    val cacheState = currentItem?.let { (engine as? CacheEngine)?.cacheInfo(it)?.state } ?: CacheState.MISS
                    status.text = buildString {
                        append("Queue items: ${selectedUris.size}\n")
                        if (selectedUris.isNotEmpty()) append("First: ${selectedUris.first()}\n")
                        append("Crossfade: ${crossfadeMs / 1000L}s  Headroom: ${(crossfadeHeadroom * 100).toInt()}%  Volume: ${(volume * 100).toInt()}%  Repeat: ${repeatMode.name}  Shuffle: ${if (shuffleEnabled) "ON" else "OFF"}  XfadePan: ${if (debugPan) "ON" else "OFF"}  SegmentPipeline: ${if (segmentPipelineEnabled) "ON" else "OFF"}  QualityCap: ${qualityCap.name}\n")
                        append("Cache: $cacheState  Event: $lastEventLine\n")
                        append("Telemetry: $lastTelemetryLine  LastLicenseId: ${lastLicenseId ?: "-"}\n")
                        append(st.toString())
                    }
                }
            }
        }
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

    override fun onDestroy() {
        super.onDestroy()
    }
}
