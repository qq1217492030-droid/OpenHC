package io.github.gcbond.openhc.tools.desktop

import io.github.gcbond.openhc.core.hisight.AccessUnit
import java.io.ByteArrayOutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoFileSinkTest {
    /** 合成的 Annex-B 帧：起始码 + NAL 头 + 几个填充字节。 */
    private fun frame(vararg nalTypes: Int, fourByteStartCode: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        for (type in nalTypes) {
            if (fourByteStartCode) out.write(0)
            out.write(byteArrayOf(0, 0, 1, (0x60 or type).toByte(), 0x11, 0x22, 0x33))
        }
        return out.toByteArray()
    }

    @Test
    fun keyFramesAreFramesWithAnIdrSlice() {
        assertTrue(H264.isKeyFrame(frame(7, 8, 5), 0, frame(7, 8, 5).size))
        assertTrue(H264.isKeyFrame(frame(9, 7, 8, 5, fourByteStartCode = true), 0, frame(9, 7, 8, 5, fourByteStartCode = true).size))
        assertFalse(H264.isKeyFrame(frame(1), 0, frame(1).size))
        assertFalse(H264.isKeyFrame(frame(6, 1), 0, frame(6, 1).size))
        // 第一个片不是 IDR 就不是关键帧，后面不再看。
        assertFalse(H264.isKeyFrame(frame(1, 5), 0, frame(1, 5).size))
        assertFalse(H264.isKeyFrame(byteArrayOf(0, 0, 1), 0, 3))
        // 只看 offset/length 范围内的数据。
        val padded = byteArrayOf(9, 9) + frame(7, 8, 5)
        assertTrue(H264.isKeyFrame(padded, 2, padded.size - 2))
        assertFalse(H264.isKeyFrame(padded, 2, 7))
    }

    @Test
    fun writesFramesAndCounts() {
        // Windows 的文件系统没有 POSIX 权限视图：0600 的断言跳过（PrivateFiles 里同样不做权限设置）。
        val posixSupported =
            java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
        val dir = Files.createTempDirectory("openhc-video-test")
        val path = dir.resolve("out.h264")
        try {
            val sink = VideoFileSink.create(path)
            if (posixSupported) assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
            val unit = AccessUnit::class.java.getDeclaredConstructor().let {
                it.isAccessible = true
                it.newInstance()
            }
            // AccessUnit 的字段只在投屏层内部可写：用反射把合成帧放进去。
            fun put(bytes: ByteArray) {
                AccessUnit::class.java.declaredFields.forEach { f ->
                    f.isAccessible = true
                    when (f.name) {
                        "data" -> f.set(unit, bytes)
                        "offset" -> f.setInt(unit, 0)
                        "length" -> f.setInt(unit, bytes.size)
                    }
                }
            }
            val key = frame(7, 8, 5)
            val delta = frame(1)
            put(key)
            sink.onAccessUnit(unit)
            put(delta)
            sink.onAccessUnit(unit)
            sink.close()
            assertEquals(2, sink.frames)
            assertEquals(1, sink.keyFrames)
            assertEquals((key.size + delta.size).toLong(), sink.bytes)
            assertContentEquals(key + delta, Files.readAllBytes(path))
            assertFailsWith<FileAlreadyExistsException> { VideoFileSink.create(path) }
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(dir)
        }
    }
}
