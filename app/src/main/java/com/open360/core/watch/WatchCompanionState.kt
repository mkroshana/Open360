package com.open360.core.watch

/**
 * A paired Wear OS watch visible to the phone app over the Wearable Data Layer.
 */
data class WatchNodeInfo(
    val id: String,
    val displayName: String,
    val isNearby: Boolean = true
)

/**
 * Progress of opening Open360's Google Play Store listing on paired Wear OS watches.
 */
sealed interface WatchInstallLaunchState {
    data object Idle : WatchInstallLaunchState
    data object Launching : WatchInstallLaunchState
    data class Opened(val watchNames: String) : WatchInstallLaunchState
    data class Failed(val message: String) : WatchInstallLaunchState
}

/**
 * Snapshot of connected Wear OS watches and whether Open360 is already installed on them.
 */
data class WatchCompanionState(
    val isSupported: Boolean = false,
    val isChecking: Boolean = false,
    val connectedWatches: List<WatchNodeInfo> = emptyList(),
    val installedWatchIds: Set<String> = emptySet(),
    val launchState: WatchInstallLaunchState = WatchInstallLaunchState.Idle
) {
    val watchesNeedingInstall: List<WatchNodeInfo>
        get() = connectedWatches.filter { it.id !in installedWatchIds }

    val hasConnectedWatch: Boolean
        get() = connectedWatches.isNotEmpty()

    val allConnectedInstalled: Boolean
        get() = hasConnectedWatch && watchesNeedingInstall.isEmpty()

    val targetWatchesForPlayStore: List<WatchNodeInfo>
        get() = watchesNeedingInstall.ifEmpty { connectedWatches }

    fun formattedNames(watches: List<WatchNodeInfo>): String {
        val names = watches.map { it.displayName.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return if (names.isEmpty()) "your watch" else names.joinToString(", ")
    }

    fun rowTitle(): String = when {
        !hasConnectedWatch -> "Open360 for Wear OS"
        watchesNeedingInstall.isNotEmpty() -> "Install on Watch"
        else -> "Wear OS App Installed"
    }

    fun rowDetail(): String = when (val launch = launchState) {
        WatchInstallLaunchState.Launching ->
            "Opening Google Play on ${formattedNames(targetWatchesForPlayStore)}..."
        is WatchInstallLaunchState.Opened ->
            "Google Play opened on ${launch.watchNames} — tap Install on your watch"
        is WatchInstallLaunchState.Failed ->
            launch.message
        WatchInstallLaunchState.Idle -> when {
            isChecking && !hasConnectedWatch -> "Checking for connected Wear OS watches..."
            !hasConnectedWatch -> "No Wear OS watch connected • Tap to check again"
            watchesNeedingInstall.isNotEmpty() ->
                "Tap to open Google Play on ${formattedNames(watchesNeedingInstall)}"
            else ->
                "Installed on ${formattedNames(connectedWatches)} • Tap to open on watch Play Store"
        }
    }

    fun isProblemDetail(): Boolean = launchState is WatchInstallLaunchState.Failed
}
