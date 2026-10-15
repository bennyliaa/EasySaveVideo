package com.easysavevideo.app.ui

import org.junit.Assert.*
import org.junit.Test

class DiscoveryAddressInputTest {
    private class Editor {
        val input = DiscoveryAddressInput()
        var text = "page.example"
        var token = input.gate.token
        var interactive = true
        var focusCleared = 0
        var opens = 0
        val feedback = mutableListOf<DiscoveryAddressFeedback>()
        var focus: () -> Unit = { focusCleared++ }
        var open: (String, () -> Boolean) -> Boolean = { _, current -> opens++; current() && false }
        fun submit(capturedText: String = text, capturedToken: Any = token) = input.submit(
            capturedText, capturedToken, { interactive }, { token = it }, focus, open, feedback::add)
        fun edit(value: String) = input.edit(interactive) {
            token = it; text = value.take(8193)
        }
    }
    @Test fun httpHttpsBareHostAndSurroundingSpacesReachOnlyExplicitOpen() {
        listOf("https://page.example/path?q=value", "http://page.example", "page.example/path", " page.example ").forEach { value ->
            val e = Editor(); e.text = value; e.submit()
            assertEquals(value, 1, e.opens); assertEquals(1, e.focusCleared)
        }
    }
    @Test fun invalidRawInputNeverClearsFocusOrDispatches() {
        listOf("", "video keywords", "javascript:alert(1)", "ftp://page.example", "https://user@page.example",
            "https://page.example/a b", "\npage.example", "page.example\t", "page.example\u007f",
            "\u0000page.example", "https://page.example/" + "a".repeat(8192)).forEach { value ->
            val e = Editor(); e.text = value; e.submit()
            assertEquals(value, 0, e.opens); assertEquals(value, 0, e.focusCleared)
            if (value.isNotEmpty()) assertEquals(listOf(DiscoveryAddressFeedback.INVALID), e.feedback)
        }
    }
    @Test fun boundedDraftKeepsOverLimitSentinelAndRejectsIt() {
        val e = Editor(); assertTrue(e.edit("a".repeat(9000))); assertEquals(8193, e.text.length)
        e.submit(); assertEquals(0, e.opens); assertEquals(DiscoveryAddressFeedback.INVALID, e.feedback.single())
    }
    @Test fun inactiveOrDisposedVisitCannotEditSubmitOrOverwriteFeedback() {
        val e = Editor(); e.interactive = false
        assertFalse(e.edit("new.example")); e.submit(); assertEquals(0, e.opens)
        e.interactive = true; e.input.gate.retire()
        assertFalse(e.edit("new.example")); e.submit(); assertTrue(e.feedback.isEmpty())
    }
    @Test fun editAndClearAbaRetireCapturedImeAndEditCallbacks() {
        val e = Editor(); val old = e.token; val oldText = e.text
        assertTrue(e.edit("other.example")); assertTrue(e.edit("")); assertTrue(e.edit(oldText))
        e.submit(oldText, old); assertFalse(e.input.clear(old, true, true) { fail("stale clear") }); assertEquals(0, e.opens)
        e.submit(); assertEquals(1, e.opens)
    }
    @Test fun clickAndImeShareOneConsumedCallback() {
        val e = Editor(); val rendered = e.token
        e.submit(capturedToken = rendered); e.submit(capturedToken = rendered)
        assertEquals(1, e.opens); assertEquals(1, e.focusCleared)
    }
    @Test fun identicalFailureFeedbackStillPublishesFreshRetryToken() {
        val e = Editor(); val first = e.token
        e.submit(); val second = e.token; e.submit(); val third = e.token
        assertNotSame(first, second); assertNotSame(second, third)
        assertEquals(2, e.opens)
        assertEquals(listOf(DiscoveryAddressFeedback.UNAVAILABLE, DiscoveryAddressFeedback.UNAVAILABLE), e.feedback)
    }
    @Test fun focusClearsBeforeDispatchAndFocusRevocationPreventsOpen() {
        val e = Editor(); e.open = { _, _ -> assertEquals(1, e.focusCleared); e.opens++; false }
        e.submit(); assertEquals(1, e.opens)
        val revoked = Editor(); revoked.focus = { revoked.focusCleared++; revoked.interactive = false }
        revoked.submit(); assertEquals(0, revoked.opens); assertTrue(revoked.feedback.isEmpty())
    }
    @Test fun reentrantFocusEditInvalidatesAttemptBeforeHost() {
        val e = Editor(); e.focus = { assertTrue(e.edit("new.example")) }
        e.submit(); assertEquals(0, e.opens); assertTrue(e.feedback.isEmpty()); assertEquals("new.example", e.text)
    }
    @Test fun reentrantFreshTokenSubmitIsRejectedDuringDispatch() {
        val e = Editor(); e.open = { _, current ->
            e.opens++; assertTrue(current()); e.submit(); assertTrue(current()); false
        }
        e.submit(); assertEquals(1, e.opens)
    }
    @Test fun reentrantClearOrDisposalCannotOverwriteNewDraftFeedback() {
        listOf(false, true).forEach { dispose ->
            val e = Editor(); e.open = { _, current ->
                e.opens++
                if (dispose) e.input.gate.retire() else e.edit("")
                assertFalse(current()); false
            }
            e.submit(); assertEquals(1, e.opens); assertTrue(e.feedback.isEmpty())
        }
    }
    @Test fun successfulRouteDepartureDoesNotWriteFailureFeedback() {
        val e = Editor(); e.open = { _, current -> assertTrue(current()); e.opens++; e.interactive = false; true }
        e.submit(); assertTrue(e.feedback.isEmpty())
    }
    @Test fun routeVetoPreservesTextAndPermitsFreshExplicitRetry() {
        val e = Editor(); e.open = { _, _ -> e.opens++; true }
        e.submit(); e.submit()
        assertEquals("page.example", e.text); assertEquals(2, e.opens)
        assertEquals(listOf(DiscoveryAddressFeedback.ROUTE_VETO, DiscoveryAddressFeedback.ROUTE_VETO), e.feedback)
    }
    @Test fun throwingDispatchReleasesSubmittingButNeverRearmsConsumedCallback() {
        val e = Editor(); val old = e.token
        e.open = { _, _ -> throw IllegalStateException("synthetic") }
        try { e.submit(); fail() } catch (_: IllegalStateException) { }
        e.open = { _, _ -> e.opens++; false }
        e.submit(capturedToken = old); assertEquals(0, e.opens)
        e.submit(); assertEquals(1, e.opens)
    }
    @Test fun newlyRenderedEditorActionKeepsItsVisitDraftAfterOwnNavigationFailure() {
        val e = Editor(); e.submit()
        val replacement = DiscoveryAddressInput()
        var opens = 0
        replacement.submit(e.text, replacement.gate.token, { true }, {}, {}, { _, _ -> opens++; false }, {})
        assertEquals("page.example", e.text); assertEquals(1, opens)
    }
    @Test fun capturedGoReadsModalOwnerImmediatelyWithoutARecomposition() {
        val input = DiscoveryAddressInput(); val renderedToken = input.gate.token
        var modal = false; var opens = 0
        val capturedGo = { input.submit("page.example", renderedToken, { !modal }, {}, {},
            { _, _ -> opens++; false }, {}) }
        modal = true
        capturedGo()
        assertEquals(0, opens)
        modal = false
        capturedGo()
        assertEquals(1, opens)
    }

