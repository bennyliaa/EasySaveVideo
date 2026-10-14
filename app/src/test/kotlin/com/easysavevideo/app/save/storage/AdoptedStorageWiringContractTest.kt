package com.easysavevideo.app.save.storage

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source contracts only; owner tests exercise real host RAFs but not AndroidSaveStorage/SQLite. */
class AdoptedStorageWiringContractTest {
    private val storage = File("src/main/kotlin/com/easysavevideo/app/save/storage/AndroidSaveStorage.kt").readText()
    @Test fun ownerFactoryIsInactiveExactRegistryScopedAndUsesRealCatalogAuthority() {
        val text = storage.substringAfter("internal fun adoptedCanonicalOutputOwner(").substringBefore("internal fun prepareMovRemux(")
        assertTrue(text.contains("canonicalReadBindings !== bindings"))
        assertTrue(text.contains("readCatalog = { catalog.findOwned(it) }"))
        assertTrue(text.contains("readSave = { queryRecord(\"task_id=?\", arrayOf(it)) }"))
        assertTrue(text.contains("ensureNoExistingHandles = ::ensureNoCanonicalAcquisitionConflict"))
        assertFalse(text.contains("ensureNoExistingHandles = ::ensureNoWriter"))
        val runtime = File("src/main/kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt").readText()
        assertFalse(runtime.contains("adoptedCanonicalOutputOwner("))
        assertFalse(runtime.contains("AdoptedVerificationBindings("))
    }
    @Test fun allExistingWriterExclusionAddsCanonicalReservationWithoutSelfRecursion() {
        val combined = storage.substringAfter("private fun ensureNoWriter(").substringBefore("private fun queryRecord(")
        assertTrue(combined.contains("ensureNoLegacyOrRemuxWriter(artifact)"))
        assertTrue(combined.contains("ensureNoCanonicalReader(artifact.artifactId)"))
        assertTrue(combined.contains("canonicalReadOwner?.hasReservation(artifactId)"))
        val oldOnly = combined.substringAfter("private fun ensureNoLegacyOrRemuxWriter(").substringBefore("private fun ensureNoCanonicalReader(")
        assertTrue(oldOnly.contains("openWriters")); assertTrue(oldOnly.contains("nativeWriters"))
        assertTrue(oldOnly.contains("normalizationFilesOwner?.hasActiveSession"))
        assertFalse(oldOnly.contains("canonicalReadOwner"))
    }
    @Test fun recoveryGetterIsMemoryOnlyAndStorageCloseCannotDropReadOwner() {
        val getter = storage.substringAfter("internal fun normalizationReleaseUnconfirmed()").substringBefore("internal val normalizationLedger")
        assertTrue(getter.contains("canonicalReadOwner?.requiresProcessRecovery()"))
        assertTrue(getter.contains("normalizationFilesOwner?.releaseUnconfirmed()"))
        assertFalse(getter.contains("synchronized")); assertFalse(getter.contains("fileCall"))
        assertFalse(getter.contains("adoptedCanonicalOutputOwner("))
        val close = storage.substringAfter("override fun close() = synchronized(fileLock) {\n        synchronized(failedOwnerLock)")
            .substringBefore("private fun releaseStorageOwner(")
        assertTrue(close.contains("canonicalReadOwner?.requiresProcessRecovery()")); assertTrue(close.contains("releaseStorageOwner(helper)"))
        assertTrue(close.indexOf("canonicalReadOwner?.requiresProcessRecovery()") < close.indexOf("releaseStorageOwner(helper)"))
        assertTrue(close.contains("canonicalReadOwner?.hasAnyLeases()"))
        assertTrue(close.indexOf("canonicalReadOwner?.hasAnyLeases()") < close.indexOf("releaseStorageOwner(helper)"))
    }
    @Test fun specializedRemuxOwnerGetsCanonicalConflictHook() {
        assertTrue(storage.contains("ensureNoCanonicalReaders = { ensureNoCanonicalReader(it.artifactId) }"))
        val text = File("src/main/kotlin/com/easysavevideo/app/save/storage/NormalizationAttemptOwner.kt").readText()
        assertTrue(text.contains("ensureNoCanonicalReaders(owner); checkOperation(context)"))
        assertTrue(text.contains("available(); ensureNoCanonicalReaders(key.owner)"))
        assertTrue(text.contains("else { ensureNoCanonicalReaders(owner); ensureNoExistingHandles(owner)"))
    }
    @Test fun activeReadReservationGuardsBothArtifactIdentitiesBeforePublicationAndRevokesAfterWrite() {
        val cas = storage.substringAfter("override fun compareAndSet(expectedRevision: Long, next: SaveRecord)")
            .substringBefore("override fun listUnfinished")
        assertTrue(cas.contains("listOfNotNull(prior.artifact?.artifactId, next.artifact?.artifactId)"))
        assertTrue(cas.contains("canonicalReadOwner?.hasReservation(it) == true"))
        val guard = cas.indexOf("AdoptedCanonicalMutationPolicy.permitsStopOnly(prior, next)")
        val publication = cas.indexOf("if (next.task.state == TaskState.SUCCEEDED)")
        val write = cas.indexOf("val changed = db.update")
        val revoke = cas.indexOf("readOwnedArtifact?.let { canonicalReadOwner?.revoke(it) }")
        assertTrue(guard >= 0 && publication > guard && write > publication && revoke > write)
        assertTrue(cas.substring(write, revoke).contains("if (changed != 1) throw"))
        assertFalse(cas.contains("canonicalReadOwner?.close"))
    }

}
