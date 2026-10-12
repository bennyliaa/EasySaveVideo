package com.easysavevideo.app.library

import com.easysavevideo.library.*
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

/** Repository/scheduling fakes only: no Android filesystem, player, device, or real media. */
class LibraryRuntimeTest {
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
            call(); assertTrue(limit in 1..LibraryLimits.MAX_DELETE_BATCH)
            return PendingDeleteReport(0, media.values.count { it.lifecycle == MediaLifecycle.DELETE_PENDING }, 0)
        }
    }

    companion object {
        private fun media(id: String) = MediaItem(id, 1, 1, null, id, 1_000, MediaDateKind.SAVED, 1_024, 30_000, 640, 480)
    }
}
