package com.easysavevideo.app.save

import com.easysavevideo.app.save.storage.SaveStorageInitializationProbe
import com.easysavevideo.app.save.storage.SaveStorageInitializationStage as S
import org.junit.Test

class SaveInitializationBoundaryTest {
    @Test fun asynchronousInstallPreservesFailureOwnerAndFixedPhase() {
        val d = SaveRuntimeFailureDiagnostics()
        var callbacks = 0
        val owner = MediaTaskActivityHandoff { callbacks++ }
        var queued: (() -> Unit)? = null
        var ready = false
        val original = IllegalStateException("secret path token")
        val lines = mutableListOf<String>()
        check(owner.enqueue({ queued = it; true }) {
            d.install({ ready }, lines::add) { throw original }
        })
        owner.preparationFailed()
        check(owner.active && lines.isEmpty())
        d.stage = SaveRuntimeFailureDiagnostics.Stage.READY
        try { queued!!(); error("expected failure") } catch (actual: IllegalStateException) { check(actual === original) }
        check(!owner.active && callbacks == 1 && !ready)
        check(lines == listOf("save_error phase=INSTALL category=STATE ready=false"))
    }
    @Test fun rejectedPostStillDoesNotInstallOrLogOrRetry() {
        val d = SaveRuntimeFailureDiagnostics()
        val owner = MediaTaskActivityHandoff {}
        var attempts = 0; var installed = false
        val lines = mutableListOf<String>()
        check(!owner.enqueue({ attempts++; false }) { d.install({ false }, lines::add) { installed = true } })
        check(attempts == 1 && !installed && !owner.active && lines.isEmpty())
    }
    @Test fun failingSinkPreservesOriginalEvenAfterReadyPublication() {
        val d = SaveRuntimeFailureDiagnostics()
        val original = java.io.IOException("secret")
        var line = ""
        try { d.install({ true }, { line = it; throw IllegalArgumentException() }) { throw original } }
        catch (actual: java.io.IOException) { check(actual === original) }
        check(line == "save_error phase=INSTALL category=IO ready=true")
    }
    @Test fun installAndWorkerShareEightEventBudget() {
        val d = SaveRuntimeFailureDiagnostics()
        repeat(7) { check(d.failure(Exception(), false) != null) }
        val lines = mutableListOf<String>()
        repeat(3) { try { d.install({ false }, lines::add) { throw Exception() } } catch (_: Exception) {} }
        check(lines.size == 1 && d.failure(Exception(), true) == null)
    }
    @Test fun diagnosticErrorAlsoCannotReplaceOriginal() {
        val d = SaveRuntimeFailureDiagnostics()
        val original = IllegalArgumentException("private")
        try { d.install({ false }, { throw AssertionError("sink") }) { throw original } }
        catch (actual: IllegalArgumentException) { check(actual === original) }
        val probe = SaveStorageInitializationProbe { throw AssertionError("observer") }
        check(probe.at(S.STORAGE_HELPER) { 42 } == 42)
    }
    @Test fun nestedDatabaseFailureKeepsInnermostStepAndOriginal() {
        val stages = mutableListOf<S>()
        val probe = SaveStorageInitializationProbe(stages::add)
        val original = java.io.IOException("private database")
        var opens = 0
        try { probe.at(S.STORAGE_PENDING_DELETES) {
            probe.at(S.STORAGE_DATABASE) { opens++; throw original }
        } } catch (actual: java.io.IOException) { check(actual === original) }
        probe.at(S.STORAGE_HELPER) { /* Cleanup cannot overwrite the original failing stage. */ }
        check(stages == listOf(S.STORAGE_PENDING_DELETES, S.STORAGE_DATABASE) && opens == 1)
    }
    @Test fun successfulDatabaseStepRestoresParentAndCompletionDisablesObservation() {
        val stages = mutableListOf<S>()
        val probe = SaveStorageInitializationProbe(stages::add)
        var calls = 0
        probe.at(S.STORAGE_PENDING_DELETES) { probe.at(S.STORAGE_DATABASE) { calls++ } }
        check(stages == listOf(S.STORAGE_PENDING_DELETES, S.STORAGE_DATABASE, S.STORAGE_PENDING_DELETES))
        probe.complete()
        probe.at(S.STORAGE_DATABASE) { calls++ }
        check(calls == 2 && stages.size == 3)
    }
    @Test fun stageObserverFailureCannotReplaceStorageFailure() {
        val probe = SaveStorageInitializationProbe { throw IllegalStateException("sink") }
        val original = SecurityException("private")
        try { probe.at(S.STORAGE_LEASE) { throw original } }
        catch (actual: SecurityException) { check(actual === original) }
    }
}
