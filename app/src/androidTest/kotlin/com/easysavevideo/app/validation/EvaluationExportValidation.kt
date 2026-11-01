package com.easysavevideo.app.validation

import android.app.Instrumentation
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import com.easysavevideo.app.BuildConfig
import com.easysavevideo.app.diagnostics.DiagnosticExportPhase
import com.easysavevideo.app.save.AndroidEvaluationDocumentPort
import com.easysavevideo.app.save.SealedEvaluationExport
import com.easysavevideo.discovery.CandidateUrl
import com.easysavevideo.discovery.DiscoverySaveEvaluationJson
import com.easysavevideo.discovery.DiscoverySaveEvaluationSession
import com.easysavevideo.discovery.PendingCandidatePolicy
import com.easysavevideo.discovery.SaveEvaluationEvidence
import com.easysavevideo.discovery.SaveEvaluationMethod
import com.easysavevideo.discovery.SaveEvaluationOutcome
import com.easysavevideo.discovery.SaveEvaluationSessionReport
import com.easysavevideo.discovery.SaveEvaluationSessionState
import com.easysavevideo.discovery.SearchFilters
import com.easysavevideo.discovery.SourceReportedCandidateMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor

/** Real resolver/descriptor checks only. Does not launch a picker or claim UI lifecycle acceptance. */
internal object EvaluationExportValidation {
    private const val SECRET_TITLE = "owned-title-never-export"
    private const val SECRET_QUERY = "owned-query-never-export"
    private const val SECRET_SOURCE = "owned-provider-never-export"

