package com.open360.wear.tile

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.open360.wear.WearCameraSnapshot
import com.open360.wear.WearSnapshotStore

/**
 * Wear OS Tile showing the last known camera status and a quick Launch/Wake Up action into
 * [com.open360.wear.WearActivity].
 */
class Open360TileService : TileService() {

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val RESOURCE_OPEN360_MARK = "open360_mark"
        const val CLICKABLE_ACTION_ID = "open360_tile_action"
        const val WEAR_ACTIVITY_CLASS = "com.open360.wear.WearActivity"

        const val COLOR_PRIMARY = 0xFFF0EBE1.toInt()
        const val COLOR_ON_PRIMARY = 0xFF151412.toInt()
        const val COLOR_SURFACE = 0xFF191917.toInt()
        const val COLOR_ON_SURFACE = 0xFFF3EFE8.toInt()
        const val COLOR_ON_SURFACE_VARIANT = 0xFFA39E94.toInt()

        const val COLOR_STATUS_RECORDING = 0xFFFF5449.toInt()
        const val COLOR_STATUS_AWAKE = 0xFF78DC8A.toInt()
        const val COLOR_STATUS_ASLEEP = 0xFFFFB95C.toInt()
        const val COLOR_STATUS_OFFLINE = 0xFFF3EFE8.toInt()

        val TILE_COLORS = Colors(
            COLOR_PRIMARY,
            COLOR_ON_PRIMARY,
            COLOR_SURFACE,
            COLOR_ON_SURFACE
        )
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> = CallbackToFutureAdapter.getFuture { completer ->
        val snapshot = WearSnapshotStore.read(this)
        val layout = buildLayout(requestParams, snapshot)
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
        completer.set(tile)
        "Open360Tile"
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> = CallbackToFutureAdapter.getFuture { completer ->
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(
                RESOURCE_OPEN360_MARK,
                ResourceBuilders.ImageResource.Builder()
                    .setAndroidResourceByResId(
                        ResourceBuilders.AndroidImageResourceByResId.Builder()
                            .setResourceId(com.open360.core.R.drawable.ic_open360_mark)
                            .build()
                    )
                    .build()
            )
            .build()
        completer.set(resources)
        "Open360Tile"
    }

    private fun buildLayout(
        requestParams: RequestBuilders.TileRequest,
        snapshot: WearCameraSnapshot
    ): LayoutElementBuilders.LayoutElement {
        val deviceParameters = requestParams.deviceConfiguration
        val statusColor = statusTextColor(snapshot)

        val primaryLabel = Text.Builder(this, snapshot.cameraName)
            .setTypography(Typography.TYPOGRAPHY_CAPTION1)
            .setColor(argb(COLOR_ON_SURFACE))
            .setMaxLines(1)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
            .build()

        val centerContent = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(RESOURCE_OPEN360_MARK)
                    .setWidth(dp(18f))
                    .setHeight(dp(18f))
                    .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_FIT)
                    .setColorFilter(
                        LayoutElementBuilders.ColorFilter.Builder()
                            .setTint(argb(COLOR_PRIMARY))
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Spacer.Builder()
                    .setHeight(dp(4f))
                    .build()
            )
            .addContent(
                Text.Builder(this, snapshot.statusLabel)
                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                    .setColor(argb(statusColor))
                    .setMaxLines(2)
                    .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                    .build()
            )
            .build()

        val actionChip = CompactChip.Builder(
            this,
            WearSnapshotStore.tileActionLabel(snapshot),
            buildLaunchClickable(snapshot),
            deviceParameters
        )
            .setChipColors(ChipColors.primaryChipColors(TILE_COLORS))
            .build()

        return PrimaryLayout.Builder(deviceParameters)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(primaryLabel)
            .setContent(centerContent)
            .apply {
                snapshot.batteryPercent?.let { pct ->
                    setSecondaryLabelTextContent(
                        Text.Builder(this@Open360TileService, "Battery $pct%")
                            .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                            .setColor(argb(COLOR_ON_SURFACE_VARIANT))
                            .setMaxLines(1)
                            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                            .build()
                    )
                }
            }
            .setPrimaryChipContent(actionChip)
            .build()
    }

    private fun buildLaunchClickable(snapshot: WearCameraSnapshot): ModifiersBuilders.Clickable {
        val activityBuilder = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(WEAR_ACTIVITY_CLASS)
        if (WearSnapshotStore.shouldUseWakeUpAction(snapshot)) {
            activityBuilder.addKeyToExtraMapping(
                WearSnapshotStore.EXTRA_QUICK_ACTION,
                ActionBuilders.AndroidStringExtra.Builder()
                    .setValue(WearSnapshotStore.ACTION_WAKE_UP)
                    .build()
            )
        }
        return ModifiersBuilders.Clickable.Builder()
            .setId(CLICKABLE_ACTION_ID)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(activityBuilder.build())
                    .build()
            )
            .build()
    }

    private fun statusTextColor(snapshot: WearCameraSnapshot): Int = when {
        snapshot.isRecording -> COLOR_STATUS_RECORDING
        snapshot.isConnected && snapshot.isAwake -> COLOR_STATUS_AWAKE
        snapshot.isConnected -> COLOR_STATUS_ASLEEP
        else -> COLOR_STATUS_OFFLINE
    }
}
