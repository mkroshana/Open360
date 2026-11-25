package com.open360.core.repository

import com.open360.core.model.CameraMediaFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFileListTest {

    private val base = "http://192.168.42.1"

    @Test
    fun testClipsTakeTheirLowResolutionCopyAsPreview() {
        // The start of a GO 3S file list, newest first
        val files = CameraFileList.toMediaFiles(
            listOf(
                "/DCIM/Camera01/PRO_LRV_20261015_181505_01_095.lrv",
                "/DCIM/Camera01/PRO_VID_20261015_181505_00_095.mp4",
                "/DCIM/Camera01/LRV_20261012_163049_01_066.lrv",
                "/DCIM/Camera01/VID_20261012_163049_00_066.mp4",
                "/DCIM/Camera01/IMG_20261010_202841_00_001.jpg"
            ),
            base
        )

        assertEquals(
            listOf("PRO_VID_20261015_181505_00_095.mp4", "VID_20261012_163049_00_066.mp4", "IMG_20261010_202841_00_001.jpg"),
            files.map { it.name }
        )

        val pro = files[0]
        assertEquals("http://192.168.42.1/DCIM/Camera01/PRO_VID_20261015_181505_00_095.mp4", pro.fileUrl)
        assertEquals("http://192.168.42.1/DCIM/Camera01/PRO_LRV_20261015_181505_01_095.lrv", pro.previewUrl)
        assertEquals("2026-10-15 18:15:05", pro.dateTime)
        assertEquals("15 Oct 2026, 18:15", pro.displayDateTime)
        // Not on the phone yet, so the low-resolution copy is what plays
        assertEquals(pro.previewUrl, pro.streamUri)
        assertTrue(pro.isVideo)
        assertEquals("PRO_VID_20261015_181505_00_095", pro.baseNameWithoutExtension)

        assertEquals("http://192.168.42.1/DCIM/Camera01/LRV_20261012_163049_01_066.lrv", files[1].previewUrl)

        val photo = files[2]
        assertFalse(photo.isVideo)
        assertNull(photo.previewUrl)
        assertEquals("2026-10-10 20:28:41", photo.dateTime)
    }

    @Test
    fun testCopiesWithoutTheirClipAndOtherNamesAreKept() {
        val files = CameraFileList.toMediaFiles(
            listOf(
                // A PRO copy does not go with a clip that is not PRO
                "/DCIM/Camera01/PRO_LRV_20261012_163049_01_066.lrv",
                "/DCIM/Camera01/VID_20261012_163049_00_066.mp4",
                "/DCIM/Camera01/notes.txt"
            ),
            base
        )

        assertEquals(listOf("PRO_LRV_20261012_163049_01_066.lrv", "VID_20261012_163049_00_066.mp4", "notes.txt"), files.map { it.name })
        assertTrue(files[0].isVideo)
        assertNull(files[1].previewUrl)
        assertFalse(files[2].isVideo)
        assertEquals("", files[2].dateTime)
        assertNull(files[2].displayDateTime)
        // A clip without a copy plays in full
        assertEquals(files[1].fileUrl, files[1].streamUri)
    }

    @Test
    fun testParseDateTimeFromNameAndEpochDisplayDateTime() {
        assertEquals("2026-10-15 18:15:05", CameraFileList.parseDateTimeFromName("PRO_VID_20261015_181505_00_095.mp4"))
        assertEquals("2026-10-10 20:28:41", CameraFileList.parseDateTimeFromName("IMG_20261010_202841_00_001.jpg"))
        assertNull(CameraFileList.parseDateTimeFromName("notes.txt"))
        val epochFile = CameraMediaFile(name = "clip.mp4", fileUrl = "content://x", dateTime = "1760552105")
        assertNotNull(epochFile.displayDateTime)
    }
}
