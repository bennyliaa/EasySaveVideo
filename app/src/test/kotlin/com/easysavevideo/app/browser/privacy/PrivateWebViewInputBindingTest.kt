package com.easysavevideo.app.browser.privacy

import org.junit.Assert.*
import org.junit.Test

/** Pure immutable binding/token tests. No real WebView, IME or EditorInfo/provider-drain evidence. */
class PrivateWebViewInputBindingTest {
    @Test fun unexposedOrWrongPresentationCannotAcquireInput() {
        val root = PrivateInputLifetime(Any()); val presentation = Any()
        val binding = PrivateWebViewInputBinding(root, Any(), presentation)
        assertNull(binding.acquire())
        root.publish(Any()); assertNull(binding.acquire())
        root.publish(presentation); assertNotNull(binding.acquire())
    }
    @Test fun exactRootPresentationAndViewAreCapturedBeforeConnectionCreation() {
        val rootIdentity = Any(); val root = PrivateInputLifetime(rootIdentity)
        val presentation = Any(); val view = Any()
        val binding = PrivateWebViewInputBinding(root, view, presentation)
        root.publish(presentation)
        val token = binding.acquire()!!
        assertSame(rootIdentity, token.snapshot.root)
        assertSame(presentation, token.snapshot.presentation)
        assertSame(view, token.snapshot.owner)
    }
    @Test fun detachOrRebindRevokesAllConnectionForksWithoutProviderCalls() {
        val root = PrivateInputLifetime(Any()); val presentation = Any()
        val binding = PrivateWebViewInputBinding(root, Any(), presentation)
        root.publish(presentation)
        val parent = binding.acquire()!!; val first = parent.fork()!!; val second = parent.fork()!!
        binding.revoke()
        assertFalse(parent.isCurrent()); assertFalse(first.isCurrent()); assertFalse(second.isCurrent())
        assertNull(binding.acquire()); assertFalse(binding.isActive)
        root.publish(presentation); assertNull(binding.acquire())
    }
    @Test fun rootCoverAndSamePresentationRevealNeverReactivateOldConnection() {
        val root = PrivateInputLifetime(Any()); val presentation = Any()
        val binding = PrivateWebViewInputBinding(root, Any(), presentation)
        root.publish(presentation)
        val old = binding.acquire()!!; val oldConnection = old.fork()!!
        root.revoke(); assertNull(binding.acquire()); root.publish(presentation)
        val fresh = binding.acquire()!!
        assertNotSame(old, fresh); assertTrue(fresh.isCurrent())
        assertFalse(old.isCurrent()); assertFalse(oldConnection.isCurrent())
        assertTrue(fresh.snapshot.generation > old.snapshot.generation)
    }
    @Test fun oldDetachThenCoveredRebindAllowsFreshAttachmentWithoutRevivingOldInput() {
        val root = PrivateInputLifetime(Any()); val view = Any(); val presentation = Any()
        val oldAttachment = PrivateWebViewInputBinding(root, view, presentation)
        root.publish(presentation)
        val oldToken = oldAttachment.acquire()!!; val oldConnection = oldToken.fork()!!
        root.revoke()
        val preparedBinding = PrivateWebViewInputBinding(root, view, presentation)
        oldAttachment.revoke()
        preparedBinding.revoke() // Old AndroidView detach revokes the newly prepared binding.
        assertNull(preparedBinding.acquire())
        val newAttachment = PrivateWebViewInputBinding(root, view, presentation)
        assertNull(newAttachment.acquire()) // Rebinding under cover is not root admission.
        root.publish(presentation)
        val fresh = newAttachment.acquire()!!
        assertTrue(fresh.isCurrent()); assertSame(view, fresh.snapshot.owner)
        assertTrue(fresh.snapshot.generation > oldToken.snapshot.generation)
        assertFalse(oldToken.isCurrent()); assertFalse(oldConnection.isCurrent())
        assertNull(oldAttachment.acquire()); assertNull(preparedBinding.acquire())
    }
    @Test fun connectionCloseDoesNotRevokeSiblingOrParentButBindingCloseDoes() {
        val root = PrivateInputLifetime(Any()); val presentation = Any()
        root.publish(presentation)
        val binding = PrivateWebViewInputBinding(root, Any(), presentation)
        val parent = binding.acquire()!!; val first = parent.fork()!!; val second = parent.fork()!!
        first.revoke()
        assertTrue(parent.isCurrent()); assertTrue(second.isCurrent())
        assertSame(parent, binding.acquire())
        binding.revoke(); assertFalse(second.isCurrent())
    }
    @Test fun lateRequestCannotMoveAnOldBindingToNewPresentation() {
        val root = PrivateInputLifetime(Any()); val first = Any(); val second = Any()
        val oldBinding = PrivateWebViewInputBinding(root, Any(), first)
        root.publish(first); val token = oldBinding.acquire()!!
        root.publish(second)
        assertFalse(token.isCurrent()); assertNull(oldBinding.acquire())
        assertEquals("PrivateWebViewInputBinding([redacted])", oldBinding.toString())
    }
    @Test fun revocationRacingAcquisitionLeavesNoLiveRetainedAdmission() {
        repeat(100) {
            val root = PrivateInputLifetime(Any()); val presentation = Any(); root.publish(presentation)
            val binding = PrivateWebViewInputBinding(root, Any(), presentation)
            var returned: PrivateInputToken? = null
            val worker = Thread { returned = binding.acquire() }
            worker.start(); binding.revoke(); worker.join()
            assertFalse(returned?.isCurrent() == true)
            assertNull(binding.acquire())
        }
    }
}
