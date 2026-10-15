package com.easysavevideo.app.browser.privacy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executor

/** Small bounded opaque state only; no browsing identifiers and no backup-eligible storage. */
internal class PrivateProfileMarkerStore(context: Context, private val worker: Executor) : PrivateProfileJournal {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "private-browser-profiles-v1"))
    private val main = Handler(Looper.getMainLooper())
    override fun read(done: (List<PrivateProfileMarker>?) -> Unit) = submit({
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) emptyList()
        else file.openRead().use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(1024)
            while (true) {
                val n = input.read(chunk)
                if (n == -1) break
                check(n > 0 && buffer.size() + n <= PrivateProfileMarkerCodec.MAX_BYTES)
                buffer.write(chunk, 0, n)
            }
            PrivateProfileMarkerCodec.decode(buffer.toByteArray())
        }
    }, done)
    override fun write(markers: List<PrivateProfileMarker>, done: (Boolean) -> Unit) {
        val immutable = markers.toList()
        submit({
            val bytes = PrivateProfileMarkerCodec.encode(immutable)
            val stream = file.startWrite()
            try {
                stream.write(bytes); stream.fd.sync(); file.finishWrite(stream)
                // AtomicFile can log a failed rename rather than throw: confirm the committed bytes.
                check(file.openRead().use { raw ->
                    val input = DataInputStream(raw); val actual = ByteArray(bytes.size)
                    input.readFully(actual); input.read() == -1 && actual.contentEquals(bytes)
                })
                val directory = Os.open(file.baseFile.parent!!, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
                try {
                    check(OsConstants.S_ISDIR(Os.fstat(directory).st_mode))
                    Os.fsync(directory)
                } finally { Os.close(directory) }
                true
            }
            catch (failure: Exception) { file.failWrite(stream); throw failure }
        }) { done(it == true) }
    }
    private fun <T> submit(work: () -> T, done: (T?) -> Unit) {
        try { worker.execute {
            val value = try { work() } catch (_: Exception) { null }
            main.post { done(value) }
        } } catch (_: Exception) { main.post { done(null) } }
    }
}

internal object PrivateProfileMarkerCodec {
    const val MAX_BYTES = 8192
    private const val MAGIC = 0x45535031
    fun encode(markers: List<PrivateProfileMarker>): ByteArray {
        require(markers.size <= PrivateBrowsingPolicy.MAX_MARKERS)
        require(markers.map { it.name }.toSet().size == markers.size)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeInt(MAGIC); out.writeInt(markers.size)
            markers.forEach { out.writeUTF(it.name.value); out.writeByte(it.phase.ordinal) }
        }
        return output.toByteArray().also { check(it.size <= MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): List<PrivateProfileMarker> {
        require(bytes.size <= MAX_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC)
            val count = input.readInt(); require(count in 0..PrivateBrowsingPolicy.MAX_MARKERS)
            val markers = List(count) {
                val name = requireNotNull(PrivateProfileName.parse(input.readUTF()))
                val phase = PrivateMarkerPhase.entries.getOrNull(input.readUnsignedByte())
                PrivateProfileMarker(name, requireNotNull(phase))
            }
            require(input.available() == 0)
            require(markers.map { it.name }.toSet().size == markers.size)
            return markers
        }
    }
}
