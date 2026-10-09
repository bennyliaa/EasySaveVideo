package android.util
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileNotFoundException
/** Original deterministic test double for contract failures; not native Android acceptance. */
class AtomicFile(val baseFile: File) {
    private val pending get() = File(baseFile.path + ".new")
    fun openRead(): FileInputStream {
        reads++
        if (denyReads) throw FileNotFoundException("injected")
        val backup = File(baseFile.path + ".bak")
        if (backup.exists()) { check(backup.renameTo(baseFile)) }
        if (pending.exists() && baseFile.exists()) pending.delete()
        return object : FileInputStream(baseFile) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (zeroRead) return 0
                return super.read(b, off, len).also { if (it > 0) bytesRead += it }
            }
        }
    }
    fun startWrite(): FileOutputStream {
        writes++
        if (denyWrites) throw java.io.IOException("injected")
        baseFile.parentFile.mkdirs()
        return FileOutputStream(pending)
    }
    fun finishWrite(stream: FileOutputStream) {
        stream.close()
        if (silentRenameFailure) return
        check(pending.renameTo(baseFile))
        if (corruptCommit) baseFile.appendBytes(byteArrayOf(0))
    }
    fun failWrite(stream: FileOutputStream) { failures++; runCatching { stream.close() }; pending.delete() }
    companion object {
        var denyReads = false; var denyWrites = false; var silentRenameFailure = false
        var corruptCommit = false; var zeroRead = false
        var reads = 0; var writes = 0; var failures = 0; var bytesRead = 0
        fun reset() { denyReads=false; denyWrites=false; silentRenameFailure=false; corruptCommit=false; zeroRead=false; reads=0; writes=0; failures=0; bytesRead=0 }
    }
}
