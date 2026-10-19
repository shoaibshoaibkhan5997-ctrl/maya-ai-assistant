package com.maya.ai.overlay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Carries what the floating bubble heard and answered into the existing chat screen.
 * Kept in memory only (last 40 items); nothing is written to disk.
 */
object BubbleChatBus {
    enum class Kind { USER, MAYA, STATUS }
    data class Event(val kind: Kind, val text: String, val ok: Boolean = true)

    private val _events = MutableSharedFlow<Event>(
        replay = 40,
        extraBufferCapacity = 40,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Event> = _events

    fun post(kind: Kind, text: String, ok: Boolean = true) {
        _events.tryEmit(Event(kind, text, ok))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun clear() {
        _events.resetReplayCache()
    }
}
