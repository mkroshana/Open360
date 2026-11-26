package com.open360.core.watch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Discovers connected Wear OS watches and opens Open360's Play Store page directly on watches
 * that do not have the companion app installed yet.
 */
class WatchCompanionInstaller(context: Context) {

    companion object {
        const val CAPABILITY_WEAR_APP = "open360_wear_app"
        private const val TAG = "WatchCompanionInstaller"
    }

    private val appContext = context.applicationContext
    private val nodeClient = Wearable.getNodeClient(appContext)
    private val capabilityClient = Wearable.getCapabilityClient(appContext)
    private val remoteActivityHelper = RemoteActivityHelper(appContext, Dispatchers.IO.asExecutor())
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)

    private val _state = MutableStateFlow(WatchCompanionState(isSupported = true))
    val state: StateFlow<WatchCompanionState> = _state.asStateFlow()

    private val refreshGeneration = AtomicInteger(0)

    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { info ->
        val installedIds = info.nodes.map { it.id }.toSet()
        _state.update { current ->
            val updated = current.copy(installedWatchIds = installedIds)
            if (updated.watchesNeedingInstall.isEmpty() &&
                (updated.launchState is WatchInstallLaunchState.Opened ||
                    updated.launchState is WatchInstallLaunchState.Failed)
            ) {
                updated.copy(launchState = WatchInstallLaunchState.Idle)
            } else {
                updated
            }
        }
    }

    fun startListening() {
        capabilityClient.addListener(capabilityListener, CAPABILITY_WEAR_APP)
            .addOnFailureListener { error ->
                Log.i(TAG, "Wear OS capability listener unavailable: ${error.message}")
            }
        refresh()
    }

    fun stopListening() {
        runCatching {
            capabilityClient.removeListener(capabilityListener, CAPABILITY_WEAR_APP)
        }
    }

    fun refresh() {
        val generation = refreshGeneration.incrementAndGet()
        _state.update { it.copy(isChecking = true) }

        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                if (generation != refreshGeneration.get()) return@addOnSuccessListener
                val watches = nodes
                    .sortedByDescending { it.isNearby }
                    .map { WatchNodeInfo(id = it.id, displayName = it.displayName, isNearby = it.isNearby) }

                capabilityClient.getCapability(CAPABILITY_WEAR_APP, CapabilityClient.FILTER_ALL)
                    .addOnSuccessListener { capabilityInfo ->
                        if (generation != refreshGeneration.get()) return@addOnSuccessListener
                        val installedIds = capabilityInfo.nodes.map { it.id }.toSet()
                        _state.update { current ->
                            val updated = current.copy(
                                isChecking = false,
                                connectedWatches = watches,
                                installedWatchIds = installedIds
                            )
                            if (updated.hasConnectedWatch && updated.watchesNeedingInstall.isEmpty() &&
                                (updated.launchState is WatchInstallLaunchState.Opened ||
                                    updated.launchState is WatchInstallLaunchState.Failed)
                            ) {
                                updated.copy(launchState = WatchInstallLaunchState.Idle)
                            } else {
                                updated
                            }
                        }
                    }
                    .addOnFailureListener { error ->
                        if (generation != refreshGeneration.get()) return@addOnFailureListener
                        Log.i(TAG, "Could not query Wear OS capability: ${error.message}")
                        _state.update { current ->
                            current.copy(
                                isChecking = false,
                                connectedWatches = watches,
                                installedWatchIds = emptySet()
                            )
                        }
                    }
            }
            .addOnFailureListener { error ->
                if (generation != refreshGeneration.get()) return@addOnFailureListener
                Log.i(TAG, "Wearable NodeClient unavailable: ${error.message}")
                _state.update { current ->
                    current.copy(
                        isChecking = false,
                        connectedWatches = emptyList(),
                        installedWatchIds = emptySet()
                    )
                }
            }
    }

    fun openPlayStoreOnWatches() {
        val targets = _state.value.targetWatchesForPlayStore
        if (targets.isEmpty()) {
            refresh()
            return
        }

        _state.update { it.copy(launchState = WatchInstallLaunchState.Launching) }

        val marketIntent = Intent(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setData(Uri.parse("market://details?id=${appContext.packageName}"))

        val remaining = AtomicInteger(targets.size)
        val lock = Any()
        val succeededTargets = mutableListOf<WatchNodeInfo>()
        var firstError: Throwable? = null
        val finished = AtomicBoolean(false)

        targets.forEach { target ->
            val future = remoteActivityHelper.startRemoteActivity(marketIntent, target.id)
            future.addListener({
                try {
                    future.get()
                    synchronized(lock) {
                        succeededTargets.add(target)
                    }
                } catch (e: ExecutionException) {
                    val cause = e.cause ?: e
                    Log.w(TAG, "Failed to open Play Store on ${target.displayName} (${target.id})", cause)
                    synchronized(lock) {
                        if (firstError == null) firstError = cause
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to open Play Store on ${target.displayName} (${target.id})", e)
                    synchronized(lock) {
                        if (firstError == null) firstError = e
                    }
                }

                if (remaining.decrementAndGet() == 0 && finished.compareAndSet(false, true)) {
                    val succeededSnapshot = synchronized(lock) { succeededTargets.toList() }
                    _state.update { current ->
                        val nextLaunchState = if (succeededSnapshot.isNotEmpty()) {
                            WatchInstallLaunchState.Opened(current.formattedNames(succeededSnapshot))
                        } else {
                            WatchInstallLaunchState.Failed(
                                "Could not open Play Store on watch. Make sure your watch is nearby and unlocked."
                            )
                        }
                        current.copy(launchState = nextLaunchState)
                    }
                }
            }, mainExecutor)
        }
    }
}
