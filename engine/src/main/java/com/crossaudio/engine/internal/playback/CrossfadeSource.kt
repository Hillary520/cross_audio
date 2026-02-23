package com.crossaudio.engine.internal.playback

import com.crossaudio.engine.internal.audio.PcmFormat
import com.crossaudio.engine.internal.audio.PcmPipe16
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal class CrossfadeSource(
    override val format: PcmFormat,
    private val a: PcmPipe16,
    private val b: PcmPipe16,
    private val totalFadeFrames: Long,
    private val debugPan: Boolean = false,
    private val headroom: Float = 0.9f,
) : RenderSource {
    // Crossfade progress in frames. We intentionally advance progress only while BOTH
    // streams are supplying audio so we don't fade A down to silence if B isn't ready.
    private var fadeProgressFrames: Long = 0L
    private var bFramesOut: Long = 0L

    private val scratchA = ShortArray(4096 * 2)
    private val scratchB = ShortArray(4096 * 2)

    fun isDone(): Boolean = fadeProgressFrames >= totalFadeFrames || a.isEosAndEmpty()
    fun bFramesMixed(): Long = bFramesOut

    override fun readFramesBlocking(out: ShortArray, frames: Int): Int {
        val ch = format.channelCount
        val samplesWanted = frames * ch
        if (samplesWanted > scratchA.size) {
            // Keep it simple: caller should use a reasonable chunk size.
            throw IllegalArgumentException("Chunk too large: samplesWanted=$samplesWanted")
        }

        // Critical behavior: do not block the render thread waiting for B while A is still playing.
        // If B isn't ready yet, treat missing samples as silence (fade-in will effectively start later).
        val gotA = a.readBlocking(scratchA, 0, samplesWanted)
        val gotB = if (gotA > 0) {
            b.readAvailable(scratchB, 0, gotA)
        } else {
            b.readBlocking(scratchB, 0, samplesWanted)
        }

        val framesA = gotA / ch
        val framesB = gotB / ch
        val framesOut = maxOf(framesA, framesB)

        if (framesOut <= 0) {
            // Only stop when both sides are truly done.
            return 0
        }

        // If A is done, we consider the crossfade complete and just output B at full volume.
        // This prevents the engine getting "stuck" in CROSSFADE mode when A ends early.
        if (framesA <= 0 && framesB > 0) {
            // Out already contains B samples in scratchB only; copy/mix into out.
            for (s in 0 until (framesOut * ch)) {
                out[s] = scratchB[s]
            }
            fadeProgressFrames = totalFadeFrames
            return framesOut
        }

        // Per-frame fade so the curve stays smooth even with larger chunks.
        for (f in 0 until framesOut) {
            val haveA = f < framesA
            val haveB = f < framesB

            val t = when {
                totalFadeFrames <= 0 -> 1f
                !haveB -> 0f // Hold off fading until B has audio.
                !haveA -> 1f
                else -> (fadeProgressFrames.toFloat() / totalFadeFrames.toFloat())
            }.coerceIn(0f, 1f)
            // Ease-in-out progress and then apply equal-power gains for a smoother overlap feel.
            val u = smoothstep(t)
            val angle = ((PI / 2.0) * u).toFloat()
            val gA = cos(angle)
            val gB = sin(angle)

            val base = f * ch
            if (debugPan && ch == 2) {
                val aL = if (haveA) scratchA[base] else 0
                val aR = if (haveA) scratchA[base + 1] else 0
                val bL = if (haveB) scratchB[base] else 0
                val bR = if (haveB) scratchB[base + 1] else 0

                // A -> left only, B -> right only.
                val outL = (aL * gA) * headroom
                val outR = (bR * gB) * headroom
                out[base] = outL.roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
                out[base + 1] = outR.roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            } else {
                for (c in 0 until ch) {
                    val sA = if (haveA) scratchA[base + c] else 0
                    val sB = if (haveB) scratchB[base + c] else 0
                    val mixed = (sA * gA + sB * gB) * headroom
                    out[base + c] = mixed.roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                }
            }

            if (haveA && haveB && totalFadeFrames > 0 && fadeProgressFrames < totalFadeFrames) {
                fadeProgressFrames += 1
            }
            if (haveB) bFramesOut += 1
        }

        return framesOut
    }

    private fun smoothstep(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }
}
