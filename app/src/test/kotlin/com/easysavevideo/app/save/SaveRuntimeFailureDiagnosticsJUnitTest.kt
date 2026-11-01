package com.easysavevideo.app.save

import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch

class SaveRuntimeFailureDiagnosticsJUnitTest {
    @Test fun fixedCategoriesNeverIncludePayload() {
        val secret = "https://private.invalid/?token=SECRET /private/media SQL"
        val cases = listOf(java.io.IOException(secret) to "IO", SecurityException(secret) to "SECURITY",
            IllegalArgumentException(secret) to "ARGUMENT", IllegalStateException(secret) to "STATE",
            java.util.concurrent.RejectedExecutionException(secret) to "REJECTED", Exception(secret) to "OTHER")
        cases.forEach { (error, category) ->
            check(SaveRuntimeFailureDiagnostics().failure(error, false) == "save_error phase=STORAGE category=$category ready=false")
        }
    }
    @Test fun neverReadsHostileThrowablePayload() {
        val hostile = object : Exception() {
            override val message: String get() = kotlin.error("message read")
            override fun toString(): String = kotlin.error("toString read")
            override fun getStackTrace(): Array<StackTraceElement> = kotlin.error("stack read")
        }
        check(SaveRuntimeFailureDiagnostics().failure(hostile, false) == "save_error phase=STORAGE category=OTHER ready=false")
    }
    @Test fun allStagesAndReadyStatesRemainFixed() {
        SaveRuntimeFailureDiagnostics.Stage.entries.forEach { stage ->
            val d=SaveRuntimeFailureDiagnostics(); d.stage=stage
            check(d.failure(Exception(),false)=="save_error phase=${stage.name} category=OTHER ready=false")
            check(d.failure(Exception(),true)=="save_error phase=CONTROL category=OTHER ready=true")
        }
    }
    @Test fun stageTransitionDoesNotResetEightErrorBudget() {
        val d=SaveRuntimeFailureDiagnostics()
        repeat(8) { check(d.failure(Exception(),false)!=null) }
        d.stage=SaveRuntimeFailureDiagnostics.Stage.READY
        repeat(100) { check(d.failure(Exception(),true)==null) }
    }
    @Test fun concurrentErrorsStayWithinSingleOwnerBudget() {
        val d=SaveRuntimeFailureDiagnostics(); val start=CountDownLatch(1)
        val lines=Collections.synchronizedList(mutableListOf<String>())
        val threads=List(16) { Thread { start.await(); repeat(20) { d.failure(Exception(),true)?.let(lines::add) } } }
        threads.forEach(Thread::start); start.countDown(); threads.forEach(Thread::join)
        check(lines.size==8)
        check(lines.all { it=="save_error phase=CONTROL category=OTHER ready=true" })
    }
}
