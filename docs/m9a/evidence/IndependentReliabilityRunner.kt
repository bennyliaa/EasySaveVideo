package com.easysavevideo.review.m9a

import org.junit.runner.JUnitCore
import org.junit.runner.Request
import org.junit.runner.Result

/** Explicitly selects ordinary M9-A tests; does not execute unrelated legacy test methods. */
fun main() {
    var total = 0
    var failures = 0
    fun report(label: String, result: Result) {
        total += result.runCount; failures += result.failureCount
        println("${if (result.wasSuccessful()) "PASS" else "FAIL"} $label TESTS=${result.runCount}")
        result.failures.forEach {
            // Deliberately omit assertion/exception text and all fixture values.
            println("FAIL_METHOD=${it.description.methodName} TYPE=${it.exception.javaClass.name}")
            it.exception.stackTrace.firstOrNull { frame -> frame.className.startsWith("com.easysavevideo.") }
                ?.let { frame -> println("LOCATION=${frame.className}.${frame.methodName}:${frame.lineNumber}") }
        }
    }
    val junit = JUnitCore()
    for ((label, clazz) in listOf(
        "INDEPENDENT_COORDINATOR" to IndependentReliabilityTest::class.java,
        "AUTHOR_COORDINATOR" to com.easysavevideo.discovery.DiscoveryReliabilityMatrixTest::class.java,
        "AUTHOR_COMMONS" to com.easysavevideo.commons.CommonsFailureRecoveryTest::class.java,
        "AUTHOR_BROWSER_SAVE_ISOLATION" to com.easysavevideo.app.reliability.OrdinaryIsolationTest::class.java,
    )) report(label, junit.run(clazz))
    val transport = com.easysavevideo.app.discovery.network.SystemCommonsTransportTest::class.java
    for (method in listOf(
        "unreachableDnsIsOneSanitizedAttemptAndExplicitNextRequestCanRecover",
        "refusedConnectionIsClosedWithoutTlsOrAutomaticRetry",
        "ordinaryPartialAndMalformedWireResponsesReleaseForOneExplicitFreshConnection",
        "interruptedBodyIoDoesNotPublishPartialResponseAndFreshConnectionRecovers",
        "ordinaryDeclaredLimitRejectionDoesNotConsumeBodyAndNextConnectionRecovers",
        "slowHeadersAndBodyDeadlineRetainSlotUntilCleanupThenReleaseWithoutRetry",
        "elapsedMonotonicDeadlineCannotBeExtendedByWallClockRollbackDuringBodyRead",
    )) report("AUTHOR_TRANSPORT_" + method, junit.run(Request.method(transport, method)))
    println("ORDINARY_TOTAL=$total FAILURES=$failures")
    if (failures != 0) throw AssertionError("ORDINARY_TEST_FAILURE")
    println("OK ($total tests)")
}
