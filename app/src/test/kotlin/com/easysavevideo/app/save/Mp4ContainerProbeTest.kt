package com.easysavevideo.app.save

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Test

class Mp4ContainerProbeTest {
    private fun box(type: String, body: ByteArray) = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).use { it.writeInt(8 + body.size); it.writeBytes(type); it.write(body) }
    }.toByteArray()
    private fun mp4(brand: String = "mp42") = box("ftyp", brand.toByteArray() + ByteArray(4) + "isom".toByteArray()) +
        box("mdat", byteArrayOf(1, 2, 3, 4)) + box("moov", byteArrayOf(5, 6, 7, 8))
    private fun probe(bytes: ByteArray): Boolean {
        val file = File.createTempFile("synthetic-mp4-", ".bin")
        return try { file.writeBytes(bytes); RandomAccessFile(file, "r").use { Mp4ContainerProbe.isCompleteMp4(it) } }
        finally { file.delete() }
    }
    @Test fun validBoxLayoutPassesPreflightOnly() { assertTrue(probe(mp4())) }
    @Test fun quicktimeBrandIsNotRenamedToMp4() { assertFalse(probe(mp4("qt  "))) }
    @Test fun htmlAndEmptyRejected() { assertFalse(probe("<html>not video</html>".toByteArray())); assertFalse(probe(byteArrayOf())) }
    @Test fun truncatedPayloadAndMissingMoovRejected() {
        assertFalse(probe(mp4().dropLast(1).toByteArray()))
        assertFalse(probe(box("ftyp", "mp42".toByteArray() + ByteArray(4)) + box("mdat", ByteArray(8))))
    }
    @Test fun oversizedUnsignedAndExtendedBoxRejected() {
        assertFalse(probe(byteArrayOf(-1, -1, -1, -1) + "ftyp".toByteArray() + ByteArray(40)))
        assertFalse(probe(byteArrayOf(0, 0, 0, 1) + "ftyp".toByteArray() + ByteArray(40)))
    }
    @Test fun boundedBoxCountRejectsBomb() {
        val tail = ByteArrayOutputStream().also { out -> repeat(4096) { out.write(box("free", byteArrayOf())) } }.toByteArray()
        assertFalse(probe(mp4() + tail))
    }
}
