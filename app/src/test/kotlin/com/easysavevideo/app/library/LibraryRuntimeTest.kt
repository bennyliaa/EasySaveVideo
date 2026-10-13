package com.easysavevideo.app.library

import com.easysavevideo.library.*
import com.easysavevideo.app.library.destination.DirectoryPickerLifecycle
import com.easysavevideo.app.library.destination.FolderPickerAsyncBridge
import com.easysavevideo.app.library.destination.FolderPickerRequestCodes
import com.easysavevideo.app.library.export.DirectoryBatchConfirmation
import com.easysavevideo.app.library.export.DirectoryConfirmationDecision
import com.easysavevideo.app.library.export.consumeDirectoryBatchConfirmation
import com.easysavevideo.app.library.export.dispatchLibraryBatchRouteExit
import com.easysavevideo.app.ui.shell.EsvDestination
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

/** Repository/scheduling fakes only: no Android filesystem, player, device, or real media. */
class LibraryRuntimeTest {
    @Test fun ordinaryForegroundReturnPreservesCompleteSecondPageQueryAcrossRepeatedPauses() {
        for (folderId in listOf(null, "folder")) {
            val h = readingPageHarness()
            h.runtime.enterFolder(folderId); h.drain()
            h.runtime.search("Entry"); h.ui.runTimers(); h.drain()
            h.runtime.sort(LibrarySort.NAME); h.drain()
            h.runtime.reverseSort(); h.drain()
            h.runtime.nextPage(); h.drain()
            h.runtime.folderPage(if (folderId == null) 1 else 0)
            val query = h.runtime.state.query
            val page = h.runtime.state.items
            val folderPage = h.runtime.state.folderPage
            assertEquals(50, query.cursor?.offset)
            assertEquals(50, page.size)
            repeat(3) {
                val beforeQueries = h.repo.queries.size
                val beforeRetries = h.repo.pendingDeleteCalls
                h.runtime.setForeground(false); h.runtime.setForeground(false)
                assertFalse(h.runtime.state.foreground)
                assertEquals(query, h.runtime.state.query)
                assertTrue(h.work.queued.isEmpty())
                h.runtime.setForeground(true); h.runtime.setForeground(true)
                assertTrue(h.runtime.state.loading)
                assertEquals(query, h.runtime.state.query)
                assertEquals(1, h.work.queued.size)
                h.drain()
                assertEquals(beforeQueries + 1, h.repo.queries.size)
                assertEquals(beforeRetries + 1, h.repo.pendingDeleteCalls)
                assertEquals(query, h.repo.queries.last())
                assertEquals(query, h.runtime.state.query)
                assertEquals(page, h.runtime.state.items)
                assertEquals(folderPage, h.runtime.state.folderPage)
                assertTrue(h.runtime.state.ready)
                val calls = h.repo.calls
                h.runtime.setForeground(true); h.drain()
                assertEquals(calls, h.repo.calls)
            }
            h.runtime.close()
        }
    }

    @Test fun ordinaryForegroundReturnRejectsOldEpochSuccessAndFailureAfterNewPagePublication() {
        for (oldFailure in listOf(false, true)) {
            val h = readingPageHarness()
            val query = h.runtime.state.query
            val selected = h.runtime.state.items.first()
            val normalQuery = h.repo.queryOverride!!
            if (oldFailure) h.repo.queryOverride = { throw LibraryFailure(LibraryError.STALE_PAGE) }
            h.runtime.setForeground(false); h.runtime.setForeground(true)
            h.work.runNext()
            val oldCompletion = h.ui.posts.removeFirst()
            h.repo.queryOverride = normalQuery
            h.repo.media[selected.itemId] = selected.copy(revision = selected.revision + 1)
            h.runtime.setForeground(false); h.runtime.setForeground(true); h.drain()
            assertEquals(query, h.runtime.state.query)
            assertEquals(selected.revision + 1, h.runtime.state.items.first { it.itemId == selected.itemId }.revision)
            val current = h.runtime.state
            val calls = h.repo.calls
            oldCompletion(); h.drain()
            assertEquals(current, h.runtime.state)
            assertEquals(calls, h.repo.calls)
            assertTrue(h.work.queued.isEmpty())
            h.runtime.close()
        }
    }

    @Test fun newerSearchOwnsReadingPageAfterLateForegroundReturnResult() {
        val h = readingPageHarness()
        h.runtime.setForeground(false); h.runtime.setForeground(true)
        h.work.runNext()
        val oldCompletion = h.ui.posts.removeFirst()
        h.runtime.search("Entry 12"); h.ui.runTimers(); h.drain()
        assertNull(h.runtime.state.query.cursor)
        assertEquals("Entry 12", h.runtime.state.query.text)
        assertEquals(5, h.runtime.state.items.size)
        assertTrue(h.runtime.state.items.all { it.displayName.startsWith("Entry 12") })
        val current = h.runtime.state
        val queries = h.repo.queries.toList()
        oldCompletion(); h.drain()
        assertEquals(current, h.runtime.state)
        assertEquals(queries, h.repo.queries)
        h.runtime.close()
    }

    @Test fun explicitReadingIntentsKeepTheirCursorSemanticsAfterForegroundReturn() {
        for (intent in 0..6) {
            val h = readingPageHarness()
            h.runtime.setForeground(false); h.runtime.setForeground(true); h.drain()
            val original = h.runtime.state.query
            assertEquals(50, original.cursor?.offset)
            val next = h.runtime.state.nextCursor
            val expected = when (intent) {
                0 -> original.copy(text = "Entry 12", cursor = null)
                1 -> original.copy(sort = LibrarySort.SIZE, cursor = null)
                2 -> original.copy(direction = SortDirection.ASCENDING, cursor = null)
                3 -> original.copy(folderId = "folder", text = "", cursor = null)
                4, 5 -> original.copy(cursor = null)
                else -> original.copy(cursor = requireNotNull(next))
            }
            when (intent) {
                0 -> { h.runtime.search("Entry 12"); h.ui.runTimers() }
                1 -> h.runtime.sort(LibrarySort.SIZE)
                2 -> h.runtime.reverseSort()
                3 -> h.runtime.enterFolder("folder")
                4 -> h.runtime.refresh()
                5 -> h.runtime.previousPage()
                6 -> h.runtime.nextPage()
            }
            h.drain()
            assertEquals(expected, h.runtime.state.query)
            assertEquals(expected, h.repo.queries.last())
            assertEquals(LibraryQueryPolicy.page(h.repo.media.values, expected, h.repo.catalogVersion).items,
                h.runtime.state.items)
            if (intent == 6) {
                assertEquals(100, h.runtime.state.query.cursor?.offset)
                h.runtime.previousPage(); h.drain()
                assertEquals(original, h.runtime.state.query)
                h.runtime.previousPage(); h.drain()
                assertNull(h.runtime.state.query.cursor)
            }
            h.runtime.close()
        }
    }

    @Test fun catalogVersionChangeOnOrdinaryReturnFallsBackToFirstPage() {
        val h = readingPageHarness()
        val oldQuery = h.runtime.state.query
        val oldPage = h.runtime.state.items
        val before = h.repo.queries.size
        h.runtime.setForeground(false)
        h.repo.catalogVersion++
        h.runtime.setForeground(true)
        assertEquals(oldQuery, h.runtime.state.query)
        h.work.runNext(); h.ui.runPosts()
        // The real cursor policy rejects the old catalog version before the fallback is read.
        assertEquals(listOf(oldQuery), h.repo.queries.drop(before))
        assertNull(h.runtime.state.query.cursor)
        assertTrue(h.runtime.state.items.isEmpty())
        assertFalse(h.runtime.state.ready)
        assertTrue(h.runtime.state.loading)
        h.drain()
        val firstPageQuery = oldQuery.copy(cursor = null)
        assertEquals(listOf(oldQuery, firstPageQuery), h.repo.queries.drop(before))
        assertEquals(LibraryQueryPolicy.page(h.repo.media.values, firstPageQuery, h.repo.catalogVersion).items,
            h.runtime.state.items)
        assertTrue(h.runtime.state.items.none { it.itemId in oldPage.map { old -> old.itemId } })
        assertTrue(h.runtime.state.notice.orEmpty().contains("变化"))
        assertTrue(h.runtime.state.ready)
        h.runtime.close()
    }

