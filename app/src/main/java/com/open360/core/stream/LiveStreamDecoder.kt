package com.open360.core.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.open360.core.protocol.NalStreamParser
import com.open360.core.protocol.NalStreamParser.ParsedAccessUnit
import com.open360.core.protocol.NalStreamParser.VideoCodec
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated low-latency hardware video decoder for Insta360 live preview streams (`video/avc` or
 * `video/hevc`). Mirrors the official player's keyframe gating (`skip non-iframe` until SPS+PPS+IDR
 * arrives) and renders directly to a caller-managed [Surface].
 */
class LiveStreamDecoder(
    private val onVideoFormatChanged: (width: Int, height: Int, codec: VideoCodec) -> Unit,
    private val onFrameRendered: (timestampMs: Long) -> Unit,
    private val onKeyFrameNeeded: () -> Unit,
    private val onDecoderError: (String) -> Unit
) {
    private companion object {
        const val TAG = "LiveStreamDecoder"
        const val FRAME_QUEUE_CAPACITY = 24
        const val POLL_TIMEOUT_MS = 50L
        const val INPUT_DEQUEUE_TIMEOUT_US = 20_000L
        const val KEYFRAME_INPUT_RETRIES = 3
        const val MIN_INPUT_BUFFER_BYTES = 1024 * 1024
        const val MAX_CONSECUTIVE_FAILURES = 3
    }

    private data class QueuedFrame(
        val timestampMs: Long,
        val accessUnit: ParsedAccessUnit,
        val vps: ByteArray?,
        val sps: ByteArray?,
        val pps: ByteArray?
    )

    private val lock = Any()
    private val running = AtomicBoolean(false)
    private val frameQueue = ArrayBlockingQueue<QueuedFrame>(FRAME_QUEUE_CAPACITY)

    @Volatile
    private var workerThread: Thread? = null

    private var surface: Surface? = null
    private var surfaceChanged = false

    @Volatile
    private var activeCodec: VideoCodec = VideoCodec.H264
    private var cachedVps: ByteArray? = null
    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null
    private var awaitingKeyFrame = true

    private var codec: MediaCodec? = null
    private var configuredCodec: VideoCodec? = null
    private var configuredVps: ByteArray? = null
    private var configuredSps: ByteArray? = null
    private var configuredPps: ByteArray? = null
    private var currentWidth = 640
    private var currentHeight = 480
    private var consecutiveFailures = 0
    private var loggedFirstParsedPacket = false
    private var loggedRejectedPayload = false
    private var loggedFirstRenderedFrame = false

    fun start(initialWidth: Int = 640, initialHeight: Int = 480) {
        if (running.get()) return
        synchronized(lock) {
            frameQueue.clear()
            activeCodec = VideoCodec.H264
            cachedVps = null
            cachedSps = null
            cachedPps = null
            currentWidth = initialWidth.coerceAtLeast(1)
            currentHeight = initialHeight.coerceAtLeast(1)
            awaitingKeyFrame = true
            consecutiveFailures = 0
            loggedFirstParsedPacket = false
            loggedRejectedPayload = false
            loggedFirstRenderedFrame = false
            surfaceChanged = surface != null
        }
        running.set(true)
        workerThread = Thread(::runDecodeLoop, "Open360-LiveDecoder").apply {
            isDaemon = true
            start()
        }
    }

    fun setSurface(newSurface: Surface?) {
        val shouldRequestKeyFrame: Boolean
        synchronized(lock) {
            if (surface === newSurface) return
            surface = newSurface
            surfaceChanged = true
            shouldRequestKeyFrame = newSurface != null && (codec == null || awaitingKeyFrame)
            (lock as Object).notifyAll()
        }
        if (shouldRequestKeyFrame && running.get()) {
            onKeyFrameNeeded()
        }
    }

    fun submitVideoPacket(timestampMs: Long, rawPayload: ByteArray) {
        if (!running.get()) return
        val parsed = NalStreamParser.parseFrame(rawPayload, activeCodec)
        if (parsed == null) {
            synchronized(lock) {
                if (!loggedRejectedPayload) {
                    loggedRejectedPayload = true
                    val headHex = rawPayload.take(minOf(16, rawPayload.size))
                        .joinToString("") { "%02x".format(it) }
                    Log.w(TAG, "Rejected video payload (${rawPayload.size}B): $headHex")
                }
            }
            return
        }

        var needKeyframe = false
        synchronized(lock) {
            if (!loggedFirstParsedPacket) {
                loggedFirstParsedPacket = true
                Log.i(
                    TAG,
                    "First parsed video unit: codec=${parsed.codec}, key=${parsed.isKeyFrame}, " +
                        "vcl=${parsed.hasVclSlice}, size=${parsed.annexBData.size}, " +
                        "sps=${parsed.sps?.size ?: 0}, pps=${parsed.pps?.size ?: 0}, vps=${parsed.vps?.size ?: 0}"
                )
            }
            if (parsed.codec != activeCodec) {
                activeCodec = parsed.codec
                cachedVps = null
                cachedSps = null
                cachedPps = null
                awaitingKeyFrame = true
            }
            parsed.vps?.let { cachedVps = it }
            parsed.sps?.let { cachedSps = it }
            parsed.pps?.let { cachedPps = it }

            if (!parsed.hasVclSlice) return

            val configReady = cachedSps != null && cachedPps != null &&
                (activeCodec != VideoCodec.H265 || cachedVps != null)

            if (awaitingKeyFrame) {
                if (!parsed.isKeyFrame || !configReady) {
                    return
                }
                awaitingKeyFrame = false
            }

            if (!frameQueue.offer(QueuedFrame(timestampMs, parsed, cachedVps, cachedSps, cachedPps))) {
                // Keep live viewfinder real-time by dropping stale frames and jumping to keyframe.
                frameQueue.clear()
                if (parsed.isKeyFrame && configReady) {
                    frameQueue.offer(QueuedFrame(timestampMs, parsed, cachedVps, cachedSps, cachedPps))
                } else {
                    awaitingKeyFrame = true
                    needKeyframe = true
                }
            }
            (lock as Object).notifyAll()
        }
        if (needKeyframe) {
            onKeyFrameNeeded()
        }
    }

    fun stop() {
        running.set(false)
        synchronized(lock) {
            (lock as Object).notifyAll()
        }
        val thread = workerThread
        workerThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            thread.interrupt()
            try {
                thread.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        releaseCodec()
        synchronized(lock) {
            frameQueue.clear()
            cachedVps = null
            cachedSps = null
            cachedPps = null
            awaitingKeyFrame = true
        }
    }

    fun release() {
        stop()
        synchronized(lock) {
            surface = null
            surfaceChanged = false
        }
    }

    private fun runDecodeLoop() {
        val bufferInfo = MediaCodec.BufferInfo()

        while (running.get()) {
            var currentSurface: Surface?
            var surfaceWasUpdated: Boolean
            synchronized(lock) {
                currentSurface = surface
                surfaceWasUpdated = surfaceChanged
                surfaceChanged = false
            }

            if (surfaceWasUpdated) {
                handleSurfaceChange(currentSurface)
            }

            // Wait for a valid rendering Surface before polling frames from the queue so an
            // early IDR keyframe is preserved rather than discarded during view transitions.
            val renderSurface = synchronized(lock) { surface }
            if (renderSurface == null || !renderSurface.isValid) {
                synchronized(lock) {
                    if (running.get() && (surface == null || surface?.isValid != true)) {
                        try {
                            (lock as Object).wait(POLL_TIMEOUT_MS)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
                continue
            }

            val frame = try {
                frameQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            }

            if (frame == null) {
                if (codec != null) {
                    drainOutputBuffers(bufferInfo)
                }
                continue
            }

            val parsed = frame.accessUnit
            val needsReconfigure = synchronized(lock) {
                codec == null ||
                    configuredCodec != parsed.codec ||
                    !nullableBytesEqual(configuredSps, frame.sps) ||
                    !nullableBytesEqual(configuredPps, frame.pps) ||
                    (parsed.codec == VideoCodec.H265 && !nullableBytesEqual(configuredVps, frame.vps))
            }

            if (needsReconfigure) {
                if (!parsed.isKeyFrame) {
                    synchronized(lock) { awaitingKeyFrame = true }
                    onKeyFrameNeeded()
                    continue
                }
                if (!configureCodec(parsed.codec, renderSurface, frame.vps, frame.sps, frame.pps)) {
                    continue
                }
            }

            val activeMediaCodec = codec ?: continue
            try {
                drainOutputBuffers(bufferInfo)
                var inputIndex = activeMediaCodec.dequeueInputBuffer(INPUT_DEQUEUE_TIMEOUT_US)
                if (inputIndex < 0 && parsed.isKeyFrame) {
                    for (retry in 1..KEYFRAME_INPUT_RETRIES) {
                        if (!running.get()) break
                        drainOutputBuffers(bufferInfo)
                        inputIndex = activeMediaCodec.dequeueInputBuffer(INPUT_DEQUEUE_TIMEOUT_US)
                        if (inputIndex >= 0) break
                    }
                }
                if (inputIndex >= 0) {
                    val inputBuffer = activeMediaCodec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        val data = parsed.annexBData
                        if (data.size <= inputBuffer.remaining()) {
                            inputBuffer.put(data)
                            val flags = if (parsed.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            activeMediaCodec.queueInputBuffer(
                                inputIndex,
                                0,
                                data.size,
                                frame.timestampMs * 1000L,
                                flags
                            )
                        } else {
                            Log.w(
                                TAG,
                                "Frame (${data.size}B) exceeds MediaCodec inputBuffer (${inputBuffer.remaining()}B); requesting keyframe"
                            )
                            activeMediaCodec.queueInputBuffer(inputIndex, 0, 0, 0L, 0)
                            synchronized(lock) { awaitingKeyFrame = true }
                            onKeyFrameNeeded()
                        }
                    }
                } else {
                    Log.w(
                        TAG,
                        "No MediaCodec input buffer available (key=${parsed.isKeyFrame}, size=${parsed.annexBData.size}B)"
                    )
                    if (parsed.isKeyFrame) {
                        synchronized(lock) { awaitingKeyFrame = true }
                        onKeyFrameNeeded()
                    }
                }
                drainOutputBuffers(bufferInfo)
                consecutiveFailures = 0
            } catch (e: Exception) {
                Log.w(TAG, "Decode error, resetting codec", e)
                handleDecodeFailure(parsed.codec)
            }
        }

        releaseCodec()
    }

    private fun handleSurfaceChange(newSurface: Surface?) {
        val existingCodec = codec ?: return
        if (newSurface == null || !newSurface.isValid) {
            releaseCodec()
            synchronized(lock) { awaitingKeyFrame = true }
            return
        }
        try {
            existingCodec.setOutputSurface(newSurface)
        } catch (e: Exception) {
            Log.w(TAG, "setOutputSurface failed, reconfiguring decoder", e)
            releaseCodec()
            synchronized(lock) { awaitingKeyFrame = true }
            onKeyFrameNeeded()
        }
    }

    private fun configureCodec(
        targetCodec: VideoCodec,
        renderSurface: Surface,
        frameVps: ByteArray?,
        frameSps: ByteArray?,
        framePps: ByteArray?
    ): Boolean {
        val sps = frameSps ?: return false
        val pps = framePps ?: return false
        val vps = frameVps
        if (targetCodec == VideoCodec.H265 && vps == null) return false

        releaseCodec()

        return try {
            val mediaCodec = MediaCodec.createDecoderByType(targetCodec.mimeType)
            val format = buildMediaFormat(targetCodec, currentWidth, currentHeight, vps, sps, pps, lowLatency = true)
            var usedLowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            try {
                try {
                    mediaCodec.configure(format, renderSurface, null, 0)
                } catch (firstError: Exception) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val fallbackFormat = buildMediaFormat(targetCodec, currentWidth, currentHeight, vps, sps, pps, lowLatency = false)
                        mediaCodec.configure(fallbackFormat, renderSurface, null, 0)
                        usedLowLatency = false
                    } else {
                        throw firstError
                    }
                }
                mediaCodec.start()
            } catch (configOrStartError: Exception) {
                try {
                    mediaCodec.release()
                } catch (_: Exception) {}
                throw configOrStartError
            }
            codec = mediaCodec
            configuredCodec = targetCodec
            configuredSps = sps
            configuredPps = pps
            configuredVps = vps
            synchronized(lock) {
                if (nullableBytesEqual(cachedSps, sps) &&
                    nullableBytesEqual(cachedPps, pps) &&
                    (targetCodec != VideoCodec.H265 || nullableBytesEqual(cachedVps, vps))
                ) {
                    awaitingKeyFrame = false
                }
            }
            consecutiveFailures = 0
            Log.i(
                TAG,
                "Configured ${targetCodec.mimeType} decoder ${currentWidth}x${currentHeight} " +
                    "(lowLatency=$usedLowLatency, sps=${sps.size}B, pps=${pps.size}B, vps=${vps?.size ?: 0}B)"
            )
            onVideoFormatChanged(currentWidth, currentHeight, targetCodec)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure ${targetCodec.mimeType} decoder", e)
            handleDecodeFailure(targetCodec)
            false
        }
    }

    private fun buildMediaFormat(
        targetCodec: VideoCodec,
        width: Int,
        height: Int,
        vps: ByteArray?,
        sps: ByteArray,
        pps: ByteArray,
        lowLatency: Boolean
    ): MediaFormat {
        val format = MediaFormat.createVideoFormat(targetCodec.mimeType, width, height)
        if (targetCodec == VideoCodec.H265 && vps != null) {
            val combined = ByteArray(vps.size + sps.size + pps.size)
            System.arraycopy(vps, 0, combined, 0, vps.size)
            System.arraycopy(sps, 0, combined, vps.size, sps.size)
            System.arraycopy(pps, 0, combined, vps.size + sps.size, pps.size)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(combined))
        } else {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
        }
        if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(width * height, MIN_INPUT_BUFFER_BYTES))
        return format
    }

    private fun drainOutputBuffers(info: MediaCodec.BufferInfo) {
        val activeMediaCodec = codec ?: return
        while (running.get()) {
            val outIndex = activeMediaCodec.dequeueOutputBuffer(info, 0)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outFormat = activeMediaCodec.outputFormat
                    val rawW = if (outFormat.containsKey(MediaFormat.KEY_WIDTH)) outFormat.getInteger(MediaFormat.KEY_WIDTH) else currentWidth
                    val rawH = if (outFormat.containsKey(MediaFormat.KEY_HEIGHT)) outFormat.getInteger(MediaFormat.KEY_HEIGHT) else currentHeight
                    val width = if (outFormat.containsKey("crop-left") && outFormat.containsKey("crop-right")) {
                        outFormat.getInteger("crop-right") - outFormat.getInteger("crop-left") + 1
                    } else rawW
                    val height = if (outFormat.containsKey("crop-top") && outFormat.containsKey("crop-bottom")) {
                        outFormat.getInteger("crop-bottom") - outFormat.getInteger("crop-top") + 1
                    } else rawH
                    if (width > 0 && height > 0) {
                        currentWidth = width
                        currentHeight = height
                        Log.i(TAG, "Decoder output format changed: ${width}x${height}")
                        onVideoFormatChanged(width, height, configuredCodec ?: activeCodec)
                    }
                }
                outIndex >= 0 -> {
                    val render = info.size > 0 && synchronized(lock) { surface?.isValid == true }
                    activeMediaCodec.releaseOutputBuffer(outIndex, render)
                    if (render) {
                        if (!loggedFirstRenderedFrame) {
                            loggedFirstRenderedFrame = true
                            Log.i(TAG, "Rendered first Live View frame (ptsUs=${info.presentationTimeUs})")
                        }
                        onFrameRendered(info.presentationTimeUs / 1000L)
                    }
                }
                else -> return
            }
        }
    }

    private fun handleDecodeFailure(targetCodec: VideoCodec) {
        releaseCodec()
        synchronized(lock) {
            awaitingKeyFrame = true
            frameQueue.clear()
        }
        consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            onDecoderError("Hardware ${targetCodec.label} decoder failed to initialize")
        } else {
            onKeyFrameNeeded()
        }
    }

    private fun releaseCodec() {
        val existing = codec ?: return
        codec = null
        configuredCodec = null
        configuredSps = null
        configuredPps = null
        configuredVps = null
        try {
            existing.stop()
        } catch (_: Exception) {}
        try {
            existing.release()
        } catch (_: Exception) {}
    }

    private fun nullableBytesEqual(a: ByteArray?, b: ByteArray?): Boolean = when {
        a == null && b == null -> true
        a != null && b != null -> a.contentEquals(b)
        else -> false
    }
}
