package com.open360.core.watch

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * No-op FOSS build of [WatchCompanionInstaller]: Wear OS Data Layer and remote Play Store launch
 * are omitted when building without Google Play services.
 */
@Suppress("UNUSED_PARAMETER")
class WatchCompanionInstaller(context: Context) {
    private val _state = MutableStateFlow(WatchCompanionState(isSupported = false))
    val state: StateFlow<WatchCompanionState> = _state.asStateFlow()

    fun startListening() = Unit
    fun stopListening() = Unit
    fun refresh() = Unit
    fun openPlayStoreOnWatches() = Unit
}