    @Test fun foregroundReturnRetainsReadingPageWithoutRestoringOldActionConsent() {
        for (action in 0..3) {
            val h = readingPageHarness()
            val item = h.runtime.state.items.first()
            val selection = LibrarySelection.Media(item)
            h.runtime.select(item)
            var deletion: LibraryDeleteSelection? = null
            when (action) {
                1 -> { h.runtime.requestDelete(); deletion = h.runtime.state.deletion; assertNotNull(deletion) }
                2 -> { h.runtime.rename(); h.runtime.editName("Changed"); assertNotNull(h.runtime.state.editor) }
                3 -> {
                    h.runtime.startMove(); h.drain(); h.runtime.nextPage(); h.drain()
                    assertNotNull(h.runtime.state.move)
                }
            }
            val query = h.runtime.state.query
            assertNotNull(query.cursor)
            h.runtime.setForeground(false)
            assertNull(h.runtime.state.selected)
            assertNull(h.runtime.state.deletion)
            assertNull(h.runtime.state.editor)
            assertNull(h.runtime.state.move)
            assertNull(h.runtime.takeSelectedReadRef(selection))
            h.runtime.setForeground(true); h.drain()
            assertEquals(query, h.runtime.state.query)
            assertTrue(h.runtime.state.items.any { it == item })
            assertNull(h.runtime.takeSelectedReadRef(selection))
            deletion?.let(h.runtime::confirmDelete)
            h.runtime.submitName(); h.runtime.confirmMove(); h.drain()
            assertEquals(0, h.repo.deleteCalls)
            assertEquals(0, h.repo.renameCalls)
            assertEquals(0, h.repo.moveCalls)
            assertNull(h.runtime.state.selected)
            h.runtime.select(item)
            assertEquals(item.readRef(), h.runtime.takeSelectedReadRef(selection))
            assertNull(h.runtime.takeSelectedReadRef(selection))
            h.runtime.close()
        }
    }

    @Test fun busyMutationReturnWaitsForAcceptedWriteAndKeepsExistingFirstPageRefresh() {
        for (conflict in listOf(false, true)) {
            val h = readingPageHarness()
            val item = h.runtime.state.items.first()
            val original = h.runtime.state.query
            h.runtime.select(item); h.runtime.rename(); h.runtime.editName("Renamed")
            if (conflict) h.repo.media[item.itemId] = item.copy(revision = item.revision + 1)
            h.runtime.submitName()
            assertTrue(h.runtime.state.busy)
            val before = h.repo.queries.size
            h.runtime.setForeground(false); h.runtime.setForeground(true); h.runtime.setForeground(true)
            assertEquals(original, h.runtime.state.query)
            assertTrue(h.runtime.state.busy)
            assertEquals(1, h.work.queued.size)
            assertEquals(before, h.repo.queries.size)
            h.drain()
            assertEquals(1, h.repo.renameCalls)
            assertFalse(h.runtime.state.busy)
            assertNull(h.runtime.state.query.cursor)
            assertEquals(before + 1, h.repo.queries.size)
            assertEquals(original.copy(cursor = null), h.repo.queries.last())
            if (conflict) assertTrue(h.runtime.state.notice.orEmpty().contains("变化"))
            else assertEquals("Renamed", h.repo.media.getValue(item.itemId).displayName)
            assertNull(h.runtime.state.selected)
            h.runtime.close()
        }
    }

    @Test fun retiredPickerCannotRegainCopyConsentWhenOrdinaryResumeKeepsEqualPage() {
        for (retirement in 0..2) {
            val h = pickerPageHarness()
            val request = PickerRequest(h)
            val query = request.selection.query
            if (retirement != 0) {
                request.receive(); h.runtime.setForeground(true); h.drain()
                assertTrue(request.canConfirm())
            }
            if (retirement == 2) assertEquals(DirectoryConfirmationDecision.CONFIRMED, request.confirm())
            else request.cancel()
            val copies = request.copies
            val before = h.repo.queries.size
            h.runtime.setForeground(false); h.runtime.setForeground(true); h.drain()
            assertEquals(before + 1, h.repo.queries.size)
            assertEquals(query, h.runtime.state.query)
            assertTrue(batchSelectionIsCurrent(request.selection, h.runtime.state))
            assertTrue(request.lease.retired)
            assertFalse(request.lease.isCurrent())
            assertFalse(request.lifecycle.receive())
            assertFalse(request.canConfirm())
            assertEquals(DirectoryConfirmationDecision.IGNORED, request.confirm())
            assertEquals(copies, request.copies)
            h.runtime.close()
        }
    }

    @Test fun oldResumeCompletionCannotClearNewEpochLoadingEvenWhenQueryIsEqual() {
        for (oldFailure in listOf(false, true)) {
            val h = readingPageHarness()
            val query = h.runtime.state.query
            val normalQuery = h.repo.queryOverride!!
            if (oldFailure) h.repo.queryOverride = { throw LibraryFailure(LibraryError.STORAGE_FAILURE) }
            h.runtime.setForeground(false); h.runtime.setForeground(true); h.work.runNext()
            val oldCompletion = h.ui.posts.removeFirst()
            h.repo.queryOverride = normalQuery
            h.runtime.setForeground(false); h.runtime.setForeground(true)
            val loading = h.runtime.state
            assertTrue(loading.loading)
            assertEquals(query, loading.query)
            assertEquals(1, h.work.queued.size)
            oldCompletion()
            assertEquals(loading, h.runtime.state)
            assertEquals(1, h.work.queued.size)
            h.drain()
            assertTrue(h.runtime.state.ready)
            assertFalse(h.runtime.state.loading)
            assertEquals(query, h.runtime.state.query)
            h.runtime.close()
        }
    }

    @Test fun queuedNextPageIsCancelledOnLeaveAndReadAtSameCursorOnReturn() {
        val h = readingPageHarness()
        val before = h.repo.queries.size
        h.runtime.nextPage()
        val nextQuery = h.runtime.state.query
        assertEquals(100, nextQuery.cursor?.offset)
        assertEquals(1, h.work.queued.size)
        h.runtime.setForeground(false)
        assertTrue(h.work.queued.isEmpty())
        assertFalse(h.runtime.state.loading)
        assertEquals(before, h.repo.queries.size)
        h.runtime.setForeground(true); h.runtime.setForeground(true); h.drain()
        assertEquals(listOf(nextQuery), h.repo.queries.drop(before))
        assertEquals(nextQuery, h.runtime.state.query)
        assertEquals(25, h.runtime.state.items.size)
        assertEquals(LibraryQueryPolicy.page(h.repo.media.values, nextQuery, h.repo.catalogVersion).items,
            h.runtime.state.items)
        h.runtime.close()
    }

    @Test fun missingFolderOnOrdinaryReturnKeepsExistingRootFirstPageFallback() {
        val h = readingPageHarness()
        h.runtime.enterFolder("folder"); h.drain(); h.runtime.nextPage(); h.drain()
        val query = h.runtime.state.query
        assertNotNull(query.cursor)
        val before = h.repo.queries.size
        h.runtime.setForeground(false)
        h.repo.folderRecords.remove("folder")
        h.runtime.setForeground(true); h.drain()
        val root = query.copy(folderId = null, cursor = null)
        assertEquals(root, h.runtime.state.query)
        assertNull(h.runtime.state.folder)
        assertEquals(listOf(root), h.repo.queries.drop(before))
        assertEquals(LibraryQueryPolicy.page(h.repo.media.values, root, h.repo.catalogVersion).items,
            h.runtime.state.items)
        assertTrue(h.runtime.state.ready)
        h.runtime.close()
    }

