package com.crossaudio.engine.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

internal class AudioFocusController(
    context: Context,
    private val onFocusChange: (Int) -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        onFocusChange(change)
    }

    private var req: AudioFocusRequest? = null

    fun requestGain(): Boolean {
        return if (Build.VERSION.SDK_INT >= 26) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(listener)
                .build()
            req = r
            audioManager.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    fun abandon() {
        if (Build.VERSION.SDK_INT >= 26) {
            val r = req ?: return
            audioManager.abandonAudioFocusRequest(r)
            req = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(listener)
        }
    }
}
