package com.crossaudio.engine.internal.playback

import android.media.AudioManager
import com.crossaudio.engine.AudioProcessingConfig
import com.crossaudio.engine.internal.audio.PcmFormat
import java.util.concurrent.atomic.AtomicBoolean

internal class PlaybackCoordinator(
    private val renderStop: AtomicBoolean,
) {
    private var renderer: AudioTrackRenderer16? = null
    @Volatile
    private var audioProcessing = AudioProcessingConfig()

    fun startRenderer(
        format: PcmFormat,
        source: RenderSource,
        volume: Float,
        requestedSessionId: Int,
        onSessionId: (Int) -> Unit,
        onEnded: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (renderer != null) return
        val sessionId = requestedSessionId.takeIf { it > 0 } ?: AudioManager.AUDIO_SESSION_ID_GENERATE
        val r = AudioTrackRenderer16(
            stop = renderStop,
            initialSource = source,
            onEnded = onEnded,
            onError = onError,
            sessionId = sessionId,
            onSessionId = onSessionId,
        )
        renderer = r
        r.start()
        r.setVolume(volume)
        r.setAudioProcessing(audioProcessing)
    }

    fun setSource(source: RenderSource) {
        renderer?.setSource(source)
    }

    fun stopRenderer() {
        renderer?.stop()
        renderer = null
    }

    fun pause() {
        renderer?.pause()
    }

    fun resume() {
        renderer?.resume()
    }

    fun setVolume(volume: Float) {
        renderer?.setVolume(volume)
    }

    fun setAudioProcessing(config: AudioProcessingConfig) {
        audioProcessing = config
        renderer?.setAudioProcessing(config)
    }

    fun isRunning(): Boolean = renderer?.isRunning() == true
    fun isPaused(): Boolean = renderer?.isPaused() == true
    fun playedFrames(): Long = renderer?.playedFrames() ?: 0L
    fun underrunCount(): Long = renderer?.underrunCount() ?: 0L

    fun clear() {
        renderer = null
    }
}