    @Test fun failedOrRejectedOrdinaryReturnDoesNotRetryOnDuplicateForeground() {
        for (rejected in listOf(false, true)) {
            val h = readingPageHarness()
            val query = h.runtime.state.query
            val normalQuery = h.repo.queryOverride!!
            h.runtime.setForeground(false)
            if (rejected) h.work.reject = true
            else h.repo.queryOverride = { throw LibraryFailure(LibraryError.STORAGE_FAILURE) }
            h.runtime.setForeground(true); h.drain()
            assertEquals(query, h.runtime.state.query)
            assertFalse(h.runtime.state.ready)
            assertFalse(h.runtime.state.loading)
            assertTrue(h.runtime.state.items.isEmpty())
            assertNull(h.runtime.state.nextCursor)
            val calls = h.repo.calls
            h.work.reject = false; h.repo.queryOverride = normalQuery
            h.runtime.setForeground(true); h.runtime.setForeground(true); h.drain()
            assertEquals(calls, h.repo.calls)
            assertFalse(h.runtime.state.ready)
            assertTrue(h.work.queued.isEmpty())
            h.runtime.setForeground(false); h.runtime.setForeground(true); h.drain()
            assertTrue(h.runtime.state.ready)
            assertEquals(query, h.runtime.state.query)
            assertEquals(query, h.repo.queries.last())
            h.runtime.close()
        }
    }

    @Test fun mutationCompletedInBackgroundRevalidatesCatalogBeforeReadingReturn() {
        val h = readingPageHarness()
        val original = h.runtime.state.query
        val item = h.runtime.state.items.first()
        h.runtime.select(item); h.runtime.rename(); h.runtime.editName("Renamed")
        h.runtime.submitName()
        val before = h.repo.queries.size
        h.runtime.setForeground(false)
        h.work.runNext()
        assertEquals("Renamed", h.repo.media.getValue(item.itemId).displayName)
        h.repo.catalogVersion++ // The committed catalog change invalidates the old continuation.
        h.ui.runPosts()
        assertFalse(h.runtime.state.busy)
        assertFalse(h.runtime.state.foreground)
        assertEquals(original, h.runtime.state.query)
        assertEquals(before, h.repo.queries.size)
        assertTrue(h.work.queued.isEmpty())
        h.runtime.setForeground(true); h.drain()
        assertEquals(listOf(original, original.copy(cursor = null)), h.repo.queries.drop(before))
        assertNull(h.runtime.state.query.cursor)
        assertTrue(h.runtime.state.ready)
        assertEquals(1, h.repo.renameCalls)
        assertTrue(h.runtime.state.notice.orEmpty().contains("变化"))
        h.runtime.close()
    }

    @Test fun repositoryCallsAreOffUiAndPagesReplaceRatherThanAccumulate() {
        val h = Harness()
        h.repo.queryOverride = { query ->
            if (query.cursor == null) LibraryPage(listOf(media("first")), 1, LibraryCursor(1, query.queryKey(), 50))
            else LibraryPage(listOf(media("second")), 1, null)
        }
        h.runtime.setForeground(true)
        assertEquals(0, h.repo.calls)
        assertTrue(h.runtime.state.loading)
        h.drain()
        assertEquals(listOf("first"), h.runtime.state.items.map { it.itemId })
        h.runtime.nextPage(); h.drain()
        assertEquals(listOf("second"), h.runtime.state.items.map { it.itemId })
        assertEquals(50, h.runtime.state.query.cursor?.offset)
        h.runtime.previousPage(); h.drain()
        assertEquals(listOf("first"), h.runtime.state.items.map { it.itemId })
        h.runtime.close()
    }

    @Test fun staleRunningSearchCannotPublishIntoNewGeneration() {
        val h = Harness()
        h.repo.media["old"] = media("old")
        h.repo.media["new"] = media("new")
        h.runtime.setForeground(true)
        h.work.runNext() // Old result is in the main callback queue, not yet visible.
        h.runtime.search("new")
        h.ui.runPosts()
        assertTrue(h.runtime.state.items.isEmpty())
        assertTrue(h.runtime.state.loading)
        h.ui.runTimers(); h.drain()
        assertEquals(listOf("new"), h.runtime.state.items.map { it.itemId })
        assertEquals("new", h.runtime.state.query.text)
        h.runtime.close()
    }

    @Test fun rapidSearchReplacesQueuedReadAndBoundsInput() {
        val h = Harness()
        h.runtime.setForeground(true)
        h.runtime.search("first")
        h.runtime.search("中".repeat(500))
        assertEquals(0, h.work.queued.size)
        assertEquals(1, h.ui.timers.size)
        assertTrue(h.runtime.state.query.text.toByteArray().size <= LibraryLimits.MAX_SEARCH_UTF8_BYTES)
        h.ui.runTimers(); h.drain()
        assertEquals(1, h.repo.queries.size)
        h.runtime.close()
    }

    @Test fun staleCursorRecoversToFirstPageWithoutAppendingObsoleteRows() {
        val h = Harness()
        var version = 1L
        h.repo.queryOverride = { query ->
            if (query.cursor != null) throw LibraryFailure(LibraryError.STALE_PAGE)
            LibraryPage(listOf(media("version-$version")), version, LibraryCursor(version, query.queryKey(), 50))
        }
        h.start()
        version = 2
        h.runtime.nextPage(); h.drain()
        assertNull(h.runtime.state.query.cursor)
        assertEquals(listOf("version-2"), h.runtime.state.items.map { it.itemId })
        assertTrue(h.runtime.state.notice.orEmpty().contains("变化"))
        h.runtime.close()
    }

    @Test fun deletionConfirmationBindsExactSelectedItemAndRevision() {
        val h = Harness()
        val first = media("first")
        val second = media("second")
        h.repo.media[first.itemId] = first; h.repo.media[second.itemId] = second
        h.start()
        h.runtime.select(first); h.runtime.requestDelete()
        val oldConfirmation = h.runtime.state.deletion!!
        h.runtime.dismissSelection(); h.runtime.select(second)
        h.runtime.confirmDelete(oldConfirmation); h.drain()
        assertEquals(0, h.repo.deleteCalls)
        assertNull(h.runtime.state.deletion)
        assertNull(h.runtime.state.selected)
        assertFalse(oldConfirmation.matches(LibrarySelection.Media(first.copy(revision = 2))))
        assertFalse(oldConfirmation.matches(LibrarySelection.Media(first.copy(representationGeneration = 2))))
        h.runtime.close()
    }

    @Test fun backgroundClearsDeleteAndMoveConsentAndRejectsOldCallbacks() {
        val h = Harness()
        val item = media("selected")
        h.repo.media[item.itemId] = item
        h.start()
        h.runtime.select(item); h.runtime.requestDelete()
        val confirmation = h.runtime.state.deletion!!
        h.runtime.setForeground(false)
        h.runtime.confirmDelete(confirmation)
        assertNull(h.runtime.state.deletion)
        assertEquals(0, h.repo.deleteCalls)
        h.runtime.setForeground(true); h.drain()
        h.runtime.select(item); h.runtime.startMove()
        assertNotNull(h.runtime.state.move)
        h.runtime.setForeground(false)
        assertNull(h.runtime.state.move)
        h.runtime.confirmMove(); h.drain()
        assertEquals(0, h.repo.moveCalls)
        h.runtime.close()
    }

    @Test fun doubleDeleteTapProducesOneDurableIntentAndHonestPendingState() {
        val h = Harness()
        val item = media("selected")
        h.repo.media[item.itemId] = item
        h.start()
        h.runtime.select(item); h.runtime.requestDelete()
        val confirmation = h.runtime.state.deletion!!
        h.runtime.confirmDelete(confirmation)
        h.runtime.confirmDelete(confirmation)
        assertTrue(h.runtime.state.busy)
        assertTrue(h.runtime.state.notice.orEmpty().contains("尚未确认完成"))
        h.drain()
        assertEquals(1, h.repo.deleteCalls)
        assertTrue(h.runtime.state.items.isEmpty())
        assertEquals(setOf(item.itemId), h.runtime.state.pendingDeletes)
        assertEquals(1, h.runtime.state.pendingDeleteCount)
        assertTrue(h.runtime.state.notice.orEmpty().contains("尚未完成"))
        h.repo.media[item.itemId] = item.copy(revision = 2, lifecycle = MediaLifecycle.DELETED)
        h.ui.runTimers(); h.drain()
        assertTrue(h.runtime.state.pendingDeletes.isEmpty())
        assertEquals(0, h.runtime.state.pendingDeleteCount)
        assertTrue(h.runtime.state.notice.orEmpty().contains("删除已完成"))
        assertTrue(h.ui.timers.isEmpty())
        h.runtime.close()
    }

