package com.open360.ui.liveview

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveViewComponentsTest {

    @Test
    fun normalizeQuarterRotationHandlesNegativeAndWrappedAngles() {
        assertEquals(0, normalizeQuarterRotation(0))
        assertEquals(90, normalizeQuarterRotation(90))
        assertEquals(180, normalizeQuarterRotation(180))
        assertEquals(270, normalizeQuarterRotation(270))
        assertEquals(0, normalizeQuarterRotation(360))
        assertEquals(270, normalizeQuarterRotation(-90))
    }

    @Test
    fun computeLiveVideoUniformScaleMatchesRotatedDimensions() {
        // 640x384 rotated 90 deg becomes 384x640; inside a 384x640 view scale is 1.0f
        assertEquals(
            1.0f,
            computeLiveVideoUniformScale(
                viewWidth = 384,
                viewHeight = 640,
                videoWidth = 640,
                videoHeight = 384,
                rotationDegrees = 90
            ),
            0.0001f
        )

        // Unrotated 640x480 inside 1280x960 view scales up 2.0f
        assertEquals(
            2.0f,
            computeLiveVideoUniformScale(
                viewWidth = 1280,
                viewHeight = 960,
                videoWidth = 640,
                videoHeight = 480,
                rotationDegrees = 0
            ),
            0.0001f
        )

        // Rotated 270 deg 640x480 inside 960x1280 fits width/height cleanly at 2.0f
        assertEquals(
            2.0f,
            computeLiveVideoUniformScale(
                viewWidth = 960,
                viewHeight = 1280,
                videoWidth = 640,
                videoHeight = 480,
                rotationDegrees = 270
            ),
            0.0001f
        )
    }
}
