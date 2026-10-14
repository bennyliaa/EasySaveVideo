package com.easysavevideo.app.save.normalization

import com.easysavevideo.core.model.*
import com.easysavevideo.save.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer

/** Real admitted muxer/extractor bytecode, no Android decoder or device claim. */
class PinnedMovRemuxTest {
    @Test fun originalSilentMovProducesConservedCanonicalMp4WithExplicitTail() {
        val raw = OriginalMovFixtures.build()
        val files = MemoryFiles(raw)
        val evidence = engine().remux(files, operation(), SaveLimits(2L * 1024 * 1024 * 1024, 4L * 1024 * 1024 * 1024))
        val expected = MovProfileProbe.probeQuickTime(OriginalMovFixtures.input(raw))
        val actual = MovProfileProbe.scanCanonicalMp4(OriginalMovFixtures.input(files.outputBytes()))
        assertEquals(expected.conservation, evidence.conservation)
        assertEquals(expected.conservation, actual.conservation)
        assertEquals(raw.size.toLong(), evidence.rawSizeBytes)
        assertEquals(files.outputBytes().size.toLong(), evidence.outputSizeBytes)
        assertNotEquals(evidence.rawSha256, evidence.outputSha256)
        assertTrue(files.inputs.all { it.releaseConfirmed }); assertTrue(files.sink.releaseConfirmed)
        assertExactTable(files.outputBytes(), 3, 30)
    }

    @Test fun twoSamplesNeverLoseTheLastDurationToPinnedFallback() {
        val files = MemoryFiles(OriginalMovFixtures.build(OriginalMovFixtures.Options(count = 2)))
        engine().remux(files, operation(), SaveLimits(2L * 1024 * 1024 * 1024, 4L * 1024 * 1024 * 1024))
        assertExactTable(files.outputBytes(), 2, 30)
    }

    @Test fun audioRejectsWholeFileBeforeCreatingOutput() {
        val files = MemoryFiles(OriginalMovFixtures.build(OriginalMovFixtures.Options(audio = true)))
        val engine = engine()
        failure(SaveError.UNSUPPORTED_MEDIA) { engine.remux(files, operation(), SaveLimits(2L * 1024 * 1024 * 1024, 4L * 1024 * 1024 * 1024)) }
        assertEquals(0, files.outputOpens); assertTrue(files.inputs.all { it.releaseConfirmed })
        assertFalse(engine.requiresProcessRecovery())
    }

    @Test fun fullOutputDiskAllowanceIsRequiredBeforeCreatingOutput() {
        val files = MemoryFiles(OriginalMovFixtures.build()).apply { free = 1000 }
        failure(SaveError.STORAGE_FULL) { engine().remux(files, operation(), SaveLimits(2L * 1024 * 1024 * 1024, 4L * 1024 * 1024 * 1024)) }
        assertEquals(0, files.outputOpens); assertTrue(files.inputs.all { it.releaseConfirmed })
    }

    @Test fun stoppedOperationDoesNotOpenAnyHandle() {
        val files = MemoryFiles(OriginalMovFixtures.build()); val cancel = CancellationSource().also { it.cancel() }
        failure(SaveError.CANCELLED) { engine().remux(files, operation(cancel), SaveLimits(2L * 1024 * 1024 * 1024, 4L * 1024 * 1024 * 1024)) }
        assertTrue(files.inputs.isEmpty()); assertEquals(0, files.outputOpens)
    }

    @Test fun allAdmittedRatesAndOwnedLayoutsConserveRealMuxerBytes() {
        val rates = intArrayOf(1,2,3,4,5,6,8,9,10,12,15,16,18,20,24,25,30,36,40,45,48,50,60)
        var cases = 0
        for (rate in rates) for (count in intArrayOf(2,3,7)) for (front in listOf(false,true)) {
            val files = MemoryFiles(OriginalMovFixtures.build(OriginalMovFixtures.Options(
                frameRate=rate, count=count, moovFirst=front, co64=front, samplesPerChunk=2, splitTiming=true)))
            engine().remux(files, operation(), limits())
            assertExactTable(files.outputBytes(), count, rate)
            assertTrue(files.inputs.all { it.releaseConfirmed }); cases++
        }
        assertEquals(138, cases)
    }

