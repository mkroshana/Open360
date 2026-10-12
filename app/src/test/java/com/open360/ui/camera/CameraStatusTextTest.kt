package com.open360.ui.camera

import com.open360.core.ble.Go2BleProtocol.CardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraStatusTextTest {

    @Test
    fun testTimeLeft() {
        assertEquals("1 h 19 min", CameraStatusText.timeLeft(4796))
        assertEquals("1 h 0 min", CameraStatusText.timeLeft(3600))
        assertEquals("42 min", CameraStatusText.timeLeft(42 * 60 + 59))
        assertEquals("1 min", CameraStatusText.timeLeft(60))
        assertEquals("Under 1 min", CameraStatusText.timeLeft(59))
        assertEquals("Under 1 min", CameraStatusText.timeLeft(0))
    }

    @Test
    fun testStorageProblemOnlyWhenSomethingIsWrong() {
        assertNull(CameraStatusText.storageProblem(null))
        assertNull(CameraStatusText.storageProblem(CardState.OK))
        assertEquals("Full", CameraStatusText.storageProblem(CardState.FULL))
        assertEquals("Not Found", CameraStatusText.storageProblem(CardState.NO_CARD))
        assertEquals("Needs Formatting", CameraStatusText.storageProblem(CardState.INVALID_FORMAT))
        assertEquals("Write-Protected", CameraStatusText.storageProblem(CardState.WRITE_PROTECTED))
        assertEquals("Error", CameraStatusText.storageProblem(CardState.ERROR))
    }
}
