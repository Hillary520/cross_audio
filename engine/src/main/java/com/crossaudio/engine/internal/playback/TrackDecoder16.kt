package com.crossaudio.engine.internal.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.crossaudio.engine.DrmRequest
import com.crossaudio.engine.internal.audio.PcmFormat
import com.crossaudio.engine.internal.audio.PcmPipe16
import com.crossaudio.engine.internal.audio.LinearResampler16Stereo
import com.crossaudio.engine.internal.drm.ActiveDrmSession
import com.crossaudio.engine.internal.drm.DrmSessionManager
import com.crossaudio.engine.internal.network.HttpMediaSource
import com.crossaudio.engine.internal.network.ResolvedMediaSource
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

internal class TrackDecoder16(
    private val context: Context,
    private val stop: AtomicBoolean,
    private val source: ResolvedMediaSource,
    private val uriForMetadata: Uri?,
    private val pipe: PcmPipe16,
    private val startPositionMs: Long,
    private val outputSampleRate: Int,
    private val drmSessionManager: DrmSessionManager? = null,
    private val drmRequest: DrmRequest? = null,
    private val drmMediaKey: String? = null,
    private val manifestInitDataBase64: String? = null,
    private val onSourceInfo: (mimeType: String, bitrateKbps: Int?) -> Unit = { _, _ -> },
    private val onFormat: (PcmFormat, durationUs: Long) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val tag = "TrackDecoder16"
    @Volatile private var thread: Thread? = null
    fun start() { thread = Thread({ runLoop() }, "cross-audio-decode").apply { start() } }
    fun join(timeoutMs: Long) { thread?.join(timeoutMs) }

    private fun runLoop() {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var activeDrmSession: ActiveDrmSession? = null

        try {
            extractor = MediaExtractor()
            HttpMediaSource.applyToExtractor(extractor, source, context.contentResolver)

            val (trackIndex, trackFormat) = selectAudioTrackFromExtractor(extractor)
                ?: throw IllegalArgumentException("No audio track found for source=$source")
            extractor.selectTrack(trackIndex)

            if (startPositionMs > 0) {
                extractor.seekTo(startPositionMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Missing MIME type for source=$source")
            val bitrateKbps = runCatching {
                if (!trackFormat.containsKey(MediaFormat.KEY_BIT_RATE)) null
                else trackFormat.getInteger(MediaFormat.KEY_BIT_RATE).takeIf { it > 0 }?.div(1_000)
            }.getOrNull()
            onSourceInfo(mime, bitrateKbps)

            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                // Fallback: metadata retriever is often more reliable for local files.
                runCatching {
                    val mmr = MediaMetadataRetriever()
                    try {
                        val u = uriForMetadata ?: return@runCatching 0L
                        mmr.setDataSource(context, u)
                        val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        ms * 1000L
                    } finally {
                        mmr.release()
                    }
                }.getOrDefault(0L)
            }

            codec = MediaCodec.createDecoderByType(mime)
            if (drmSessionManager != null && drmRequest != null) {
                activeDrmSession = drmSessionManager.openPlaybackSession(
                    mediaKey = drmMediaKey ?: source.toString(),
                    request = drmRequest,
                    manifestInitDataBase64 = manifestInitDataBase64,
                )
            }
            codec.configure(trackFormat, null, activeDrmSession?.mediaCrypto, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            var fmt: PcmFormat? = null
            var srcSampleRate: Int = 0
            var srcChannelCount: Int = 0
            var resampler: LinearResampler16Stereo? = null

            // Some devices expose the output format immediately; grab it if available to unblock the renderer sooner.
            runCatching {
                val outFormat = codec.outputFormat
                if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE) && outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    srcSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    srcChannelCount = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val pf = PcmFormat(sampleRate = outputSampleRate, channelCount = 2, encoding = AudioFormat.ENCODING_PCM_16BIT)
                    fmt = pf
                    if (srcSampleRate != 0 && srcSampleRate != outputSampleRate) {
                        resampler = LinearResampler16Stereo(srcSampleRate, outputSampleRate, stop)
                    }
                    onFormat(pf, durationUs)
                }
            }

            // Scratch used for writing to the pipe.
            val scratch = ShortArray(4096 * 2)
            val monoScratch = ShortArray(4096)
            val resampleOut = ShortArray(4096 * 2)

            while (!stop.get() && !sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)
                            ?: throw IllegalStateException("codec.getInputBuffer returned null")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            val ptsUs = extractor.sampleTime
                            val isEncrypted =
                                (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0 &&
                                    activeDrmSession != null
                            if (isEncrypted && Build.VERSION.SDK_INT >= 24) {
                                val cryptoInfo = MediaCodec.CryptoInfo()
                                val hasCryptoInfo = extractor.getSampleCryptoInfo(cryptoInfo)
                                if (hasCryptoInfo) {
                                    codec.queueSecureInputBuffer(inIndex, 0, cryptoInfo, ptsUs, 0)
                                } else {
                                    codec.queueInputBuffer(inIndex, 0, sampleSize, ptsUs, 0)
                                }
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, ptsUs, 0)
                            }
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        srcSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        srcChannelCount = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val enc = if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        if (enc != AudioFormat.ENCODING_PCM_16BIT) {
                            throw IllegalStateException("Only PCM_16BIT supported. pcmEncoding=$enc")
                        }
                        if (srcChannelCount != 1 && srcChannelCount != 2) {
                            throw IllegalStateException("Only mono/stereo supported. channels=$srcChannelCount")
                        }
                        val pf = PcmFormat(sampleRate = outputSampleRate, channelCount = 2, encoding = enc)
                        fmt = pf
                        if (srcSampleRate != outputSampleRate) {
                            resampler = LinearResampler16Stereo(srcSampleRate, outputSampleRate, stop)
                        } else {
                            resampler = null
                        }
                        onFormat(pf, durationUs)
                    }
                    else -> {
                        if (outIndex >= 0) {
                            val outBuf = codec.getOutputBuffer(outIndex)
                                ?: throw IllegalStateException("codec.getOutputBuffer returned null")

                            if (fmt == null) {
                                val outFormat = codec.outputFormat
                                srcSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                srcChannelCount = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                val pf = PcmFormat(sampleRate = outputSampleRate, channelCount = 2, encoding = AudioFormat.ENCODING_PCM_16BIT)
                                fmt = pf
                                if (srcSampleRate != outputSampleRate) {
                                    resampler = LinearResampler16Stereo(srcSampleRate, outputSampleRate, stop)
                                }
                                onFormat(pf, durationUs)
                            }

                            if (bufferInfo.size > 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                // fmt must be known before we can write.
                                fmt ?: throw IllegalStateException("PCM format not available")
                                // Decode -> stereo scratch at *source* sample rate, then resample if needed.
                                val rs = resampler
                                while (outBuf.hasRemaining() && !stop.get()) {
                                    val writtenSamplesStereo = PcmDecodeBuffer.readBlockToStereoScratch(
                                        outBuf,
                                        srcChannelCount,
                                        stereoScratch = scratch,
                                        monoScratch = monoScratch,
                                    )
                                    if (writtenSamplesStereo <= 0) break

                                    if (rs == null || srcSampleRate == outputSampleRate) {
                                        pipe.writeBlocking(scratch, 0, writtenSamplesStereo)
                                    } else {
                                        rs.push(scratch, writtenSamplesStereo)
                                        while (!stop.get()) {
                                            val frames = rs.drain(resampleOut)
                                            if (frames <= 0) break
                                            pipe.writeBlocking(resampleOut, 0, frames * 2)
                                        }
                                    }
                                }
                            }

                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                sawOutputEos = true
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                        }
                    }
                }
            }

            pipe.markEos()
        } catch (t: Throwable) {
            if (t is InterruptedException) return
            Log.e(tag, "Decode error", t); runCatching { pipe.markEos() }; onError(t)
        } finally {
            cleanupTrackDecoder(codec, extractor, activeDrmSession, drmSessionManager)
        }
    }
}