    @Test fun missingTombstoneNeverCountsAsCompletedDeletion() {
        val h = Harness()
        val item = media("selected")
        h.repo.media[item.itemId] = item
        h.start()
        h.runtime.select(item); h.runtime.requestDelete()
        h.runtime.confirmDelete(h.runtime.state.deletion!!); h.drain()
        h.repo.media.remove(item.itemId)
        h.ui.runTimers(); h.drain()
        assertEquals(setOf(item.itemId), h.runtime.state.pendingDeletes)
        assertEquals(1, h.runtime.state.pendingDeleteCount)
        assertTrue(h.runtime.state.notice.orEmpty().contains("暂不可核实"))
        h.runtime.close()
    }

    @Test fun pendingDeletionPollDoesNotDismissAnotherConfirmation() {
        val h = Harness()
        val first = media("first"); val second = media("second")
        h.repo.media[first.itemId] = first; h.repo.media[second.itemId] = second
        h.start()
        h.runtime.select(first); h.runtime.requestDelete()
        h.runtime.confirmDelete(h.runtime.state.deletion!!); h.drain()
        h.runtime.select(second); h.runtime.requestDelete()
        val confirmation = h.runtime.state.deletion
        h.ui.runTimers(); h.drain()
        assertEquals(confirmation, h.runtime.state.deletion)
        assertEquals(1, h.repo.deleteCalls)
        h.runtime.close()
    }

    @Test fun restartPendingReportIsShownWithoutInventingSuccessfulDeletion() {
        val h = Harness()
        h.repo.media["pending"] = media("pending").copy(lifecycle = MediaLifecycle.DELETE_PENDING)
        h.start()
        assertEquals(1, h.runtime.state.pendingDeleteCount)
        assertTrue(h.runtime.state.items.isEmpty())
        assertTrue(h.runtime.state.notice.orEmpty().contains("尚未完成"))
        assertEquals(1, h.ui.timers.size)
        h.runtime.close()
    }

    @Test fun nonemptyFolderDeleteExplainsCountsAndDoesNotQueueMutation() {
        val h = Harness()
        val folder = LibraryFolder("folder", 1, null, "Folder", itemCount = 2, childFolderCount = 3)
        h.repo.folderRecords[folder.folderId] = folder
        h.start()
        h.runtime.select(folder); h.runtime.requestDelete(); h.drain()
        assertNull(h.runtime.state.deletion)
        assertTrue(h.runtime.state.notice.orEmpty().contains("2 个视频、3 个子文件夹"))
        assertEquals(0, h.repo.folderDeleteCalls)
        h.runtime.close()
    }

    @Test fun acceptedMutationFinishesAfterDisposeWithoutPublishingOrClosingSharedRepository() {
        val h = Harness()
        val item = media("selected")
        h.repo.media[item.itemId] = item
        h.start()
        h.runtime.select(item); h.runtime.rename(); h.runtime.editName("Cafe\u0301")
        h.runtime.submitName()
        assertTrue(h.runtime.state.busy)
        h.runtime.close()
        h.drain()
        assertEquals("Café", h.repo.media[item.itemId]!!.displayName)
        assertEquals(1, h.repo.renameCalls)
        assertFalse(h.runtime.state.foreground)
        assertEquals(1, h.work.closes)
        assertNull(h.runtime.state.selected)
    }

    @Test fun playbackReferenceRequiresExactActiveForegroundSelectionAndIsSingleUse() {
        val h = Harness()
        val item = media("one")
        h.repo.media[item.itemId] = item
        h.start()
        val selection = LibrarySelection.Media(item)
        h.runtime.select(item)
        assertEquals(item.readRef(), h.runtime.takeSelectedReadRef(selection))
        assertNull(h.runtime.takeSelectedReadRef(selection))
        h.runtime.select(item)
        assertNull(h.runtime.takeSelectedReadRef(LibrarySelection.Media(item.copy(revision = 2))))
        h.runtime.setForeground(false)
        assertNull(h.runtime.takeSelectedReadRef(selection))
        h.runtime.close()
    }

    @Test fun disposedReadCannotPublishItsLateResult() {
        val h = Harness()
        h.repo.media["one"] = media("one")
        h.runtime.setForeground(true); h.work.runNext()
        h.runtime.close(); h.ui.runPosts()
        assertFalse(h.runtime.state.ready)
        assertTrue(h.runtime.state.items.isEmpty())
        assertTrue(h.ui.timers.isEmpty())
    }

    @Test fun queueRejectionRestoresControlsAndDoesNotClaimWriteSuccess() {
        val h = Harness()
        h.start()
        h.runtime.createFolder(); h.runtime.editName("Folder")
        h.work.reject = true
        h.runtime.submitName()
        assertFalse(h.runtime.state.busy)
        assertEquals(0, h.repo.createCalls)
        assertTrue(h.runtime.state.notice.orEmpty().contains("队列已满"))
        h.runtime.close()
    }

    @Test fun failedFolderNavigationClearsPreviousSnapshotAndDisablesDestination() {
        val h = Harness()
        val folder = LibraryFolder("folder", 1, null, "Folder")
        h.repo.folderRecords[folder.folderId] = folder
        h.repo.media["one"] = media("one")
        h.start()
        h.repo.queryOverride = { throw LibraryFailure(LibraryError.STORAGE_FAILURE) }
        h.runtime.enterFolder(folder.folderId)
        assertFalse(h.runtime.state.ready)
        assertTrue(h.runtime.state.items.isEmpty())
        assertTrue(h.runtime.state.folders.isEmpty())
        assertNull(h.runtime.state.folder)
        h.drain()
        assertFalse(h.runtime.state.ready)
        assertFalse(h.runtime.state.loading)
        assertTrue(h.runtime.state.items.isEmpty())
        assertTrue(h.runtime.state.folders.isEmpty())
        assertNull(h.runtime.state.folder)
        h.runtime.close()
    }

    @Test fun failedPageCannotExposePriorRowsUnderNewPageNumber() {
        val h = Harness()
        h.repo.queryOverride = { query ->
            if (query.cursor == null) LibraryPage(listOf(media("one")), 1, LibraryCursor(1, query.queryKey(), 50))
            else throw LibraryFailure(LibraryError.STORAGE_FAILURE)
        }
        h.start()
        h.runtime.nextPage(); h.drain()
        assertFalse(h.runtime.state.ready)
        assertTrue(h.runtime.state.items.isEmpty())
        assertNull(h.runtime.state.nextCursor)
        h.runtime.select(media("one"))
        assertNull(h.runtime.state.selected)
        h.runtime.close()
    }

    @Test fun moveCannotConfirmDestinationWhoseLatestLoadFailed() {
        val h = Harness()
        val item = media("one")
        val folder = LibraryFolder("folder", 1, null, "Folder")
        h.repo.media[item.itemId] = item
        h.repo.folderRecords[folder.folderId] = folder
        h.start()
        h.runtime.select(item); h.runtime.startMove(); h.drain()
        h.repo.queryOverride = { throw LibraryFailure(LibraryError.STORAGE_FAILURE) }
        h.runtime.enterFolder(folder.folderId); h.drain()
        assertFalse(h.runtime.state.ready)
        assertNotNull(h.runtime.state.move)
        h.runtime.confirmMove(); h.drain()
        assertEquals(0, h.repo.moveCalls)
        h.runtime.close()
    }

    @Test fun staleMutationShowsConflictAndReloadsCurrentMetadata() {
        val h = Harness()
        val item = media("selected")
        h.repo.media[item.itemId] = item
        h.start()
        h.runtime.select(item); h.runtime.rename(); h.runtime.editName("Edited")
        h.repo.media[item.itemId] = item.copy(revision = 2, displayName = "Already edited")
        h.runtime.submitName(); h.drain()
        assertEquals("Already edited", h.runtime.state.items.single().displayName)
        assertTrue(h.runtime.state.notice.orEmpty().contains("变化"))
        assertFalse(h.runtime.state.busy)
        h.runtime.close()
    }

