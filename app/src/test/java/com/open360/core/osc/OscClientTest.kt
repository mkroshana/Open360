package com.open360.core.osc

import com.open360.core.model.CameraMediaFile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OscClientTest {

    @Test
    fun testParseListFilesJsonEntries() {
        val jsonString = """
        {
            "name": "camera.listFiles",
            "state": "done",
            "results": {
                "entries": [
                    {
                        "name": "VID_20261012_163049_00_066.mp4",
                        "fileUrl": "http://192.168.42.1:80/DCIM/Camera01/VID_20261012_163049_00_066.mp4",
                        "size": 524288000,
                        "dateTimeZone": "2026:10:12 16:30:49",
                        "width": 3840,
                        "height": 2160,
                        "thumbnail": "data:image/jpeg;base64,/9j/4AAQSkZJRg=="
                    },
                    {
                        "name": "IMG_20261012_163500_00_067.jpg",
                        "fileUrl": "http://192.168.42.1:80/DCIM/Camera01/IMG_20261012_163500_00_067.jpg",
                        "size": 4194304,
                        "dateTimeZone": "2026:10:12 16:35:00",
                        "width": 4000,
                        "height": 3000
                    }
                ],
                "totalEntries": 2
            }
        }
        """.trimIndent()

        val json = JSONObject(jsonString)
        val results = json.getJSONObject("results")
        val entriesArray = results.getJSONArray("entries")

        val list = mutableListOf<CameraMediaFile>()
        for (i in 0 until entriesArray.length()) {
            val item = entriesArray.getJSONObject(i)
            val name = item.getString("name")
            val fileUrl = item.getString("fileUrl")
            val size = item.getLong("size")
            val dateTime = item.getString("dateTimeZone")
            val width = item.getInt("width")
            val height = item.getInt("height")
            val thumb = item.optString("thumbnail").takeIf { it.isNotBlank() }
            val isVideo = name.endsWith(".mp4", ignoreCase = true) ||
                    name.endsWith(".insv", ignoreCase = true) ||
                    name.endsWith(".lrv", ignoreCase = true)

            list.add(
                CameraMediaFile(
                    name = name,
                    fileUrl = fileUrl,
                    sizeBytes = size,
                    dateTime = dateTime,
                    width = width,
                    height = height,
                    isVideo = isVideo,
                    thumbnailUrl = thumb
                )
            )
        }

        assertEquals(2, list.size)

        val video = list[0]
        assertEquals("VID_20261012_163049_00_066.mp4", video.name)
        assertEquals("VID_20261012_163049_00_066", video.baseNameWithoutExtension)
        assertTrue(video.isVideo)
        assertEquals("500.0 MB", video.formattedSize)
        assertEquals(3840, video.width)
        assertEquals(2160, video.height)
        assertEquals("data:image/jpeg;base64,/9j/4AAQSkZJRg==", video.thumbnailUrl)

        val photo = list[1]
        assertEquals("IMG_20261012_163500_00_067.jpg", photo.name)
        assertEquals("IMG_20261012_163500_00_067", photo.baseNameWithoutExtension)
        assertFalse(photo.isVideo)
        assertEquals("4.0 MB", photo.formattedSize)
    }
}
