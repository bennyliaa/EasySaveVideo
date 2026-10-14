package com.easysavevideo.save

import com.easysavevideo.core.model.TaskMethod
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

enum class DirectNormalizationProfile { QT_MOV_AVC_BASELINE_AAC_LC_V1 }

/** RAW HTTP completion remains unchanged; this tuple digest binds its exact technical values. */
fun sourceTransferFingerprint(transfer: TransferResult): String {
    require(transfer.streamExpectation == null && transfer.recordingExpectation == null)
    return directDigest { out ->
        out.writeUTF("ESV-DIRECT-SOURCE-TRANSFER-1"); out.writeUTF(transfer.artifactId)
        out.writeLong(transfer.bytesOnDisk); out.writeUTF(transfer.responseMimeType)
        out.writeBoolean(transfer.expectedTotalBytes != null); transfer.expectedTotalBytes?.let(out::writeLong)
    }
}

/** All values are immutable and bounded; this is source/output evidence, not file movement authority. */
data class DirectNormalizationProof(
    val normalizationId: String,
    val taskId: String,
    val sourceIdentity: String,
    val artifactId: String,
    val commitId: String,
    val rawSizeBytes: Long,
    val rawSha256: String,
    val sourceTransferSha256: String,
    val outputSizeBytes: Long,
    val outputSha256: String,
    val conservation: DirectRemuxConservation,
    val schemaVersion: Int = 1,
    val profile: DirectNormalizationProfile = DirectNormalizationProfile.QT_MOV_AVC_BASELINE_AAC_LC_V1,
    val engineRevision: Int = 1,
    val policyRevision: Int = 1,
    val noEncoding: Boolean = true,
) {
    init {
        requireNormalizationId(normalizationId); requireSaveId(taskId); requireSaveId(artifactId); requireSaveId(commitId)
        requireDirectHash(sourceIdentity); requireDirectHash(rawSha256); requireDirectHash(sourceTransferSha256); requireDirectHash(outputSha256)
        require(rawSizeBytes in 1..DirectNormalizationLimits.MAX_BYTES && outputSizeBytes in 1..DirectNormalizationLimits.MAX_BYTES)
        require(schemaVersion == 1 && engineRevision == 1 && policyRevision == 1 && noEncoding)
        require(conservation.video.canonicalBytes + (conservation.audio?.canonicalBytes ?: 0) <= outputSizeBytes)
    }
    fun matchesSource(taskId: String, sourceIdentity: String, artifact: StagingArtifact, transfer: TransferResult): Boolean =
        this.taskId == taskId && this.sourceIdentity == sourceIdentity && artifactId == artifact.artifactId &&
            commitId == artifact.commitId && matchesTransfer(transfer)

    fun matchesTransfer(transfer: TransferResult): Boolean = transfer.artifactId == artifactId &&
        transfer.streamExpectation == null && transfer.recordingExpectation == null &&
        transfer.bytesOnDisk == rawSizeBytes &&
        (transfer.expectedTotalBytes == null || transfer.expectedTotalBytes == rawSizeBytes) &&
        sourceTransferFingerprint(transfer) == sourceTransferSha256

    /** Versioned length-delimited technical tuple; no toString, runtime hashCode or private metadata. */
    fun digest(): String = directDigest { out ->
        out.writeUTF("ESV-DIRECT-NORMALIZATION-PROOF-1"); out.writeInt(schemaVersion); out.writeUTF(profile.name)
        out.writeUTF(normalizationId); out.writeUTF(taskId); out.writeUTF(sourceIdentity)
        out.writeUTF(artifactId); out.writeUTF(commitId); out.writeLong(rawSizeBytes); out.writeUTF(rawSha256)
        out.writeUTF(sourceTransferSha256); out.writeLong(outputSizeBytes); out.writeUTF(outputSha256)
        out.writeInt(engineRevision); out.writeInt(policyRevision); out.writeBoolean(noEncoding)
        writeDirectConservationDigest(out, conservation)
    }
}