    @Test fun wideRefreshRetainsIdentityButConsumesOnlyLatestReference() {
        val h=Harness();val old=media("one");h.repo.media[old.itemId]=old;h.start();h.runtime.select(old)
        val latest=old.copy(revision=2,representationGeneration=3,displayName="Latest")
        h.repo.media[old.itemId]=latest
        h.runtime.refresh(preserveSelection=true)
        assertEquals(old.itemId,h.runtime.state.selected?.id)
        assertNull(h.runtime.takeSelectedReadRef(LibrarySelection.Media(old)))
        h.drain()
        assertEquals(LibrarySelection.Media(latest),h.runtime.state.selected)
        assertNull(h.runtime.takeSelectedReadRef(LibrarySelection.Media(old)))
        assertEquals(latest.readRef(),h.runtime.takeSelectedReadRef(LibrarySelection.Media(latest)))
        assertEquals(0,h.repo.renameCalls);assertEquals(0,h.repo.moveCalls);assertEquals(0,h.repo.deleteCalls)
        h.runtime.close()
    }
    @Test fun wideRefreshMissingSelectionDoesNotJumpOrAuthorizeOperations() {
        val h=Harness();val one=media("one");val other=media("other")
        h.repo.media[one.itemId]=one;h.repo.media[other.itemId]=other;h.start();h.runtime.select(one)
        h.repo.media.remove(one.itemId);h.runtime.refresh(preserveSelection=true);h.drain()
        assertEquals(one.itemId,h.runtime.state.selected?.id)
        assertNull(libraryCurrentDetail(h.runtime.state.selected,h.runtime.state.items,h.runtime.state.folders))
        assertNull(h.runtime.takeSelectedReadRef(LibrarySelection.Media(one)))
        h.runtime.rename();assertNull(h.runtime.state.editor)
        assertEquals(0,h.repo.renameCalls);assertEquals(0,h.repo.moveCalls);assertEquals(0,h.repo.deleteCalls)
        h.runtime.select(other);assertEquals(other.itemId,h.runtime.state.selected?.id)
        h.runtime.close()
    }
    @Test fun defaultCompactRefreshStillClearsSelectionAndChangedQueryDoesNotRestoreIt() {
        val h=Harness();val one=media("one");h.repo.media[one.itemId]=one;h.start();h.runtime.select(one)
        h.runtime.refresh();h.drain();assertNull(h.runtime.state.selected)
        h.runtime.select(one);h.runtime.refresh(preserveSelection=true)
        h.runtime.dismissSelection();h.drain();assertNull(h.runtime.state.selected)
        h.runtime.select(one);h.runtime.sort(LibrarySort.SIZE);h.drain();assertNull(h.runtime.state.selected)
        h.runtime.close()
    }
    @Test fun wideRefreshFailureAndBackgroundCannotExposeOldReference() {
        val h=Harness();val one=media("one");h.repo.media[one.itemId]=one;h.start();h.runtime.select(one)
        h.repo.queryOverride={throw LibraryFailure(LibraryError.STORAGE_FAILURE)}
        h.runtime.refresh(preserveSelection=true);h.drain()
        assertFalse(h.runtime.state.ready);assertNull(h.runtime.takeSelectedReadRef(LibrarySelection.Media(one)))
        h.runtime.requestDelete();h.runtime.startMove();assertNull(h.runtime.state.deletion);assertNull(h.runtime.state.move)
        h.runtime.setForeground(false);assertNull(h.runtime.state.selected)
        h.runtime.close()
    }

    @Test fun batchMoveAdmitsExactSnapshotAndCannotDuplicateConfirmation() {
        val h=Harness();val a=media("a");val b=media("b");h.repo.media[a.itemId]=a;h.repo.media[b.itemId]=b
        h.repo.folderRecords["target"]=LibraryFolder("target",1,null,"target");h.start()
        assertTrue(h.runtime.startMoveBatch(listOf(a.readRef(),b.readRef())))
        assertFalse(h.runtime.startMoveBatch(listOf(a.readRef())))
        h.drain();h.runtime.enterFolder("target");h.drain()
        h.runtime.confirmMove();h.runtime.confirmMove();h.drain()
        assertEquals(2,h.repo.moveCalls)
        assertEquals(listOf(BatchMoveStatus.MOVED,BatchMoveStatus.MOVED),h.runtime.state.batchMoveResults.map{it.status})
        h.runtime.close()
    }

    @Test fun batchAdmissionRejectsStaleDuplicateLoadingAndBackground() {
        val h=Harness();val a=media("a");h.repo.media[a.itemId]=a
        h.runtime.setForeground(true);assertFalse(h.runtime.startMoveBatch(listOf(a.readRef())));h.drain()
        assertFalse(h.runtime.startMoveBatch(listOf(a.readRef().copy(representationGeneration=2))))
        assertFalse(h.runtime.startMoveBatch(listOf(a.readRef(),a.readRef())))
        h.runtime.setForeground(false);assertFalse(h.runtime.startMoveBatch(listOf(a.readRef())))
        assertEquals(0,h.repo.moveCalls);h.runtime.close()
    }

    @Test fun cancellingAcceptedQueuedBatchDoesNotMoveAnyItem() {
        val h=Harness();val a=media("a");h.repo.media[a.itemId]=a;h.start()
        assertTrue(h.runtime.startMoveBatch(listOf(a.readRef())));h.drain()
        h.runtime.confirmMove();h.runtime.cancelMove();h.drain()
        assertEquals(0,h.repo.moveCalls)
        assertEquals(BatchMoveStatus.CANCELLED_BEFORE_START,h.runtime.state.batchMoveResults.single().status)
        h.runtime.close()
    }

    @Test fun backgroundAndDisposalCancelQueuedBatchWithoutStaleUiPublication() {
        for(dispose in listOf(false,true)) {
            val h=Harness();val a=media("a");h.repo.media[a.itemId]=a;h.start()
            assertTrue(h.runtime.startMoveBatch(listOf(a.readRef())));h.drain();h.runtime.confirmMove()
            if(dispose)h.runtime.close() else h.runtime.setForeground(false)
            h.drain();assertEquals(0,h.repo.moveCalls);assertFalse(h.runtime.state.foreground)
            if(dispose)assertTrue(h.runtime.state.batchMoveResults.isEmpty()) else assertEquals(BatchMoveStatus.CANCELLED_BEFORE_START,h.runtime.state.batchMoveResults.single().status)
            h.runtime.close()
        }
    }

    @Test fun batchWorkerRevalidatesConsentAfterDestinationSelection() {
        val h=Harness();val a=media("a");val b=media("b");h.repo.media[a.itemId]=a;h.repo.media[b.itemId]=b;h.start()
        assertTrue(h.runtime.startMoveBatch(listOf(a.readRef(),b.readRef())));h.drain()
        h.repo.media[a.itemId]=a.copy(revision=2);h.repo.media.remove(b.itemId)
        h.runtime.confirmMove();h.drain()
        assertEquals(0,h.repo.moveCalls)
        assertEquals(listOf(BatchMoveStatus.STALE,BatchMoveStatus.UNAVAILABLE),h.runtime.state.batchMoveResults.map{it.status})
        h.runtime.close()
    }

    @Test fun pickerReturnRevalidatesOriginalPageForObservedAndCoalescedPauseAndBothResultOrderings() {
        for (pageTwo in listOf(false, true)) for (observedPause in listOf(false, true)) for (resultBeforeResume in listOf(false, true)) {
            val h = pickerPageHarness(pageTwo)
            val request = PickerRequest(h)
            val original = h.runtime.state.query
            val beforeQueries = h.repo.queries.size
            if (observedPause) h.runtime.setForeground(false)
            if (resultBeforeResume) request.receive()
            h.runtime.setForeground(true)
            if (!resultBeforeResume) {
                assertEquals(original, h.runtime.state.query)
                assertFalse(request.lease.refreshStarted)
                assertFalse(request.canConfirm())
                request.receive()
                h.runtime.setForeground(true) // The staged-result effect, even if already foreground.
            }
            assertEquals(original, h.runtime.state.query)
            assertTrue(h.runtime.state.loading)
            assertFalse(request.canConfirm())
            assertEquals(0, request.copies)
            h.runtime.setForeground(true) // Duplicate effects do not replace the bounded read.
            h.drain()
            assertEquals(beforeQueries + 1, h.repo.queries.size)
            assertEquals(original, h.repo.queries.last())
            assertTrue(request.canConfirm())
            assertTrue(batchSelectionIsCurrent(request.selection, h.runtime.state))
            assertEquals(0, request.copies)
            assertEquals(DirectoryConfirmationDecision.CONFIRMED, request.confirm())
            assertEquals(DirectoryConfirmationDecision.IGNORED, request.confirm())
            assertEquals(1, request.copies)
            h.runtime.close()
        }
    }

