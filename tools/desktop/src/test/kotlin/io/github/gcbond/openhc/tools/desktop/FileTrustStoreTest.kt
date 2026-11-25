package io.github.gcbond.openhc.tools.desktop

import io.github.gcbond.openhc.core.auth.LocalIdentity
import io.github.gcbond.openhc.core.auth.ServiceId
import io.github.gcbond.openhc.core.auth.TrustedPeer
import io.github.gcbond.openhc.core.common.identity.AuthServiceIdentity
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileTrustStoreTest {
    private val root: Path = Files.createTempDirectory("openhc-trust-test")
    private val service = ServiceId.of(AuthServiceIdentity("org.example.auth", "EXAMPLE"))
    private val carId = "0123456789ABCDEF".toByteArray(Charsets.US_ASCII)
    private val phoneId = "FEDCBA9876543210".toByteArray(Charsets.US_ASCII)

    /** Windows 的文件系统没有 POSIX 权限视图：权限相关的断言和用例都跳过（PrivateFiles 里同样跳过）。 */
    private val posixSupported =
        java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    @AfterTest
    fun cleanUp() {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach {
            it.toFile().setWritable(true)
            Files.deleteIfExists(it)
        }
    }

    private fun identity() = LocalIdentity(service, carId, ByteArray(32) { it.toByte() })
    private fun peer() = TrustedPeer(service, phoneId, ByteArray(32) { (0xA0 + it).toByte() })

    @Test
    fun pairingIsPersistedPrivatelyAndSurvivesReopen() {
        val dir = root.resolve("trust")
        val store = FileTrustStore.open(dir)
        if (posixSupported) assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
        assertNull(store.findPeer(service, phoneId))
        store.commitPairing(identity(), peer())

        val file = dir.resolve(FileTrustStore.FILE_NAME)
        if (posixSupported) assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals(listOf(FileTrustStore.FILE_NAME), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() }, "临时文件已改名")

        val reopened = FileTrustStore.open(dir)
        assertEquals(1, reopened.peerCount)
        assertContentEquals(peer().publicKey, assertNotNull(reopened.findPeer(service, phoneId)).publicKey)
        assertContentEquals(identity().privateKey, assertNotNull(reopened.findLocalIdentity(service, carId)).privateKey)
    }

    @Test
    fun loosePermissionsAreRefused() {
        if (!posixSupported) return
        val dir = Files.createDirectory(root.resolve("loose"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
        assertFailsWith<IOException> { FileTrustStore.open(dir) }

        val ok = root.resolve("ok")
        FileTrustStore.open(ok).commitPairing(identity(), peer())
        Files.setPosixFilePermissions(ok.resolve(FileTrustStore.FILE_NAME), PosixFilePermissions.fromString("rw-r--r--"))
        assertFailsWith<IOException> { FileTrustStore.open(ok) }
    }

    @Test
    fun corruptFileIsAnErrorNotAnEmptyStore() {
        val dir = root.resolve("corrupt")
        FileTrustStore.open(dir)
        PrivateFiles.writeAtomically(dir.resolve(FileTrustStore.FILE_NAME), byteArrayOf(1, 2, 3))
        val e = assertFailsWith<IOException> { FileTrustStore.open(dir) }
        assertTrue(e.message!!.contains("无法解析"), e.message)
    }

    @Test
    fun failedWriteLeavesMemoryUnchanged() {
        if (!posixSupported || System.getProperty("user.name") == "root") return // root 不受目录权限限制，测不了写失败。
        val dir = root.resolve("readonly")
        val store = FileTrustStore.open(dir)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"))
        try {
            assertFailsWith<IOException> { store.commitPairing(identity(), peer()) }
            assertNull(store.findPeer(service, phoneId))
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        }
    }
}