    fun run(instrumentation: Instrumentation) {
        val cases = JSONArray()
        val failures = JSONArray()
        val report = JSONObject().put("phase", "evaluation-export")
            .put("scope", "owned synthetic SAF adapter instrumentation")
            .put("sdk", Build.VERSION.SDK_INT).put("cases", cases).put("failures", failures)
            .put("ui_lifecycle_acceptance", "NOT_EXECUTED")
            .put("real_picker_acceptance", "NOT_EXECUTED")
            .put("injected_close_failure", "NOT_EXECUTED")
        var fixtures: Fixtures? = null
        fun case(id: String, action: () -> Unit) {
            val row = JSONObject().put("id", id)
            try { action(); row.put("passed", true) }
            catch (failure: Throwable) {
                row.put("passed", false).put("technical_failure", failure.javaClass.simpleName)
                failures.put(id)
            }
            cases.put(row)
        }
        try {
            check(BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 31)
            val context = instrumentation.targetContext
            check(context.packageName == EvaluationFixtureContract.TARGET_PACKAGE)
            check(context.packageManager.getPackageUid(context.packageName, 0) == Process.myUid())
            listOf(EvaluationFixtureContract.CONTROL_AUTHORITY, EvaluationFixtureContract.DOCUMENT_AUTHORITY).forEach { authority ->
                val info = requireNotNull(context.packageManager.resolveContentProvider(authority, 0))
                check(info.packageName == instrumentation.context.packageName && info.applicationInfo.uid != Process.myUid())
            }
            val fixture = Fixtures(context).also { fixtures = it }
            report.put("fixture_setup_complete", fixture.setupComplete)
            check(fixture.setupComplete)
            check(EvaluationFixtureContract.MAX_FIXTURE_BYTES == DiscoverySaveEvaluationJson.MAX_EXPORT_BYTES)
            val sealed = syntheticReport()
            check(sealed.state == SaveEvaluationSessionState.FINISHED)
            val expected = DiscoverySaveEvaluationJson.export(sealed).toByteArray(Charsets.UTF_8)

            case("empty_document_json_redaction") {
                export(context, sealed, fixture.result("empty"), DiagnosticExportPhase.SUCCEEDED)
                val snapshot = fixture.awaitClosed("empty")
                val bytes = requireNotNull(snapshot.getByteArray("bytes"))
                check(bytes.contentEquals(expected))
                check(snapshot.getBoolean("append_seen") && snapshot.getBoolean("only_rw"))
                check(snapshot.getInt("open_calls") == 1 && snapshot.getInt("opened") == 1)
                val text = bytes.toString(Charsets.UTF_8)
                listOf(SECRET_TITLE, SECRET_QUERY, SECRET_SOURCE, "example.invalid", "https://", "candidateId", "pageUrl").forEach {
                    check(!text.contains(it))
                }
                val json = JSONObject(text)
                check(json.getInt("schemaVersion") == 2 && json.getString("state") == "FINISHED")
                check(json.getString("evidence") == "SYNTHETIC_FIXTURE")
                check(json.getInt("unfinishedMethodAttempts") == 0)
                val overall = json.getJSONObject("overall")
                check(overall.getInt("uniquePages") == 3 && overall.getInt("actualTestPages") == 2)
                check(overall.getInt("notStartedPages") == 1 && overall.getBoolean("partial"))
                check(overall.getDouble("save1Share") == 0.5 && overall.getDouble("save2Share") == 0.5)
                check(overall.getDouble("fallbackShare") == 0.0 && overall.isNull("fallbackRescueRate"))
                check(overall.getJSONObject("save3").getInt("notTested") == 1)
                val providers = json.getJSONArray("providers")
                check(providers.length() == 2)
                for (index in 0 until providers.length()) check(providers.getJSONObject(index).getString("provider") == "source-${index + 1}")
            }
            case("nonempty_refused_bytes_preserved") {
                val before = requireNotNull(fixture.snapshot("nonempty").getByteArray("bytes"))
                check(before.contentEquals(EvaluationFixtureContract.SENTINEL))
                export(context, sealed, fixture.result("nonempty"), DiagnosticExportPhase.FAILED)
                val after = fixture.awaitClosed("nonempty")
                check(requireNotNull(after.getByteArray("bytes")).contentEquals(before))
                check(after.getBoolean("only_rw") && after.getBoolean("append_seen"))
                check(after.getInt("open_calls") == 1 && after.getInt("opened") == 1)
            }
            for ((id, flags) in listOf("missing_all_result_flags" to 0,
                "missing_read_result_flag" to Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                "missing_write_result_flag" to Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
                case(id) { rejected { AndroidEvaluationDocumentPort.fromResult(context, fixture.result("empty", flags)) } }
            }
            for ((id, fixtureId) in listOf("missing_all_grants" to "no_grant",
                "missing_read_grant" to "write_grant", "missing_write_grant" to "read_grant")) {
                case(id) {
                    rejected { AndroidEvaluationDocumentPort.fromResult(context, fixture.result(fixtureId)) }
                    check(fixture.snapshot(fixtureId).getInt("open_calls") == 0)
                }
            }
            val uri = fixture.uri("empty")
            val invalid = linkedMapOf<String, Intent?>(
                "null_result" to null,
                "missing_result_uri" to Intent().addFlags(EvaluationFixtureContract.READ_WRITE),
                "non_content_uri" to result(Uri.parse("https://example.invalid/owned")),
                "non_document_path" to result(Uri.parse("content://${EvaluationFixtureContract.DOCUMENT_AUTHORITY}/owned")),
                "tree_uri" to result(DocumentsContract.buildTreeDocumentUri(EvaluationFixtureContract.DOCUMENT_AUTHORITY, "owned")),
                "query_uri" to result(uri.buildUpon().appendQueryParameter("owned", "1").build()),
                "fragment_uri" to result(uri.buildUpon().fragment("owned").build()),
                "clip_data" to result(uri).apply { clipData = ClipData.newRawUri("owned", uri) },
                "unregistered_provider" to result(DocumentsContract.buildDocumentUri("com.easysavevideo.app.test.evaluation.absent", "owned")),
                "control_provider_uri" to result(EvaluationFixtureContract.CONTROL_URI),
            )
            invalid.forEach { (id, data) -> case(id) { rejected { AndroidEvaluationDocumentPort.fromResult(context, data) } } }
            case("invalid_selection_does_not_open_document") {
                check(fixture.snapshot("empty").getInt("open_calls") == 1)
            }
            case("provider_open_failure") {
                export(context, sealed, fixture.result("open_failure"), DiagnosticExportPhase.FAILED)
                val snapshot = fixture.snapshot("open_failure")
                check(snapshot.getInt("open_calls") == 1 && snapshot.getInt("opened") == 0)
                check(requireNotNull(snapshot.getByteArray("bytes")).isEmpty())
            }
            case("nonregular_pipe_refused") {
                export(context, sealed, fixture.result("pipe"), DiagnosticExportPhase.FAILED)
                val snapshot = fixture.awaitClosed("pipe")
                check(snapshot.getInt("open_calls") == 1 && snapshot.getInt("opened") == 1)
                check(snapshot.getInt("pipe_bytes") == 0 && snapshot.getBoolean("only_rw"))
            }
            case("read_only_descriptor_write_failure") {
                export(context, sealed, fixture.result("read_only"), DiagnosticExportPhase.FAILED)
                val snapshot = fixture.awaitClosed("read_only")
                check(snapshot.getInt("open_calls") == 1 && snapshot.getInt("opened") == 1)
                check(requireNotNull(snapshot.getByteArray("bytes")).isEmpty())
            }
        } catch (failure: Throwable) {
            failures.put("HARNESS_${failure.javaClass.simpleName}")
        } finally {
            val fixture = fixtures
            if (fixture == null) report.put("fixture_cleanup", "SETUP_OWNERSHIP_NOT_CONFIRMED")
            else case("exact_temporary_cleanup_and_grant_revocation") {
                val cleanup = fixture.cleanup()
                report.put("fixture_cleanup", JSONObject()
                    .put("complete", cleanup.getBoolean("complete"))
                    .put("descriptors_closed", cleanup.getBoolean("descriptors_closed"))
                    .put("grants_revoked", cleanup.getBoolean("grants_revoked"))
                    .put("exact_files_removed", cleanup.getBoolean("exact_files_removed")))
                check(cleanup.getBoolean("complete"))
                fixture.assertGrantsRevoked()
            }
        }
        // The technical report is returned through instrumentation, not persisted beside user files.
        instrumentation.finish(if (failures.length() == 0) -1 else 0, Bundle().apply {
            putString("stream", "Evaluation export technical report: $report\n")
        })
    }

    private fun export(context: Context, report: SaveEvaluationSessionReport, result: Intent, expected: DiagnosticExportPhase) {
        var selected: String? = null
        var dismissals = 0
        SealedEvaluationExport(true, { report }, Executor { it.run() }, { it() }, {},
            choose = { selected = it }, dismiss = { dismissals++ }).use { export ->
            check(export.begin(report))
            check(export.controller.state.phase == DiagnosticExportPhase.CHOOSING)
            val port = AndroidEvaluationDocumentPort.fromResult(context, result)
            export.controller.selected(requireNotNull(selected), port)
            check(export.controller.state.phase == expected && !export.controller.state.busy)
            check(!export.controller.state.releaseUnconfirmed && dismissals == 1)
        }
    }

    private fun result(uri: Uri, flags: Int = EvaluationFixtureContract.READ_WRITE): Intent = Intent().setData(uri).addFlags(flags)
    private fun rejected(action: () -> Any?) {
        var refused = false
        try { action() } catch (_: IllegalArgumentException) { refused = true }
        catch (_: SecurityException) { refused = true }
        check(refused)
    }

    private fun syntheticReport(): SaveEvaluationSessionReport {
        val session = DiscoverySaveEvaluationSession(SaveEvaluationEvidence.SYNTHETIC_FIXTURE).also { it.begin() }
        repeat(3) { index ->
            val candidate = requireNotNull(PendingCandidatePolicy.normalize(CandidateUrl(
                "https://example.invalid/owned-$index?query=$SECRET_QUERY",
                SourceReportedCandidateMetadata(title = SECRET_TITLE)), SECRET_SOURCE, SearchFilters()))
            session.register(candidate)
            if (index == 0) session.register(candidate.copy(provenance = setOf("owned-second-provider")))
            if (index < 2) {
                session.beginPage(candidate.candidateId)
                val primary = if (index == 0) SaveEvaluationOutcome.SUCCEEDED else SaveEvaluationOutcome.FAILED
                listOf(SaveEvaluationMethod.SAVE1, SaveEvaluationMethod.SAVE2).forEach { method ->
                    session.finishMethod(session.beginMethod(candidate.candidateId, method), primary)
                }
                // SAVE3 is deliberately untested in this live-session export scope.
            }
        }
        return session.finish()
    }

    private class Fixtures(private val context: Context) {
        private val resolver = context.contentResolver
        private val started = requireNotNull(resolver.call(EvaluationFixtureContract.CONTROL_URI, EvaluationFixtureContract.BEGIN, null, null))
        private val token = requireNotNull(started.getString("token"))
        val setupComplete: Boolean get() = started.getBoolean("setup_complete")
        fun uri(id: String): Uri {
            require(id in EvaluationFixtureContract.CASE_IDS)
            return Uri.parse(requireNotNull(started.getString("uri_$id")))
        }
        fun result(id: String, flags: Int = EvaluationFixtureContract.READ_WRITE) = EvaluationExportValidation.result(uri(id), flags)
        fun snapshot(id: String) = requireNotNull(resolver.call(EvaluationFixtureContract.CONTROL_URI,
            EvaluationFixtureContract.SNAPSHOT, token, Bundle().apply { putString("case", id) }))
        fun awaitClosed(id: String): Bundle {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (true) {
                val value = snapshot(id)
                check(value.getInt("close_errors") == 0)
                if (value.getInt("opened") == value.getInt("closed")) return value
                check(SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(20)
            }
        }
        fun cleanup(): Bundle = requireNotNull(resolver.call(EvaluationFixtureContract.CONTROL_URI, EvaluationFixtureContract.CLEANUP, token, null))
        fun assertGrantsRevoked() {
            EvaluationFixtureContract.CASE_IDS.forEach { id ->
                if (started.getString("uri_$id") == null) return@forEach
                listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION).forEach { flag ->
                    check(context.checkUriPermission(uri(id), Process.myPid(), Process.myUid(), flag) == PackageManager.PERMISSION_DENIED)
                }
            }
        }
    }
}
