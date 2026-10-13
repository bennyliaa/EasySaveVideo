package com.easysavevideo.app.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserJsDialogControllerTest {
    private class Fixture {
        var time = 1L
        var visible: BrowserJsDialogRequest? = null
        var valid = true
        var allowed = true
        var blocked = false
        val outcomes = mutableListOf<Pair<Boolean, String?>>()
        val controller = BrowserJsDialogController({ time }) { visible = it }
        fun offer(kind: BrowserJsDialogKind = BrowserJsDialogKind.CONFIRM, default: String = "", origin: String? = "https://example.com",
            completed: (Boolean, String?) -> Unit = { yes, input -> outcomes.add(yes to input) }) =
            controller.offer(kind, origin, "Page message", default, allowed, { valid }, { blocked = true }, complete = completed)
    }
    @Test fun unrelatedOwnerCannotCancelCurrentDialog() {
        val f = Fixture(); val owner = Any(); val other = Any()
        f.controller.offer(BrowserJsDialogKind.PROMPT, "https://example.com", "message", "",
            true, { true }, {}, owner) { yes, value -> f.outcomes.add(yes to value) }
        val active = f.visible
        f.controller.cancel(other); assertSame(active, f.visible); assertTrue(f.outcomes.isEmpty())
        f.controller.cancel(owner); assertNull(f.visible); assertEquals(listOf(false to null), f.outcomes)
        f.controller.cancel(owner); assertEquals(1, f.outcomes.size)
    }
    @Test fun alertRequiresExplicitAcknowledgment() {
        val f = Fixture(); assertTrue(f.offer(BrowserJsDialogKind.ALERT)); assertTrue(f.outcomes.isEmpty())
        f.controller.finish(f.visible!!, true); assertEquals(listOf(true to null), f.outcomes); assertNull(f.visible)
    }
    @Test fun confirmCancelReturnsFalse() {
        val f = Fixture(); f.offer(); f.controller.finish(f.visible!!, false)
        assertEquals(listOf(false to null), f.outcomes)
    }
    @Test fun promptReturnsOnlyExplicitExactInput() {
        val f = Fixture(); f.offer(BrowserJsDialogKind.PROMPT, "page default")
        assertEquals("page default", f.visible!!.defaultValue); assertTrue(f.outcomes.isEmpty())
        f.controller.finish(f.visible!!, true, "user answer")
        assertEquals(listOf(true to "user answer"), f.outcomes)
    }
    @Test fun promptEmptyIsDistinctFromCancel() {
        val f = Fixture(); f.offer(BrowserJsDialogKind.PROMPT); f.controller.finish(f.visible!!, true, "")
        f.offer(BrowserJsDialogKind.PROMPT); f.controller.cancel()
        assertEquals(listOf(true to "", false to null), f.outcomes)
    }
    @Test fun duplicateAndLateClicksCannotCompleteTwiceOrFinishNewRequest() {
        val f = Fixture(); f.offer(); val old = f.visible!!; f.controller.finish(old, true)
        f.offer(); val current = f.visible!!; f.controller.finish(old, true); f.controller.finish(old, false)
        assertSame(current, f.visible); assertEquals(1, f.outcomes.size)
        f.controller.cancel(); f.controller.finish(current, true); assertEquals(2, f.outcomes.size)
    }
    @Test fun lifecycleInvalidityTurnsConfirmIntoCancel() {
        val f = Fixture(); f.offer(); val request = f.visible!!; f.valid = false
        f.controller.finish(request, true); assertEquals(listOf(false to null), f.outcomes)
    }
    @Test fun cancelClearsPresentationBeforeCallback() {
        val f = Fixture(); f.offer(completed = { yes, _ -> assertFalse(yes); assertNull(f.visible) })
        f.controller.cancel(); f.controller.cancel()
    }
    @Test fun onlyOnePendingNoQueue() {
        val f = Fixture(); f.offer(); val first = f.visible
        assertFalse(f.offer()); assertSame(first, f.visible); assertEquals(listOf(false to null), f.outcomes)
        f.controller.cancel(); assertNull(f.visible); assertEquals(2, f.outcomes.size)
    }
    @Test fun noForegroundOrOwnerAuthorizationCancelsImmediately() {
        val f = Fixture(); f.allowed = false; assertFalse(f.offer()); assertNull(f.visible)
        assertEquals(listOf(false to null), f.outcomes)
    }
    @Test fun alreadyInvalidOwnerCannotPresent() {
        val f = Fixture(); f.valid = false; assertFalse(f.offer()); assertNull(f.visible)
    }
    @Test fun invalidSourceCannotPresent() {
        val f = Fixture(); assertFalse(f.offer(origin = null)); assertNull(f.visible)
    }
    @Test fun oversizedDefaultIsRejectedWithoutTruncatingOrSubmitting() {
        val f = Fixture(); assertFalse(f.offer(BrowserJsDialogKind.PROMPT, "a".repeat(1025)))
        assertEquals(listOf(false to null), f.outcomes)
    }
    @Test fun oversizedOrMissingPromptInputKeepsRequestOpen() {
        val f = Fixture(); f.offer(BrowserJsDialogKind.PROMPT); val request = f.visible!!
        f.controller.finish(request, true, "a".repeat(1025)); f.controller.finish(request, true)
        assertSame(request, f.visible); assertTrue(f.outcomes.isEmpty())
        f.controller.finish(request, true, "a".repeat(1024)); assertEquals(1024, f.outcomes.single().second!!.length)
    }
    @Test fun blockIsCancelAndMarksDocumentBeforeCallback() {
        val f = Fixture(); f.offer(completed = { yes, input -> assertFalse(yes); assertNull(input); assertTrue(f.blocked) })
        f.controller.finish(f.visible!!, true, block = true); assertNull(f.visible)
    }
    @Test fun globalRateBudgetSurvivesDocumentOwnerChanges() {
        val f = Fixture(); repeat(3) { assertTrue(f.offer()); f.controller.cancel() }
        assertFalse(f.offer()); f.time += 29_999; assertFalse(f.offer()); f.time++
        assertTrue(f.offer()); assertNotNull(f.visible)
    }
    @Test fun rejectionDoesNotConsumeDisplayBudget() {
        val f = Fixture(); f.allowed = false; repeat(50) { assertFalse(f.offer()) }; f.allowed = true
        repeat(3) { assertTrue(f.offer()); f.controller.cancel() }; assertFalse(f.offer())
    }
    @Test fun completionReentryCannotInstallReplacement() {
        val f = Fixture(); var nested = 0
        f.offer(completed = { _, _ ->
            assertFalse(f.offer(completed = { yes, _ -> assertFalse(yes); nested++ }))
            assertNull(f.visible)
        })
        f.controller.finish(f.visible!!, true); assertEquals(1, nested); assertTrue(f.offer())
    }
    @Test fun failureInCompletionStillClearsPendingAndReentryGuard() {
        val f = Fixture(); f.offer(completed = { _, _ -> throw IllegalStateException() })
        try { f.controller.cancel(); fail() } catch (_: IllegalStateException) { }
        assertNull(f.visible); assertTrue(f.offer())
    }
    @Test fun requestToStringDoesNotExposePageOrPromptContent() {
        val f = Fixture(); f.offer(BrowserJsDialogKind.PROMPT, "secret sentinel")
        assertFalse(f.visible.toString().contains("secret sentinel")); assertFalse(f.visible.toString().contains("Page message"))
    }
}
