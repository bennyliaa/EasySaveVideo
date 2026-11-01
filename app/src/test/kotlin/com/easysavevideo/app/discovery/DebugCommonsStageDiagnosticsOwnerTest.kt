package com.easysavevideo.app.discovery

import org.junit.Assert.*
import org.junit.Test

class DebugCommonsStageDiagnosticsOwnerTest {
    @Test fun releaseOwnerCannotAllocateArmOrPreviewARecorder() {
        val owner = DebugCommonsStageDiagnosticsOwner(false)
        assertNull(owner.recorder)
        assertNull(owner.preview().diagnostics)
        assertFalse(owner.preview().active)
        assertFalse(owner.preview().armed)
        assertNull(owner.start(owner.preview().revision))
        assertNull(owner.stop(owner.preview().revision))
        assertNull(owner.captureAuthorization())
    }

    @Test fun defaultOffAndExplicitStartOnlyArmsWithoutStartingAnySession() {
        val owner = DebugCommonsStageDiagnosticsOwner(true)
        val initial = owner.preview()
        assertFalse(initial.active)
        assertFalse(initial.diagnostics!!.enabled)
        assertTrue(initial.diagnostics.samples.isEmpty())
        assertNull(initial.diagnostics.sessionAlias)
        val started = requireNotNull(owner.start(initial.revision))
        assertTrue(started.active)
        assertTrue(started.armed)
        assertTrue(started.diagnostics!!.enabled)
        assertFalse(started.diagnostics.sessionOpen)
        assertNull(started.diagnostics.sessionAlias)
        assertNotNull(owner.captureAuthorization())
    }

    @Test fun repeatedAndStaleStartCannotReplaceTheExactArmedCapability() {
        val owner = DebugCommonsStageDiagnosticsOwner(true)
        val before = owner.preview()
        val started = requireNotNull(owner.start(before.revision))
        val token = owner.captureAuthorization()
        assertNull(owner.start(before.revision))
        assertNull(owner.start(started.revision))
        assertSame(token, owner.captureAuthorization())
    }

    @Test fun stopRetiresCapabilityAndOldStopCannotAffectNewArm() {
        val owner = DebugCommonsStageDiagnosticsOwner(true)
        val started = requireNotNull(owner.start(owner.preview().revision))
        val oldToken = owner.captureAuthorization()
        val stopped = requireNotNull(owner.stop(started.revision))
        assertFalse(stopped.active)
        assertFalse(stopped.armed)
        assertFalse(stopped.diagnostics!!.enabled)
        assertNull(owner.captureAuthorization())
        val replacement = requireNotNull(owner.start(stopped.revision))
        val newToken = owner.captureAuthorization()
        assertNotSame(oldToken, newToken)
        assertNull(owner.stop(started.revision))
        assertEquals(replacement, owner.preview())
        assertSame(newToken, owner.captureAuthorization())
    }

    @Test fun synchronousLifecycleRevocationRejectsOldUiRevisionAndRequiresExplicitNewStart() {
        val owner = DebugCommonsStageDiagnosticsOwner(true)
        val armed = requireNotNull(owner.start(owner.preview().revision))
        val stopped = owner.revoke()
        assertFalse(stopped.active)
        assertNull(owner.captureAuthorization())
        assertNull(owner.start(armed.revision))
        assertNull(owner.stop(armed.revision))
        assertEquals(stopped, owner.preview())
        assertNotNull(owner.start(stopped.revision))
    }

    @Test fun previewIsReadOnlyAndDoesNotConsumeTheArmedSearch() {
        val owner = DebugCommonsStageDiagnosticsOwner(true)
        val armed = requireNotNull(owner.start(owner.preview().revision))
        val token = owner.captureAuthorization()
        repeat(100) { assertEquals(armed, owner.preview()) }
        assertSame(token, owner.captureAuthorization())
        assertNull(owner.preview().diagnostics!!.sessionAlias)
    }
}
