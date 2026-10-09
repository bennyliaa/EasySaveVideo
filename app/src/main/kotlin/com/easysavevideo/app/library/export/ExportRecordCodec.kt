package com.easysavevideo.app.library.export

import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Bounded private journal encoding, independent from retained Save proof/codec bytes. */
internal object ExportRecordCodec {
    const val MAX_BYTES = 4_096
    fun encode(record: ExportRecord): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(1)
            with(record.request) {
                out.writeUTF(operationId); out.writeUTF(source.itemId); out.writeLong(source.revision); out.writeLong(source.representationGeneration)
                out.writeUTF(destination.kind.name); out.writeUTF(destination.grantId)
            }
            out.writeUTF(record.phase.name)
            out.writeBoolean(record.output != null); record.output?.let { out.writeUTF(it.outputId); out.writeUTF(it.kind.name) }
            out.writeBoolean(record.evidence != null); record.evidence?.let { out.writeLong(it.bytes); out.writeUTF(it.sha256) }
            out.writeBoolean(record.cancelRequested); out.writeUTF(record.verification.name); out.writeUTF(record.cleanup.name); out.writeUTF(record.error.name)
        }
    }.toByteArray().also { require(it.size <= MAX_BYTES) }
    fun decode(bytes: ByteArray): ExportRecord {
        require(bytes.size in 8..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 1)
            val request = ExportRequest(input.readUTF(), MediaReadRef(input.readUTF(), input.readLong(), input.readLong()),
                ExportDestination(ExportKind.valueOf(input.readUTF()), input.readUTF()))
            val phase = ExportPhase.valueOf(input.readUTF())
            val output = if (input.readBoolean()) ExportOutput(input.readUTF(), ExportKind.valueOf(input.readUTF())) else null
            val evidence = if (input.readBoolean()) ExportEvidence(input.readLong(), input.readUTF()) else null
            ExportRecord(request, phase, output, evidence, input.readBoolean(), ExportVerification.valueOf(input.readUTF()),
                ExportCleanup.valueOf(input.readUTF()), ExportError.valueOf(input.readUTF())).also { require(input.read() == -1) }
        }
    }
}
