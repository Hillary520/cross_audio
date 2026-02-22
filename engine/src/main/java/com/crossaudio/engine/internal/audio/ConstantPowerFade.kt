package com.crossaudio.engine.internal.audio

import kotlin.math.cos
import kotlin.math.sin

internal object ConstantPowerFade {
    fun gainA(t01: Float): Float {
        val t = t01.coerceIn(0f, 1f)
        return cos(t * (Math.PI.toFloat() / 2f))
    }

    fun gainB(t01: Float): Float {
        val t = t01.coerceIn(0f, 1f)
        return sin(t * (Math.PI.toFloat() / 2f))
    }
}

