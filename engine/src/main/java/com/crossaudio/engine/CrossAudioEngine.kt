package com.crossaudio.engine

import android.content.Context
import android.media.AudioManager
import com.crossaudio.engine.internal.audio.PcmFormat
import com.crossaudio.engine.internal.audio.PcmPipe16
import com.crossaudio.engine.internal.cache.CacheManager
import com.crossaudio.engine.internal.cache.PinManager
import com.crossaudio.engine.internal.events.EventBus
import com.crossaudio.engine.internal.events.EventEmitter
import com.crossaudio.engine.internal.network.MediaSourceResolver
import com.crossaudio.engine.internal.playback.CrossfadeSource
import com.crossaudio.engine.internal.playback.ConcatSource
import com.crossaudio.engine.internal.playback.DecoderSession
import com.crossaudio.engine.internal.playback.FadeOutSource
import com.crossaudio.engine.internal.playback.PlaybackCoordinator
import com.crossaudio.engine.internal.playback.PipeSource
import com.crossaudio.engine.internal.playback.TransitionController
import com.crossaudio.engine.internal.telemetry.TelemetryAccumulator
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import com.crossaudio.engine.internal.queue.QueueState
import com.crossaudio.engine.internal.drm.DrmSessionManager
import com.crossaudio.engine.internal.streaming.ManifestResolver
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CrossAudioEngine(
    context: Context,
) : PlayerEngine, DebugControls, QueueMutableEngine, ShuffleEngine, ObservableEngine, CacheEngine,
    AdaptiveStreamingEngine, DrmEngine, AudioProcessingEngine, TelemetryEngine {
    internal val appContext = context.applicationContext
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val tag = "CrossAudioEngine"

    // Lazily captured from the first AudioTrack that actually plays audio.
    // Some 3rd-party equalizers attach more reliably when we broadcast the *actual* session id.
    internal val outputAudioSessionId = AtomicInteger(0)

    internal val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    internal val _audioSessionId = MutableStateFlow(0)
    val audioSessionIdState: StateFlow<Int> = _audioSessionId.asStateFlow()

    internal val eventBus = EventBus()
    internal val emitter = EventEmitter(eventBus)
    override val events: SharedFlow<PlayerEvent> = eventBus.events
    private val telemetryFlow = MutableSharedFlow<EngineTelemetryEvent>(extraBufferCapacity = 128)
    override val telemetry: SharedFlow<EngineTelemetryEvent> = telemetryFlow.asSharedFlow()

    internal val queueState = QueueState()
    internal var queue: List<MediaItem> = emptyList()
    internal var index: Int = -1

    internal val crossfadeDurationMs = AtomicLong(0L)
    @Volatile internal var volume: Float = 1.0f
    @Volatile internal var repeatMode: RepeatMode = RepeatMode.OFF
    @Volatile internal var shuffleEnabled: Boolean = false
    @Volatile internal var debugCrossfadePan: Boolean = false
    @Volatile internal var crossfadeHeadroom: Float = 0.9f

    internal val renderStop = AtomicBoolean(false)
    internal var controlJob: Job? = null
    internal val generation = AtomicLong(0L)
    @Volatile internal var activeGeneration: Long = 0L
    @Volatile internal var inhibitTransitions: Boolean = false

    internal val playback = PlaybackCoordinator(renderStop)
    internal var currentTrackStartFrames: Long = 0L
    @Volatile internal var currentBasePositionMs: Long = 0L
    internal val currentPipeCapacitySamples = 48000 * 2 * 12 // ~12s stereo at 48k for jitter tolerance on lossless streams
    internal val nextPipeCapacitySamples = 48000 * 2 * 10 // ~10s stereo at 48k for next-track prefetch/crossfade

    internal enum class RenderMode { PIPE, CONCAT, CROSSFADE, FADEOUT }
    @Volatile internal var renderMode: RenderMode = RenderMode.PIPE

    @Volatile internal var currentPipe: PcmPipe16? = null
    @Volatile internal var currentDecoder: DecoderSession? = null
    @Volatile internal var currentFormat: PcmFormat? = null
    @Volatile internal var currentDurationUs: Long = 0L

    @Volatile internal var nextPipe: PcmPipe16? = null
    @Volatile internal var nextDecoder: DecoderSession? = null
    @Volatile internal var nextFormat: PcmFormat? = null
    @Volatile internal var nextDurationUs: Long = 0L
    @Volatile internal var nextFailed: Boolean = false
    @Volatile internal var nextQueueIndex: Int = -1

    internal var crossfadeSource: CrossfadeSource? = null
    internal var concatSource: ConcatSource? = null
    internal var fadeOutSource: FadeOutSource? = null

    internal val cacheManager = CacheManager(appContext, emitter)
    internal val pinManager = PinManager(cacheManager)
    internal val manifestResolver = ManifestResolver()
    internal val sourceResolver = MediaSourceResolver(
        cacheManager = cacheManager,
        manifestResolver = manifestResolver,
        qualityCapProvider = { qualityCap },
    )
    internal val drmManager = DrmSessionManager(appContext) { emitTelemetry(it) }

    @Volatile internal var streamingConfig: StreamingConfig = StreamingConfig()
    @Volatile internal var qualityCap: QualityCap = QualityCap.AUTO
    @Volatile internal var qualityInfo: QualityInfo = QualityInfo(sourceType = SourceType.PROGRESSIVE)
    @Volatile internal var currentMimeType: String? = null
    @Volatile private var drmConfig: DrmGlobalConfig = DrmGlobalConfig()
    @Volatile private var audioProcessingConfig: AudioProcessingConfig = AudioProcessingConfig()
    private val telemetryAccumulator = TelemetryAccumulator()

    internal fun syncQueueFromState() {
        val s = queueState.snapshot()
        queue = s.items; index = s.currentIndex; repeatMode = s.repeatMode; shuffleEnabled = s.shuffleEnabled
    }
    internal fun emitQueueChanged() { emitter.queueChanged(queue.size, index, shuffleEnabled) }
    override fun setQueue(items: List<MediaItem>, startIndex: Int) { setQueueImpl(items, startIndex) }
    override fun addToQueue(items: List<MediaItem>, atIndex: Int?) { addToQueueImpl(items, atIndex) }
    override fun removeFromQueue(indices: IntArray) { removeFromQueueImpl(indices) }
    override fun moveQueueItem(fromIndex: Int, toIndex: Int) { moveQueueItemImpl(fromIndex, toIndex) }
    override fun clearQueue() { clearQueueImpl() }
    override fun setShuffleEnabled(enabled: Boolean) { setShuffleEnabledImpl(enabled) }
    override fun isShuffleEnabled(): Boolean = isShuffleEnabledImpl()
    override fun play() { playImpl() }
    override fun pause() { pauseImpl() }
    override fun stop() { stopInternal(updateState = true) }
    override fun seekTo(positionMs: Long) { seekToImpl(positionMs) }
    override fun skipNext() { skipNextImpl() }
    override fun skipPrevious() { skipPreviousImpl() }
    override fun skipToIndex(index: Int) { skipToIndexImpl(index) }
    override fun setCrossfadeDurationMs(durationMs: Long) { crossfadeDurationMs.set(durationMs.coerceAtLeast(0L)) }
    override fun setRepeatMode(mode: RepeatMode) { queueState.setRepeatMode(mode); syncQueueFromState(); repeatMode = mode }
    override fun setCrossfadeDebugPanning(enabled: Boolean) { debugCrossfadePan = enabled; Log.d(tag, "Debug crossfade pan set: $enabled") }
    override fun setCrossfadeHeadroom(headroom: Float) { crossfadeHeadroom = headroom.coerceIn(0.5f, 1.0f); Log.d(tag, "Crossfade headroom set: $crossfadeHeadroom") }
    override fun setVolume(volume: Float) { val v = volume.coerceIn(0f, 1f); this.volume = v; playback.setVolume(v) }
    override fun setCacheConfig(config: CacheConfig) { cacheManager.setConfig(config) }
    override fun pinForOffline(item: MediaItem) { pinManager.pin(item) }
    override fun unpinOffline(item: MediaItem) { pinManager.unpin(item) }
    override fun pinCacheGroup(cacheGroupKey: String) { cacheManager.pinGroup(cacheGroupKey) }
    override fun unpinCacheGroup(cacheGroupKey: String) { cacheManager.unpinGroup(cacheGroupKey) }
    override fun evictCacheGroup(cacheGroupKey: String) { cacheManager.evictGroup(cacheGroupKey) }
    override fun clearUnpinnedCache() { cacheManager.clearUnpinnedCache() }
    override fun cacheInfo(item: MediaItem): CacheInfo = cacheManager.cacheInfo(item)
    override fun setStreamingConfig(config: StreamingConfig) { streamingConfig = config; qualityCap = config.qualityCap }
    override fun setQualityCap(cap: QualityCap) { qualityCap = cap; qualityInfo = qualityInfo.copy(sourceType = qualityInfo.sourceType) }
    override fun currentQuality(): QualityInfo = qualityInfo
    fun currentStreamInfo(): StreamInfo {
        val q = qualityInfo
        return StreamInfo(
            sourceType = q.sourceType,
            fileType = inferFileType(currentMimeType, q.representationId),
            mimeType = currentMimeType,
            bitrateKbps = q.bitrateKbps,
            streamId = q.representationId,
        )
    }

    override fun preloadManifest(item: MediaItem) {
        if (item.sourceType == SourceType.PROGRESSIVE) return
        scope.launch {
            runCatching {
                manifestResolver.resolve(item, qualityCap)
            }.onSuccess { resolved ->
                qualityInfo = qualityInfo.copy(
                    sourceType = resolved.sourceType,
                    representationId = resolved.selectedStreamUri,
                )
            }.onFailure {
                emitter.playbackError("Manifest preload failed: ${it.message}", it)
            }
        }
    }

    override fun setDrmConfig(config: DrmGlobalConfig) { drmConfig = config; drmManager.setGlobalConfig(config) }

    override fun acquireOfflineLicense(item: MediaItem): OfflineLicenseResult {
        val drm = item.drm ?: return OfflineLicenseResult.Failure("MediaItem has no drm request")
        val manifestInitDataBase64 = runCatching {
            if (manifestResolver.detectSourceType(item) == SourceType.PROGRESSIVE) null
            else manifestResolver.resolve(item, qualityCap).initDataBase64
        }.getOrNull()
        val result = drmManager.acquireOfflineLicense(
            mediaKey = item.cacheGroupKey?.takeIf { it.isNotBlank() } ?: item.uri.toString(),
            request = drm,
            manifestInitDataBase64 = manifestInitDataBase64,
        )
        if (result is OfflineLicenseResult.Success) {
            emitTelemetry(EngineTelemetryEvent.LicenseAcquired(result.licenseId))
        }
        return result
    }

    override fun releaseOfflineLicense(licenseId: String) { drmManager.releaseOfflineLicense(licenseId) }
    override fun setAudioProcessing(config: AudioProcessingConfig) { audioProcessingConfig = config; playback.setAudioProcessing(config) }

    override fun telemetrySnapshot(): EngineTelemetrySnapshot {
        return EngineTelemetrySnapshot(
            currentBitrateKbps = qualityInfo.bitrateKbps,
            estimatedBandwidthKbps = telemetryAccumulator.estimatedBandwidthKbps(),
            rebufferCount = telemetryAccumulator.rebufferCount(),
            decoderDrops = telemetryAccumulator.decoderDrops(),
            drmSessionCount = telemetryAccumulator.drmSessionCount(),
            cacheHitRatio = telemetryAccumulator.cacheHitRatio(),
        )
    }

    override fun release() { stop(); drmManager.release(); cacheManager.release(); scope.coroutineContext.cancel() }

    /**
     * Audio session used for our single output [android.media.AudioTrack].
     * External equalizers attach to this id.
     */
    fun audioSessionId(): Int = outputAudioSessionId.get()

    private fun stopInternal(updateState: Boolean) = stopInternalImpl(updateState)
    private fun startCurrentTrack(item: MediaItem, startPositionMs: Long, onStarted: (Boolean) -> Unit) = startCurrentTrackImpl(item, startPositionMs, onStarted)
    private fun startNextTrackIfNeeded() = startNextTrackIfNeededImpl()
    private fun maybeStartControlLoop() = maybeStartControlLoopImpl()
    private fun onRendererEnded() = onRendererEndedImpl()
    internal fun rendererUnderrunCount(): Long = rendererUnderrunCountImpl()
    internal fun emitTelemetry(event: EngineTelemetryEvent) {
        telemetryAccumulator.onEvent(event)
        telemetryFlow.tryEmit(event)
    }
    private fun setRendererSource(mode: RenderMode, src: com.crossaudio.engine.internal.playback.RenderSource) = setRendererSourceImpl(mode, src)
    private fun cancelTransitionsSync(cancelPreload: Boolean) = cancelTransitionsSyncImpl(cancelPreload)
    private fun cancelPreloadSync() = cancelPreloadSyncImpl()
    internal fun debugSnapshot(): String = debugSnapshotImpl()

    private fun inferFileType(mimeType: String?, streamId: String?): String? {
        val mime = mimeType?.trim()?.lowercase().orEmpty()
        if (mime.isNotBlank()) {
            return when {
                mime.contains("flac") -> "FLAC"
                mime.contains("mpeg") || mime.contains("mp3") -> "MP3"
                mime.contains("aac") -> "AAC"
                mime.contains("mp4a") || mime.contains("m4a") -> "M4A"
                mime.contains("opus") -> "OPUS"
                mime.contains("ogg") -> "OGG"
                mime.contains("wav") -> "WAV"
                mime.startsWith("audio/") -> mime.substringAfter("audio/").uppercase()
                else -> null
            }
        }
        val lowerPath = streamId?.substringBefore('?')?.lowercase().orEmpty()
        return when {
            lowerPath.endsWith(".flac") -> "FLAC"
            lowerPath.endsWith(".mp3") -> "MP3"
            lowerPath.endsWith(".m4a") || lowerPath.endsWith(".mp4") -> "M4A"
            lowerPath.endsWith(".aac") -> "AAC"
            lowerPath.endsWith(".opus") -> "OPUS"
            lowerPath.endsWith(".ogg") -> "OGG"
            lowerPath.endsWith(".wav") -> "WAV"
            lowerPath.endsWith(".m3u8") -> "HLS"
            lowerPath.endsWith(".mpd") -> "DASH"
            else -> null
        }
    }
}
