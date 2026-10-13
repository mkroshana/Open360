package com.open360.core.osc

import com.open360.core.model.BatteryInfo
import com.open360.core.model.CameraInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OscClient(
    private val baseUrl: String = "http://192.168.42.1"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun getInfo(): Result<CameraInfo> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/osc/info")
                .header("User-Agent", "Open360/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP error ${response.code}"))
                }
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
                val json = JSONObject(body)
                val info = CameraInfo(
                    manufacturer = json.optString("manufacturer", "Insta360"),
                    model = json.optString("model", "Insta360 GO 3S"),
                    serialNumber = json.optString("serialNumber", ""),
                    firmwareVersion = json.optString("firmwareVersion", "")
                )
                Result.success(info)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getState(): Result<Pair<BatteryInfo?, String?>> = withContext(Dispatchers.IO) {
        try {
            val emptyBody = "".toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$baseUrl/osc/state")
                .post(emptyBody)
                .header("User-Agent", "Open360/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP error ${response.code}"))
                }
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
                val json = JSONObject(body)
                val stateObj = json.optJSONObject("state")

                val storageUri = stateObj?.optString("storageUri")
                val batteryLevel = stateObj?.optDouble("batteryLevel", -1.0)
                val battery = if (batteryLevel != null && batteryLevel >= 0) {
                    BatteryInfo(percentage = (batteryLevel * 100).toInt(), isCharging = false)
                } else null

                Result.success(Pair(battery, storageUri))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun executeCommand(name: String, parameters: Map<String, Any> = emptyMap()): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("name", name)
                if (parameters.isNotEmpty()) {
                    put("parameters", JSONObject(parameters))
                }
            }

            val request = Request.Builder()
                .url("$baseUrl/osc/commands/execute")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("User-Agent", "Open360/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
                val json = JSONObject(body)
                if (json.optString("state") == "error") {
                    val err = json.optJSONObject("error")?.optString("message") ?: "Command failed"
                    Result.failure(Exception(err))
                } else {
                    Result.success(json)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun takePicture(): Result<Unit> = executeCommand("camera.takePicture").map { }

    suspend fun startCapture(): Result<Unit> = executeCommand("camera.startCapture").map { }

    suspend fun stopCapture(): Result<Unit> = executeCommand("camera.stopCapture").map { }

    suspend fun listFiles(
        fileType: String = "all",
        entryCount: Int = 100,
        startPosition: Int = 0,
        maxThumbSize: Int = 200
    ): Result<List<com.open360.core.model.CameraMediaFile>> = withContext(Dispatchers.IO) {
        val params = mapOf(
            "fileType" to fileType,
            "entryCount" to entryCount,
            "startPosition" to startPosition,
            "maxThumbSize" to maxThumbSize
        )
        executeCommand("camera.listFiles", params).map { json ->
            val results = json.optJSONObject("results") ?: JSONObject()
            val entriesArray = results.optJSONArray("entries") ?: org.json.JSONArray()
            val list = mutableListOf<com.open360.core.model.CameraMediaFile>()
            for (i in 0 until entriesArray.length()) {
                val item = entriesArray.optJSONObject(i) ?: continue
                val name = item.optString("name", "")
                val fileUrl = item.optString("fileUrl", "")
                val size = item.optLong("size", 0L)
                val dateTime = item.optString("dateTimeZone", "")
                val width = item.optInt("width", 0)
                val height = item.optInt("height", 0)
                val thumb = item.optString("thumbnail").takeIf { it.isNotBlank() }
                val isVideo = name.endsWith(".mp4", ignoreCase = true) ||
                        name.endsWith(".insv", ignoreCase = true) ||
                        name.endsWith(".lrv", ignoreCase = true)

                if (name.isNotEmpty() && fileUrl.isNotEmpty()) {
                    list.add(
                        com.open360.core.model.CameraMediaFile(
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
            }
            list
        }
    }
}