    @Test fun waitingResumeDoesNotSpendMatchingResultAllowanceEvenAcrossRepeatedPauses() {
        val h = pickerPageHarness()
        val request = PickerRequest(h)
        val query = request.selection.query
        val before = h.repo.queries.size
        repeat(3) {
            h.runtime.setForeground(false); h.runtime.setForeground(true); h.drain()
            assertEquals(query, h.runtime.state.query)
            assertFalse(request.lease.refreshStarted)
            assertEquals(before, h.repo.queries.size)
        }
        h.runtime.setForeground(false)
        request.receive() // Normal result while paused; no foreground or copy authority yet.
        assertFalse(request.canConfirm())
        assertEquals(before, h.repo.queries.size)
        h.runtime.setForeground(true); h.drain()
        assertTrue(request.canConfirm())
        assertEquals(before + 1, h.repo.queries.size)
        assertEquals(0, request.copies)
        request.cancel(); h.runtime.close()
    }

    @Test fun realPickerBridgeStagesPausedMultiItemResultAndRejectsReplayWithoutAnotherLaunch() {
        val h = pickerPageHarness()
        h.repo.media["third"] = media("third")
        h.repo.queryOverride = { query -> LibraryPage(
            if (query.cursor == null) listOf(media("first")) else listOf(media("second"), media("third")),
            1, if (query.cursor == null) LibraryCursor(1, query.queryKey(), 50) else null) }
        h.runtime.refresh(); h.drain(); h.runtime.nextPage(); h.drain()
        val request = PickerRequest(h)
        assertEquals(2, request.selection.references.size)
        val host = Any()
        var currentHost = host
        var currentRequest: PickerRequest? = request
        var launches = 0
        var deliveries = 0
        val bridge = FolderPickerAsyncBridge<Any, Unit>({}, { it() }, { it() }, { FolderPickerRequestCodes.FIRST })
        bridge.launch(host, current = { currentRequest === request && batchSelectionIsCurrent(request.selection, h.runtime.state) },
            launch = { launches++ }, failed = { request.cancel() }, result = { deliveries++; request.receive() },
            related = { currentHost === host && currentRequest === request })
        assertEquals(1, launches)
        h.runtime.setForeground(false)
        assertFalse(batchSelectionIsCurrent(request.selection, h.runtime.state))
        assertTrue(bridge.dispatch(host, FolderPickerRequestCodes.FIRST, Unit))
        assertEquals(1, deliveries)
        assertFalse(request.canConfirm())
        h.runtime.setForeground(true); h.drain()
        assertTrue(request.canConfirm())
        assertEquals(0, request.copies)
        currentHost = Any(); currentRequest = null
        assertTrue(bridge.dispatch(host, FolderPickerRequestCodes.FIRST, Unit))
        assertEquals(1, deliveries)
        assertEquals(1, launches)
        request.cancel(); h.runtime.close()
    }

    @Test fun pickerStaleCursorKeepsOriginalFirstPageFallbackAndRetiresConsent() {
        val h = pickerPageHarness()
        val request = PickerRequest(h)
        h.runtime.setForeground(false)
        h.repo.queryOverride = { query ->
            if (query.cursor != null) throw LibraryFailure(LibraryError.STALE_PAGE)
            LibraryPage(listOf(media("new-first")), 2, LibraryCursor(2, query.queryKey(), 50))
        }
        request.receive(); h.runtime.setForeground(true); h.drain()
        assertNull(h.runtime.state.query.cursor)
        assertEquals(listOf("new-first"), h.runtime.state.items.map { it.itemId })
        assertTrue(h.runtime.state.notice.orEmpty().contains("变化"))
        assertEquals(1, request.staleCalls)
        assertFalse(request.canConfirm())
        assertFalse(batchSelectionIsCurrent(request.selection, h.runtime.state))
        assertEquals(DirectoryConfirmationDecision.IGNORED, request.confirm())
        assertEquals(0, request.copies)
        h.runtime.setForeground(true); h.drain()
        assertNull(h.runtime.state.query.cursor)
        h.runtime.close()
    }

    @Test fun missingFolderReturnRetiresLeaseAndKeepsExistingRootFallback() {
        val h = pickerPageHarness()
        h.runtime.enterFolder("folder"); h.drain(); h.runtime.nextPage(); h.drain()
        val request = PickerRequest(h)
        h.runtime.setForeground(false)
        h.repo.folderRecords.remove("folder")
        request.receive(); h.runtime.setForeground(true); h.drain()
        assertNull(h.runtime.state.query.folderId)
        assertNull(h.runtime.state.query.cursor)
        assertEquals(listOf("first"), h.runtime.state.items.map { it.itemId })
        assertEquals(1, request.staleCalls)
        assertFalse(request.canConfirm())
        assertEquals(0, request.copies)
        h.runtime.close()
    }

    @Test fun pickerReturnRejectsChangedRevisionGenerationDeletionAndMissingSource() {
        for (change in 0..3) {
            val h = pickerPageHarness()
            val request = PickerRequest(h)
            h.runtime.setForeground(false)
            val item = request.selection.items.single()
            when (change) {
                0 -> h.repo.media[item.itemId] = item.copy(revision = 2)
                1 -> h.repo.media[item.itemId] = item.copy(representationGeneration = 2)
                2 -> h.repo.media[item.itemId] = item.copy(lifecycle = MediaLifecycle.DELETED)
                3 -> h.repo.media.remove(item.itemId)
            }
            request.receive(); h.runtime.setForeground(true); h.drain()
            assertEquals(1, request.staleCalls)
            assertFalse(request.canConfirm())
            assertFalse(batchSelectionIsCurrent(request.selection, h.runtime.state))
            assertEquals(0, request.copies)
            h.runtime.close()
        }
    }

    @Test fun explicitQueryIntentCannotRevivePickerAfterReturningToEqualQuery() {
        for (change in listOf<(AndroidLibraryRuntime) -> Unit>(
            { it.search("away") }, { it.sort(LibrarySort.SIZE) }, { it.reverseSort() },
            { it.previousPage() }, { it.enterFolder("folder") }, { it.folderPage(0) }, { it.refresh() },
        )) {
            val h = pickerPageHarness()
            val request = PickerRequest(h)
            val original = request.selection.query
            change(h.runtime)
            h.ui.runTimers(); h.drain()
            // Explicitly reconstruct the same values, including the same version-bound cursor.
            h.runtime.enterFolder(null); h.drain()
            h.runtime.search(original.text); h.ui.runTimers(); h.drain()
            h.runtime.sort(original.sort); h.drain()
            if (h.runtime.state.query.direction != original.direction) { h.runtime.reverseSort(); h.drain() }
            h.runtime.nextPage(); h.drain()
            assertEquals(original, h.runtime.state.query)
            assertTrue(batchSelectionIsCurrent(request.selection, h.runtime.state))
            assertTrue(request.lease.retired)
            assertEquals(1, request.staleCalls)
            assertFalse(request.lifecycle.receive())
            h.runtime.setForeground(true); h.drain()
            assertFalse(request.canConfirm())
            assertEquals(0, request.copies)
            h.runtime.close()
        }
    }

    @Test fun selectingThenDismissingOrEnteringMultiSelectionCannotRevivePickerConsent() {
        for (select in listOf(true, false)) {
            val h = pickerPageHarness()
            val request = PickerRequest(h)
            if (select) h.runtime.select(request.selection.items.single())
            h.runtime.dismissSelection() // Also the production entry into multi-selection.
            assertTrue(batchSelectionIsCurrent(request.selection, h.runtime.state))
            assertTrue(request.lease.retired)
            assertFalse(request.lifecycle.receive())
            assertFalse(request.canConfirm())
            assertEquals(1, request.staleCalls)
            h.runtime.close()
        }
    }