/** Verification independently rescans canonical output; a normalizer's own completion is insufficient. */
data class DirectNormalizationVerificationProof(
    val normalizationId: String,
    val taskId: String,
    val sourceIdentity: String,
    val artifactId: String,
    val commitId: String,
    val normalizationProofSha256: String,
    val conservation: DirectRemuxConservation,
    val videoTail: DirectVideoTailProof,
) {
    init {
        requireNormalizationId(normalizationId); requireSaveId(taskId); requireSaveId(artifactId); requireSaveId(commitId)
        requireDirectHash(sourceIdentity); requireDirectHash(normalizationProofSha256)
    }
    fun matches(expected: DirectNormalizationProof): Boolean = normalizationId == expected.normalizationId &&
        taskId == expected.taskId && sourceIdentity == expected.sourceIdentity && artifactId == expected.artifactId &&
        commitId == expected.commitId && normalizationProofSha256 == expected.digest() &&
        conservation == expected.conservation && videoTail.matches(expected.conservation)
}

/** Canonical verification input. A normalized descriptor never fabricates an HTTP TransferResult. */
sealed interface ArtifactOutputDescriptor {
    val sourceTransfer: TransferResult
    val sizeBytes: Long
    val sha256: String?
    val mimeType: String

    data class Transferred(val transfer: TransferResult) : ArtifactOutputDescriptor {
        override val sourceTransfer: TransferResult get() = transfer
        override val sizeBytes: Long get() = transfer.bytesOnDisk
        override val sha256: String? get() = null
        override val mimeType: String get() = transfer.responseMimeType
    }
    data class NormalizedDirect(
        override val sourceTransfer: TransferResult,
        val normalizationProof: DirectNormalizationProof,
    ) : ArtifactOutputDescriptor {
        init { require(normalizationProof.matchesTransfer(sourceTransfer)) }
        override val sizeBytes: Long get() = normalizationProof.outputSizeBytes
        override val sha256: String get() = normalizationProof.outputSha256
        override val mimeType: String get() = "video/mp4"
    }
}

/** All durable consumers must use this selection, including historical-read validation. */
fun SaveRecord.selectedOutputDescriptor(): ArtifactOutputDescriptor? {
    val completed = transfer ?: return null
    val proof = directNormalization ?: return ArtifactOutputDescriptor.Transferred(completed)
    require(request.method == TaskMethod.DIRECT && artifact != null && request.networkResource != null)
    require(proof.matchesSource(task.taskId, request.networkResource!!.sourceIdentity, artifact, completed))
    return ArtifactOutputDescriptor.NormalizedDirect(completed, proof)
}

private fun requireNormalizationId(value: String) { require(value.length == 36 && UUID.fromString(value).toString() == value) }
private fun directDigest(write: (DataOutputStream) -> Unit): String {
    val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use(write) }.toByteArray()
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
private fun writeDirectConservationDigest(out: DataOutputStream, c: DirectRemuxConservation) {
    out.writeInt(c.representationVersion); out.writeInt(c.width); out.writeInt(c.height); out.writeInt(c.frameRate)
    out.writeLong(c.sourceOriginOffsetUs); writeDirectTrackDigest(out, c.video)
    out.writeBoolean(c.audio != null); c.audio?.let { writeDirectTrackDigest(out, it) }
    out.writeBoolean(c.audioSampleRate != null); c.audioSampleRate?.let(out::writeInt)
    out.writeBoolean(c.audioChannelCount != null); c.audioChannelCount?.let(out::writeInt)
    writeDirectSampleDigest(out, c.precedingVideoSync)
}
private fun writeDirectTrackDigest(out: DataOutputStream, t: DirectTrackConservation) {
    out.writeInt(t.representationVersion); out.writeUTF(t.kind.name); out.writeUTF(t.timingRepresentation.name)
    out.writeUTF(t.codecConfigurationSha256); out.writeLong(t.sampleCount); out.writeLong(t.canonicalBytes)
    out.writeUTF(t.payloadSha256); out.writeUTF(t.sampleFlagsSha256); out.writeUTF(t.timingSha256)
    out.writeLong(t.firstPresentationTimeUs); writeDirectSampleDigest(out, t.lastSample)
    out.writeLong(t.endpoint.minimumUs); out.writeLong(t.endpoint.maximumUs)
}
private fun writeDirectSampleDigest(out: DataOutputStream, s: DirectSampleIdentity) {
    out.writeLong(s.index); out.writeLong(s.presentationTimeUs); out.writeInt(s.canonicalBytes); out.writeUTF(s.sha256); out.writeInt(s.flags)
}
