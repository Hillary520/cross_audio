package com.crossaudio.engine.internal.audiofx

internal class LookaheadLimiter {
    private var gain = 1.0f

    fun apply(
        pcm: ShortArray,
        sampleCount: Int,
        ceilingDbfs: Float,
        attackMs: Int,
        releaseMs: Int,
        sampleRate: Int,
    ) {
        if (sampleCount <= 0 || sampleRate <= 0) return
        val ceiling = dbToLinear(ceilingDbfs).coerceIn(0.01f, 1.0f)
        val attack = msToCoeff(attackMs, sampleRate)
        val release = msToCoeff(releaseMs, sampleRate)
        val n = sampleCount.coerceAtMost(pcm.size)

        for (i in 0 until n) {
            val x = pcm[i] / 32768f
            val abs = kotlin.math.abs(x)
            val desiredGain = if (abs <= ceiling) 1.0f else (ceiling / abs)
            gain = if (desiredGain < gain) {
                attack * desiredGain + (1f - attack) * gain
            } else {
                release * desiredGain + (1f - release) * gain
            }
            val y = (x * gain * 32767f).toInt()
            pcm[i] = y.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun dbToLinear(db: Float): Float = Math.pow(10.0, (db / 20f).toDouble()).toFloat()

    private fun msToCoeff(ms: Int, sampleRate: Int): Float {
        val clampedMs = ms.coerceAtLeast(1)
        val samples = (sampleRate * clampedMs / 1000f).coerceAtLeast(1f)
        return (1f / samples)
    }
}
