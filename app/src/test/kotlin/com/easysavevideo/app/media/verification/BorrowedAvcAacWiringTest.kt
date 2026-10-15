package com.easysavevideo.app.media.verification

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Reachability/ownership checks only, never proof that an Android codec or extractor succeeded. */
class BorrowedAvcAacWiringTest {
    private fun media(name: String) = File("src/main/kotlin/com/easysavevideo/app/media/verification/$name.kt").readText()
    private fun editor(name: String) = File("src/main/kotlin/com/easysavevideo/app/editor/verification/$name.kt").readText()

    @Test fun productionEditorCallsBorrowedCoreAndKeepsItsOnlyExistingLaneAndGate() {
        val text = editor("AndroidEditOutputVerifier")
        assertTrue(text.contains("val owner = EditVerificationReadOwner(openOwnedRead)"))
        assertTrue(text.contains("owner.inspect(output, expected) { checkpoint(context, deadline) }"))
        assertTrue(text.contains("= { BorrowedAvcAacInspection(it) }"))
        assertEquals(1, Regex("nativeLane.submit<EditVerificationEvidence>").findAll(text).count())
        assertEquals(1, Regex("private val nativeGate = EditVerificationNativeGate\\(\\)").findAll(text).count())
        assertFalse(text.contains("MediaExtractor()")); assertFalse(text.contains("MediaCodec.createDecoderByType"))
        val core = media("BorrowedAvcAacInspection")
        assertTrue(core.contains("AndroidAvcAacOutputInspection::inspect"))
        assertTrue(core.contains("finally { resources.releaseAll() }"))
        assertTrue(core.contains("val nativeReleaseConfirmed: Boolean get() = resources.nativeReleaseConfirmed"))
    }

    @Test fun borrowedCoreCannotOpenOrCloseCallerReadOrEnterAnotherLane() {
        val names = listOf("BorrowedAvcAacInspection", "AndroidAvcAacOutputInspection", "AvcAacOutputExpectation",
            "AvcAacVerificationPolicy", "AvcAacMp4Probe")
        names.forEach { name ->
            val text = media(name)
            listOf("FileInputStream(", "FileOutputStream(", "RandomAccessFile(", ".close(",
                "Thread(", "ThreadPoolExecutor", "Executors.", "nativeGate", "library.edit", "com.easysavevideo.save").forEach {
                assertFalse("$name contains forbidden owned-read/product/lane operation $it", text.contains(it))
            }
        }
        val android = media("AndroidAvcAacOutputInspection")
        assertTrue(android.contains("value.setDataSource(read.originalFd, 0, size)"))
        assertTrue(android.contains("AvcAacMp4Probe.read(read.channel, size, expected, checkpoint)"))
        assertTrue(android.contains("resources.add(value, release = value::release)"))
        assertTrue(android.contains("resources.add(it, stop = it::stop, release = it::release)"))
    }

    @Test fun finalHashUsesSameBorrowedReadAfterNativeReleaseAndEvidenceWaitsForSoleEditorClose() {
        val text = editor("AndroidEditOutputVerifier")
        val owner = text.substringAfter("internal class EditVerificationReadOwner(")
        assertTrue(owner.contains("BorrowedAvcAacRead(owned.input.fd, channel, size)"))
        assertTrue(owner.contains("val channel = owned.input.channel"))
        assertTrue(owner.indexOf("native.inspect(expected.inspectionExpectation(), ::check)") <
            owner.indexOf("if (!native.nativeReleaseConfirmed)"))
        assertTrue(owner.indexOf("if (!native.nativeReleaseConfirmed)") < owner.indexOf("borrowed.requireUnchanged(initialHash, ::check)"))
        assertTrue(owner.indexOf("borrowed.requireUnchanged(initialHash, ::check)") < owner.indexOf("EditVerificationEvidence("))
        assertEquals(1, Regex("read\\?\\.closeAfterNativeRelease\\(\\)").findAll(owner).count())
        assertTrue(owner.indexOf("releaseConfirmed = inspection?.releaseNative() ?: true") < owner.indexOf("read?.closeAfterNativeRelease()"))
        assertTrue(owner.contains("if (!releaseConfirmed) try { read?.retainForProcessRecovery() }"))
        val worker = text.substringAfter("future = nativeLane.submit<EditVerificationEvidence>").substringBefore("catch (_: RejectedExecutionException)")
        assertTrue(worker.indexOf("confirmed = owner.releaseAll()") < worker.indexOf("evidence.copy(nativeReleaseConfirmed = true)"))
        assertTrue(worker.indexOf("if (!confirmed) throw EditFailure(EditError.RELEASE_UNCONFIRMED)") <
            worker.indexOf("evidence.copy(nativeReleaseConfirmed = true)"))
        assertTrue(worker.contains("if (!owner.cleanupFinished) confirmed = owner.releaseAll()"))
        assertTrue(worker.contains("nativeGate.finish(owner, confirmed)"))
    }

    @Test fun allExistingPureEditorEntryPointsDelegateWithoutSubstitutingSaveExpectations() {
        val policy = editor("EditVerificationPolicy")
        assertTrue(policy.contains("internal fun EditOutputExpectation.inspectionExpectation() = AvcAacOutputExpectation("))
        assertTrue(policy.contains("durationToleranceUs, audioPrimingToleranceUs, maxBytes, videoFrameCount"))
        assertTrue(policy.contains("AvcAacSampleLedger(video, expected.inspectionExpectation(), timing)"))
        assertTrue(policy.contains("AvcAacAudioDecodeLedger(expected.inspectionExpectation(), track)"))
        assertTrue(editor("EditMp4Probe").contains("AvcAacMp4Probe.read(channel, size, expected.inspectionExpectation(), checkpoint)"))
        assertTrue(editor("EditAacConfigPolicy").contains("AvcAacAacConfigPolicy.requireLc(bytes, sampleRate, channels)"))
        assertFalse(media("AvcAacOutputExpectation").contains("EditOutputExpectation"))
    }
}
