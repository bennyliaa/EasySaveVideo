package com.easysavevideo.validation.contract

import kotlin.coroutines.*

private class FakeEngine(
    override val id: EngineId,
    override val sendsDataToAnotherProcess: Boolean = false,
    override val capabilities: Set<Capability> = setOf(Capability.STATIC_HTML),
    val available: Boolean = true,
    val action: suspend (VerificationRequest, VerificationCancellation) -> VerificationResult
) : VerificationEngine {
    var calls = 0
    override fun isAvailable() = available
    override suspend fun verify(request: VerificationRequest, cancellation: VerificationCancellation): VerificationResult {
        calls++
        return action(request, cancellation)
    }
    override fun close() {}
}

private fun runImmediate(block: suspend () -> VerificationResult): VerificationResult {
    var completed: Result<VerificationResult>? = null
    block.startCoroutine(object : Continuation<VerificationResult> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<VerificationResult>) { completed = result }
    })
    return requireNotNull(completed) { "Test engine suspended unexpectedly" }.getOrThrow()
}

fun main() {
    var count = 0
    fun test(name: String, block: () -> Unit) { block(); count++; println("PASS $name") }
    val request = VerificationRequest("request-1", 7, "https://local.fixture/page",
        setOf(Capability.STATIC_HTML), VerificationBudget(1000, 3, 1000),
        NetworkConstraints(setOf("https://local.fixture")))
    val cancellation = object : VerificationCancellation { override fun isCancelled() = false }
    fun result(r: VerificationRequest, id: EngineId, outcome: Outcome = Outcome.SUCCESS) =
        VerificationResult(r.requestId, r.sourceGeneration, id, outcome, EvidenceLevel.UNKNOWN)
    fun registry(vararg engines: VerificationEngine) = LocalEngineRegistry().apply { engines.forEach(::register) }

    test("third engine registers without enum change") {
        val c = FakeEngine(EngineId("future-c")) { r, _ -> result(r, EngineId("future-c")) }
        check(runImmediate { VerificationScheduler(registry(c)) { 0 }.verify(request, cancellation) }.engineId == c.id)
    }
    test("remote consent excludes A and permits local B") {
        val a = FakeEngine(EngineId("a"), true) { r, _ -> result(r, EngineId("a")) }
        val b = FakeEngine(EngineId("b")) { r, _ -> result(r, EngineId("b")) }
        val s = VerificationScheduler(registry(a, b)) { 0 }
        check(runImmediate { s.verify(request, cancellation) }.engineId == b.id)
        check(a.calls == 0 && b.calls == 1)
    }
    test("fallback shares requests bytes and absolute deadline") {
        val a = FakeEngine(EngineId("a")) { r, _ -> result(r, EngineId("a"), Outcome.UNAVAILABLE).copy(requestsConsumed=1, bytesConsumed=300) }
        val b = FakeEngine(EngineId("b")) { r, _ ->
            check(r.budget.maxRequests == 2 && r.budget.maxResponseBytes == 700L)
            check(r.budget.deadlineElapsedRealtimeMs == 1000L)
            result(r, EngineId("b")).copy(requestsConsumed=1, bytesConsumed=200)
        }
        val output = runImmediate { VerificationScheduler(registry(a,b)) { 0 }.verify(request,cancellation) }
        check(output.requestsConsumed == 2 && output.bytesConsumed == 500L)
    }
    test("capability mismatch skips engine") {
        val a = FakeEngine(EngineId("a"), capabilities=setOf(Capability.DYNAMIC_DOM)) { r, _ -> result(r,EngineId("a")) }
        check(runImmediate { VerificationScheduler(registry(a)) { 0 }.verify(request,cancellation) }.outcome == Outcome.UNAVAILABLE)
        check(a.calls == 0)
    }
    test("explicit preference selects engine") {
        val a = FakeEngine(EngineId("a")) { r, _ -> result(r,EngineId("a")) }
        val b = FakeEngine(EngineId("b")) { r, _ -> result(r,EngineId("b")) }
        check(runImmediate { VerificationScheduler(registry(a,b)) { 0 }.verify(request,cancellation,EngineSelection(listOf(b.id))) }.engineId == b.id)
        check(a.calls == 0)
    }
    test("denial stops source without fallback") {
        val a = FakeEngine(EngineId("a")) { r, _ -> result(r,EngineId("a"),Outcome.DENIED) }
        val b = FakeEngine(EngineId("b")) { r, _ -> result(r,EngineId("b")) }
        check(runImmediate { VerificationScheduler(registry(a,b)) { 0 }.verify(request,cancellation) }.outcome == Outcome.DENIED)
        check(b.calls == 0)
    }
    test("expired deadline never calls engine") {
        val a = FakeEngine(EngineId("a")) { r, _ -> result(r,EngineId("a")) }
        check(runImmediate { VerificationScheduler(registry(a)) { 1000 }.verify(request,cancellation) }.outcome == Outcome.TIMEOUT)
        check(a.calls == 0)
    }
    test("cancelled request never calls engine") {
        val cancelled = object : VerificationCancellation { override fun isCancelled() = true }
        check(runImmediate { VerificationScheduler(registry()) { 0 }.verify(request,cancelled) }.outcome == Outcome.CANCELLED)
    }
    test("stale generation rejected") {
        val a = FakeEngine(EngineId("a")) { r, _ -> result(r,EngineId("a")).copy(sourceGeneration=6) }
        check(runImmediate { VerificationScheduler(registry(a)) { 0 }.verify(request,cancellation) }.outcome == Outcome.ERROR)
    }
    test("failure cannot confirm video") {
        val a = FakeEngine(EngineId("a")) { r, _ -> result(r,EngineId("a"),Outcome.ERROR).copy(evidence=EvidenceLevel.VERIFIED_MEDIA,mediaUrls=listOf("https://local.fixture/media.mp4")) }
        check(runImmediate { VerificationScheduler(registry(a)) { 0 }.verify(request,cancellation) }.evidence == EvidenceLevel.UNKNOWN)
    }
    test("exception exhausts unknown ledger and does not fallback") {
        val a = FakeEngine(EngineId("a")) { _, _ -> error("simulated failure") }
        val b = FakeEngine(EngineId("b")) { r, _ -> result(r,EngineId("b")) }
        check(runImmediate { VerificationScheduler(registry(a,b)) { 0 }.verify(request,cancellation) }.outcome == Outcome.ERROR)
        check(b.calls == 0)
    }
    test("duplicate identity rejected") {
        val a = FakeEngine(EngineId("a")) { r, _ -> result(r,EngineId("a")) }
        check(runCatching { registry(a,a) }.isFailure)
    }
    test("budget overrun cannot fallback or confirm") {
        val a = FakeEngine(EngineId("a")) { r, _ -> result(r,EngineId("a")).copy(requestsConsumed=4) }
        val b = FakeEngine(EngineId("b")) { r, _ -> result(r,EngineId("b")) }
        check(runImmediate { VerificationScheduler(registry(a,b)) { 0 }.verify(request,cancellation) }.outcome == Outcome.ERROR)
        check(b.calls == 0)
    }
    test("deadline after engine result suppresses late confirmation") {
        var clock = 0L
        val a = FakeEngine(EngineId("a")) { r, _ -> clock=1000; result(r,EngineId("a")) }
        check(runImmediate { VerificationScheduler(registry(a)) { clock }.verify(request,cancellation) }.outcome == Outcome.TIMEOUT)
    }
    test("cancel after engine result suppresses stale delivery") {
        var cancelled = false
        val token = object : VerificationCancellation { override fun isCancelled() = cancelled }
        val a = FakeEngine(EngineId("a")) { r, _ -> cancelled=true; result(r,EngineId("a")) }
        check(runImmediate { VerificationScheduler(registry(a)) { 0 }.verify(request,token) }.outcome == Outcome.CANCELLED)
    }
    test("one active engine blocks nested request and releases afterwards") {
        lateinit var scheduler: VerificationScheduler
        val a = FakeEngine(EngineId("a")) { r, _ ->
            check(scheduler.verify(r.copy(requestId="nested"),cancellation).outcome == Outcome.UNAVAILABLE)
            result(r,EngineId("a"))
        }
        scheduler=VerificationScheduler(registry(a)) { 0 }
        repeat(2) { check(runImmediate { scheduler.verify(request,cancellation) }.outcome == Outcome.SUCCESS) }
        check(a.calls == 2)
    }
    test("disabled fallback respects user selection policy") {
        val a = FakeEngine(EngineId("a")) { r, _ -> result(r,EngineId("a"),Outcome.UNAVAILABLE) }
        val b = FakeEngine(EngineId("b")) { r, _ -> result(r,EngineId("b")) }
        check(runImmediate { VerificationScheduler(registry(a,b)) { 0 }.verify(request,cancellation,EngineSelection(allowFallback=false)) }.engineId == a.id)
        check(b.calls == 0)
    }
    test("unknown consumption prohibits fallback") {
        val a = FakeEngine(EngineId("a")) { r, _ -> result(r,EngineId("a"),Outcome.UNAVAILABLE).copy(resourceUsageKnown=false) }
        check(runImmediate { VerificationScheduler(registry(a)) { 0 }.verify(request,cancellation) }.outcome == Outcome.ERROR)
    }
    println("Scheduler checks: $count passed; fake engines only; ESV integration NOT_RUN")
}
