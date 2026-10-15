package com.easysavevideo.app.library.storage

import com.easysavevideo.app.save.storage.SaveRecordCodec
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.save.SaveRecord
import com.easysavevideo.save.selectedOutputDescriptor
import com.easysavevideo.save.hasDirectOutputProof

/** Pure validation shared by migration and new publication; it never invents verification evidence. */
internal object LibraryPublicationEvidence {
    fun decode(bytes: ByteArray, legacy: Boolean): SaveRecord {
        require(bytes.size in 8..SaveRecordCodec.MAX_BYTES)
        return SaveRecordCodec.decode(bytes).also { requireValid(it, legacy) }
    }

    fun requireValid(record: SaveRecord, legacy: Boolean) {
        require(record.task.state == TaskState.SUCCEEDED)
        val artifact = requireNotNull(record.artifact)
        val output = requireNotNull(record.selectedOutputDescriptor())
        val evidence = requireNotNull(record.verification)
        val receipt = requireNotNull(record.receipt)
        // SaveRecord's constructor checks historical method-specific evidence. Legacy records keep
        // their actual proof unchanged; unknown old HLS/Record fields are not new capture evidence.
        if (!legacy || record.hasDirectOutputProof()) require(evidence.isValidFor(artifact, output, record.request.networkSourceMetadata?.expectedAudio ?: false))
        require(receipt.artifactId == artifact.artifactId && receipt.commitId == record.commitId)
        require(receipt.sizeBytes == evidence.sizeBytes && receipt.sha256 == evidence.sha256)
    }
}