    @Test fun supportedNalLayoutsRemainByteExactThroughBothExtractors() {
        for (options in listOf(
            OriginalMovFixtures.Options(aud=true), OriginalMovFixtures.Options(inBandConfig=true),
            OriginalMovFixtures.Options(allIdr=true), OriginalMovFixtures.Options(pocType=2),
            OriginalMovFixtures.Options(width=32,slices=2), OriginalMovFixtures.Options(aud=true,inBandConfig=true))) {
            val files=MemoryFiles(OriginalMovFixtures.build(options))
            engine().remux(files,operation(),limits()); assertExactTable(files.outputBytes(),options.count,options.frameRate)
        }
    }

    @Test fun unsupportedProfilesNeverCreateAnOutput() {
        for (options in listOf(OriginalMovFixtures.Options(extraVideo=true), OriginalMovFixtures.Options(sdr=false),
            OriginalMovFixtures.Options(nonIdrSliceType=1), OriginalMovFixtures.Options(referenceListModification=true),
            OriginalMovFixtures.Options(adaptiveReferenceMarking=true), OriginalMovFixtures.Options(ppsSliceGroups=1))) {
            val files=MemoryFiles(OriginalMovFixtures.build(options))
            failure(SaveError.UNSUPPORTED_MEDIA) { engine().remux(files,operation(),limits()) }
            assertEquals(0,files.outputOpens); assertTrue(files.inputs.all { it.releaseConfirmed })
        }
    }

    @Test fun cancellationDuringActualWriteClosesEveryOwnedHandleAndAllowsNextJob() {
        val cancel=CancellationSource(); val files=MemoryFiles(OriginalMovFixtures.build())
        files.sink.onWrite={cancel.cancel()}; val engine=engine()
        failure(SaveError.CANCELLED) { engine.remux(files,operation(cancel),limits()) }
        assertTrue(files.sink.releaseConfirmed); assertTrue(files.inputs.all { it.releaseConfirmed })
        assertFalse(engine.requiresProcessRecovery())
        engine.remux(MemoryFiles(OriginalMovFixtures.build()),operation(),limits())
    }

    @Test fun diskExhaustionAfterAdmissionCannotReturnEvidence() {
        val files=MemoryFiles(OriginalMovFixtures.build()); files.sink.onWrite={files.free=0}
        failure(SaveError.STORAGE_FULL) { engine().remux(files,operation(),limits()) }
        assertTrue(files.sink.releaseConfirmed); assertTrue(files.inputs.all { it.releaseConfirmed })
    }

    @Test fun finalizedCorruptionIsRejectedAfterAllHandlesClose() {
        val files=MemoryFiles(OriginalMovFixtures.build())
        files.finalizedTransform={ bytes -> bytes.also { it[0]=0x7f } }
        failure(SaveError.INVALID_MEDIA) { engine().remux(files,operation(),limits()) }
        assertTrue(files.sink.releaseConfirmed); assertTrue(files.inputs.all { it.releaseConfirmed })
    }