    @Test fun queuedReturnCannotPublishAfterUserNavigationOrCancel() {
        for (navigate in listOf(false, true)) {
            val h = pickerPageHarness()
            val request = PickerRequest(h)
            h.runtime.setForeground(false); request.receive(); h.runtime.setForeground(true)
            h.work.runNext() // Completion is queued on UI, then a newer intent wins.
            if (navigate) { h.runtime.search("new-query"); h.ui.runTimers() } else request.cancel()
            h.ui.runPosts(); h.drain()
            assertFalse(request.lease.revalidated)
            assertFalse(request.canConfirm())
            if (navigate) assertEquals("new-query", h.runtime.state.query.text)
            assertEquals(0, request.copies)
            h.runtime.close()
        }
    }

    @Test fun returnReadFailureAndRejectedLaneRetireConsentWithNoAutomaticCopy() {
        for (rejected in listOf(false, true)) {
            val h = pickerPageHarness()
            val request = PickerRequest(h)
            h.runtime.setForeground(false)
            if (rejected) h.work.reject = true else h.repo.queryOverride = { throw LibraryFailure(LibraryError.STORAGE_FAILURE) }
            request.receive(); h.runtime.setForeground(true); h.drain()
            assertFalse(h.runtime.state.ready)
            assertFalse(request.canConfirm())
            assertEquals(1, request.staleCalls)
            assertEquals(0, request.copies)
            h.runtime.close()
        }
    }

    @Test fun cancelRouteExitBackgroundHostLossAndDisposalInvalidateTheExactReturn() {
        for (reason in 0..4) {
            val h = pickerPageHarness()
            val request = PickerRequest(h)
            request.receive(); h.runtime.setForeground(true); h.drain()
            assertTrue(request.canConfirm())
            when (reason) {
                0 -> request.cancel()
                1 -> assertTrue(dispatchLibraryBatchRouteExit(EsvDestination.LIBRARY, EsvDestination.EDITOR, request::cancel))
                2 -> { // The existing background rule cancels RECEIVED but not WAITING.
                    assertFalse(request.lifecycle.expectedPickerPause()); request.cancel(); h.runtime.setForeground(false)
                }
                3 -> { request.related = false; h.runtime.setForeground(true) }
                4 -> h.runtime.close()
            }
            assertTrue(request.lease.retired)
            assertFalse(request.canConfirm())
            assertEquals(0, request.copies)
            h.runtime.close()
        }
    }

    @Test fun oldRequestAndOldConfirmationCannotInvalidateNewPickerReturn() {
        val h = pickerPageHarness()
        val old = PickerRequest(h)
        old.receive(); h.runtime.setForeground(true)
        h.work.runNext()
        val oldCompletion = h.ui.posts.removeFirst()
        old.cancel()
        h.runtime.refresh(); h.drain(); h.runtime.nextPage(); h.drain()
        val current = PickerRequest(h)
        old.lease.close()
        assertFalse(old.lifecycle.receive())
        assertFalse(current.lease.retired)
        current.receive(); h.runtime.setForeground(true)
        oldCompletion() // An old running read completes while the newer request's revalidation is queued.
        assertTrue(h.runtime.state.loading)
        assertFalse(current.lease.retired)
        assertFalse(current.lease.revalidated)
        h.drain()
        assertTrue(current.canConfirm())
        assertEquals(DirectoryConfirmationDecision.IGNORED, current.confirm(old.confirmation))
        assertTrue(current.canConfirm())
        assertEquals(0, old.copies)
        assertEquals(0, current.copies)
        current.cancel(); h.runtime.close()
    }

    @Test fun runtimeReplacementAndUnrelatedResumeCannotRestoreAnOldPageLease() {
        val old = pickerPageHarness()
        val request = PickerRequest(old)
        old.runtime.close()
        val recreated = pickerPageHarness(false)
        assertNull(recreated.runtime.state.query.cursor)
        request.lease.close()
        assertFalse(request.canConfirm())
        assertFalse(request.lifecycle.receive())
        recreated.runtime.nextPage(); recreated.drain()
        assertNotNull(recreated.runtime.state.query.cursor)
        recreated.runtime.setForeground(false); recreated.runtime.setForeground(true); recreated.drain()
        assertNotNull(recreated.runtime.state.query.cursor) // Reading position survives; the retired lease does not.
        assertFalse(request.lease.isCurrent())
        assertFalse(request.canConfirm())
        assertFalse(request.lifecycle.receive())
        assertEquals(0, request.copies)
        recreated.runtime.close()
    }

    @Test fun pendingDeletePollingResumesAfterRevalidationAndAfterLeaseRetirement() {
        for (finish in 0..2) {
            val h = pickerPageHarness()
            h.repo.media["pending"] = media("pending").copy(lifecycle = MediaLifecycle.DELETE_PENDING)
            h.runtime.refresh(); h.drain(); h.runtime.nextPage(); h.drain()
            assertEquals(1, h.runtime.state.pendingDeleteCount)
            assertEquals(1, h.ui.timers.size)
            val request = PickerRequest(h)
            assertTrue(h.ui.timers.isEmpty())
            if (finish != 0) {
                request.receive(); h.runtime.setForeground(true)
                assertTrue(h.ui.timers.isEmpty())
                h.drain()
                assertTrue(request.canConfirm())
                assertEquals(1, h.ui.timers.size) // Confirmation waiting does not indefinitely suspend cleanup.
            }
            if (finish == 2) assertEquals(DirectoryConfirmationDecision.CONFIRMED, request.confirm()) else request.cancel()
            assertEquals(1, h.ui.timers.size)
            h.repo.media["pending"] = media("pending").copy(lifecycle = MediaLifecycle.DELETED)
            h.ui.runTimers(); h.drain()
            assertEquals(0, h.runtime.state.pendingDeleteCount)
            assertTrue(h.ui.timers.isEmpty())
            h.runtime.close()
        }
    }

    @Test fun confirmedReturnPollStillRejectsLaterSourceChangesBeforeExplicitCopyConfirmation() {
        val h = pickerPageHarness()
        h.repo.media["pending"] = media("pending").copy(lifecycle = MediaLifecycle.DELETE_PENDING)
        h.runtime.refresh(); h.drain(); h.runtime.nextPage(); h.drain()
        val request = PickerRequest(h)
        request.receive(); h.runtime.setForeground(true); h.drain()
        assertTrue(request.canConfirm())
        val item = request.selection.items.single()
        h.repo.media[item.itemId] = item.copy(revision = 2)
        h.ui.runTimers(); h.drain()
        assertFalse(request.canConfirm())
        assertEquals(1, request.staleCalls)
        assertEquals(0, request.copies)
        h.runtime.close()
    }

    /** Real query/cursor policy with repository scheduling controlled by the existing runtime harness. */
    private fun readingPageHarness(): Harness = Harness().also { h ->
        h.repo.folderRecords["folder"] = LibraryFolder("folder", 1, null, "Folder")
        repeat(55) { index ->
            val id = "child-$index"
            h.repo.folderRecords[id] = LibraryFolder(id, 1, null, "Child $index")
        }
        for (folderId in listOf(null, "folder")) repeat(125) { index ->
            val suffix = index.toString().padStart(3, '0')
            val id = "${folderId ?: "root"}-$suffix"
            h.repo.media[id] = media(id).copy(folderId = folderId, displayName = "Entry $suffix",
                createdAtMillis = 1_000L + index, sizeBytes = 1_024L + index)
        }
        h.repo.queryOverride = { query -> LibraryQueryPolicy.page(h.repo.media.values, query, h.repo.catalogVersion) }
        h.start(); h.runtime.nextPage(); h.drain()
    }

