package com.crossaudio.engine.internal.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.crossaudio.engine.AudioProcessingConfig
import com.crossaudio.engine.internal.audio.PcmFormat
import com.crossaudio.engine.internal.audiofx.LookaheadLimiter
import com.crossaudio.engine.internal.audiofx.LoudnessAnalyzer
import com.crossaudio.engine.internal.audiofx.LoudnessNormalizer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class AudioTrackRenderer16(
    private val stop: AtomicBoolean,
    initialSource: RenderSource,
    private val onEnded: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val sessionId: Int,
    private val onSessionId: (Int) -> Unit,
) {
    private val tag = "AudioTrackRenderer16"

    @Volatile
    private var source: RenderSource = initialSource

    private var audioTrack: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running: Boolean = false
    @Volatile private var paused: Boolean = false
    private val pauseLock = Object()
    @Volatile private var volume: Float = 1.0f
    @Volatile private var audioProcessing = AudioProcessingConfig()

    private val playedFrames = AtomicLong(0L)
    private val underruns = AtomicLong(0L)
    @Volatile private var lastUnderrunRaw: Int = 0
    private val analyzer = LoudnessAnalyzer()
    private val normalizer = LoudnessNormalizer()
    private val limiter = LookaheadLimiter()

    fun isRunning(): Boolean = running
    fun isPaused(): Boolean = paused
    fun setVolume(v: Float) {
        val nv = v.coerceIn(0f, 1f)
        volume = nv
        try {
            audioTrack?.setVolume(nv)
        } catch (_: Throwable) {
        }
    }

    fun setSource(newSource: RenderSource) {
        source = newSource
    }

    fun setAudioProcessing(config: AudioProcessingConfig) {
        audioProcessing = config
    }

    fun playedFrames(): Long = playedFrames.get()
    fun underrunCount(): Long = underruns.get()

    fun audioSessionId(): Int = sessionId

    fun start() {
        running = true
        thread = Thread({ runLoop() }, "cross-audio-render").apply { start() }
    }

    fun pause() {
        paused = true
        try {
            audioTrack?.pause()
        } catch (_: Throwable) {
        }
    }

    fun resume() {
        paused = false
        try {
            audioTrack?.play()
        } catch (_: Throwable) {
        }
        synchronized(pauseLock) { pauseLock.notifyAll() }
    }

    fun stop() {
        stop.set(true)
        running = false
        paused = false
        synchronized(pauseLock) { pauseLock.notifyAll() }
        thread?.interrupt()
        thread?.join(750)
        thread = null
        try {
            audioTrack?.stop()
        } catch (_: Throwable) {
        }
        try {
            audioTrack?.release()
        } catch (_: Throwable) {
        }
        audioTrack = null
    }

    private fun ensureAudioTrack(format: PcmFormat) {
        val current = audioTrack
        if (current != null) return

        val channelMask = when (format.channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> throw IllegalArgumentException("Unsupported channels: ${format.channelCount}")
        }

        val minBufferSize = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, format.encoding)
        // For external EQ apps (AudioEffect), we generally want to avoid the "fast track" path, which
        // may bypass effects on some devices. A larger buffer + PERFORMANCE_MODE_NONE nudges AudioTrack
        // onto the normal mixer path.
        val bufferSize = (minBufferSize.coerceAtLeast(8192)) * 4

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val af = AudioFormat.Builder()
            .setSampleRate(format.sampleRate)
            .setEncoding(format.encoding)
            .setChannelMask(channelMask)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(af)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .also { b ->
                if (Build.VERSION.SDK_INT >= 26) {
                    b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                }
            }
            .setSessionId(sessionId)
            .build()
            .also {
                runCatching {
                    val actual = it.audioSessionId
                    onSessionId(actual)
                    if (sessionId > 0 && actual != sessionId) {
                        Log.w(tag, "Requested sessionId=$sessionId but AudioTrack created with sessionId=$actual")
                    } else {
                        Log.d(tag, "AudioTrack created with sessionId=$actual")
                    }
                }
                it.setVolume(volume)
                it.play()
                if (Build.VERSION.SDK_INT >= 24) {
                    lastUnderrunRaw = it.underrunCount
                }
            }
    }

    private fun runLoop() {
        try {
            // Best-effort: make the renderer thread "audio priority" to reduce underruns.
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            }
            val out = ShortArray(2048 * 2) // 2048 stereo frames max for our sources.
            while (!stop.get()) {
                if (paused) {
                    synchronized(pauseLock) {
                        while (paused && !stop.get()) pauseLock.wait(100)
                    }
                    continue
                }
                val s = source
                ensureAudioTrack(s.format)
                val frames = s.readFramesBlocking(out, frames = 2048)
                if (frames <= 0) {
                    onEnded()
                    return
                }
                val samples = frames * s.format.channelCount
                val at = audioTrack ?: throw IllegalStateException("AudioTrack not initialized")
                applyAudioFx(out, samples, s.format.sampleRate)
                val written = at.write(out, 0, samples, AudioTrack.WRITE_BLOCKING)
                if (written < 0) throw IllegalStateException("AudioTrack.write failed: $written")
                playedFrames.addAndGet(frames.toLong())

                if (Build.VERSION.SDK_INT >= 24) {
                    val raw = at.underrunCount
                    val delta = raw - lastUnderrunRaw
                    if (delta > 0) underruns.addAndGet(delta.toLong())
                    lastUnderrunRaw = raw
                }
            }
        } catch (t: Throwable) {
            if (t is InterruptedException) return
            Log.e(tag, "Render error", t)
            onError(t)
        } finally {
            running = false
        }
    }

    private fun applyAudioFx(buffer: ShortArray, sampleCount: Int, sampleRate: Int) {
        val cfg = audioProcessing
        if (!cfg.loudnessEnabled && !cfg.limiterEnabled) return
        if (cfg.loudnessEnabled) {
            val currentLufs = analyzer.estimateLufs(buffer, sampleCount)
            normalizer.apply(
                pcm = buffer,
                sampleCount = sampleCount,
                currentLufs = currentLufs,
                targetLufs = cfg.targetLufs,
            )
        }
        if (cfg.limiterEnabled) {
            limiter.apply(
                pcm = buffer,
                sampleCount = sampleCount,
                ceilingDbfs = cfg.limiterCeilingDbfs,
                attackMs = cfg.limiterAttackMs,
                releaseMs = cfg.limiterReleaseMs,
                sampleRate = sampleRate,
            )
        }
    }
}