    @Test fun uncheckedMuxFinalizationAndUnconfirmedSinkClosePoisonFurtherAdmission() {
        val files=MemoryFiles(OriginalMovFixtures.build()); files.sink.failFinalization=true; files.sink.failClose=true
        val engine=engine()
        failure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) { engine.remux(files,operation(),limits()) }
        assertTrue(engine.requiresProcessRecovery()); assertTrue(files.inputs.all { it.releaseConfirmed }); assertEquals(1,files.sink.finalizationFailures)
        val next=MemoryFiles(OriginalMovFixtures.build())
        failure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) { engine.remux(next,operation(),limits()) }
        assertTrue(next.inputs.isEmpty()); assertEquals(0,next.outputOpens)
    }

    @Test fun normalCloseAfterUncheckedOutputFailureDoesNotPoisonNextJob() {
        val files=MemoryFiles(OriginalMovFixtures.build()); files.sink.failFinalization=true
        val engine=engine()
        failure(SaveError.UNSUPPORTED_MEDIA) { engine.remux(files,operation(),limits()) }
        assertTrue(files.sink.releaseConfirmed); assertFalse(engine.requiresProcessRecovery()); assertEquals(1,files.sink.finalizationFailures)
        engine.remux(MemoryFiles(OriginalMovFixtures.build()),operation(),limits())
    }

    @Test fun actualCallbackAcceptsFragmentationOffsetsAndBothDataApis() {
        val (profile,callback)=callback()
        val samples=callbackSamples(profile)
        callback.sampleData(androidx.media3.common.util.ParsableByteArray(samples[0]+samples[1]), samples[0].size+samples[1].size,0)
        callback.sampleMetadata(0,1,samples[0].size,samples[1].size,null)
        callback.sampleMetadata(profile.clock.presentationTimeUs(1),0,samples[1].size,0,null)
        var at=0
        val reader=androidx.media3.common.DataReader { buffer,offset,length ->
            val count=minOf(3,length,samples[2].size-at)
            if(count==0) -1 else { samples[2].copyInto(buffer,offset,at,at+count); at+=count; count }
        }
        while(at<samples[2].size) callback.sampleData(reader,samples[2].size-at,false,0)
        callback.sampleMetadata(profile.clock.presentationTimeUs(2),0x20000000,samples[2].size,0,null)
        callback.finish()
    }

    @Test fun callbackRejectsNonProgressingReaderAndUnconsumedTail() {
        val (_,zero)=callback()
        failure(SaveError.INCOMPLETE_TRANSFER) { zero.sampleData(androidx.media3.common.DataReader { _,_,_->0 },16,false,0) }
        val (_,tail)=callback()
        tail.sampleData(androidx.media3.common.util.ParsableByteArray(byteArrayOf(1)),1,0)
        failure(SaveError.INCOMPLETE_TRANSFER) { tail.finish() }
    }

    @Test fun callbackRejectsProtectedDataAndMismatchedOffset() {
        val (_,protected)=callback()
        failure(SaveError.PROTECTED_MEDIA) { protected.sampleData(androidx.media3.common.util.ParsableByteArray(byteArrayOf(1)),1,1) }
        val (profile,badOffset)=callback(); val first=callbackSamples(profile)[0]
        badOffset.sampleData(androidx.media3.common.util.ParsableByteArray(first),first.size,0)
        failure(SaveError.INVALID_MEDIA) { badOffset.sampleMetadata(0,1,first.size,1,null) }
    }

    private fun callback(): Pair<MovProfile,MovRemuxEngine.OneVideoOutput> {
        val raw=OriginalMovFixtures.input(OriginalMovFixtures.build())
        val profile=MovProfileProbe.probeQuickTime(raw)
        val callback=MovRemuxEngine.OneVideoOutput(raw,profile,null,{})
        callback.track(1,androidx.media3.common.C.TRACK_TYPE_VIDEO)
        callback.format(androidx.media3.common.Format.Builder().setSampleMimeType("video/avc")
            .setWidth(profile.width).setHeight(profile.height).setColorInfo(androidx.media3.common.ColorInfo.SDR_BT709_LIMITED)
            .setInitializationData(listOf(byteArrayOf(0,0,0,1)+profile.configuration.sps,byteArrayOf(0,0,0,1)+profile.configuration.pps)).build())
        callback.endTracks();return profile to callback
    }
    private fun callbackSamples(profile: MovProfile): List<ByteArray> {
        val raw=OriginalMovFixtures.input(OriginalMovFixtures.build())
        return profile.samples.indices.map { n ->
            DirectMovCanonical.avcNals(MovProfileProbe.readCanonicalSample(raw,profile,n),DirectMovNalFraming.AVCC4)
                .fold(byteArrayOf()) { all,nal -> all+byteArrayOf(0,0,0,1)+nal }
        }
    }

    @Test fun busyReentryCannotOpenOrRetireTheActiveOwner() {
        val engine=engine(); val files=MemoryFiles(OriginalMovFixtures.build()); val second=MemoryFiles(OriginalMovFixtures.build())
        var attempted=false
        files.onSourceRead={ if(!attempted) { attempted=true
            failure(SaveError.BUSY) { engine.remux(second,operation(),limits()) }
        } }
        engine.remux(files,operation(),limits())
        assertTrue(attempted); assertTrue(second.inputs.isEmpty()); assertEquals(0,second.outputOpens)
        assertFalse(engine.requiresProcessRecovery())
    }

    @Test fun wrappedIoWriteFailuresRemainStorageFailuresWithConfirmedRelease() {
        val engine=engine(); val files=MemoryFiles(OriginalMovFixtures.build());files.sink.ioWrite=true
        failure(SaveError.STORAGE_FAILURE) { engine.remux(files,operation(),limits()) }
        assertTrue(files.sink.releaseConfirmed);assertTrue(files.inputs.all { it.releaseConfirmed });assertFalse(engine.requiresProcessRecovery())
    }

    @Test fun zeroProgressOutputCannotBeTreatedAsSuccessfulMedia() {
        val files=MemoryFiles(OriginalMovFixtures.build());files.sink.zeroWrite=true
        failure(SaveError.STORAGE_FAILURE) { engine().remux(files,operation(),limits()) }
        assertTrue(files.sink.releaseConfirmed);assertTrue(files.inputs.all { it.releaseConfirmed })
    }

    @Test fun finalizedInputCloseFailureSuppressesOtherwiseValidEvidence() {
        val engine=engine();val files=MemoryFiles(OriginalMovFixtures.build());files.failFinalizedClose=true
        failure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) { engine.remux(files,operation(),limits()) }
        assertTrue(engine.requiresProcessRecovery());assertTrue(files.sink.releaseConfirmed)
        assertTrue(files.inputs.first().releaseConfirmed);assertFalse(files.inputs.last().releaseConfirmed)
    }

    @Test fun sourceCloseFailureSuppressesOtherwiseValidEvidence() {
        val engine=engine();val files=MemoryFiles(OriginalMovFixtures.build());files.failSourceClose=true
        failure(SaveError.RESOURCE_RELEASE_UNCONFIRMED) { engine.remux(files,operation(),limits()) }
        assertTrue(engine.requiresProcessRecovery());assertTrue(files.sink.releaseConfirmed)
        assertFalse(files.inputs.first().releaseConfirmed);assertTrue(files.inputs.last().releaseConfirmed)
    }

    @Test fun operationByteBudgetCoversRawAndOutputTogether() {
        val raw=OriginalMovFixtures.build();val files=MemoryFiles(raw)
        val context=OperationContext("mov-budget",100_000,CancellationSource(),resourceBudget=ResourceBudget(1,raw.size.toLong()+32))
        failure(SaveError.BYTE_LIMIT_EXCEEDED) { engine().remux(files,context,limits()) }
        assertTrue(files.inputs.all { it.releaseConfirmed });assertTrue(files.sink.releaseConfirmed)
    }

    @Test fun validFinalizedMiddlePayloadMutationFailsConservationRatherThanStructure() {
        val files=MemoryFiles(OriginalMovFixtures.build(OriginalMovFixtures.Options(allIdr=true)))
        files.finalizedTransform={ bytes ->
            val before=MovProfileProbe.scanCanonicalMp4(OriginalMovFixtures.input(bytes))
            val sample=before.samples[1];val at=sample.offset.toInt()+sample.size-8
            bytes[at]=(bytes[at].toInt() xor 1).toByte()
            val after=MovProfileProbe.scanCanonicalMp4(OriginalMovFixtures.input(bytes))
            assertNotEquals(before.conservation,after.conservation);bytes
        }
        failure(SaveError.INVALID_MEDIA) { engine().remux(files,operation(),limits()) }
        assertTrue(files.inputs.all { it.releaseConfirmed });assertTrue(files.sink.releaseConfirmed)
    }

    @Test fun structurallyValidOutputRetimingCannotPassConservation() {
        val files=MemoryFiles(OriginalMovFixtures.build())
        files.finalizedTransform={ bytes ->
            val stts=tag(bytes,"stts");val entries=u32(bytes,stts+8).toInt()
            repeat(entries) { put32(bytes,stts+16+it*8,3_600) }
            put32(bytes,tag(bytes,"mdhd")+20,10_800)
            put32(bytes,tag(bytes,"mvhd")+20,1_200)
            put32(bytes,tag(bytes,"tkhd")+24,1_200)
            assertEquals(25,MovProfileProbe.scanCanonicalMp4(OriginalMovFixtures.input(bytes)).frameRate);bytes
        }
        failure(SaveError.INVALID_MEDIA) { engine().remux(files,operation(),limits()) }
        assertTrue(files.inputs.all { it.releaseConfirmed });assertTrue(files.sink.releaseConfirmed)
    }

    @Test fun callbackValidPayloadMutationCannotMasqueradeAsRawSample() {
        val (profile,callback)=callback();val bytes=callbackSamples(profile)[0]
        bytes[bytes.size-8]=(bytes[bytes.size-8].toInt() xor 1).toByte()
        DirectMovCanonical.avcSample(bytes,DirectMovNalFraming.ANNEX_B) // framing remains valid.
        callback.sampleData(androidx.media3.common.util.ParsableByteArray(bytes),bytes.size,0)
        failure(SaveError.INVALID_MEDIA) { callback.sampleMetadata(0,1,bytes.size,0,null) }
    }

    private fun put32(bytes: ByteArray,at: Int,value: Int) {
        repeat(4) { bytes[at+it]=(value ushr (24-it*8)).toByte() }
    }

    private fun limits()=SaveLimits(2L*1024*1024*1024,4L*1024*1024*1024)

    private fun assertExactTable(bytes: ByteArray, count: Int, rate: Int) {
        val stts = tag(bytes, "stts"); val entries = u32(bytes, stts + 8).toInt()
        assertTrue(entries in 1..count)
        var total = 0L
        repeat(entries) { n -> total += u32(bytes, stts + 12 + n * 8); assertEquals(90_000L / rate, u32(bytes, stts + 16 + n * 8)) }
        assertEquals(count.toLong(), total)
        val mdhd = tag(bytes, "mdhd"); assertEquals(0, bytes[mdhd + 4].toInt())
        assertEquals(90_000L, u32(bytes, mdhd + 16)); assertEquals(count * (90_000L / rate), u32(bytes, mdhd + 20))
        val ticks=count*(90_000L/rate)
        val roundedUs=(ticks*1_000_000L+45_000L)/90_000L
        val movieTicks=(roundedUs+50L)/100L
        val mvhd=tag(bytes,"mvhd");val tkhd=tag(bytes,"tkhd")
        assertEquals(10_000L,u32(bytes,mvhd+16));assertEquals(movieTicks,u32(bytes,mvhd+20))
        assertEquals(movieTicks,u32(bytes,tkhd+24))
    }
    private fun tag(bytes: ByteArray, name: String): Int {
        val tag = name.toByteArray(Charsets.US_ASCII)
        val matches = (0..bytes.size - 4).filter { p -> (0..3).all { bytes[p + it] == tag[it] } }
        assertEquals("Expected unique owned fixture box $name", 1, matches.size); return matches.single()
    }
    private fun u32(bytes: ByteArray, at: Int): Long = (0..3).fold(0L) { value, n -> (value shl 8) or (bytes[at + n].toInt() and 255).toLong() }
    private fun engine() = MovRemuxEngine(EpochClock { 0 })
    private fun operation(token: CancellationToken = CancellationSource()) = OperationContext("mov-host", 100_000, token,
        resourceBudget = ResourceBudget(1, 4L * 1024 * 1024 * 1024))
    private fun failure(error: SaveError, block: () -> Unit) {
        try { block(); fail("Expected $error") } catch (failure: SaveFailure) { assertEquals(error, failure.code) }
    }

    private class MemoryInput(private val bytes: ByteArray, private val onRead: () -> Unit = {}, private val failClose: Boolean = false) : MovRemuxInput {
        override val length = bytes.size.toLong()
        override var releaseConfirmed = false; private set
        override fun validateIdentity() { check(!releaseConfirmed && bytes.size.toLong() == length) }
        override fun readAt(offset: Long, target: ByteArray, targetOffset: Int, length: Int) {
            onRead(); validateIdentity(); check(offset >= 0 && length >= 0 && offset <= this.length - length)
            bytes.copyInto(target, targetOffset, offset.toInt(), offset.toInt() + length)
        }
        override fun close() { if(failClose) throw IOException("HOST_INPUT_CLOSE"); releaseConfirmed = true }
    }
    private class MemoryOutput : MovRemuxOutput {
        var onWrite: () -> Unit = {}; var failFinalization=false; var finalizationFailures=0; var failClose=false; var ioWrite=false; var zeroWrite=false
        private var bytes = ByteArray(128 * 1024); private var cursor = 0; private var extent = 0
        override var releaseConfirmed = false; private set
        override fun isOpen() = !releaseConfirmed
        override fun close() { if(failClose) throw IOException("HOST_CLOSE"); releaseConfirmed = true }
        override fun getPosition(): Long { check(isOpen()); return cursor.toLong() }
        override fun setPosition(position: Long) { check(isOpen()); if(failFinalization && Thread.currentThread().stackTrace.any { it.methodName == "finishWritingSamplesAndFinalizeMoovBox" }) { finalizationFailures++; throw IllegalStateException("HOST_UNCHECKED_FINALIZE") }; require(position in 0..1024L * 1024); cursor = position.toInt() }
        override fun getSize(): Long { check(isOpen()); return extent.toLong() }
        override fun truncate(size: Long) { check(isOpen()); require(size in 0..extent.toLong()); extent = size.toInt(); cursor = minOf(cursor, extent) }
        override fun write(source: ByteBuffer): Int {
            check(isOpen()); if(ioWrite) throw IOException("HOST_WRITE"); if(zeroWrite) return 0; val count = source.remaining()
            if (cursor.toLong() + count > 1024 * 1024) throw IOException("HOST_OUTPUT_CAP")
            if (bytes.size < cursor + count) bytes = bytes.copyOf(maxOf(bytes.size * 2, cursor + count))
            source.get(bytes, cursor, count); cursor += count; extent = maxOf(extent, cursor); onWrite(); return count
        }
        fun snapshot(): ByteArray { check(releaseConfirmed); return bytes.copyOf(extent) }
    }
    private class MemoryFiles(private val raw: ByteArray) : MovRemuxFiles {
        val inputs = mutableListOf<MemoryInput>(); val sink = MemoryOutput()
        var finalizedTransform: (ByteArray)->ByteArray = { it }
        var onSourceRead: () -> Unit = {}; var failSourceClose=false; var failFinalizedClose=false
        var outputOpens = 0; var free = 4L * 1024 * 1024 * 1024
        override fun openSource(): MovRemuxInput = MemoryInput(raw.clone(),onSourceRead,failSourceClose).also(inputs::add)
        override fun openEmptyOutput(): MovRemuxOutput { outputOpens++; return sink }
        override fun openFinalizedOutput(): MovRemuxInput = MemoryInput(finalizedTransform(sink.snapshot()),failClose=failFinalizedClose).also(inputs::add)
        override fun availableBytes() = free
        fun outputBytes() = sink.snapshot()
    }
}
