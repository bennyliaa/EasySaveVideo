package com.easysavevideo.commons.rules

import com.easysavevideo.commons.maintenance.*
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.core.model.OperationContext
import com.easysavevideo.discovery.*
import java.io.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Real repository files; fresh public InMemory stores rebuilt from bounded fixture snapshots.
 * This tests recovery policy and exact runtime consumption, not Android SQLite crash durability. */
class SignedJsonProviderProgressRecoveryTest {
    private class Clock(@Volatile var now: Long = ProviderPackageFixtures.NOW, private val step: Long = 0) : EpochClock {
        @Synchronized override fun nowMillis(): Long = now.also { now += step }
    }
    private val profile = SearchProfile("profile", "recovery fixture", filters = SearchFilters(safeSearch = false))
    private val rules = """{"schemaVersion":1,"ruleVersion":1,"itemsPath":["items"],"fields":{"pageUrl":["url"]},"nextCursorPath":["next"]}""".toByteArray()
    private fun response(id: String, second: Boolean = false, continued: Boolean = false) =
        """{"items":[{"url":"https://example.org/$id/${if (second) 2 else 1}"}],"next":${if (continued) "\"page-two\"" else "null"}}""".toByteArray()
    private inner class Fixture(val id: String = "alpha", val clock: Clock = Clock()) : AutoCloseable {
        val root = Files.createTempDirectory("esv-rule-recovery").toFile()
        val directory = root.resolve("repository")
        val image = root.resolve("discovery-fixture.bin")
        val target = ProviderTarget.declarativeJson(id, "1", "Fixture $id", listOf(ProviderRuleSelfTestVector(
            response(id), listOf(CandidateUrl("https://example.org/$id/1", SourceReportedCandidateMetadata(sourceName = "Fixture $id"))))))
        fun open() = ProviderRepository(directory, target, ProviderPackageFixtures.verifier(target), clock, RepositoryPolicyFixtures.verifier(target))
        fun signed(version: Long = 1) = ProviderPackageFixtures.signedRules(target, rules, version = version)
        fun install(repo: ProviderRepository, version: Long = 1) = success(repo.install(signed(version)))
        fun policy(repo: ProviderRepository, enabled: Boolean = true, revoke: List<Long> = emptyList(), floor: Long = 0,
            expires: Long = clock.now + 60_000, rollback: Long? = null, digest: ByteArray = ByteArray(32)) =
            success(repo.installPolicy(RepositoryPolicyFixtures.signed(target = target, enabled = enabled,
                revokedPackageVersions = revoke, minimumSafePackageVersion = floor, expires = expires,
                rollbackTargetVersion = rollback, rollbackTargetDigest = digest)))
        fun provider(repo: ProviderRepository, source: Source = Source(id)) = SignedJsonVideoSearchProvider(repo, source, clock)
        fun promote(repo: ProviderRepository) {
            repeat(3) { index ->
                Harness(listOf(provider(repo)), clock).use { h ->
                    h.start(profile.copy(query = "health $index ${repo.snapshot().highestAcceptedVersion}")); h.settled()
                }
            }
            assertEquals(ProviderHealthPhase.HEALTHY, repo.snapshot().health.phase)
        }
        fun prepare(version: Long = 1, pending: Boolean = false, shortPolicy: Boolean = false): ProviderDescriptor {
            open().use { repo ->
                install(repo); promote(repo)
                if (version == 2L || pending) { install(repo, 2); if (!pending) promote(repo) }
                if (shortPolicy) policy(repo, expires = clock.now + 1_000)
                val source = Source(id)
                Harness(listOf(provider(repo, source)), clock).use { h ->
                    h.start(); h.settled(); assertEquals(listOf<String?>(null), source.cursors.toList())
                    assertEquals("page-two", h.store.transaction { progress(profile.profileId, id, profile.queryKey) }!!.cursor)
                    addHistory(h.store)
                    Snapshot.write(image, h.store, listOf(id))
                }
                return repo.ruleDescriptor()
            }
        }
        override fun close() { root.deleteRecursively() }
    }
    private inner class Source(private val id: String) : RuleResponseSource {
        val cursors = CopyOnWriteArrayList<String?>()
        override fun create(request: RuleResponseRequest): RuleResponseCall {
            cursors += request.cursor
            return object : RuleResponseCall {
                override fun execute() = ByteArrayInputStream(response(id, request.cursor != null, request.cursor == null))
                override fun cancel() = Unit
            }
        }
    }
    private inner class Harness(providers: List<VideoSearchProvider>, clock: Clock,
        val store: DiscoveryStore = InMemoryDiscoveryStore(), private val configuration: String? = null) : AutoCloseable {
        private val timer = JvmDiscoveryTimer()
        private val runtime = IsolatedProviderRuntime(ValidationPipeline(clock = clock, retainUnvalidatedCandidates = true), timer, clock)
        val coordinator = VideoDiscoveryCoordinator(ProviderRegistry(providers), runtime, store, timer, clock,
            limits = DiscoveryLimits(maxRequestsPerRound = 1), sourceConfigurationKey = configuration)
        fun start(selected: SearchProfile = profile, incognito: Boolean = false) {
            coordinator.setVisibility(true, true); coordinator.start("session", selected, incognito)
        }
        fun restore(requireExplicit: Boolean = false) {
            coordinator.setVisibility(true, true); assertTrue(coordinator.restore("session", requireExplicit))
        }
        fun settled() = waitUntil { coordinator.status().inFlightCount == 0 }
        override fun close() { coordinator.close(); runtime.close(); timer.close() }
    }
    private fun success(result: ProviderRepositoryResult): ProviderRepositorySnapshot {
        assertTrue(result.toString(), result is ProviderRepositoryResult.Success)
        return (result as ProviderRepositoryResult.Success).snapshot
    }
    private fun waitUntil(check: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!check() && System.nanoTime() < until) Thread.sleep(5)
        assertTrue("fixture did not settle", check())
    }
    private val historical = ResultNormalizer.normalize(RawVideoResult("https://example.org/independent-history", true,
        title = "independently verified fixture", sourceName = "Fixture validator"), "validator")!!
    private fun addHistory(store: DiscoveryStore) = store.transaction {
        val old = checkpoint("session")!!
        putCheckpoint(old.copy(pool = PoolState(listOf(PoolEntry(historical, ProviderPackageFixtures.NOW, 0)), nextOrder = 1)))
        putSeen(SeenRecord(profile.profileId, historical.fingerprint, ProviderPackageFixtures.NOW, shownAt = ProviderPackageFixtures.NOW))
    }
    private fun assertHistory(store: DiscoveryStore) = store.transaction {
        assertEquals(historical, checkpoint("session")!!.pool.entries.single().result)
        assertEquals(ProviderPackageFixtures.NOW, seen(profile.profileId, historical.fingerprint)!!.shownAt)
    }

    @Test fun stableRepositoryAndFreshStoreRestoreTheExactNextCursorThenExhaustion() {
        Fixture().use { f ->
            val original = f.prepare()
            f.open().use { reopened ->
                assertEquals(original, reopened.ruleDescriptor())
                val source = Source(f.id); val store = Snapshot.read(f.image)
                Harness(listOf(f.provider(reopened, source)), f.clock, store).use { h ->
                    h.restore(); h.settled()
                    assertEquals(listOf("page-two"), source.cursors.toList())
                    assertEquals(1, h.coordinator.pendingCandidates().size)
                    assertTrue(h.coordinator.pendingCandidates().single().canonicalPageUrl.endsWith("/2"))
                    assertHistory(store)
                    Snapshot.write(f.image, store, listOf(f.id))
                }
            }
            f.open().use { reopened ->
                assertEquals(original, reopened.ruleDescriptor())
                val source = Source(f.id)
                Harness(listOf(f.provider(reopened, source)), f.clock, Snapshot.read(f.image)).use { h ->
                    h.restore(); h.settled(); assertTrue(source.cursors.isEmpty())
                    assertTrue(h.coordinator.pendingCandidates().isEmpty()); assertHistory(h.store)
                }
            }
        }
    }

    private enum class InvalidOwner { REBUILT, UPDATED, DISABLED, REVOKED, FLOOR, PACKAGE_EXPIRED, POLICY_EXPIRED, PENDING }
    @Test fun incompatibleOwnerCannotSendOldCursorAndNeverErasesIndependentHistory() {
        for (reason in InvalidOwner.entries) Fixture().use { f ->
            val old = f.prepare(pending = reason == InvalidOwner.PENDING, shortPolicy = reason == InvalidOwner.POLICY_EXPIRED)
            if (reason == InvalidOwner.REBUILT) assertTrue(f.directory.deleteRecursively())
            if (reason == InvalidOwner.PACKAGE_EXPIRED) f.clock.now += 60_000
            if (reason == InvalidOwner.POLICY_EXPIRED) f.clock.now += 1_000
            f.open().use { repo ->
                when (reason) {
                    InvalidOwner.REBUILT -> { f.install(repo); f.promote(repo); assertNotEquals(old, repo.ruleDescriptor()) }
                    InvalidOwner.UPDATED -> f.install(repo, 2)
                    InvalidOwner.DISABLED -> f.policy(repo, enabled = false)
                    InvalidOwner.REVOKED -> f.policy(repo, revoke = listOf(1))
                    InvalidOwner.FLOOR -> f.policy(repo, floor = 2)
                    else -> Unit
                }
                val source = Source(f.id); val store = Snapshot.read(f.image)
                Harness(listOf(f.provider(repo, source)), f.clock, store).use { h ->
                    h.restore(); h.settled()
                    assertFalse(reason.name, source.cursors.contains("page-two"))
                    assertHistory(store)
                    if (reason in setOf(InvalidOwner.REBUILT, InvalidOwner.UPDATED)) assertEquals(listOf<String?>(null), source.cursors.toList())
                    else assertTrue(reason.name, source.cursors.isEmpty())
                }
            }
        }
    }

    @Test fun exactAuthorizedRollbackInvalidatesOldProgressWithoutLoweringHighWater() {
        Fixture().use { f ->
            val previous = f.prepare(version = 2)
            f.open().use { repo ->
                f.policy(repo, rollback = 1, digest = RepositoryPolicyFixtures.digest(f.signed(1)))
                success(repo.rollback())
                assertEquals(2L, repo.snapshot().highestAcceptedVersion); assertEquals(1L, repo.snapshot().activeVersion)
                assertNotEquals(previous, repo.ruleDescriptor())
                val source = Source(f.id)
                Harness(listOf(f.provider(repo, source)), f.clock, Snapshot.read(f.image)).use { h ->
                    h.restore(); h.settled(); assertEquals(listOf<String?>(null), source.cursors.toList()); assertHistory(h.store)
                }
            }
        }
    }

    @Test fun expiredSchemaAndVersionMismatchProgressRestartOnlyThatQuery() {
        for (variant in 0..2) Fixture().use { f ->
            f.prepare()
            val edited = Snapshot.read(f.image)
            edited.transaction {
                val p = progress(profile.profileId, f.id, profile.queryKey)!!
                putProgress(when (variant) {
                    0 -> p.copy(expiresAtEpochMillis = f.clock.now)
                    1 -> p.copy(cursorSchemaVersion = p.cursorSchemaVersion + 1)
                    else -> p.copy(providerVersion = "foreign-binding")
                })
            }
            Snapshot.write(f.image, edited, listOf(f.id))
            f.open().use { repo ->
                val source = Source(f.id)
                Harness(listOf(f.provider(repo, source)), f.clock, Snapshot.read(f.image)).use { h ->
                    h.restore(); h.settled(); assertEquals(listOf<String?>(null), source.cursors.toList()); assertHistory(h.store)
                }
            }
        }
    }

    @Test fun profileAndQueryChangesNeverReuseAnotherSearchCursor() {
        for (changed in listOf(profile.copy(profileId = "other-profile"), profile.copy(query = "other query"))) Fixture().use { f ->
            f.prepare(); f.open().use { repo ->
                val source = Source(f.id); val store = Snapshot.read(f.image)
                Harness(listOf(f.provider(repo, source)), f.clock, store).use { h ->
                    h.start(changed); h.settled(); assertEquals(listOf<String?>(null), source.cursors.toList())
                    store.transaction { assertEquals("page-two", progress(profile.profileId, f.id, profile.queryKey)!!.cursor) }
                }
            }
        }
    }

    @Test fun targetSpecificRevocationPreservesSiblingDurableCursor() {
        val clock = Clock()
        Fixture("alpha", clock).use { a -> Fixture("beta", clock).use { b ->
            a.prepare(); b.prepare()
            val first = Snapshot.read(a.image); val second = Snapshot.read(b.image)
            first.transaction { putProgress(second.transaction { progress(profile.profileId, b.id, profile.queryKey)!! }) }
            Snapshot.write(a.image, first, listOf(a.id, b.id))
            a.open().use { ar -> b.open().use { br ->
                a.policy(ar, enabled = false)
                val asource = Source(a.id); val bsource = Source(b.id)
                Harness(listOf(a.provider(ar, asource), b.provider(br, bsource)), clock, Snapshot.read(a.image)).use { h ->
                    h.restore(); h.settled(); assertTrue(asource.cursors.isEmpty())
                    assertEquals(listOf("page-two"), bsource.cursors.toList()); assertHistory(h.store)
                }
            } }
        } }
    }

    @Test fun readonlyRecoveryViewConsumesNoRequestResourcesOrHealthObservation() {
        Fixture().use { f -> f.open().use { repo ->
            f.install(repo)
            val source = Source(f.id); val provider = f.provider(repo, source); val descriptor = provider.descriptor
            val before = repo.snapshot().health
            repeat(100) { assertTrue(provider.progressAuthority(descriptor, f.clock.now)!!.isCurrent(f.clock.now)) }
            assertEquals(before, repo.snapshot().health); assertTrue(source.cursors.isEmpty())
            assertNull(provider.progressAuthority(descriptor.copy(version = "other"), f.clock.now))
            val old = provider.progressAuthority(descriptor, f.clock.now)!!
            f.policy(repo, enabled = false)
            assertFalse(old.isCurrent(f.clock.now)); assertNull(provider.progressAuthority(descriptor, f.clock.now))
        } }
    }

    @Test fun explicitSearchAndConfigurationGatesStillApplyAfterRecovery() {
        for (configuration in listOf<String?>(null, "a".repeat(64))) Fixture().use { f ->
            f.prepare(); f.open().use { repo ->
                val source = Source(f.id)
                Harness(listOf(f.provider(repo, source)), f.clock, Snapshot.read(f.image), configuration).use { h ->
                    h.restore(requireExplicit = configuration == null); h.settled()
                    assertTrue(source.cursors.isEmpty()); assertFalse(h.coordinator.currentCheckpoint()!!.queryAuthorized)
                }
            }
        }
    }

    @Test fun restoredAuthorityPortRunsOutsideStoreTransactionAndCannotBorrowChangedBinding() {
        Fixture().use { f ->
            f.prepare(); f.open().use { repo ->
                val source = Source(f.id); val actual = f.provider(repo, source)
                val depth = AtomicInteger(); val portCalls = AtomicInteger(); val backing = Snapshot.read(f.image)
                val store = object : DiscoveryStore {
                    override fun <T> transaction(block: DiscoveryTransaction.() -> T): T {
                        depth.incrementAndGet(); try { return backing.transaction(block) } finally { depth.decrementAndGet() }
                    }
                }
                val provider = object : VideoSearchProvider by actual {
                    override fun progressAuthority(expected: ProviderDescriptor, nowMillis: Long): ProviderAuthorityView? {
                        assertEquals(0, depth.get())
                        if (portCalls.incrementAndGet() == 1) f.policy(repo, enabled = false)
                        return actual.progressAuthority(expected, nowMillis)
                    }
                }
                Harness(listOf(provider), f.clock, store).use { h ->
                    h.restore(); h.settled(); assertTrue(portCalls.get() > 0); assertTrue(source.cursors.isEmpty())
                    assertTrue(h.coordinator.pendingCandidates().isEmpty()); assertHistory(store)
                }
            }
        }
    }

    @Test fun descriptorChangeAfterProgressReadCannotReachTheResponsePortWithOldCursor() {
        Fixture().use { f ->
            f.prepare(); f.open().use { repo ->
                val source = Source(f.id); val actual = f.provider(repo, source)
                val provider = object : VideoSearchProvider by actual {
                    override fun acquire(request: SearchRequest, operation: DiscoveryOperation): ProviderRequestLease {
                        f.install(repo, 2)
                        return actual.acquire(request, operation)
                    }
                }
                Harness(listOf(provider), f.clock, Snapshot.read(f.image)).use { h ->
                    h.restore(); h.settled(); assertTrue(source.cursors.isEmpty())
                    assertTrue(h.coordinator.pendingCandidates().isEmpty()); assertHistory(h.store)
                }
            }
        }
    }

    @Test fun monotonicallyAdvancingRealRepositoryClockDoesNotDiscardARecoverableCursor() {
        Fixture(clock = Clock(step = 1)).use { f ->
            val descriptor = f.prepare()
            f.open().use { repo ->
                assertEquals(descriptor, repo.ruleDescriptor())
                val source = Source(f.id)
                Harness(listOf(f.provider(repo, source)), f.clock, Snapshot.read(f.image)).use { h ->
                    h.restore(); h.settled(); assertEquals(listOf("page-two"), source.cursors.toList())
                    assertEquals(1, h.coordinator.pendingCandidates().size); assertHistory(h.store)
                }
            }
        }
    }

    @Test fun retiredAuthorityIsNotRewarmedByPollingButExplicitDemandMayUseTheNewPlan() {
        Fixture().use { f ->
            f.prepare(); f.open().use { repo ->
                val source = Source(f.id); val actual = f.provider(repo, source); val ports = AtomicInteger()
                val provider = object : VideoSearchProvider by actual {
                    override fun progressAuthority(expected: ProviderDescriptor, nowMillis: Long): ProviderAuthorityView? {
                        ports.incrementAndGet(); return actual.progressAuthority(expected, nowMillis)
                    }
                }
                Harness(listOf(provider), f.clock, Snapshot.read(f.image)).use { h ->
                    h.restore(); h.settled(); assertEquals(listOf("page-two"), source.cursors.toList())
                    val old = h.coordinator.pendingCandidates().single()
                    f.install(repo, 2)
                    val previousPorts = ports.get()
                    repeat(5) { h.coordinator.status(); assertTrue(h.coordinator.pendingCandidates().isEmpty()) }
                    assertEquals(previousPorts, ports.get()); assertEquals(1, source.cursors.size)
                    assertFalse(h.coordinator.verifyPending(h.coordinator.currentCheckpoint()!!.stamp, old.candidateId))
                    h.coordinator.requestMore(); h.settled()
                    assertEquals(listOf("page-two", null), source.cursors.toList())
                    assertTrue(ports.get() > previousPorts)
                }
            }
        }
    }

    @Test fun incognitoUsesOnlyItsFreshStoreAndNeverReusesOrChangesNormalDurableProgress() {
        Fixture().use { f ->
            f.prepare(); val store = Snapshot.read(f.image); val before = f.image.readBytes()
            f.open().use { repo ->
                val source = Source(f.id)
                Harness(listOf(f.provider(repo, source)), f.clock, store).use { h ->
                    h.start(incognito = true); h.settled(); assertEquals(listOf<String?>(null), source.cursors.toList())
                    assertEquals(1, h.coordinator.pendingCandidates().size)
                }
            }
            Snapshot.write(f.image, store, listOf(f.id)); assertArrayEquals(before, f.image.readBytes())
        }
    }

    @Test fun olderLegitimateSampleSurvivesNewerOwnerObservationButNeverExtendsOperationDeadline() {
        Fixture().use { f ->
            f.prepare(); f.open().use { repo ->
                val provider = f.provider(repo); val descriptor = provider.descriptor
                val sampled = f.clock.now; val view = provider.progressAuthority(descriptor, sampled)!!
                f.clock.now = sampled + 1; assertNull(repo.snapshot().error)
                assertTrue(view.isCurrent(sampled)); assertNotNull(provider.progressAuthority(descriptor, sampled))
                assertFalse(view.isCurrent(-1)); assertNull(provider.progressAuthority(descriptor, -1))
                assertFalse(view.publishIfCurrent(-1, null) { fail("negative time cannot publish") })
                val cancellation = DiscoveryCancellation()
                val operation = DiscoveryOperation(OperationContext("deadline", sampled + 10, cancellation), cancellation)
                f.clock.now = sampled + 11; assertNull(repo.snapshot().error)
                var published = false
                try { view.publishIfCurrent(sampled, operation) { published = true }; fail("owner floor crossed deadline") }
                catch (failure: DiscoveryFailure) { assertEquals(DiscoveryError.DEADLINE_EXCEEDED, failure.code) }
                assertFalse(published)
            }
        }
    }

    @Test fun genuineOwnerClockRollbackBlocksViewsAndExecutionUntilARealSampleCatchesUp() {
        Fixture().use { f ->
            f.prepare(); f.open().use { repo ->
                val source = Source(f.id); val provider = f.provider(repo, source); val descriptor = provider.descriptor
                val sampled = f.clock.now; val view = provider.progressAuthority(descriptor, sampled)!!
                f.clock.now = sampled - 1
                assertEquals(ProviderRepositoryError.CLOCK_ROLLBACK, repo.snapshot().error)
                assertFalse(view.isCurrent(sampled + 100)); assertNull(provider.progressAuthority(descriptor, sampled + 100))
                val cancellation = DiscoveryCancellation()
                val operation = DiscoveryOperation(OperationContext("rollback", sampled + 1000, cancellation), cancellation)
                val request = SearchRequest(SessionStamp("rollback", 1), profile, null, descriptor)
                try { provider.acquire(request, operation).use { it.search(request, operation) }; fail("rollback cannot execute") }
                catch (failure: DiscoveryFailure) { assertEquals(DiscoveryError.SOURCE_CONFIGURATION, failure.code) }
                assertTrue(source.cursors.isEmpty())
                f.clock.now = sampled + 1; assertNull(repo.snapshot().error)
                assertTrue(view.isCurrent(sampled)); assertNotNull(provider.progressAuthority(descriptor, sampled))
                assertEquals(descriptor, provider.descriptor)
            }
        }
    }

    @Test fun activeExecutionLeaseItselfSamplesOwnerClockAndRejectsRealRollbackBeforeSourceCall() {
        Fixture().use { f -> f.open().use { repo ->
            f.install(repo)
            val source = Source(f.id); val provider = f.provider(repo, source); val descriptor = provider.descriptor
            val cancellation = DiscoveryCancellation()
            val operation = DiscoveryOperation(OperationContext("lease-rollback", f.clock.now + 1000, cancellation), cancellation)
            val request = SearchRequest(SessionStamp("lease-rollback", 1), profile, null, descriptor)
            provider.acquire(request, operation).use { lease ->
                f.clock.now--
                try { lease.search(request, operation); fail("owner execution clock must detect rollback") }
                catch (failure: DiscoveryFailure) { assertEquals(DiscoveryError.CANCELLED, failure.code) }
            }
            assertTrue(source.cursors.isEmpty()); assertTrue(cancellation.isCancelled)
            assertEquals(ProviderRepositoryError.CLOCK_ROLLBACK, repo.snapshot().error)
        } }
    }

    @Test fun alreadyObservedPackageExpiryCannotBeReanimatedByAnOlderCallerSample() {
        Fixture().use { f ->
            f.prepare(); f.open().use { repo ->
                val provider = f.provider(repo); val descriptor = provider.descriptor
                val sampled = f.clock.now; val view = provider.progressAuthority(descriptor, sampled)!!
                f.clock.now = ProviderPackageFixtures.NOW + 60_000
                assertEquals(ProviderRepositoryError.EXPIRED, repo.snapshot().error)
                assertFalse(view.isCurrent(sampled)); assertNull(provider.progressAuthority(descriptor, sampled))
                assertFalse(view.publishIfCurrent(sampled, null) { fail("expired identity cannot publish") })
            }
        }
    }

    /** Fixture codec only. Explicit known keys, complete finite rows, no old map/object aliases.
     * Not a DiscoveryStore implementation, database format, or SQLite durability simulation. */
    private val Snapshot = FixtureSnapshot()
    private inner class FixtureSnapshot {
        fun write(file: File, store: DiscoveryStore, ids: List<String>) {
            require(ids.size <= 8)
            val data = ByteArrayOutputStream()
            DataOutputStream(data).use { out -> store.transaction {
                val cp = checkpoint("session")!!
                require(cp.profile.filters == SearchFilters(safeSearch = false))
                require(cp.pool.entries.size <= 8 && cp.pool.pages.size <= 8)
                out.writeInt(0x46525031)
                out.writeUTF(cp.stamp.sessionId); out.writeLong(cp.stamp.epoch)
                out.writeUTF(cp.profile.profileId); out.writeUTF(cp.profile.query); out.writeUTF(cp.profile.locale)
                out.writeUTF(cp.profile.historyPolicy.name); out.writeUTF(cp.profile.sortOrder.name)
                out.text(cp.sourceConfigurationKey); out.writeBoolean(cp.queryAuthorized)
                out.writeLong(cp.pool.viewRevision); out.writeLong(cp.pool.nextOrder)
                out.writeInt(cp.pool.entries.size)
                cp.pool.entries.forEach { entry ->
                    val r = entry.result
                    out.writeUTF(r.fingerprint); out.writeUTF(r.canonicalPageUrl); out.text(r.title); out.writeUTF(r.sourceName)
                    out.text(r.sourceNamespace); out.text(r.sourceVideoId); out.text(r.thumbnailUrl); out.number(r.durationMs)
                    out.text(r.description); out.number(r.publishedAtEpochMillis)
                    out.strings(r.provenance); out.strings(r.identityAliases); out.writeInt(r.identityVersion)
                    val rights = RightsCodec.encode(r.rights); out.writeInt(rights.size); out.write(rights)
                    out.writeLong(entry.discoveredAtEpochMillis); out.writeLong(entry.order)
                }
                cp.pool.pages.forEach { require(it.stamp == cp.stamp) }
                out.writeInt(cp.pool.pages.size)
                cp.pool.pages.forEach { page ->
                    out.writeLong(page.revision); out.writeInt(page.pageIndex); out.writeInt(page.pageSize)
                    out.strings(page.orderedResultIds)
                }
                val progresses = ids.mapNotNull { progress(cp.profile.profileId, it, cp.profile.queryKey) }
                out.writeInt(progresses.size)
                progresses.forEach { p ->
                    out.writeUTF(p.profileId); out.writeUTF(p.providerId); out.writeUTF(p.queryKey); out.writeUTF(p.providerVersion)
                    out.writeInt(p.cursorSchemaVersion); out.text(p.cursor); out.number(p.expiresAtEpochMillis)
                    out.writeBoolean(p.exhausted); out.number(p.lastSuccessAtEpochMillis)
                }
                val fact = seen(profile.profileId, historical.fingerprint)
                out.writeBoolean(fact != null)
                fact?.let {
                    out.writeUTF(it.profileId); out.writeUTF(it.fingerprint); out.writeLong(it.firstDiscoveredAt)
                    out.number(it.shownAt); out.number(it.openedAt); out.number(it.playedAt); out.number(it.savedAt); out.number(it.dismissedAt)
                }
            } }
            val bytes = data.toByteArray(); require(bytes.size <= 262_144); file.writeBytes(bytes)
        }
        fun read(file: File): InMemoryDiscoveryStore {
            require(file.length() in 1..262_144)
            return DataInputStream(ByteArrayInputStream(file.readBytes())).use { input ->
                require(input.readInt() == 0x46525031)
                val stamp = SessionStamp(input.readUTF(), input.readLong())
                val restoredProfile = SearchProfile(input.readUTF(), input.readUTF(), input.readUTF(), SearchFilters(safeSearch = false),
                    com.easysavevideo.core.model.RepeatPolicy.valueOf(input.readUTF()), ResultSort.valueOf(input.readUTF()))
                val configuration = input.text(); val authorized = input.readBoolean()
                val revision = input.readLong(); val nextOrder = input.readLong()
                val entries = List(input.count(8)) {
                    val fingerprint = input.readUTF(); val url = input.readUTF(); val title = input.text(); val name = input.readUTF()
                    val namespace = input.text(); val videoId = input.text(); val thumbnail = input.text(); val duration = input.number()
                    val description = input.text(); val published = input.number(); val provenance = input.strings().toSet()
                    val aliases = input.strings().toSet(); val identityVersion = input.readInt()
                    val rightsSize = input.count(65_536); require(rightsSize <= input.available())
                    val rights = RightsCodec.decode(ByteArray(rightsSize).also(input::readFully))
                    val result = VideoSearchResult(fingerprint, url, title, name, namespace, videoId, thumbnail, duration,
                        description, published, provenance, aliases, identityVersion, rights)
                    PoolEntry(result, input.readLong(), input.readLong())
                }
                val pages = List(input.count(8)) {
                    val pageRevision = input.readLong(); val index = input.readInt(); val size = input.readInt()
                    PageSnapshot(stamp, pageRevision, index, input.strings(), size)
                }
                val progresses = List(input.count(8)) { ProviderProgress(input.readUTF(), input.readUTF(), input.readUTF(),
                    input.readUTF(), input.readInt(), input.text(), input.number(), input.readBoolean(), input.number()) }
                val fact = if (input.readBoolean()) SeenRecord(input.readUTF(), input.readUTF(), input.readLong(),
                    input.number(), input.number(), input.number(), input.number(), input.number()) else null
                require(input.available() == 0)
                InMemoryDiscoveryStore().apply { transaction {
                    putCheckpoint(DiscoveryCheckpoint(stamp, restoredProfile, PoolState(entries, pages, revision, nextOrder), configuration, authorized))
                    progresses.forEach(::putProgress); fact?.let(::putSeen)
                } }
            }
        }
        private fun DataOutputStream.text(value: String?) { writeBoolean(value != null); if (value != null) { require(value.length <= 8192); writeUTF(value) } }
        private fun DataInputStream.text(): String? = if (readBoolean()) readUTF().also { require(it.length <= 8192) } else null
        private fun DataOutputStream.number(value: Long?) { writeBoolean(value != null); value?.let(::writeLong) }
        private fun DataInputStream.number(): Long? = if (readBoolean()) readLong() else null
        private fun DataOutputStream.strings(values: Collection<String>) { require(values.size <= 128); writeInt(values.size); values.forEach { writeUTF(it) } }
        private fun DataInputStream.strings(): List<String> = List(count(128)) { readUTF() }
        private fun DataInputStream.count(maximum: Int): Int = readInt().also { require(it in 0..maximum) }
    }
}
