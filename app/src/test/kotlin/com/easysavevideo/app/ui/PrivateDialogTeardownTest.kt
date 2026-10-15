package com.easysavevideo.app.ui

import org.junit.Assert.*
import org.junit.Test

/** Exercises production teardown ports, not Android attachment or compositor/privacy acceptance. */
class PrivateDialogTeardownTest {
    private class Fixture {
        val lifetime = PrivateDialogLifetime()
        var decorAttached = true
        var rootAttached = true
        var composition = true
        var contentChildren = true
        var rootChildren = true
        var registered = true
        var dismissFails = false
        var dismissDefersDetach = false
        var disposeFails = false
        var removeContentFails = false
        var removeRootFails = false
        var protectFails = false
        var readbackFails = false
        var releaseFails = false
        var childCloseFails = false
        var revoked = 0
        var dismissed = 0
        var disposed = 0
        var contentRemovals = 0
        var rootRemovals = 0
        var releaseAttempts = 0
        var releases = 0
        var duringDismiss: () -> Unit = {}
        val teardown = PrivateDialogTeardown(lifetime,
            revoke = { revoked++ },
            protect = { if (protectFails) throw IllegalStateException() },
            dismiss = {
                dismissed++
                duringDismiss()
                if (dismissFails) throw IllegalStateException()
                if (!dismissDefersDetach) { decorAttached = false; rootAttached = false }
            },
            disposeComposition = {
                disposed++
                if (disposeFails) throw IllegalStateException()
                composition = false
            },
            removeContentChildren = {
                contentRemovals++
                if (removeContentFails) throw IllegalStateException()
                contentChildren = false
            },
            removeRootChildren = {
                rootRemovals++
                if (removeRootFails) throw IllegalStateException()
                rootChildren = false
            },
            detachedAndEmpty = {
                if (readbackFails) throw IllegalStateException()
                !decorAttached && !rootAttached && !composition && !contentChildren && !rootChildren
            },
            release = {
                releaseAttempts++
                if (childCloseFails) throw IllegalStateException()
                if (releaseFails) false else { registered = false; releases++; true }
            })
    }

    @Test fun thrownDismissKeepsAttachedWindowRegisteredAfterRevocation() {
        val f = Fixture().apply { dismissFails = true }
        f.teardown.close()
        assertTrue(f.lifetime.isClosed)
        assertEquals(1, f.revoked)
        assertFalse(f.composition)
        assertFalse(f.rootChildren)
        assertTrue(f.registered)
        assertFalse(f.teardown.isComplete)
        assertEquals(0, f.releaseAttempts)
        f.dismissFails = false
        f.teardown.close()
        assertTrue(f.teardown.isComplete)
        assertFalse(f.registered)
        assertEquals(1, f.revoked)
    }

    @Test fun failedContentRemovalDoesNotSkipRootRemovalOrUnregisterRetainedSubtree() {
        val f = Fixture().apply { removeContentFails = true }
        f.teardown.close()
        assertEquals(1, f.rootRemovals)
        assertFalse(f.rootChildren)
        assertTrue(f.contentChildren)
        assertTrue(f.registered)
        assertEquals(0, f.releaseAttempts)
        f.removeContentFails = false
        f.teardown.close(false)
        assertTrue(f.teardown.isComplete)
    }

    @Test fun failedRootRemovalOrCompositionDisposalMustBeRetried() {
        val f = Fixture().apply { removeRootFails = true; disposeFails = true }
        f.teardown.close()
        assertTrue(f.rootChildren)
        assertTrue(f.composition)
        assertTrue(f.registered)
        assertEquals(1, f.contentRemovals)
        f.removeRootFails = false
        f.teardown.close(false)
        assertFalse(f.rootChildren)
        assertTrue(f.registered)
        f.disposeFails = false
        f.teardown.close(false)
        assertTrue(f.teardown.isComplete)
        assertEquals(1, f.revoked)
    }

    @Test fun deferredNativeDismissNeedsBothExactDecorAndRootDetach() {
        val f = Fixture().apply { dismissDefersDetach = true }
        f.teardown.close()
        assertTrue(f.registered)
        f.rootAttached = false
        f.teardown.close(false)
        assertTrue(f.registered)
        assertFalse(f.teardown.isComplete)
        f.decorAttached = false
        f.teardown.close(false)
        assertTrue(f.teardown.isComplete)
        assertEquals(1, f.dismissed)
        assertEquals(1, f.revoked)
    }

    @Test fun lateDetachStillFinishesAnAlreadyClosedLifetime() {
        val f = Fixture().apply { dismissFails = true }
        f.teardown.close()
        assertTrue(f.lifetime.isClosed)
        f.decorAttached = false
        f.rootAttached = false
        f.teardown.close(false)
        assertTrue(f.teardown.isComplete)
        assertFalse(f.registered)
        assertEquals(1, f.releases)
    }

    @Test fun nativeDismissReentrancyCannotUnregisterBeforeNativeAttemptFinishes() {
        val f = Fixture().apply { decorAttached = false; rootAttached = false }
        f.teardown.beforeNativeDismiss()
        assertTrue(f.lifetime.isClosed)
        f.teardown.close(false)
        assertTrue(f.registered)
        assertEquals(0, f.disposed)
        assertEquals(0, f.releaseAttempts)
        f.teardown.afterNativeDismiss()
        assertTrue(f.teardown.isComplete)
        assertEquals(0, f.dismissed)
        assertEquals(1, f.revoked)
    }

    @Test fun duplicatedLateNativeCallbacksCannotRepeatReleaseOrRevocation() {
        val f = Fixture()
        f.teardown.close()
        f.teardown.close(false)
        f.teardown.beforeNativeDismiss()
        f.teardown.afterNativeDismiss()
        f.teardown.close()
        assertEquals(1, f.releases)
        assertEquals(1, f.revoked)
        assertEquals(1, f.disposed)
        assertEquals(1, f.dismissed)
    }

    @Test fun synchronousDismissCallbackDoesNotReenterCleanup() {
        val f = Fixture()
        f.duringDismiss = { f.teardown.close(false) }
        f.teardown.close()
        assertTrue(f.teardown.isComplete)
        assertEquals(1, f.disposed)
        assertEquals(1, f.releases)
    }

    @Test fun childCloseThrowAndRejectedReleaseKeepRegistrationRetryable() {
        val f = Fixture().apply { childCloseFails = true }
        f.teardown.close()
        assertTrue(f.registered)
        assertFalse(f.teardown.isComplete)
        f.childCloseFails = false
        f.releaseFails = true
        f.teardown.close(false)
        assertTrue(f.registered)
        f.releaseFails = false
        f.teardown.close(false)
        assertTrue(f.teardown.isComplete)
        assertEquals(3, f.releaseAttempts)
        assertEquals(1, f.releases)
    }

    @Test fun failedReadbackNeverReleasesEvenAfterAllCleanupReturned() {
        val f = Fixture().apply { readbackFails = true }
        f.teardown.close()
        assertTrue(f.registered)
        assertEquals(0, f.releaseAttempts)
        f.readbackFails = false
        f.teardown.close(false)
        assertTrue(f.teardown.isComplete)
    }

    @Test fun failedProtectionStillAttemptsEveryIndependentCleanup() {
        val f = Fixture().apply { protectFails = true }
        f.teardown.close()
        assertEquals(1, f.revoked)
        assertEquals(1, f.dismissed)
        assertEquals(1, f.disposed)
        assertEquals(1, f.contentRemovals)
        assertEquals(1, f.rootRemovals)
        assertTrue(f.teardown.isComplete)
    }
}
