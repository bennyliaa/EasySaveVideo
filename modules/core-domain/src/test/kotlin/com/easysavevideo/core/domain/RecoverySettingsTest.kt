package com.easysavevideo.core.domain

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

class RecoverySettingsTest {
    @Test fun failedDomainIsIsolatedAndOtherDomainsStillChecked() {
        val manager = RecoveryManager(EpochClock { 10 })
        val fail = object : RecoveryProbe {
            override val kind = RecoveryObjectKind.TASK_JOURNAL
            override fun check(operation: OperationContext): RecoveryOutcome = throw IllegalStateException("secret path and url")
        }
        val report = manager.check(listOf(fail, readyProbe(RecoveryObjectKind.BROWSER_SESSION)), operation())
        assertEquals(RecoveryDisposition.ISOLATED, report.outcomes[0].disposition)
        assertEquals(RecoveryDisposition.READY, report.outcomes[1].disposition)
        assertTrue(report.browserShellAllowed)
        assertFalse(report.toString().contains("secret"))
    }

    @Test fun deadlineDefersRemainingProbesAndRepeatedFailuresAreBounded() {
        var now = 0L
        var calls = 0
        val probe = object : RecoveryProbe {
            override val kind = RecoveryObjectKind.SETTINGS
            override fun check(operation: OperationContext): RecoveryOutcome {
                calls++
                now = 2_000
                return RecoveryOutcome(kind, RecoveryDisposition.ISOLATED)
            }
        }
        val manager = RecoveryManager(EpochClock { now })
        val report = manager.check(listOf(probe, readyProbe(RecoveryObjectKind.BROWSER_SESSION)), operation())
        assertEquals(RecoveryDisposition.DEFERRED, report.outcomes[1].disposition)
        assertTrue(report.deadlineReached)
        now = 0
        assertEquals(RecoveryDisposition.ISOLATED, manager.check(listOf(probe), operation()).outcomes[0].disposition)
        assertEquals(1, calls)
    }


    @Test fun successfulRecoveryIsIdempotentWithoutRepeatedIoOrFalseIsolation() {
        var calls = 0
        val probe = object : RecoveryProbe {
            override val kind = RecoveryObjectKind.SETTINGS
            override fun check(operation: OperationContext): RecoveryOutcome {
                calls++
                return RecoveryOutcome(kind, RecoveryDisposition.READY)
            }
        }
        val manager = RecoveryManager(EpochClock { 10 })
        repeat(3) { assertEquals(RecoveryDisposition.READY, manager.check(listOf(probe), operation()).outcomes.single().disposition) }
        assertEquals(1, calls)
    }

    @Test fun loggerFailureCannotEscapeAndContextDoesNotAcceptArbitraryText() {
        ErrorSink { throw IllegalStateException("disk full") }.reportSafely(
            ErrorEnvelope(ErrorCode.RECOVERY_FAILED, ErrorModule.RECOVERY, false, UserAction.RETRY))
        assertThrows(IllegalArgumentException::class.java) { SafeContext(httpStatus = 900) }
    }

    @Test fun settingsDefaultsAndBoundsAreExplicit() {
        val defaults = AppSettings()
        assertEquals(2, defaults.discovery.columns)
        assertEquals(20, defaults.discovery.pageSize)
        assertEquals(RepeatPolicy.PREFER_NEW, defaults.discovery.repeatPolicy)
        assertFalse(defaults.browser.acceptThirdPartyCookies)
        assertTrue(defaults.browser.javaScriptEnabled)
        assertThrows(IllegalArgumentException::class.java) { BrowserSettings(maxActiveWebViews = 100) }
        assertThrows(IllegalArgumentException::class.java) { DiscoverySettings(pageSize = Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { AppSettings(schemaVersion = 2) }
    }

    private fun readyProbe(kind: RecoveryObjectKind) = object : RecoveryProbe {
        override val kind = kind
        override fun check(operation: OperationContext) = RecoveryOutcome(kind, RecoveryDisposition.READY)
    }
    private fun operation() = OperationContext("recovery", 2_000, CancellationSource())
}