    private fun pickerPageHarness(pageTwo: Boolean = true): Harness = Harness().also { h ->
        h.repo.media["first"] = media("first"); h.repo.media["second"] = media("second")
        h.repo.folderRecords["folder"] = LibraryFolder("folder", 1, null, "folder")
        h.repo.queryOverride = { query ->
            val id = if (query.cursor == null) "first" else "second"
            LibraryPage(listOfNotNull(h.repo.media[id]).filter { it.lifecycle == MediaLifecycle.READY }, 1,
                if (query.cursor == null) LibraryCursor(1, query.queryKey(), 50) else null)
        }
        h.start()
        if (pageTwo) { h.runtime.nextPage(); h.drain() }
    }

    /** Real runtime + production lease/lifecycle/confirmation gates; only platform delivery is driven here. */
    private class PickerRequest(private val h: Harness) {
        val selection = requireNotNull(LibraryBatchSelection.admit(h.runtime.state.items.map { it.readRef() }, h.runtime.state))
        val lifecycle = DirectoryPickerLifecycle()
        val confirmation = DirectoryBatchConfirmation()
        var related = true
        var staleCalls = 0
        var copies = 0
        val lease = requireNotNull(h.runtime.beginPickerReturn(selection, lifecycle, { related }) {
            staleCalls++; lifecycle.cancel()
        })
        fun receive() { assertTrue(lifecycle.receive()) }
        fun canConfirm() = lifecycle.canConfirm(true) && lease.isCurrent() && batchSelectionIsCurrent(selection, h.runtime.state)
        fun cancel() { lease.close(); lifecycle.cancel() }
        fun confirm(expected: DirectoryBatchConfirmation = confirmation): DirectoryConfirmationDecision =
            consumeDirectoryBatchConfirmation(expected, confirmation, lifecycle) {
                lease.isCurrent() && batchSelectionIsCurrent(selection, h.runtime.state)
            }.also { if (it == DirectoryConfirmationDecision.CONFIRMED) { lease.close(); copies++ } }
    }

    private class Harness {
        val ui = FakeUi()
        val work = FakeWork()
        val repo = FakeRepository { assertTrue("Repository access must run on worker lane", work.running) }
        val runtime = AndroidLibraryRuntime(repo, ui, work)
        fun start() { runtime.setForeground(true); drain() }
        fun drain() {
            var passes = 0
            while (work.queued.isNotEmpty() || ui.posts.isNotEmpty()) {
                check(++passes <= 50) { "Unbounded work scheduling" }
                if (work.queued.isNotEmpty()) work.runNext()
                ui.runPosts()
            }
        }
    }

    private class FakeUi : LibraryUiDispatcher {
        val posts = ArrayDeque<() -> Unit>()
        val timers = linkedSetOf<Runnable>()
        override fun requireCurrentThread() = Unit
        override fun post(block: () -> Unit) { posts.addLast(block) }
        override fun later(task: Runnable, delayMillis: Long) { assertTrue(delayMillis > 0); timers.add(task) }
        override fun remove(task: Runnable) { timers.remove(task) }
        fun runPosts() { while (posts.isNotEmpty()) posts.removeFirst().invoke() }
        fun runTimers() { val current = timers.toList(); timers.clear(); current.forEach(Runnable::run) }
    }

    private class FakeWork : LibraryWorkLane {
        val queued = ArrayDeque<Runnable>()
        var reject = false
        var running = false
        var closes = 0
        override fun submit(task: Runnable): Boolean {
            if (reject || closes > 0) return false
            queued.addLast(task); return true
        }
        override fun remove(task: Runnable) { queued.remove(task) }
        override fun close() { closes++ }
        fun runNext() { running = true; try { queued.removeFirst().run() } finally { running = false } }
    }

    private class FakeRepository(private val requireWorker: () -> Unit) : LibraryRepository {
        val media = linkedMapOf<String, MediaItem>()
        val folderRecords = linkedMapOf<String, LibraryFolder>()
        val queries = mutableListOf<LibraryQuery>()
        var catalogVersion = 1L
        var pendingDeleteCalls = 0
        var calls = 0; var deleteCalls = 0; var folderDeleteCalls = 0
        var createCalls = 0; var renameCalls = 0; var moveCalls = 0
        var queryOverride: ((LibraryQuery) -> LibraryPage)? = null
        private fun call() { requireWorker(); calls++ }
        override fun query(query: LibraryQuery): LibraryPage {
            call(); queries += query
            return queryOverride?.invoke(query) ?: LibraryPage(media.values.filter {
                it.lifecycle == MediaLifecycle.READY && it.folderId == query.folderId && LibraryNames.key(it.displayName).contains(LibraryNames.searchKey(query.text))
            }.take(query.pageSize), 1, null)
        }
        override fun item(itemId: String): MediaItem? { call(); return media[itemId] }
        override fun folder(folderId: String): LibraryFolder? { call(); return folderRecords[folderId] }
        override fun folders(parentId: String?): List<LibraryFolder> { call(); return folderRecords.values.filter { it.parentId == parentId } }
        override fun createFolder(displayName: String, parentId: String?, operationId: String): LibraryFolder {
            call(); createCalls++
            return LibraryFolder("new-folder", 1, parentId, LibraryNames.validate(displayName)).also { folderRecords[it.folderId] = it }
        }
        override fun renameFolder(folderId: String, expectedRevision: Long, displayName: String, operationId: String): LibraryFolder {
            call(); val folder = folderRecords.getValue(folderId)
            if (folder.revision != expectedRevision) throw LibraryFailure(LibraryError.STALE_REVISION)
            return folder.copy(displayName = displayName, revision = expectedRevision + 1).also { folderRecords[folderId] = it }
        }
        override fun moveFolder(folderId: String, expectedRevision: Long, parentId: String?, operationId: String): LibraryFolder {
            call(); moveCalls++; val folder = folderRecords.getValue(folderId)
            if (folder.revision != expectedRevision) throw LibraryFailure(LibraryError.STALE_REVISION)
            return folder.copy(parentId = parentId, revision = expectedRevision + 1).also { folderRecords[folderId] = it }
        }
        override fun deleteEmptyFolder(folderId: String, expectedRevision: Long, operationId: String) {
            call(); folderDeleteCalls++; val folder = folderRecords.getValue(folderId)
            if (folder.revision != expectedRevision) throw LibraryFailure(LibraryError.STALE_REVISION)
            if (folder.itemCount > 0 || folder.childFolderCount > 0) throw LibraryFailure(LibraryError.FOLDER_NOT_EMPTY)
            folderRecords.remove(folderId)
        }
        override fun renameItem(itemId: String, expectedRevision: Long, displayName: String, operationId: String): MediaItem {
            call(); renameCalls++; val item = media.getValue(itemId)
            if (item.revision != expectedRevision) throw LibraryFailure(LibraryError.STALE_REVISION)
            return item.copy(displayName = displayName, revision = expectedRevision + 1).also { media[itemId] = it }
        }
        override fun moveItem(itemId: String, expectedRevision: Long, folderId: String?, operationId: String): MediaItem {
            call(); moveCalls++; val item = media.getValue(itemId)
            if (item.revision != expectedRevision) throw LibraryFailure(LibraryError.STALE_REVISION)
            return item.copy(folderId = folderId, revision = expectedRevision + 1).also { media[itemId] = it }
        }
        override fun requestDelete(request: ConfirmedDeleteRequest): DeleteOutcome {
            call(); request.requireConfirmation(); deleteCalls++
            val item = media.getValue(request.ref.itemId)
            if (item.readRef() != request.ref) throw LibraryFailure(LibraryError.STALE_REVISION)
            media[item.itemId] = item.copy(revision = item.revision + 1, lifecycle = MediaLifecycle.DELETE_PENDING)
            return DeleteOutcome.DELETE_PENDING
        }
        override fun processPendingDeletes(limit: Int): PendingDeleteReport {
            call(); pendingDeleteCalls++; assertTrue(limit in 1..LibraryLimits.MAX_DELETE_BATCH)
            return PendingDeleteReport(0, media.values.count { it.lifecycle == MediaLifecycle.DELETE_PENDING }, 0)
        }
    }

    companion object {
        private fun media(id: String) = MediaItem(id, 1, 1, null, id, 1_000, MediaDateKind.SAVED, 1_024, 30_000, 640, 480)
    }
}