    @Test fun preRecompositionIdentityChangeRejectsOldEditsClearAndSubmit() {
        val input = DiscoveryAddressInput(); val rendered = input.gate.token
        val expectedRevision = 1; var revision = expectedRevision
        val actionCurrent = { expectedRevision == revision }
        var changes = 0; var opens = 0
        revision++
        assertFalse(input.edit(actionCurrent()) { changes++ })
        assertFalse(input.clear(rendered, actionCurrent(), true) { changes++ })
        input.submit("page.example", rendered, { true }, {}, {}, { _, _ -> opens++; false }, {}, actionCurrent)
        assertEquals(0, changes); assertEquals(0, opens)
        val nextInput = DiscoveryAddressInput(); val nextExpected = revision
        nextInput.submit("page.example", nextInput.gate.token, { true }, {}, {}, { _, _ -> opens++; false }, {},
            { nextExpected == revision })
        assertEquals(1, opens)
    }
    @Test fun ownTouchChangesInitialIdentityButKeepsContinuationAndFailureFeedbackLive() {
        val input = DiscoveryAddressInput(); var revision = 1; val expected = revision
        val feedback = mutableListOf<DiscoveryAddressFeedback>()
        input.submit("page.example", input.gate.token, { true }, {}, {}, { _, current ->
            revision++; assertTrue(current()); false
        }, feedback::add, { expected == revision })
        assertEquals(listOf(DiscoveryAddressFeedback.UNAVAILABLE), feedback)
    }

    @Test fun liveTextCallbackAcceptsConsecutiveImeUpdatesBeforeNextFrame() {
        val input = DiscoveryAddressInput(); val commandBeforeTyping = input.gate.token
        var text = ""; var token = input.gate.token
        val capturedEdit: (String) -> Unit = { value -> input.edit(true) { text = value; token = it }; Unit }
        capturedEdit("p"); val firstToken = token
        capturedEdit("pa"); capturedEdit("page.example")
        assertEquals("page.example", text); assertNotSame(firstToken, token)
        var opens = 0
        input.submit(text, commandBeforeTyping, { true }, {}, {}, { _, _ -> opens++; false }, {})
        assertEquals(0, opens)
        input.gate.retire(); capturedEdit("stale.example")
        assertEquals("page.example", text)
    }

}
