package com.crossaudio.engine.internal.events

import com.crossaudio.engine.PlayerEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class EventBus {
    private val flow = MutableSharedFlow<PlayerEvent>(
        replay = 0,
        extraBufferCapacity = 128,
    )

    val events: SharedFlow<PlayerEvent> = flow.asSharedFlow()

    fun emit(event: PlayerEvent) {
        flow.tryEmit(event)
    }
}
