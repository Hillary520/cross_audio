package com.crossaudio.engine

internal fun CrossAudioEngine.rendererUnderrunCountImpl(): Long = playback.underrunCount()

internal fun CrossAudioEngine.debugSnapshotImpl(): String {
    val st = _state.value
    val fmt = currentFormat
    val nf = nextFormat
    return buildString {
        append("idx=").append(index)
        append(" state=").append(st::class.java.simpleName)
        append(" mode=").append(renderMode)
        append(" curFmt=").append(fmt?.sampleRate).append("Hz/").append(fmt?.channelCount).append("ch")
        append(" nextFmt=").append(nf?.sampleRate).append("Hz/").append(nf?.channelCount).append("ch")
        append(" nextFailed=").append(nextFailed)
        append(" nextIdx=").append(nextQueueIndex)
        append(" repeat=").append(repeatMode)
        append(" shuffle=").append(shuffleEnabled)
        append(" underruns=").append(rendererUnderrunCountImpl())
    }
}
