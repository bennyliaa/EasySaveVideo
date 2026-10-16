package com.easysavevideo.app.vault.playback

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** SOURCE_CONTRACT only. These guards do not execute Android surfaces, Player or AudioTrack. */
class VaultPlaybackPlatformSourceContractTest {
    private fun source(): String = File(System.getProperty("esv.vault.playback.source") ?:
        "src/main/kotlin/com/easysavevideo/app/vault/playback/AndroidVaultPlayback.kt").readText()
    @Test fun slotAndNativeRevokeSynchronouslyCloseSharedPublicationToken() {
        val source = source()
        val cover = source.substringAfter("    fun cover() {").substringBefore("    fun requestRetirement()")
        assertTrue(cover.indexOf("ticket.host.revoke()") < cover.indexOf("opaque.visibility = View.VISIBLE"))
        assertTrue(cover.indexOf("ticket.host.revoke()") < cover.indexOf("prepared.revoke()"))
        val revoke = source.substringAfter("    override fun revoke() {").substringBefore("    override fun readyForReadRetirement()")
        assertTrue(revoke.indexOf("ticket.host.revoke()") < revoke.indexOf("revoked.set(true)"))
        assertTrue(revoke.indexOf("ticket.host.revoke()") < revoke.indexOf("signal()"))
    }
    @Test fun opaqueCoverDoesNotChangeSurfaceVisibilityOrAttachment() {
        val cover = source().substringAfter("    fun cover() {").substringBefore("    fun requestRetirement()")
        assertFalse(cover.contains("surfaceView.visibility ="))
        assertFalse(cover.contains("removeView("))
        val removal = source().substringAfter("prepared.detachSurface(exact)").substringBefore("    fun observeDrain()")
        assertTrue(removal.indexOf("if (!ok)") < removal.indexOf("surfaceView.visibility = View.GONE"))
    }
    @Test fun systemHolderDestructionRevokesBeforeIdentityChecksOrCallbacks() {
        val destroyed = source().substringAfter("override fun surfaceDestroyed(holder: SurfaceHolder) {").substringBefore("        })")
        assertTrue(destroyed.indexOf("cover()") < destroyed.indexOf("val exact = surface"))
        assertTrue(destroyed.indexOf("cover()") < destroyed.indexOf("notifyChanged()"))
    }
    @Test fun actualAdapterHasTwoNonMainOwnersAndBorrowedRawSurface() {
        val source = source()
        assertTrue(source.contains(".setLooper(requireNotNull(command).looper)"))
        assertTrue(source.contains(".setPlaybackLooper(requireNotNull(playback).looper)"))
        assertTrue(source.contains("actual.setVideoSurface(surface.surface)"))
        assertFalse(source.contains("setVideoSurfaceView("))
        assertFalse(source.contains("setVideoSurfaceHolder("))
        assertFalse(source.contains("surface.surface.release("))
        assertTrue(source.contains("while (!ledger.mayJoinLoopers())"))
    }
}
