package com.open360.core.ble

/**
 * Decides whether a link that has dropped is opened again by itself.
 *
 * A link that drops within [earlyDropMs] of the camera starting to answer on it is opened again,
 * and so is an automatic reconnection that fails before the camera answers, at most [maxInARow]
 * times in a row. A link that held past [earlyDropMs] makes the reconnections available again,
 * and so does connecting from the app ([reset]). Any other drop is left as it is.
 */
class ReconnectPolicy(private val earlyDropMs: Long, private val maxInARow: Int) {

    enum class Action {
        STAY_DISCONNECTED,
        RECONNECT,
        // The drop would be reconnected, but the reconnections in a row are used up
        GIVE_UP
    }

    private var left = maxInARow

    /** Reconnections still allowed before giving up. */
    val reconnectsLeft: Int
        @Synchronized get() = left

    @Synchronized
    fun reset() {
        left = maxInARow
    }

    /**
     * @param answeredAtMs when the camera first answered on the link that dropped, null if it never did
     * @param linkWasReconnect whether that link was opened by an automatic reconnection
     */
    @Synchronized
    fun onDrop(nowMs: Long, answeredAtMs: Long?, linkWasReconnect: Boolean): Action {
        val droppedEarly = answeredAtMs != null && nowMs - answeredAtMs < earlyDropMs
        if (answeredAtMs != null && !droppedEarly) left = maxInARow
        val reconnectFailed = linkWasReconnect && answeredAtMs == null
        if (!droppedEarly && !reconnectFailed) return Action.STAY_DISCONNECTED
        if (left <= 0) return Action.GIVE_UP
        left--
        return Action.RECONNECT
    }
}
