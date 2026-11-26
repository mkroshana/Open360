package com.open360.wear.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.open360.wear.WearActivity
import com.open360.wear.WearCameraSnapshot
import com.open360.wear.WearSnapshotStore

/**
 * Watch-face complication data source surfacing live camera state and battery percentage from
 * [WearSnapshotStore].
 */
class Open360ComplicationService : SuspendingComplicationDataSourceService() {

    private companion object {
        const val CONTENT_DESCRIPTION = "Open360 camera status"
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val snapshot = WearSnapshotStore.read(this)
        return buildComplicationData(
            type = request.complicationType,
            snapshot = snapshot,
            tapAction = createLaunchPendingIntent()
        )
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val previewSnapshot = WearCameraSnapshot(
            cameraName = "GO 3S",
            statusLabel = "Ready",
            batteryPercent = 82,
            isRecording = false,
            isConnected = true,
            isAwake = true
        )
        return buildComplicationData(
            type = type,
            snapshot = previewSnapshot,
            tapAction = null
        )
    }

    private fun buildComplicationData(
        type: ComplicationType,
        snapshot: WearCameraSnapshot,
        tapAction: PendingIntent?
    ): ComplicationData? {
        val monochromaticImage = MonochromaticImage.Builder(
            Icon.createWithResource(this, com.open360.core.R.drawable.ic_open360_mark)
        ).build()
        val contentDescription = PlainComplicationText.Builder(CONTENT_DESCRIPTION).build()
        val shortText = PlainComplicationText.Builder(
            WearSnapshotStore.complicationShortText(snapshot)
        ).build()

        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = shortText,
                contentDescription = contentDescription
            )
                .setMonochromaticImage(monochromaticImage)
                .apply {
                    if (snapshot.isRecording || (snapshot.isConnected && snapshot.isAwake)) {
                        setTitle(PlainComplicationText.Builder(snapshot.cameraName).build())
                    }
                    if (tapAction != null) setTapAction(tapAction)
                }
                .build()

            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = (snapshot.batteryPercent ?: 0).coerceIn(0, 100).toFloat(),
                min = 0f,
                max = 100f,
                contentDescription = contentDescription
            )
                .setText(shortText)
                .setMonochromaticImage(monochromaticImage)
                .apply {
                    if (tapAction != null) setTapAction(tapAction)
                }
                .build()

            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(
                monochromaticImage = monochromaticImage,
                contentDescription = contentDescription
            )
                .apply {
                    if (tapAction != null) setTapAction(tapAction)
                }
                .build()

            else -> null
        }
    }

    private fun createLaunchPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, WearActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
