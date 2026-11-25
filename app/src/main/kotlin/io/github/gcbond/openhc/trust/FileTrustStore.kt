package io.github.gcbond.openhc.trust

import io.github.gcbond.openhc.core.auth.InMemoryTrustStore
import io.github.gcbond.openhc.core.auth.LocalIdentity
import io.github.gcbond.openhc.core.auth.ServiceId
import io.github.gcbond.openhc.core.auth.TrustStore
import io.github.gcbond.openhc.core.auth.TrustStoreCodec
import io.github.gcbond.openhc.core.auth.TrustStoreSnapshot
import io.github.gcbond.openhc.core.auth.TrustedPeer
import io.github.gcbond.openhc.core.common.MalformedMessageException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** 信任库文件内容损坏或版本不认识。不当成空库：里面有本机长期身份，悄悄换掉会让所有已配对的手机都要重新配对。 */
class TrustStoreCorruptException(message: String) : IOException(message)

/**
 * 应用私有目录里的信任库：一个目录里一个文件，内容是 core:auth 的版本化格式（[TrustStoreCodec]），
 * 含本机长期身份的私钥。首次配对成功时整份重写：先写同目录的临时文件并刷盘，再改名覆盖，写一半断电也不会坏。
 *
 * 只用 java.io：安卓 8.0 以前没有 java.nio.file。不引用安卓类，可以直接在电脑上测。
 * 目录放在应用私有目录里（不备份的那个），其他应用读不到，所以不再单独检查文件权限。
 *
 * 写盘失败时内存里的内容不变、异常抛给认证层，本次配对按失败处理。线程安全。
 */
class FileTrustStore private constructor(
    val file: File,
    initial: TrustStoreSnapshot,
) : TrustStore {
    private var memory = InMemoryTrustStore(initial)

    /** 已配对的手机数（日志用）。 */
    val peerCount: Int
        @Synchronized get() = memory.snapshot().peers.size

    @Synchronized
    override fun findLocalIdentity(serviceId: ServiceId, authId: ByteArray): LocalIdentity? =
        memory.findLocalIdentity(serviceId, authId)

    @Synchronized
    override fun findPeer(serviceId: ServiceId, authId: ByteArray): TrustedPeer? = memory.findPeer(serviceId, authId)

    @Synchronized
    override fun commitPairing(identity: LocalIdentity, peer: TrustedPeer) {
        // 先算出新内容、写盘成功后再换内存：写不进去时内存保持原样。
        val next = InMemoryTrustStore(memory.snapshot())
        next.commitPairing(identity, peer)
        writeAtomically(file, TrustStoreCodec.encode(next.snapshot()))
        memory = next
    }

    override fun toString() = "FileTrustStore(${file.name}, ${peerCount} 台手机)"

    companion object {
        const val FILE_NAME = "trust-store.bin"
        private const val TEMP_NAME = ".$FILE_NAME.tmp"
        private const val SET_ASIDE_PREFIX = "$FILE_NAME.bad-"

        /**
         * 打开 [dir] 里的信任库；目录不存在就新建，文件不存在就当空库。上次写到一半留下的临时文件直接删掉
         * （改名之前断电，原文件没动过）。
         *
         * @throws TrustStoreCorruptException 文件内容损坏或版本不认识，见 [setAside]。
         * @throws IOException 读不了（目录建不了、不是普通文件、读出错）。
         */
        @Throws(IOException::class)
        fun open(dir: File): FileTrustStore {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("建不了信任库目录 ${dir.path}")
            File(dir, TEMP_NAME).delete()
            val file = File(dir, FILE_NAME)
            if (!file.exists()) return FileTrustStore(file, TrustStoreSnapshot.EMPTY)
            if (!file.isFile) throw IOException("${file.path} 不是普通文件")
            val snapshot = try {
                TrustStoreCodec.decode(file.readBytes())
            } catch (e: MalformedMessageException) {
                throw TrustStoreCorruptException("信任库文件无法解析：${e.message}")
            }
            return FileTrustStore(file, snapshot)
        }

        /**
         * 用户确认清除时：把损坏的信任库文件改名留存（`trust-store.bin.bad-时间`，排查用），之后 [open] 得到空库。
         * 没有文件时返回 null。
         */
        @Throws(IOException::class)
        fun setAside(dir: File, wallClockMillis: Long): File? {
            val file = File(dir, FILE_NAME)
            if (!file.exists()) return null
            var target = File(dir, SET_ASIDE_PREFIX + wallClockMillis)
            var n = 1
            while (target.exists()) target = File(dir, SET_ASIDE_PREFIX + wallClockMillis + "-" + n++)
            if (!file.renameTo(target)) throw IOException("把 ${file.name} 改名为 ${target.name} 失败")
            return target
        }

        /**
         * 原子写：先写同目录下的临时文件并刷到磁盘，再改名覆盖目标（同一文件系统内的 rename 是原子的）。
         * 中途失败时目标文件保持原样，临时文件删掉。java.io 打不开目录，改名后没法再刷目录项，这一步省掉。
         *
         * 改名覆盖在安卓（rename(2)）上直接成功。Windows 的桌面 JVM 上 java.io 的改名不覆盖已存在的目标，
         * 第二次写会失败：这时把旧文件挪到旁边的 .old 再换，换不成把旧文件挪回去（旧内容不丢），成了删掉 .old。
         * 旧文件不是普通文件（例如被目录占着）时不挪，照旧报错。
         */
        @Throws(IOException::class)
        internal fun writeAtomically(target: File, bytes: ByteArray) {
            val dir = target.absoluteFile.parentFile ?: throw IOException("没有上级目录: ${target.path}")
            val temp = File(dir, ".${target.name}.tmp")
            try {
                FileOutputStream(temp).use { out ->
                    out.write(bytes)
                    out.flush()
                    out.fd.sync()
                }
                if (!temp.renameTo(target)) {
                    if (!target.exists()) throw IOException("把临时文件改名为 ${target.name} 失败")
                    if (!target.isFile) throw IOException("把临时文件改名为 ${target.name} 失败（目标不是普通文件）")
                    val backup = File(dir, ".${target.name}.old")
                    if (!target.renameTo(backup)) throw IOException("把临时文件改名为 ${target.name} 失败（旧文件挪不开）")
                    if (!temp.renameTo(target)) {
                        if (backup.renameTo(target)) throw IOException("把临时文件改名为 ${target.name} 失败（已恢复旧文件）")
                        throw IOException("把临时文件改名为 ${target.name} 失败，旧内容挪到了 ${backup.name}")
                    }
                    backup.delete()
                }
            } catch (e: IOException) {
                temp.delete()
                throw e
            }
        }
    }
}
