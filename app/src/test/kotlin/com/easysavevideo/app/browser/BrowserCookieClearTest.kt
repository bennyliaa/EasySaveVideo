package com.easysavevideo.app.browser

import org.junit.Assert.*
import org.junit.Test

/** Executes the production operation/feedback seam, never Android or real cookie deletion. */
class BrowserCookieClearTest {
    private class Fixture : BrowserCookieClearPort {
        var allowed = true
        var prepares = 0
        var acquisitions = 0
        var removals = 0
        var flushes = 0
        var prepareAction: () -> Unit = {}
        var acquireAction: () -> Unit = {}
        var removeAction: ((Boolean) -> Unit) -> Unit = {}
        var flushAction: () -> Unit = {}
        lateinit var callback: (Boolean) -> Unit
        val results = mutableListOf<BrowserCookieClearResult>()
        fun start(complete: (BrowserCookieClearResult) -> Unit = { results += it }) = requestBrowserCookieClear(
            isAllowed = { allowed },
            prepare = { prepares++; prepareAction() },
            acquire = { acquisitions++; acquireAction(); this },
            onComplete = complete,
        )
        override fun removeAll(onRemoved: (Boolean) -> Unit) { removals++; callback = onRemoved; removeAction(onRemoved) }
        override fun flush() { flushes++; flushAction() }
    }
    private fun failures(): List<Throwable> = listOf(IllegalStateException("do not retain native details"),
        Exception("checked platform exception"), UnsatisfiedLinkError("private native details"))
    private fun expectSameFailure(expected: Throwable, action: () -> Unit) {
        try { action(); fail("Expected failure") } catch (actual: Throwable) { assertSame(expected, actual) }
    }
    @Test fun rejectedNeverPreparesAcquiresRemovesOrFlushes() {
        val f = Fixture(); f.allowed = false; f.start()
        assertEquals(listOf(BrowserCookieClearResult.REJECTED), f.results)
        assertEquals(listOf(0, 0, 0, 0), listOf(f.prepares, f.acquisitions, f.removals, f.flushes))
    }
    @Test fun preparationExceptionsAreNotStartedAndNeverTouchManager() {
        for (failure in failures()) {
            val f = Fixture(); f.prepareAction = { throw failure }; f.start()
            assertEquals(listOf(BrowserCookieClearResult.NOT_STARTED), f.results)
            assertEquals(listOf(1, 0, 0, 0), listOf(f.prepares, f.acquisitions, f.removals, f.flushes))
        }
    }
    @Test fun factoryExceptionsAreNotStartedAndNeverSubmit() {
        for (failure in failures()) {
            val f = Fixture(); f.acquireAction = { throw failure }; f.start()
            assertEquals(listOf(BrowserCookieClearResult.NOT_STARTED), f.results)
            assertEquals(listOf(1, 0, 0), listOf(f.acquisitions, f.removals, f.flushes))
        }
    }
    @Test fun preparationReentrancyRechecksBeforeManagerAccess() {
        val f = Fixture(); f.prepareAction = { f.allowed = false }; f.start()
        assertEquals(listOf(BrowserCookieClearResult.REJECTED), f.results)
        assertEquals(0, f.acquisitions); assertEquals(0, f.removals)
    }
    @Test fun factoryReentrancyRechecksBeforeNativeSubmission() {
        val f = Fixture(); f.acquireAction = { f.allowed = false }; f.start()
        assertEquals(listOf(BrowserCookieClearResult.REJECTED), f.results)
        assertEquals(1, f.acquisitions); assertEquals(0, f.removals); assertEquals(0, f.flushes)
    }
    @Test fun submissionExceptionsAreUnconfirmedAndLateCallbacksCannotFlush() {
        for (failure in failures()) {
            val f = Fixture(); f.removeAction = { throw failure }; f.start(); f.callback(true); f.callback(false)
            assertEquals(listOf(BrowserCookieClearResult.UNCONFIRMED), f.results)
            assertEquals(1, f.removals); assertEquals(0, f.flushes)
        }
    }
    @Test fun asynchronousTrueCompletesOnlyAfterFlushOnCapturedManager() {
        val f = Fixture(); f.start(); assertTrue(f.results.isEmpty()); assertEquals(0, f.flushes)
        f.flushAction = { assertTrue(f.results.isEmpty()) }; f.callback(true)
        assertEquals(listOf(BrowserCookieClearResult.REMOVED), f.results)
        assertEquals(listOf(1, 1, 1), listOf(f.acquisitions, f.removals, f.flushes))
    }
    @Test fun asynchronousFalseIsSuccessfulNoRemoval() {
        val f = Fixture(); f.start(); f.callback(false)
        assertEquals(listOf(BrowserCookieClearResult.NONE_REMOVED), f.results); assertEquals(1, f.flushes)
    }
    @Test fun flushFailuresAreUnconfirmedForEitherCallbackBoolean() {
        for (failure in failures()) for (changed in listOf(false, true)) {
            val f = Fixture(); f.flushAction = { throw failure }; f.start(); f.callback(changed); f.callback(!changed)
            assertEquals(listOf(BrowserCookieClearResult.UNCONFIRMED), f.results)
            assertEquals(1, f.acquisitions); assertEquals(1, f.flushes)
        }
    }
    @Test fun synchronousCompletionWaitsForSubmissionToUnwind() {
        val f = Fixture(); var insideSubmission = false
        f.removeAction = { cb -> insideSubmission = true; cb(true); assertTrue(f.results.isEmpty()); insideSubmission = false }
        f.start { assertFalse(insideSubmission); f.results += it }
        assertEquals(listOf(BrowserCookieClearResult.REMOVED), f.results)
    }
    @Test fun synchronousCallbackThenSubmitFailurePreservesFirstTerminal() {
        for (failure in failures()) for (changed in listOf(false, true)) {
            val f = Fixture(); f.removeAction = { cb -> cb(changed); throw failure }; f.start()
            assertEquals(listOf(if (changed) BrowserCookieClearResult.REMOVED else BrowserCookieClearResult.NONE_REMOVED), f.results)
            assertEquals(1, f.removals); assertEquals(1, f.flushes)
        }
    }
    @Test fun synchronousFlushFailureThenSubmitFailureStaysUnconfirmedOnce() {
        val f = Fixture(); f.flushAction = { throw IllegalStateException() }
        f.removeAction = { cb -> cb(true); throw UnsatisfiedLinkError() }; f.start()
        assertEquals(listOf(BrowserCookieClearResult.UNCONFIRMED), f.results); assertEquals(1, f.flushes)
    }
    @Test fun repeatedCallbacksFlushAndCompleteOnce() {
        val f = Fixture(); f.start(); f.callback(true); f.callback(false); f.callback(true)
        assertEquals(listOf(BrowserCookieClearResult.REMOVED), f.results); assertEquals(1, f.flushes)
    }
    @Test fun flushReentrantCallbackCannotFlushAgain() {
        val f = Fixture(); f.flushAction = { f.callback(false) }; f.start(); f.callback(true)
        assertEquals(listOf(BrowserCookieClearResult.REMOVED), f.results); assertEquals(1, f.flushes)
    }
    @Test fun observerReentrantCallbackCannotCompleteAgain() {
        val f = Fixture(); f.start { result -> f.results += result; f.callback(false) }; f.callback(true)
        assertEquals(listOf(BrowserCookieClearResult.REMOVED), f.results); assertEquals(1, f.removals)
    }
    @Test fun throwingAsynchronousObserverPropagatesOriginalAndIsConsumed() {
        for (failure in failures() + AssertionError("programmer")) {
            val f = Fixture(); f.start { f.results += it; throw failure }
            expectSameFailure(failure) { f.callback(true) }; f.callback(false)
            assertEquals(listOf(BrowserCookieClearResult.REMOVED), f.results); assertEquals(1, f.flushes)
        }
    }
    @Test fun throwingSynchronousObserverIsNotClassifiedAsSubmissionFailure() {
        for (failure in failures() + AssertionError("programmer")) {
            val f = Fixture(); f.removeAction = { it(true) }
            expectSameFailure(failure) { f.start { f.results += it; throw failure } }; f.callback(false)
            assertEquals(listOf(BrowserCookieClearResult.REMOVED), f.results); assertEquals(1, f.flushes)
        }
    }
    @Test fun failureObserverPropagatesWithoutRetryOrSecondCompletion() {
        val f = Fixture(); f.removeAction = { throw IllegalStateException("primary platform failure") }
        val observer = IllegalArgumentException("observer")
        expectSameFailure(observer) { f.start { f.results += it; throw observer } }; f.callback(true)
        assertEquals(listOf(BrowserCookieClearResult.UNCONFIRMED), f.results); assertEquals(0, f.flushes)
    }
    @Test fun assertionsAndFatalErrorsAreNeverConvertedToPlatformResults() {
        for (failure in listOf(AssertionError("programmer"), OutOfMemoryError("fatal"))) {
            for (stage in 0..3) {
                val f = Fixture()
                when (stage) {
                    0 -> f.prepareAction = { throw failure }
                    1 -> f.acquireAction = { throw failure }
                    2 -> f.removeAction = { throw failure }
                    3 -> f.flushAction = { throw failure }
                }
                expectSameFailure(failure) { f.start(); if (stage == 3) f.callback(true) }
                assertTrue(f.results.isEmpty())
            }
        }
    }
    @Test fun noCallbackRemainsPendingWithoutFlushRetryOrSuccess() {
        val f = Fixture(); f.start()
        assertEquals(1, f.removals); assertEquals(0, f.flushes); assertTrue(f.results.isEmpty())
    }
    @Test fun lostAdmissionAfterSubmissionStillFlushesSameManagerAndCompletesOriginal() {
        val f = Fixture(); f.start(); f.allowed = false; f.callback(true)
        assertEquals(listOf(BrowserCookieClearResult.REMOVED), f.results)
        assertEquals(listOf(1, 1, 1), listOf(f.acquisitions, f.removals, f.flushes))
    }
    @Test fun legacyBooleanIsTrueOnlyForConfirmedRemoval() {
        for (result in BrowserCookieClearResult.entries) assertEquals(result == BrowserCookieClearResult.REMOVED, result.removed)
    }
    @Test fun staleFeedbackCannotConsumeNewerRequest() {
        val feedback = BrowserCookieClearFeedback(); val first = feedback.begin(); val second = feedback.begin()
        assertFalse(feedback.isCurrent(first)); assertFalse(feedback.consume(first)); assertTrue(feedback.isCurrent(second))
        assertTrue(feedback.consume(second)); assertFalse(feedback.consume(second)); assertFalse(feedback.isCurrent(second))
    }
    @Test fun twoSeparateOperationsFinishIndependentlyWithOnlyLatestFeedback() {
        val feedback = BrowserCookieClearFeedback(); val notices = mutableListOf<BrowserCookieClearResult>()
        val first = Fixture(); val firstRequest = feedback.begin()
        first.start { first.results += it; if (feedback.consume(firstRequest)) notices += it }
        val second = Fixture(); val secondRequest = feedback.begin()
        second.start { second.results += it; if (feedback.consume(secondRequest)) notices += it }
        second.callback(false); first.callback(true)
        assertEquals(listOf(BrowserCookieClearResult.NONE_REMOVED), notices)
        assertEquals(listOf(BrowserCookieClearResult.REMOVED), first.results)
        assertEquals(listOf(BrowserCookieClearResult.NONE_REMOVED), second.results)
        assertEquals(1, first.flushes); assertEquals(1, second.flushes)
    }
    @Test fun consumedFeedbackAllowsObserverToBeginNewRequestWithoutBeingRetired() {
        val feedback = BrowserCookieClearFeedback(); val first = feedback.begin()
        assertTrue(feedback.consume(first)); val second = feedback.begin()
        assertFalse(feedback.consume(first)); assertTrue(feedback.isCurrent(second))
    }
    @Test fun twoFactoriesNeverFlushEachOthersManager() {
        val first = Fixture(); val second = Fixture(); first.start(); second.start()
        first.callback(true); assertEquals(1, first.flushes); assertEquals(0, second.flushes)
        second.callback(false); assertEquals(1, first.acquisitions); assertEquals(1, second.acquisitions)
    }
    @Test fun predicateStartingAndCompletingNewRequestCannotPublishOldResult() {
        val feedback = BrowserCookieClearFeedback(); val old = feedback.begin()
        val notices = mutableListOf<String>()
        val oldAllowed = feedback.consume(old) {
            val newer = feedback.begin()
            if (feedback.consume(newer)) notices += "new"
            true
        }
        if (oldAllowed) notices += "old"
        assertFalse(oldAllowed); assertEquals(listOf("new"), notices)
    }
    @Test fun falsePresentationConsumesOnlyOriginalAndCannotReappearAfterReturn() {
        val feedback = BrowserCookieClearFeedback(); val request = feedback.begin()
        assertFalse(feedback.consume(request) { false })
        assertFalse(feedback.consume(request) { true })
    }
    @Test fun staleRequestDoesNotConsultNewPresentation() {
        val feedback = BrowserCookieClearFeedback(); val old = feedback.begin(); val current = feedback.begin()
        assertFalse(feedback.consume(old) { fail("Old completion must not consult new presentation"); true })
        assertTrue(feedback.isCurrent(current))
    }

}
