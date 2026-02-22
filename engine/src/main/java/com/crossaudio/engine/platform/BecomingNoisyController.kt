package com.crossaudio.engine.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat

internal class BecomingNoisyController(
    private val context: Context,
    private val onNoisy: () -> Unit,
) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                onNoisy()
            }
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }
}

