package com.crossaudio.engine.service

import android.content.Intent
import android.media.audiofx.AudioEffect
import com.crossaudio.engine.PlayerState

internal fun CrossAudioPlaybackService.updateAudioEffectSessionImpl(st: PlayerState) {
    val shouldBeOpen = st is PlayerState.Playing || st is PlayerState.Paused
    val sid = core.audioSessionId()

    if (!shouldBeOpen) {
        if (effectSessionOpened) closeAudioEffectSessionImpl()
        return
    }
    if (sid <= 0) return

    if (!effectSessionOpened || effectSessionId != sid) {
        if (effectSessionOpened) closeAudioEffectSessionImpl()
        openAudioEffectSessionImpl(sid)
    }
}

internal fun CrossAudioPlaybackService.openAudioEffectSessionImpl(sessionId: Int) {
    android.util.Log.d("CrossAudioService", "AudioEffect OPEN sessionId=$sessionId")
    val i = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    }
    sendBroadcast(i)
    effectSessionOpened = true
    effectSessionId = sessionId
}

internal fun CrossAudioPlaybackService.closeAudioEffectSessionImpl() {
    val sessionId = effectSessionId
    if (sessionId <= 0) return
    android.util.Log.d("CrossAudioService", "AudioEffect CLOSE sessionId=$sessionId")
    val i = Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
    }
    sendBroadcast(i)
    effectSessionOpened = false
    effectSessionId = 0
}
