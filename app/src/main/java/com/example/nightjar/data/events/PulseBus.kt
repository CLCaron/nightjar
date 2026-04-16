package com.example.nightjar.data.events

import com.example.nightjar.ui.studio.GroupKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide event bus for sibling-pulse notifications.
 *
 * Repositories emit a [GroupKey] after any content mutation that propagates
 * to linked siblings (trim, take switch, rename, split, unlink, overdub-add).
 * Non-propagating mutations (offsetMs changes, per-instance isMuted) do NOT
 * emit. The Studio UI observes this flow and flashes the link-indicator
 * strip on every clip sharing the emitted group key.
 */
@Singleton
class PulseBus @Inject constructor() {
    private val _pulses = MutableSharedFlow<GroupKey>(extraBufferCapacity = 16)
    val pulses: SharedFlow<GroupKey> = _pulses.asSharedFlow()

    suspend fun emit(key: GroupKey) {
        _pulses.emit(key)
    }

    fun tryEmit(key: GroupKey) {
        _pulses.tryEmit(key)
    }
}
