package com.easysavevideo.app.validation

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Bundle
import com.easysavevideo.app.BuildConfig
import com.easysavevideo.app.save.AndroidDirectExecutionBridge
import com.easysavevideo.app.save.storage.AndroidSaveStorage
import com.easysavevideo.app.save.storage.OwnedDirectTechnicalDiagnostics
import com.easysavevideo.core.model.*
import com.easysavevideo.library.thumbnail.ThumbnailNativeResourceGate
import com.easysavevideo.save.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Debug-only original-asset technical run. No playback/proof/publication acceptance is issued. */
internal object OpusTechnicalValidation {
    // Passive strong references only: no new owner, release action or authority.
    private data class RetainedCase(val storage: AndroidSaveStorage, val admission: SaveExecutionAdmission,
        val claim: SaveExecutionAdmission.Claim?, val nativeGate: ThumbnailNativeResourceGate, val batchOwner: Any,
        val batch: ThumbnailNativeResourceGate.Ownership?, val bridge: AndroidDirectExecutionBridge?,
        val components: AndroidSaveStorage.ControlledComponents?, val coordinator: SaveCoordinator?)
    private var unclosedCase: RetainedCase? = null

    fun run(instrumentation: Instrumentation) {
        val report = JSONObject().put("scope", "original Opus technical pipeline only")
            .put("head_marker_acceptance", "NOT_OBSERVED").put("independent_aac_mapping", "NOT_OBSERVED")
            .put("public_conversion_enabled", false).put("native_codec_identities", "NOT_OBSERVED")
            .put("raw_N_and_D", "NOT_OBSERVED").put("sdk", Build.VERSION.SDK_INT)
            .put("build_fingerprint", Build.FINGERPRINT).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        val rows = JSONArray(); val failures = JSONArray()
        var outputRoot: File? = null
        try {
            check(BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 31 && unclosedCase == null)
            val root = File(instrumentation.targetContext.cacheDir, "esv-opus-technical-${UUID.randomUUID()}")
            check(root.mkdir()); outputRoot = root
            val cases = JSONObject(instrumentation.context.assets.open("opus-technical/fixtures.json")
                .bufferedReader().use { it.readText() }).getJSONArray("cases")
            check(cases.length() == 3)
            val expectedIds = listOf("aligned_1s", "nonaligned_1007ms", "short_audio_600ms")
            for (index in 0 until cases.length()) {
                val fixture = cases.getJSONObject(index)
                check(fixture.getString("id") == expectedIds[index])
                val bytes = instrumentation.context.assets.open("opus-technical/${expectedIds[index]}.webm").use { it.readBytes() }
                check(bytes.size in 1..2 * 1024 * 1024 && bytes.size == fixture.getInt("bytes"))
                check(sha256(bytes) == fixture.getString("sha256"))
                val caseRoot = File(root, expectedIds[index]); check(caseRoot.mkdir())
                val row = runCase(IsolatedOpusContext(instrumentation.targetContext, caseRoot), fixture, bytes)
                rows.put(row)
                if (!row.optBoolean("expected_technical_result")) failures.put(expectedIds[index])
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "Opus technical ${expectedIds[index]}: ${row.optString("terminal_error")}\n")
                })
                if (unclosedCase != null || !row.optBoolean("claim_released")) {
                    failures.put("RESOURCE_RELEASE_UNCONFIRMED"); break
                }
            }
        } catch (failure: Throwable) {
            failures.put("HARNESS_${failure.javaClass.simpleName}")
        }
        report.put("cases", rows).put("failures", failures).put("native_mapping_accepted", false)
        try { outputRoot?.let { File(it, "report.json").writeText(report.toString(2)) } }
        catch (_: Throwable) { failures.put("REPORT_WRITE_FAILED") }
        instrumentation.finish(if (failures.length() == 0) -1 else 0, Bundle().apply {
            putString("stream", "Opus technical report: ${report}\n")
        })
    }

    private fun runCase(context: IsolatedOpusContext, fixture: JSONObject, bytes: ByteArray): JSONObject {
        val id = fixture.getString("id")
        var operationId = ""
        val taskId = "opus-${UUID.randomUUID()}"
        val row = JSONObject().put("id", id).put("sha256", sha256(bytes))
            .put("reference_pcm_sha256", fixture.getString("reference_pcm_sha256"))
            .put("known_source_frames", fixture.getLong("known_source_frames"))
        val clock = EpochClock { System.currentTimeMillis() }
        val limits = SaveLimits(64L * 1024 * 1024, 64L * 1024 * 1024)
        val storage = AndroidSaveStorage(context)
        val nativeGate = ThumbnailNativeResourceGate()
        val batchOwner = Any()
        var batch: ThumbnailNativeResourceGate.Ownership? = null
        val admission = SaveExecutionAdmission(maxArtifactBytes = limits.maxArtifactBytes,
            resourcesUnreleased = storage::normalizationReleaseUnconfirmed,
            onFirstDirect = { nativeGate.claim(batchOwner).also { if (it) batch = nativeGate.ownership(batchOwner) } },
            onLastDirectReleased = { nativeGate.released(batchOwner); batch = null })
        var claim: SaveExecutionAdmission.Claim? = null
        var retainedBridge: AndroidDirectExecutionBridge? = null
        var retainedComponents: AndroidSaveStorage.ControlledComponents? = null
        var retainedCoordinator: SaveCoordinator? = null
        var captureStarted = false
        var result: SaveRecord? = null
        try {
            val gate = DirectLocalProcessingGate(clock, storage::normalizationReleaseUnconfirmed)
            val bridge = AndroidDirectExecutionBridge(admission, gate) { batch }.also { retainedBridge = it }
            val components = storage.controlledDirectComponents(gate, bridge, clock, limits).also { retainedComponents = it }
            val transfer = object : DirectTransferPort {
                override fun transfer(resource: SelectedMediaResource, artifact: StagingArtifact,
                    checkpoint: DownloadCheckpoint?, context: OperationContext, limits: SaveLimits,
                    progress: TransferProgress): TransferResult {
                    check(checkpoint == null && context.stopReason(clock.nowMillis()) == null)
                    storage.openStaging(artifact, false).use { output -> output.write(bytes); output.fd.sync() }
                    storage.syncStaging(artifact)
                    check(storage.stagingLength(artifact) == bytes.size.toLong())
                    progress.onBytes(bytes.size.toLong())
                    // Exact local fixture bytes; no network request is performed.
                    return TransferResult(artifact.artifactId, bytes.size.toLong(), "application/octet-stream", bytes.size.toLong())
                }
            }
            val coordinator = SaveCoordinator(storage, transfer,
                ArtifactVerifier { _, _, _ -> error("UNEXPECTED_VERIFIER") }, storage, clock, limits,
                admission = admission,
                controlledDirect = ControlledDirectPipeline(gate, components.storage, conversionExecutionEnabled = true)).also { retainedCoordinator = it }
            operationId = OwnedDirectTechnicalDiagnostics.begin(); captureStarted = true
            val operation = OperationContext(operationId, clock.nowMillis() + 120_000, CancellationSource())
            val source = SelectedMediaResource("fixture", id, SourceUrl("https://example.invalid/opus-technical/$id"), expectedAudio = true)
            val acquired = requireNotNull(admission.tryAcquireDirect()); claim = acquired
            check(coordinator.submit(SaveRequest(taskId, taskId, source), operation, acquired) is SaveSubmission.Accepted)
            result = coordinator.execute(taskId, acquired)
            val actual = result ?: error("MISSING_SAVE_RESULT")
            row.put("terminal_state", actual.task.state.name).put("terminal_error", actual.error?.name ?: "NONE")
            val noProof = actual.directConversion == null && actual.directNormalization == null &&
                actual.verification == null && actual.receipt == null && storage.visibleMedia(100).isEmpty()
            row.put("no_proof_or_publication", noProof)
            check(noProof)
        } catch (failure: Throwable) {
            row.put("primary_failure", failure.javaClass.simpleName)
            row.put("terminal_error", (failure as? SaveFailure)?.code?.name ?: "HARNESS_FAILURE")
        } finally {
            try {
            if (captureStarted) {
                val snapshot = OwnedDirectTechnicalDiagnostics.end(operationId)
                row.put("stages", JSONArray(snapshot.stages.map { it.name }))
                snapshot.observation?.let { observed ->
                    row.put("video_frames", observed.video.displayedFrames)
                    observed.audio?.let { audio ->
                        row.put("audio", JSONObject().put("source_start_us", audio.sourceFirstLocalPtsUs)
                            .put("source_end_us", audio.sourceEndLocalPtsUs).put("S", audio.sourcePcmFrames)
                            .put("E", audio.encoderPcmFrames).put("A", audio.encoderPcmFrames - audio.sourcePcmFrames)
                            .put("rate", audio.sampleRate).put("channels", audio.channels)
                            .put("codec_delay_us", audio.codecDelayUs).put("seek_preroll_us", audio.seekPrerollUs)
                            .put("discard_padding_us", audio.discardPaddingUs).put("head_mapping", audio.headMapping.name))
                    }
                }
                val expected = if (fixture.getBoolean("candidate_expected")) {
                    snapshot.stages.containsAll(OwnedDirectTechnicalDiagnostics.Stage.entries) &&
                        snapshot.observation?.audio?.sourcePcmFrames == fixture.getLong("known_source_frames")
                } else snapshot.stages == listOf(OwnedDirectTechnicalDiagnostics.Stage.PREFLIGHT_STARTED)
                row.put("expected_technical_result", expected && result?.error == SaveError.UNSUPPORTED_MEDIA &&
                    row.optBoolean("no_proof_or_publication"))
            }
            } catch (diagnostic: Throwable) {
                row.put("diagnostic_failure", diagnostic.javaClass.simpleName).put("expected_technical_result", false)
            } finally {
            val batchBeforeRelease = batch
            var released = false
            var storageClosed = false
            var releaseFailure: String? = null
            var closeFailure: String? = null
            // Each existing cleanup is attempted once. A release exception cannot skip the guarded close.
            try { released = claim?.let(admission::release) ?: true }
            catch (cleanup: Throwable) { releaseFailure = cleanup.javaClass.simpleName }
            try { storage.close(); storageClosed = true }
            catch (cleanup: Throwable) { closeFailure = cleanup.javaClass.simpleName }
            if (!released || !storageClosed) {
                // Retain the entire original graph even when guarded storage close returned normally.
                unclosedCase = RetainedCase(storage, admission, claim, nativeGate, batchOwner, batchBeforeRelease,
                    retainedBridge, retainedComponents, retainedCoordinator)
            }
            row.put("claim_released", released).put("storage_closed", storageClosed)
            releaseFailure?.let { row.put("admission_release_failure", it) }
            closeFailure?.let { row.put("storage_close_failure", it) }
            if (!released || !storageClosed) row.put("expected_technical_result", false)
            }
        }
        return row
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** All touched storage entrypoints stay under one unique root; no fallback to application data. */
private class IsolatedOpusContext(base: Context, root: File) : ContextWrapper(base) {
    private val root = root.canonicalFile.also { check(it.isDirectory) }
    private fun child(name: String): File = inside(File(root, name)).also { check(it.isDirectory || it.mkdirs()) }
    private fun inside(file: File): File = file.canonicalFile.also {
        check(it.path.startsWith(root.path + File.separator)) { "TEST_STORAGE_ESCAPE" }
    }
    private fun simple(name: String): String = name.also { check(it.matches(Regex("[A-Za-z0-9_.-]{1,128}")) && it != "." && it != "..") }
    override fun getApplicationContext(): Context = this
    override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(super.getApplicationInfo()).apply {
        dataDir = root.path; deviceProtectedDataDir = root.path
    }
    override fun getDataDir(): File = root
    override fun getNoBackupFilesDir(): File = child("no-backup")
    override fun getFilesDir(): File = child("files")
    override fun getCacheDir(): File = child("cache")
    override fun getCodeCacheDir(): File = child("code-cache")
    override fun getDir(name: String, mode: Int): File { check(mode == MODE_PRIVATE); return child("app-${simple(name)}") }
    override fun getDatabasePath(name: String): File = inside(if (File(name).isAbsolute) File(name) else File(child("databases"), simple(name)))
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        openOrCreateDatabase(name, mode, factory, null)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase {
        check(mode == MODE_PRIVATE)
        val file = getDatabasePath(name); check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        return SQLiteDatabase.openDatabase(file.path, factory, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
    }
    override fun databaseList(): Array<String> = child("databases").list() ?: emptyArray()
    override fun deleteDatabase(name: String): Boolean = error("UNEXPECTED_DATABASE_DELETE")
    override fun getFileStreamPath(name: String): File = inside(File(filesDir, simple(name)))
    override fun openFileInput(name: String): FileInputStream = FileInputStream(getFileStreamPath(name))
    override fun openFileOutput(name: String, mode: Int): FileOutputStream {
        check(mode == MODE_PRIVATE); return FileOutputStream(getFileStreamPath(name), false)
    }
    override fun fileList(): Array<String> = filesDir.list() ?: emptyArray()
    override fun deleteFile(name: String): Boolean = error("UNEXPECTED_FILE_DELETE")
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = error("UNEXPECTED_PREFERENCES")
    override fun createDeviceProtectedStorageContext(): Context = error("UNEXPECTED_STORAGE_CONTEXT")
    override fun getExternalFilesDir(type: String?): File? = error("UNEXPECTED_EXTERNAL_STORAGE")
    override fun getExternalFilesDirs(type: String?): Array<File> = error("UNEXPECTED_EXTERNAL_STORAGE")
    override fun getExternalCacheDir(): File? = error("UNEXPECTED_EXTERNAL_STORAGE")
    override fun getExternalCacheDirs(): Array<File> = error("UNEXPECTED_EXTERNAL_STORAGE")
    override fun getExternalMediaDirs(): Array<File> = error("UNEXPECTED_EXTERNAL_STORAGE")
    override fun getObbDir(): File = error("UNEXPECTED_EXTERNAL_STORAGE")
    override fun getObbDirs(): Array<File> = error("UNEXPECTED_EXTERNAL_STORAGE")
}
