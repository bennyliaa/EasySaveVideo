package com.easysavevideo.app.save.storage

import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import com.easysavevideo.recording.*
import java.io.*

/** Versioned private functional state, never a diagnostics/backup format. No headers or request leases. */
internal object SaveRecordCodec {
    const val MAX_BYTES = 32 * 1024
    private const val MAGIC = 0x45535633
    private const val EVIDENCE_MAGIC = 0x45535645
    private const val VERSION = 7
    fun encode(record: SaveRecord): ByteArray {
        require(record.schemaVersion == 1 && record.request.networkResource?.let {
            it.requestContextRef == null && it.durableReference && it.url == it.url.originOnly()
        } != false)
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { o ->
            o.writeInt(MAGIC); o.writeInt(VERSION)
            val r = record.request; val t = record.task
            o.writeUTF(r.requestId); o.writeUTF(r.idempotencyKey); o.writeUTF(r.method.name); o.writeUTF(r.origin.name)
            when (val input = r.input) {
                is SaveInput.Network -> {
                    o.writeUTF("NETWORK"); val s = input.resource
                    o.writeUTF(s.candidateId); o.writeUTF(s.variantId); o.writeUTF(s.url.value); o.writeBoolean(s.expectedAudio)
                    o.nullable(s.expiresAtEpochMillis) { o.writeLong(it) }
                    o.writeUTF(s.sourceIdentity); o.writeBoolean(s.durableReference)
                }
                is SaveInput.Capture -> { o.writeUTF("CAPTURE"); writeDescriptor(o, input.descriptor) }
            }
            o.writeUTF(t.taskId); o.writeLong(t.revision); o.writeUTF(t.state.name); o.writeUTF(t.method.name)
            o.nullable(t.progressPercent) { o.writeInt(it) }; o.nullable(t.errorCode) { o.writeUTF(it.name) }
            o.nullable(t.waitingUser) { o.writeLong(it.expiresAtEpochMillis); o.writeUTF(it.resumeAction.name) }
            o.writeUTF(record.commitId); o.writeUTF(record.operationId); o.writeLong(record.deadlineEpochMillis)
            o.writeInt(record.retryBudget); o.writeInt(record.attempt); o.nullable(record.phase) { o.writeUTF(it.name) }
            o.nullable(record.artifact) { o.writeUTF(it.artifactId); o.writeUTF(it.commitId) }
            o.nullable(record.transfer) {
                o.writeUTF(it.artifactId); o.writeLong(it.bytesOnDisk); o.writeUTF(it.responseMimeType)
                o.nullable(it.expectedTotalBytes) { value -> o.writeLong(value) }
                o.nullable(it.streamExpectation) { e ->
                    o.writeLong(e.durationMillis); o.writeInt(e.videoTrackCount); o.writeInt(e.audioTrackCount)
                    o.writeInt(e.width); o.writeInt(e.height)
                    o.nullable(e.conservation) { proof -> writeConservation(o, proof) }
                }
                o.nullable(it.recordingExpectation) { e -> writeRecordingExpectation(o, e) }
            }
            o.nullable(record.verification) { evidenceBytes(it); writeEvidence(o, it) }
            o.nullable(record.receipt) { writeReceipt(o, it) }
            o.writeLong(record.bytesTransferred)
            o.nullable(record.checkpoint) {
                o.writeUTF(it.artifactId); o.writeUTF(it.resourceIdentity); o.writeLong(it.offset)
                o.writeUTF(it.strongEtag); o.writeLong(it.totalBytes)
            }
            o.nullable(record.waitReason) { o.writeUTF(it.name) }; o.nullable(record.error) { o.writeUTF(it.name) }
            o.nullable(record.directNormalization) { writeDirectNormalization(o, it) }
        }
        return buffer.toByteArray().also { require(it.size <= MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): SaveRecord {
        require(bytes.size in 8..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            require(i.readInt() == MAGIC)
            val version = i.readInt(); require(version in 1..VERSION)
            val requestId = i.readUTF(); val key = i.readUTF(); val method = TaskMethod.valueOf(i.readUTF())
            val origin = CommandOrigin.valueOf(i.readUTF())
            val discriminator = if (version >= 4) i.readUTF() else "NETWORK"
            val input = when (discriminator) {
                "NETWORK" -> SaveInput.Network(SelectedMediaResource(i.readUTF(), i.readUTF(), SourceUrl(i.readUTF()), i.readBoolean(), null,
                    i.nullable { i.readLong() }, i.readUTF(), i.readBoolean()))
                "CAPTURE" -> SaveInput.Capture(readDescriptor(i, version >= 6))
                else -> throw IllegalArgumentException("Unknown save input")
            }
            val task = TaskSnapshot(i.readUTF(), i.readLong(), TaskState.valueOf(i.readUTF()), TaskMethod.valueOf(i.readUTF()),
                i.nullable { i.readInt() }, i.nullable { ErrorCode.valueOf(i.readUTF()) },
                i.nullable { UserWait(i.readLong(), ResumeAction.valueOf(i.readUTF())) })
            val commit = i.readUTF(); val operation = i.readUTF(); val deadline = i.readLong()
            val retry = i.readInt(); val attempt = i.readInt(); val phase = i.nullable { SavePhase.valueOf(i.readUTF()) }
            val artifact = i.nullable { StagingArtifact(i.readUTF(), i.readUTF()) }
            val transfer = i.nullable {
                TransferResult(i.readUTF(), i.readLong(), i.readUTF(), i.nullable { i.readLong() },
                    if (version >= 2) i.nullable { StreamArtifactExpectation(i.readLong(), i.readInt(), i.readInt(), i.readInt(), i.readInt(),
                        if (version >= 3) i.nullable { readConservation(i) } else null) } else null,
                    if (version >= 4) i.nullable { readRecordingExpectation(i, version >= 5, version >= 6) } else null)
            }
            val verification = i.nullable { readEvidence(i, version >= 3, version >= 4, version >= 5, version >= 7) }; val receipt = i.nullable { readReceipt(i) }
            val count = i.readLong()
            val checkpoint = i.nullable { DownloadCheckpoint(i.readUTF(), i.readUTF(), i.readLong(), i.readUTF(), i.readLong()) }
            val wait = i.nullable { SaveWaitReason.valueOf(i.readUTF()) }; val error = i.nullable { SaveError.valueOf(i.readUTF()) }
            val directNormalization = if (version >= 7) i.nullable { readDirectNormalization(i) } else null
            require(i.available() == 0)
            SaveRecord(SaveRequest(requestId, key, input, method, origin), task, commit, operation, deadline, retry,
                attempt, phase, artifact, transfer, verification, receipt, count, checkpoint, wait, error,
                directNormalization = directNormalization)
        }
    }
    internal fun evidenceBytes(evidence: VerificationEvidence): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { o -> o.writeInt(EVIDENCE_MAGIC); o.writeInt(4); writeEvidence(o, evidence) }
    }.toByteArray().also { require(it.size <= 4096) }
    internal fun evidence(bytes: ByteArray): VerificationEvidence {
        require(bytes.size in 1..4096)
        return DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            i.mark(8)
            val versioned = bytes.size >= 8 && i.readInt() == EVIDENCE_MAGIC
            val version = if (versioned) i.readInt().also { require(it in 1..4) } else { i.reset(); 0 }
            readEvidence(i, version >= 1, version >= 2, version >= 3, version >= 4).also { require(i.available() == 0) }
        }
    }
    private fun writeEvidence(o: DataOutputStream, e: VerificationEvidence) {
        requireEvidenceDiscriminator(e)
        o.writeUTF(e.artifactId); o.writeLong(e.sizeBytes); o.writeUTF(e.sha256); o.writeBoolean(e.containerIsMp4)
        o.writeInt(e.videoTrackCount); o.writeInt(e.audioTrackCount); o.writeLong(e.durationMillis)
        o.writeInt(e.width); o.writeInt(e.height); o.writeBoolean(e.encrypted)
        o.writeInt(e.decodedPositions.fold(0) { flags, p -> flags or (1 shl p.ordinal) }); o.writeBoolean(e.audioTrackReadable)
        o.nullable(e.streamConservation) { writeConservation(o, it) }
        o.nullable(e.streamTailProof) { proof ->
            writeSample(o, proof.precedingSync); writeSample(o, proof.lastSample)
            o.writeLong(proof.terminalOutputTimeUs); o.writeBoolean(proof.outputEos)
        }
        o.nullable(e.recordingProof) { writeRecordingProof(o, it) }
        o.nullable(e.directNormalizationProof) { writeDirectVerification(o, it) }
    }
    private fun readEvidence(i: DataInputStream, includeStream: Boolean, includeRecording: Boolean, includeResumeBoundaries: Boolean,
        includeDirect: Boolean): VerificationEvidence {
        val before = i.available()
        val id = i.readUTF(); val size = i.readLong(); val sha = i.readUTF(); val mp4 = i.readBoolean()
        require(id.matches(Regex("[A-Za-z0-9_.-]{1,128}")) && size > 0 && sha.matches(Regex("[a-f0-9]{64}")))
        val video = i.readInt(); val audio = i.readInt(); val duration = i.readLong(); val width = i.readInt(); val height = i.readInt()
        require(video > 0 && audio >= 0 && duration > 0 && width > 0 && height > 0)
        val encrypted = i.readBoolean(); val flags = i.readInt(); require(flags and 7 == flags)
        val audioReadable = i.readBoolean()
        return VerificationEvidence(id, size, sha, mp4, video, audio, duration, width, height, encrypted,
            DecodePosition.entries.filter { flags and (1 shl it.ordinal) != 0 }.toSet(), audioReadable,
            if (includeStream) i.nullable { readConservation(i) } else null,
            if (includeStream) i.nullable { StreamTailProof(readSample(i), readSample(i), i.readLong(), i.readBoolean()) } else null,
            if (includeRecording) i.nullable { readRecordingProof(i, includeResumeBoundaries) } else null,
            if (includeDirect) i.nullable { readDirectVerification(i) } else null).also {
                require(before - i.available() + 8 <= 4096)
                requireEvidenceDiscriminator(it)
            }
    }
    private fun requireEvidenceDiscriminator(e: VerificationEvidence) {
        val stream = e.streamConservation != null || e.streamTailProof != null
        require(listOf(stream, e.recordingProof != null, e.directNormalizationProof != null).count { it } <= 1)
    }
    private fun writeProfile(o: DataOutputStream, p: RecordingProfile) {
        o.writeInt(p.width); o.writeInt(p.height); o.writeInt(p.frameRate); o.writeInt(p.videoBitrate)
        o.writeInt(p.audioSampleRate); o.writeInt(p.audioChannels); o.writeInt(p.audioBitrate)
    }
    private fun readProfile(i: DataInputStream) = RecordingProfile(i.readInt(), i.readInt(), i.readInt(), i.readInt(),
        i.readInt(), i.readInt(), i.readInt())
    private fun writeDescriptor(o: DataOutputStream, d: CaptureDescriptor) {
        o.writeUTF(d.sessionId); o.writeLong(d.generation); o.writeUTF(d.audioChoice.name); writeProfile(o, d.requestedProfile)
        o.nullable(d.regionCaptureRecipe) { writeRegion(o, it) }
    }
    private fun readDescriptor(i: DataInputStream, includeRegion: Boolean) = CaptureDescriptor(i.readUTF(), i.readLong(),
        RecordingAudioChoice.valueOf(i.readUTF()), readProfile(i), if (includeRegion) i.nullable { readRegion(i) } else null)
    private fun writeRegion(o: DataOutputStream, r: RegionCaptureRecipe) {
        with(r.bounds) { o.writeDouble(left); o.writeDouble(top); o.writeDouble(right); o.writeDouble(bottom) }
        with(r.source) {
            o.writeInt(width); o.writeInt(height); o.writeInt(rotationDegrees)
            with(insets) { o.writeInt(left); o.writeInt(top); o.writeInt(right); o.writeInt(bottom) }
            o.writeInt(transformVersion)
        }
        o.writeUTF(r.scopeEvidence.name); o.writeLong(r.selectedAtMonotonicUs)
    }
    private fun readRegion(i: DataInputStream) = RegionCaptureRecipe(
        RegionNormalizedRect(i.readDouble(), i.readDouble(), i.readDouble(), i.readDouble()),
        CaptureDisplayGeometry(i.readInt(), i.readInt(), i.readInt(), CaptureInsets(i.readInt(), i.readInt(), i.readInt(), i.readInt()), i.readInt()),
        CaptureScopeEvidence.valueOf(i.readUTF()), i.readLong())
    private fun writeTrack(o: DataOutputStream, t: RecordingTrackEvidence) {
        o.writeLong(t.sampleCount); o.writeLong(t.firstPtsUs); o.writeLong(t.lastPtsUs); o.writeLong(t.lastDurationUs)
        o.writeLong(t.syncSampleCount); o.writeBoolean(t.firstSampleIsKeyFrame)
    }
    private fun readTrack(i: DataInputStream) = RecordingTrackEvidence(i.readLong(), i.readLong(), i.readLong(), i.readLong(),
        i.readLong(), i.readBoolean())
    private fun writeRecordingExpectation(o: DataOutputStream, e: RecordingArtifactExpectation) {
        writeDescriptor(o, e.descriptor); writeProfile(o, e.actualProfile)
        o.writeUTF(e.audioOutcome.name); o.writeUTF(e.stopReason.name); writeTrack(o, e.video)
        o.nullable(e.audio) { writeTrack(o, it) }; o.writeLong(e.effectiveDurationUs); o.writeLong(e.bytesWritten)
        o.writeBoolean(e.finalized); o.writeBoolean(e.resourcesReleased)
        writeResumeBoundaries(o, e.resumeBoundaries)
    }
    private fun readRecordingExpectation(i: DataInputStream, includeResumeBoundaries: Boolean, includeRegion: Boolean) = RecordingArtifactExpectation(readDescriptor(i, includeRegion), readProfile(i),
        RecordingAudioOutcome.valueOf(i.readUTF()), RecordingStopReason.valueOf(i.readUTF()), readTrack(i),
        i.nullable { readTrack(i) }, i.readLong(), i.readLong(), i.readBoolean(), i.readBoolean(),
        if (includeResumeBoundaries) readResumeBoundaries(i) else null)
    private fun writeRecordingProof(o: DataOutputStream, p: RecordingVerificationProof) {
        writeTrack(o, p.video); o.nullable(p.audio) { writeTrack(o, it) }; o.writeUTF(p.videoMimeType)
        o.nullable(p.audioMimeType) { o.writeUTF(it) }; o.nullable(p.audioSampleRate) { o.writeInt(it) }
        o.nullable(p.audioChannelCount) { o.writeInt(it) }; o.writeLong(p.terminalVideoOutputTimeUs); o.writeBoolean(p.videoOutputEos)
        writeResumeBoundaries(o, p.decodedResumeBoundaries)
    }
    private fun readRecordingProof(i: DataInputStream, includeResumeBoundaries: Boolean) = RecordingVerificationProof(readTrack(i), i.nullable { readTrack(i) },
        i.readUTF(), i.nullable { i.readUTF() }, i.nullable { i.readInt() }, i.nullable { i.readInt() }, i.readLong(), i.readBoolean(),
        if (includeResumeBoundaries) readResumeBoundaries(i) else null)
    private fun writeResumeBoundaries(o: DataOutputStream, boundaries: List<RecordingResumeBoundary>?) {
        o.nullable(boundaries) { values ->
            o.writeInt(values.size)
            values.forEach { o.writeLong(it.sampleIndex); o.writeLong(it.ptsUs) }
        }
    }
    private fun readResumeBoundaries(i: DataInputStream): List<RecordingResumeBoundary>? = i.nullable {
        val count = i.readInt(); require(count in 0..MAX_RECORDING_RESUME_BOUNDARIES)
        List(count) { RecordingResumeBoundary(i.readLong(), i.readLong()) }
    }
    private fun writeConservation(o: DataOutputStream, c: StreamConservationContract) {
        writeSequence(o, c.video); o.nullable(c.audio) { writeSequence(o, it) }
        o.nullable(c.audioStartOffsetUs) { o.writeLong(it) }
        writeSample(o, c.videoTail.lastSample); writeSample(o, c.videoTail.precedingSync)
        o.writeUTF(c.endpoint.rule.name); o.writeLong(c.endpoint.minimumUs); o.writeLong(c.endpoint.maximumUs)
        o.nullable(c.audioChannelCount) { o.writeInt(it) }
    }
    private fun readConservation(i: DataInputStream): StreamConservationContract = StreamConservationContract(
        readSequence(i), i.nullable { readSequence(i) }, i.nullable { i.readLong() },
        StreamVideoTail(readSample(i), readSample(i)),
        StreamEndpoint(StreamEndpointRule.valueOf(i.readUTF()), i.readLong(), i.readLong()), i.nullable { i.readInt() })
    private fun writeSequence(o: DataOutputStream, s: StreamSampleSequence) {
        o.writeUTF(s.kind.name); o.writeInt(s.timescale); o.writeLong(s.sampleCount)
        o.writeLong(s.canonicalBytes); o.writeUTF(s.sequenceSha256); writeSample(o, s.lastSample)
    }
    private fun readSequence(i: DataInputStream): StreamSampleSequence = StreamSampleSequence(
        StreamSampleKind.valueOf(i.readUTF()), i.readInt(), i.readLong(), i.readLong(), i.readUTF(), readSample(i))
    private fun writeSample(o: DataOutputStream, s: StreamSampleIdentity) {
        o.writeLong(s.index); o.writeLong(s.presentationTimeUs); o.writeInt(s.canonicalBytes); o.writeUTF(s.sha256); o.writeInt(s.flags)
    }
    private fun readSample(i: DataInputStream): StreamSampleIdentity = StreamSampleIdentity(
        i.readLong(), i.readLong(), i.readInt(), i.readUTF(), i.readInt())
    private fun writeDirectNormalization(o: DataOutputStream, p: DirectNormalizationProof) {
        o.writeInt(p.schemaVersion); o.writeUTF(p.profile.name)
        o.writeUTF(p.normalizationId); o.writeUTF(p.taskId); o.writeUTF(p.sourceIdentity)
        o.writeUTF(p.artifactId); o.writeUTF(p.commitId)
        o.writeLong(p.rawSizeBytes); o.writeUTF(p.rawSha256); o.writeUTF(p.sourceTransferSha256)
        o.writeLong(p.outputSizeBytes); o.writeUTF(p.outputSha256)
        o.writeInt(p.engineRevision); o.writeInt(p.policyRevision); o.writeBoolean(p.noEncoding)
        writeDirectConservation(o, p.conservation)
    }
    private fun readDirectNormalization(i: DataInputStream): DirectNormalizationProof {
        val version = i.readInt(); require(version == 1)
        val profile = DirectNormalizationProfile.valueOf(i.readUTF())
        val id = i.readUTF(); val task = i.readUTF(); val source = i.readUTF(); val artifact = i.readUTF(); val commit = i.readUTF()
        val rawSize = i.readLong(); val rawHash = i.readUTF(); val transferHash = i.readUTF()
        val size = i.readLong(); val hash = i.readUTF(); val engine = i.readInt(); val policy = i.readInt(); val noEncoding = i.readStrictBoolean()
        return DirectNormalizationProof(id, task, source, artifact, commit, rawSize, rawHash, transferHash, size, hash,
            readDirectConservation(i), version, profile, engine, policy, noEncoding)
    }
    private fun writeDirectVerification(o: DataOutputStream, p: DirectNormalizationVerificationProof) {
        o.writeUTF(p.normalizationId); o.writeUTF(p.taskId); o.writeUTF(p.sourceIdentity)
        o.writeUTF(p.artifactId); o.writeUTF(p.commitId); o.writeUTF(p.normalizationProofSha256)
        writeDirectConservation(o, p.conservation)
        writeDirectSample(o, p.videoTail.precedingSync); writeDirectSample(o, p.videoTail.lastSample)
        o.writeLong(p.videoTail.terminalOutputTimeUs); o.writeBoolean(p.videoTail.outputEos)
    }
    private fun readDirectVerification(i: DataInputStream) = DirectNormalizationVerificationProof(
        i.readUTF(), i.readUTF(), i.readUTF(), i.readUTF(), i.readUTF(), i.readUTF(), readDirectConservation(i),
        DirectVideoTailProof(readDirectSample(i), readDirectSample(i), i.readLong(), i.readStrictBoolean()))
    private fun writeDirectConservation(o: DataOutputStream, c: DirectRemuxConservation) {
        o.writeInt(c.representationVersion); o.writeInt(c.width); o.writeInt(c.height); o.writeInt(c.frameRate)
        o.writeLong(c.sourceOriginOffsetUs); writeDirectTrack(o, c.video)
        o.nullable(c.audio) { writeDirectTrack(o, it) }; o.nullable(c.audioSampleRate) { o.writeInt(it) }
        o.nullable(c.audioChannelCount) { o.writeInt(it) }; writeDirectSample(o, c.precedingVideoSync)
    }
    private fun readDirectConservation(i: DataInputStream): DirectRemuxConservation {
        val version = i.readInt(); require(version == 1)
        val width = i.readInt(); val height = i.readInt(); val rate = i.readInt(); val origin = i.readLong()
        return DirectRemuxConservation(width, height, rate, readDirectTrack(i), i.nullable { readDirectTrack(i) },
            i.nullable { i.readInt() }, i.nullable { i.readInt() }, readDirectSample(i), origin, version)
    }
    private fun writeDirectTrack(o: DataOutputStream, t: DirectTrackConservation) {
        o.writeInt(t.representationVersion); o.writeUTF(t.kind.name); o.writeUTF(t.timingRepresentation.name)
        o.writeUTF(t.codecConfigurationSha256); o.writeLong(t.sampleCount); o.writeLong(t.canonicalBytes)
        o.writeUTF(t.payloadSha256); o.writeUTF(t.sampleFlagsSha256); o.writeUTF(t.timingSha256)
        o.writeLong(t.firstPresentationTimeUs); writeDirectSample(o, t.lastSample)
        o.writeLong(t.endpoint.minimumUs); o.writeLong(t.endpoint.maximumUs)
    }
    private fun readDirectTrack(i: DataInputStream): DirectTrackConservation {
        val version = i.readInt(); require(version == 1)
        val kind = DirectSampleKind.valueOf(i.readUTF()); val timing = DirectTimingRepresentation.valueOf(i.readUTF())
        return DirectTrackConservation(kind, i.readUTF(), i.readLong(), i.readLong(), i.readUTF(), i.readUTF(), i.readUTF(),
            i.readLong(), readDirectSample(i), DirectTrackEndpoint(i.readLong(), i.readLong()), version, timing)
    }
    private fun writeDirectSample(o: DataOutputStream, p: DirectSampleIdentity) {
        o.writeLong(p.index); o.writeLong(p.presentationTimeUs); o.writeInt(p.canonicalBytes); o.writeUTF(p.sha256); o.writeInt(p.flags)
    }
    private fun readDirectSample(i: DataInputStream) = DirectSampleIdentity(i.readLong(), i.readLong(), i.readInt(), i.readUTF(), i.readInt())
    private fun writeReceipt(o: DataOutputStream, r: MediaCommitReceipt) {
        o.writeUTF(r.commitId); o.writeUTF(r.artifactId); o.writeUTF(r.mediaId); o.writeLong(r.sizeBytes); o.writeUTF(r.sha256)
    }
    private fun readReceipt(i: DataInputStream) = MediaCommitReceipt(i.readUTF(), i.readUTF(), i.readUTF(), i.readLong(), i.readUTF())
    private inline fun <T> DataOutputStream.nullable(value: T?, write: (T) -> Unit) { writeBoolean(value != null); if (value != null) write(value) }
    private fun DataInputStream.readStrictBoolean(): Boolean = when (readUnsignedByte()) {
        0 -> false; 1 -> true; else -> throw IllegalArgumentException("Invalid boolean")
    }
    private inline fun <T> DataInputStream.nullable(read: () -> T): T? = if (readStrictBoolean()) read() else null
}
