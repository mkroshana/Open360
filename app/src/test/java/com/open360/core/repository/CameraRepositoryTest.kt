package com.open360.core.repository

import com.open360.core.model.CaptureMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraRepositoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun testInitialCameraStateIsDisconnected() {
        val repository = CameraRepository(testScope)
        val state = repository.cameraState.value
        assertEquals(false, state.isConnected)
        assertEquals(false, state.isRecording)
        assertEquals(CaptureMode.VIDEO, state.currentMode)
    }

    @Test
    fun testModeSwitchingUpdatesState() {
        val repository = CameraRepository(testScope)
        repository.setCaptureMode(CaptureMode.PHOTO)
        assertEquals(CaptureMode.PHOTO, repository.cameraState.value.currentMode)

        repository.setCaptureMode(CaptureMode.VIDEO)
        assertEquals(CaptureMode.VIDEO, repository.cameraState.value.currentMode)
    }
}
