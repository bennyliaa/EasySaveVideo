package com.easysavevideo.app.storage

import com.easysavevideo.app.save.SaveSettingsOwner
import com.easysavevideo.core.model.SaveSettings
import com.easysavevideo.core.model.TaskMethod
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Fixed in-memory primitive doubles exercise the real shared checked-commit boundary. */
class SaveMethodPreferencesPersistenceTest {
    private class Preferences {
        var methods = SaveSettings(directEnabled = false, streamEnabled = false, recordEnabled = false)
        var writes = 0
        fun commit(value: SaveSettings?): Boolean {
            if (value != null) { methods = value; writes++ }
            return true
        }
    }

    @Test fun missingLegacyKeysAndMalformedValuesHaveEnabledDefaults() {
        assertTrue(readSaveMethodPreference { true }) // SharedPreferences getBoolean missing-key default.
        assertFalse(readSaveMethodPreference { false })
        for (stored: Any in listOf("false", 0, 0L, 0.0, setOf("true")))
            assertTrue(readSaveMethodPreference { stored as Boolean })
        assertThrows(IllegalStateException::class.java) {
            readSaveMethodPreference { error("fixed store failure") }
        }
    }

    @Test fun unfinishedFailedAndUnconfirmedLoadsNeverResolveOrOverwriteUnknownKeys() {
        val prefs = Preferences()
        for (include in listOf(false, true)) for (loaded in listOf(false, true)) {
            if (include && loaded) continue
            SettingsWriteBoundary.commit(false, { null }) {
                prefs.commit(saveMethodPreferencesForWrite(include, loaded, SaveSettings()) { error("unknown owner") })
            }
        }
        assertEquals(0, prefs.writes)
        assertEquals(SaveSettings(false, 1, false, false, false), prefs.methods)
    }

    @Test fun absentAcceptedOwnerOmitsKeysButStandaloneLoadedStoreCanSave() {
        assertNull(saveMethodPreferencesForWrite(true, true, SaveSettings()) { null })
        val snapshot = SaveSettings(true, 2, false, true, false)
        assertEquals(snapshot, saveMethodPreferencesForWrite(true, true, snapshot, null))
        assertNull(saveMethodPreferencesForWrite(true, false, snapshot, null))
    }

    @Test fun staleActivityWritersResolveCurrentAcceptedBitsInEitherCommitOrder() {
        for (oldFirst in listOf(false, true)) {
            val owner = SaveSettingsOwner(); owner.initialize(SaveSettings())
            val prefs = Preferences()
            val staleSnapshot = owner.snapshot()
            val oldWrite = {
                SettingsWriteBoundary.commit(false, { null }) {
                    prefs.commit(saveMethodPreferencesForWrite(true, true, staleSnapshot, owner::acceptedMethodSettings))
                }
            }
            owner.updateMethodEnabled(TaskMethod.DIRECT, false)
            owner.updateMethodEnabled(TaskMethod.STREAM, false)
            val currentSnapshot = owner.snapshot()
            val newWrite = {
                SettingsWriteBoundary.commit(false, { null }) {
                    prefs.commit(saveMethodPreferencesForWrite(true, true, currentSnapshot, owner::acceptedMethodSettings))
                }
            }
            if (oldFirst) { oldWrite(); newWrite() } else { newWrite(); oldWrite() }
            assertEquals(currentSnapshot, prefs.methods); assertEquals(2, prefs.writes)
        }
    }

    @Test fun failedCommitDoesNotRollBackLiveChoicesOrClaimSuccessfulPersistence() {
        val owner = SaveSettingsOwner(); owner.initialize(SaveSettings())
        val accepted = owner.updateMethodEnabled(TaskMethod.RECORD, false)!!
        val prefs = Preferences()
        assertThrows(IllegalStateException::class.java) {
            SettingsWriteBoundary.commit(false, { null }) {
                assertEquals(accepted, saveMethodPreferencesForWrite(true, true, SaveSettings(), owner::acceptedMethodSettings))
                false
            }
        }
        assertEquals(accepted, owner.acceptedMethodSettings()); assertEquals(0, prefs.writes)
        SettingsWriteBoundary.commit(false, { null }) {
            prefs.commit(saveMethodPreferencesForWrite(true, true, SaveSettings(), owner::acceptedMethodSettings))
        }
        assertEquals(accepted, prefs.methods)
    }

    @Test fun crossInstanceSerializationPreventsAnOldResolvedCommitLandingAfterTheNewChoice() {
        val owner = SaveSettingsOwner(); owner.initialize(SaveSettings())
        val prefs = Preferences()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1); val secondResolved = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val first = Thread {
            try { SettingsWriteBoundary.commit(false, { null }) {
                val value = saveMethodPreferencesForWrite(true, true, SaveSettings(), owner::acceptedMethodSettings)
                entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); prefs.commit(value)
            } } catch (error: Throwable) { failure.set(error) }
        }
        val second = Thread {
            secondStarted.countDown()
            try { SettingsWriteBoundary.commit(false, { null }) {
                prefs.commit(saveMethodPreferencesForWrite(true, true, SaveSettings()) {
                    secondResolved.countDown(); owner.acceptedMethodSettings()
                })
            } } catch (error: Throwable) { failure.set(error) }
        }
        first.start()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            owner.updateMethodEnabled(TaskMethod.DIRECT, false)
            second.start(); assertTrue(secondStarted.await(3, TimeUnit.SECONDS))
            assertFalse(secondResolved.await(50, TimeUnit.MILLISECONDS))
        } finally { release.countDown(); first.join(3_000); second.join(3_000) }
        assertFalse(first.isAlive); assertFalse(second.isAlive); failure.get()?.let { throw it }
        assertEquals(0L, secondResolved.count)
        assertEquals(owner.snapshot(), prefs.methods); assertEquals(2, prefs.writes)
    }
}
