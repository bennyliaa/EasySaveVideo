package com.easysavevideo.app.vault

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Structural wiring evidence only, never Android frame/Surface/codec/security acceptance. */
class VaultPlaybackHostSourceContractTest {
    private fun source(path: String): String = File(System.getProperty("esv.vault.host.source")
        ?: "src/main/kotlin/com/easysavevideo/app").resolve(path).readText()
    private fun requiredIndex(text: String, marker: String): Int = text.indexOf(marker).also {
        assertTrue("Missing source marker: $marker", it >= 0)
    }
    private fun section(text: String, start: String, end: String): String {
        val first = requiredIndex(text, start); val last = requiredIndex(text, end)
        assertTrue("Source section markers out of order", last > first)
        return text.substring(first, last)
    }
    @Test fun actualProductionEntryInjectsFactoryAndHostButRemainsDisabled() {
        val entry = source("vault/AndroidVaultRuntimeEntry.kt")
        assertTrue(entry.contains("const val ENABLED: Boolean = false"))
        assertTrue(entry.contains("AndroidVaultPlaybackFactory(context.applicationContext), presentation"))
        assertTrue(source("vault/VaultRuntime.kt").contains("VaultPlaybackRuntime(gate, authentication, store, clock, work, ui, scheduler"))
    }
    @Test fun stableNativeSlotIsOutsideTheNavHostRouteBranch() {
        val main = source("MainActivity.kt")
        val stable = requiredIndex(main, "vaultHost?.let { VaultPlaybackSurfaceHost(it) }")
        val routeEnd = requiredIndex(main, "if (!browserChild) SideEffect { childApplied() }")
        assertTrue(stable > routeEnd)
        val ui = source("vault/VaultPlaybackUi.kt")
        assertTrue(ui.contains("AndroidView(factory = { host.playbackContainer }"))
        assertFalse(ui.contains("if (host.playbackSlotRetained) AndroidView"))
        assertTrue(ui.contains("host.playbackCompositionRemoved()"))
    }
    @Test fun actualWindowClaimUsesTheNativeContainerAndForbidsCapture() {
        val owner = source("browser/privacy/PrivateMainPresentationOwner.kt")
        assertTrue(owner.contains("val content = vaultClaim.host.playbackContainer"))
        assertTrue(owner.contains("vaultClaim.target, vaultClaim.presentation, content, actualParent"))
        assertTrue(owner.contains("vaultClaim.host.playbackCompositionCurrent() && ownsContent(content)"))
        assertEquals(2, "capturePolicy = PrivateCapturePolicy.FORBIDDEN".toRegex(RegexOption.LITERAL).findAll(owner).count())
    }
    @Test fun hostCoversBeforeRetirementAndOnlyRemovesAfterExactDrain() {
        val host = source("vault/VaultProtectedHost.kt")
        val revoke = section(host, "fun revokePlaybackPresentation", "fun playbackProtectionLost")
        assertTrue(requiredIndex(revoke, "playbackSlot?.cover()") < requiredIndex(revoke, "playbackSlot?.requestRetirement()"))
        assertFalse(revoke.contains("if (ordinaryReleaseAllowed()"))
        val progress = section(host, "fun playbackProgressed()", "fun playbackCompositionCurrent")
        assertTrue(progress.contains("if (drain == VaultPlaybackDrain.RELEASED)"))
        assertTrue(progress.contains("playbackContainer.removeView(slot.view)"))
        assertTrue(host.contains("!playbackAcquisitionUnconfirmed && playbackSlot == null && runtime.playbackCleanupComplete()"))
    }
    @Test fun hostToRuntimeButtonsAndExecutionFencesAreReal() {
        val ui = source("vault/VaultRuntimeUi.kt")
        assertTrue(ui.contains("host.openPlayback(item.ref)"))
        val playback = source("vault/VaultPlaybackUi.kt")
        listOf("host.playPlayback(ticket)", "host.pausePlayback(ticket)", "host.seekPlayback(ticket,", "host.closePlayback(ticket)").forEach {
            assertTrue(it, playback.contains(it))
        }
        val runtime = source("vault/VaultRuntime.kt")
        assertTrue(runtime.contains("ref !in revealedPlaybackRefs"))
        assertTrue(runtime.contains("readLease === lease && activeRoot === root"))
        assertTrue(runtime.contains("media?.pending() == true || media?.active() == true"))
    }
    @Test fun playbackStopDoesNotBecomeAnAccountLockOrOrdinaryRecordingStop() {
        val runtime = source("vault/VaultRuntime.kt")
        val stop = section(runtime, "fun revokePlayback()", "fun playbackHostProgressed")
        assertTrue(stop.contains("playback?.revokeAll()")); assertFalse(stop.contains("transition("))
        listOf("vault/VaultProtectedHost.kt", "vault/VaultPlaybackUi.kt", "vault/AndroidVaultPresentationOwner.kt").forEach { path ->
            val text = source(path)
            listOf("stopRecording", "cancelRecording", "revokeRecording", "MediaSession", "CacheDataSource", "createTempFile").forEach {
                assertFalse("$path must not invoke $it", text.contains(it))
            }
        }
    }
    @Test fun presenterHasExactTicketGenerationFenceAndNoDuplicateQueue() {
        val presenter = source("vault/AndroidVaultPresentationOwner.kt")
        assertTrue(presenter.contains("target?.playbackReservation()"))
        assertTrue(presenter.contains("vaultPlaybackViewMatchesReservation(ticket, latest.playback.ticket, reserved,"))
        assertTrue(presenter.contains("view.copy(positionMillis = 0, durationMillis = null, seekable = false)"))
        val wiring = section(source("vault/VaultRuntime.kt"), "private val playback:", "/** Called by the playback leaf")
        assertTrue(wiring.contains("{ view -> presentation.presentPlayback(view) }, ::tryAcknowledgeCleanup, thumbnailFactory)"))
    }
    @Test fun presentationDetachDoesNotReverseRuntimeLockOrder() {
        val detach = section(source("vault/AndroidVaultPresentationOwner.kt"), "fun detachAfterActualRetirement", "private fun requestCover")
        assertTrue(requiredIndex(detach, "val retired = value.actualHostRetired()") < requiredIndex(detach, "synchronized(gate)"))
        assertFalse(detach.contains("host === value && value.actualHostRetired()"))
    }
    @Test fun fixedWarningDrawsAboveCoverWithoutExposingContentOrTakingDynamicText() {
        val root = source("browser/privacy/PrivateContentRoot.kt")
        assertTrue(root.contains("fun showVaultPlaybackReleaseWarning(show: Boolean)"))
        assertTrue(root.contains("R.string.vault_playback_release_warning"))
        assertTrue(root.contains("!vaultPlaybackReleaseWarning || !protection.covered"))
        val draw = section(root, "override fun draw(canvas: Canvas)", "private fun hideContentAfterFailure()")
        assertTrue(requiredIndex(draw, "canvas.drawColor(Color.BLACK") < requiredIndex(draw, "drawVaultPlaybackReleaseWarning(canvas)"))
        assertTrue(root.contains("if (protection.covered) return true"))
        val host = source("vault/VaultProtectedHost.kt")
        assertTrue(host.contains("view.status == VaultPlaybackStatus.UNAVAILABLE && !runtime.playbackCleanupComplete()"))
    }
}
