package com.open360.core.protocol

import java.io.ByteArrayOutputStream

/**
 * Splits incoming H.264 / H.265 elementary stream packets (Annex-B or 4-byte length-prefixed AVCC/HVCC)
 * into normalized Annex-B access units, extracting parameter sets (VPS/SPS/PPS) and IDR keyframe flags.
 */
object NalStreamParser {

    private val ANNEX_B_START_CODE_4 = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    enum class VideoCodec(val mimeType: String, val label: String) {
        H264("video/avc", "H.264"),
        H265("video/hevc", "H.265")
    }

    data class ParsedAccessUnit(
        val codec: VideoCodec,
        val annexBData: ByteArray,
        val sps: ByteArray?,
        val pps: ByteArray?,
        val vps: ByteArray?,
        val isKeyFrame: Boolean,
        val hasVclSlice: Boolean
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ParsedAccessUnit) return false
            return codec == other.codec &&
                annexBData.contentEquals(other.annexBData) &&
                nullableBytesEquals(sps, other.sps) &&
                nullableBytesEquals(pps, other.pps) &&
                nullableBytesEquals(vps, other.vps) &&
                isKeyFrame == other.isKeyFrame &&
                hasVclSlice == other.hasVclSlice
        }

        override fun hashCode(): Int {
            var result = codec.hashCode()
            result = 31 * result + annexBData.contentHashCode()
            result = 31 * result + (sps?.contentHashCode() ?: 0)
            result = 31 * result + (pps?.contentHashCode() ?: 0)
            result = 31 * result + (vps?.contentHashCode() ?: 0)
            result = 31 * result + isKeyFrame.hashCode()
            result = 31 * result + hasVclSlice.hashCode()
            return result
        }
    }

    /**
     * Parses a video frame payload from a `0x20`/`0x21` stream packet into normalized Annex-B NAL units.
     * Supports 3-byte (`00 00 01`) and 4-byte (`00 00 00 01`) Annex-B streams, as well as 4-byte
     * big-endian length-prefixed AVCC/HVCC payloads as fallback.
     */
    fun parseFrame(rawData: ByteArray, currentCodec: VideoCodec = VideoCodec.H264): ParsedAccessUnit? {
        if (rawData.size < 4) return null

        val annexBUnits: List<ByteArray>
        val normalizedAnnexB: ByteArray
        val firstStartCodeLen = startCodeLengthAt(rawData, 0)
        if (firstStartCodeLen > 0) {
            annexBUnits = splitAnnexBNalUnits(rawData)
            if (annexBUnits.isEmpty()) return null
            normalizedAnnexB = rawData
        } else {
            val avccUnits = parseLengthPrefixedNalUnits(rawData) ?: return null
            annexBUnits = avccUnits
            normalizedAnnexB = buildAnnexBStream(avccUnits)
        }

        val codec = detectCodec(annexBUnits, currentCodec)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var vps: ByteArray? = null
        var isKeyFrame = false
        var hasVclSlice = false

        for (nal in annexBUnits) {
            if (nal.isEmpty()) continue
            val header = nal[0].toInt() and 0xFF
            when (codec) {
                VideoCodec.H264 -> {
                    when (header and 0x1F) {
                        7 -> sps = withStartCode(nal)
                        8 -> pps = withStartCode(nal)
                        5 -> {
                            isKeyFrame = true
                            hasVclSlice = true
                        }
                        in 1..4 -> hasVclSlice = true
                    }
                }
                VideoCodec.H265 -> {
                    when ((header ushr 1) and 0x3F) {
                        32 -> vps = withStartCode(nal)
                        33 -> sps = withStartCode(nal)
                        34 -> pps = withStartCode(nal)
                        in 16..21 -> {
                            isKeyFrame = true
                            hasVclSlice = true
                        }
                        in 0..9 -> hasVclSlice = true
                    }
                }
            }
        }

        return ParsedAccessUnit(
            codec = codec,
            annexBData = normalizedAnnexB,
            sps = sps,
            pps = pps,
            vps = vps,
            isKeyFrame = isKeyFrame,
            hasVclSlice = hasVclSlice
        )
    }

    private fun detectCodec(units: List<ByteArray>, fallback: VideoCodec): VideoCodec {
        var hasHevcVps = false
        var hasHevcParam = false
        var hasH264Sps = false
        var hasH264Pps = false

        for (nal in units) {
            if (nal.isEmpty()) continue
            val b0 = nal[0].toInt() and 0xFF
            if ((b0 and 0x80) == 0 && (b0 and 0x01) == 0 && nal.size >= 2) {
                val hevcType = (b0 ushr 1) and 0x3F
                if (hevcType == 32) hasHevcVps = true
                if (hevcType == 33 || hevcType == 34) hasHevcParam = true
            }
            if ((b0 and 0x80) == 0) {
                val h264Type = b0 and 0x1F
                if (h264Type == 7) hasH264Sps = true
                if (h264Type == 8) hasH264Pps = true
            }
        }

        return when {
            hasHevcVps && hasHevcParam -> VideoCodec.H265
            hasH264Sps && hasH264Pps -> VideoCodec.H264
            else -> fallback
        }
    }

    private fun splitAnnexBNalUnits(data: ByteArray): List<ByteArray> {
        val units = mutableListOf<ByteArray>()
        var i = 0
        var nalStart = -1

        while (i < data.size) {
            val scLen = startCodeLengthAt(data, i)
            if (scLen > 0) {
                if (nalStart >= 0 && i > nalStart) {
                    units.add(data.copyOfRange(nalStart, i))
                }
                i += scLen
                nalStart = i
            } else {
                i++
            }
        }

        if (nalStart >= 0 && data.size > nalStart) {
            units.add(data.copyOfRange(nalStart, data.size))
        }
        return units
    }

    private fun parseLengthPrefixedNalUnits(data: ByteArray): List<ByteArray>? {
        val units = mutableListOf<ByteArray>()
        var offset = 0
        while (offset + 4 <= data.size) {
            val len = ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
            if (len <= 0 || offset + 4 + len > data.size) {
                return null
            }
            units.add(data.copyOfRange(offset + 4, offset + 4 + len))
            offset += 4 + len
        }
        return if (offset == data.size && units.isNotEmpty()) units else null
    }

    private fun buildAnnexBStream(units: List<ByteArray>): ByteArray {
        val totalSize = units.sumOf { ANNEX_B_START_CODE_4.size + it.size }
        val out = ByteArrayOutputStream(totalSize)
        for (nal in units) {
            out.write(ANNEX_B_START_CODE_4)
            out.write(nal)
        }
        return out.toByteArray()
    }

    private fun withStartCode(nalBody: ByteArray): ByteArray {
        val out = ByteArray(ANNEX_B_START_CODE_4.size + nalBody.size)
        System.arraycopy(ANNEX_B_START_CODE_4, 0, out, 0, ANNEX_B_START_CODE_4.size)
        System.arraycopy(nalBody, 0, out, ANNEX_B_START_CODE_4.size, nalBody.size)
        return out
    }

    private fun startCodeLengthAt(data: ByteArray, index: Int): Int {
        if (index + 3 > data.size) return 0
        if (data[index] != 0x00.toByte() || data[index + 1] != 0x00.toByte()) return 0
        if (index + 4 <= data.size &&
            data[index + 2] == 0x00.toByte() &&
            data[index + 3] == 0x01.toByte()
        ) {
            return 4
        }
        if (data[index + 2] == 0x01.toByte()) {
            return 3
        }
        return 0
    }

    private fun nullableBytesEquals(a: ByteArray?, b: ByteArray?): Boolean = when {
        a == null && b == null -> true
        a != null && b != null -> a.contentEquals(b)
        else -> false
    }
}
