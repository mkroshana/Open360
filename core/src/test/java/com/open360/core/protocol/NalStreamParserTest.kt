package com.open360.core.protocol

import com.open360.core.protocol.NalStreamParser.VideoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class NalStreamParserTest {

    @Test
    fun parsesH264AnnexBKeyFrameWithSpsAndPps() {
        val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1E, 0xD9.toByte())
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
        val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x21, 0x0F)

        val stream = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x00, 0x00, 0x00, 0x01)); write(sps)
            write(byteArrayOf(0x00, 0x00, 0x00, 0x01)); write(pps)
            write(byteArrayOf(0x00, 0x00, 0x00, 0x01)); write(idr)
        }.toByteArray()

        val parsed = NalStreamParser.parseFrame(stream)
        assertNotNull(parsed)
        assertEquals(VideoCodec.H264, parsed!!.codec)
        assertTrue(parsed.isKeyFrame)
        assertTrue(parsed.hasVclSlice)
        assertArrayEquals(stream, parsed.annexBData)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x01) + sps, parsed.sps)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x01) + pps, parsed.pps)
        assertNull(parsed.vps)
    }

    @Test
    fun parsesH264ThreeByteStartCodeNonKeyFrame() {
        val pFrame = byteArrayOf(0x00, 0x00, 0x01, 0x41, 0x9A.toByte(), 0x22, 0x33)

        val parsed = NalStreamParser.parseFrame(pFrame, VideoCodec.H264)
        assertNotNull(parsed)
        assertEquals(VideoCodec.H264, parsed!!.codec)
        assertFalse(parsed.isKeyFrame)
        assertTrue(parsed.hasVclSlice)
        assertNull(parsed.sps)
        assertNull(parsed.pps)
    }

    @Test
    fun parsesH264ParameterSetPacketWithoutVclSlice() {
        val csdOnly = byteArrayOf(
            0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1E,
            0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C
        )

        val parsed = NalStreamParser.parseFrame(csdOnly)
        assertNotNull(parsed)
        assertFalse(parsed!!.isKeyFrame)
        assertFalse(parsed.hasVclSlice)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1E), parsed.sps)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C), parsed.pps)
    }

    @Test
    fun convertsAvccLengthPrefixedFrameToAnnexB() {
        val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38)
        val idr = byteArrayOf(0x65, 0x11, 0x22, 0x33, 0x44)

        fun ByteArrayOutputStream.writeAvccNal(nal: ByteArray) {
            write((nal.size ushr 24) and 0xFF)
            write((nal.size ushr 16) and 0xFF)
            write((nal.size ushr 8) and 0xFF)
            write(nal.size and 0xFF)
            write(nal)
        }

        val avcc = ByteArrayOutputStream().apply {
            writeAvccNal(sps)
            writeAvccNal(pps)
            writeAvccNal(idr)
        }.toByteArray()

        val expectedAnnexB = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x00, 0x00, 0x00, 0x01)); write(sps)
            write(byteArrayOf(0x00, 0x00, 0x00, 0x01)); write(pps)
            write(byteArrayOf(0x00, 0x00, 0x00, 0x01)); write(idr)
        }.toByteArray()

        val parsed = NalStreamParser.parseFrame(avcc)
        assertNotNull(parsed)
        assertEquals(VideoCodec.H264, parsed!!.codec)
        assertTrue(parsed.isKeyFrame)
        assertTrue(parsed.hasVclSlice)
        assertArrayEquals(expectedAnnexB, parsed.annexBData)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x01) + sps, parsed.sps)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x01) + pps, parsed.pps)
    }

    @Test
    fun detectsAndParsesH265KeyFrame() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C, 0x01)
        val sps = byteArrayOf(0x42, 0x01, 0x01, 0x60)
        val pps = byteArrayOf(0x44, 0x01, 0xC1.toByte(), 0x73)
        val idr = byteArrayOf(0x26, 0x01, 0xAF.toByte(), 0x08) // NAL type 19 (IDR_W_RADL)

        val stream = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x00, 0x00, 0x00, 0x01)); write(vps)
            write(byteArrayOf(0x00, 0x00, 0x00, 0x01)); write(sps)
            write(byteArrayOf(0x00, 0x00, 0x00, 0x01)); write(pps)
            write(byteArrayOf(0x00, 0x00, 0x00, 0x01)); write(idr)
        }.toByteArray()

        val parsed = NalStreamParser.parseFrame(stream, VideoCodec.H264)
        assertNotNull(parsed)
        assertEquals(VideoCodec.H265, parsed!!.codec)
        assertTrue(parsed.isKeyFrame)
        assertTrue(parsed.hasVclSlice)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x01) + vps, parsed.vps)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x01) + sps, parsed.sps)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x01) + pps, parsed.pps)
    }

    @Test
    fun rejectsEmptyAndMalformedPayloads() {
        assertNull(NalStreamParser.parseFrame(ByteArray(0)))
        assertNull(NalStreamParser.parseFrame(byteArrayOf(0x00, 0x00, 0x01)))
        assertNull(NalStreamParser.parseFrame(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)))
        // Invalid AVCC length exceeding buffer size (16 bytes declared, only 2 present)
        assertNull(NalStreamParser.parseFrame(byteArrayOf(0x00, 0x00, 0x00, 0x10, 0x65, 0x01)))
    }
}
