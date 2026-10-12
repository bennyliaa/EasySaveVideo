package com.easysavevideo.library

import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class LibraryBatchMoveTest {
    private class Fake {
        val items = linkedMapOf<String, MediaItem>()
        val writes = mutableListOf<String>()
        val operations = mutableListOf<String>()
        var lookup: (() -> Unit)? = null
        var write: ((MediaItem) -> Unit)? = null
        val repository = Proxy.newProxyInstance(LibraryRepository::class.java.classLoader,
            arrayOf(LibraryRepository::class.java)) { _, method, args ->
            when (method.name) {
                "item" -> { lookup?.invoke(); items[args!![0]] }
                "moveItem" -> {
                    val id = args!![0] as String
                    val item = items.getValue(id)
                    if (item.revision != args[1]) throw LibraryFailure(LibraryError.STALE_REVISION)
                    writes += id; operations += args[3] as String
                    val moved = item.copy(folderId = args[2] as String?, revision = item.revision + 1)
                    items[id] = moved
                    write?.invoke(moved)
                    moved
                }
                else -> error("Unexpected port")
            }
        } as LibraryRepository
    }

    @Test fun movesOnlySelectedItemsWithDistinctOperationIds() {
        val f = Fake(); val a = media("a"); val b = media("b"); val untouched = media("c")
        listOf(a,b,untouched).forEach { f.items[it.itemId] = it }
        val results = LibraryBatchMove(listOf(a.readRef(),b.readRef()), "target").execute(f.repository) { false }
        assertEquals(listOf(BatchMoveStatus.MOVED,BatchMoveStatus.MOVED), results.map { it.status })
        assertEquals(untouched, f.items["c"]); assertEquals(2,f.operations.distinct().size)
    }

    @Test fun rejectsChangedRevisionGenerationAndLifecycleWithoutWrites() {
        val f = Fake(); val original = listOf(media("a"),media("b"),media("c"),media("d"))
        f.items["a"] = original[0].copy(revision=2)
        f.items["b"] = original[1].copy(representationGeneration=2)
        f.items["c"] = original[2].copy(lifecycle=MediaLifecycle.DELETE_PENDING)
        val results = LibraryBatchMove(original.map { it.readRef() }, "target").execute(f.repository) { false }
        assertEquals(listOf(BatchMoveStatus.STALE,BatchMoveStatus.STALE,BatchMoveStatus.UNAVAILABLE,BatchMoveStatus.UNAVAILABLE),results.map { it.status })
        assertTrue(f.writes.isEmpty())
    }

    @Test fun alreadyAtTargetIsNotReportedAsMoved() {
        val f=Fake();val a=media("a");f.items["a"]=a
        assertEquals(BatchMoveStatus.ALREADY_AT_TARGET,LibraryBatchMove(listOf(a.readRef()),null).execute(f.repository){false}.single().status)
        assertTrue(f.writes.isEmpty())
    }

    @Test fun cancellationBeforeStartDoesNotAccessRepository() {
        val f=Fake();f.lookup={error("Unexpected read")}
        assertEquals(BatchMoveStatus.CANCELLED_BEFORE_START,LibraryBatchMove(listOf(media("a").readRef()),"target").execute(f.repository){true}.single().status)
    }

    @Test fun cancellationDuringLookupFencesMutation() {
        val f=Fake();val a=media("a");f.items["a"]=a;var cancelled=false;f.lookup={cancelled=true}
        assertEquals(BatchMoveStatus.CANCELLED_BEFORE_START,LibraryBatchMove(listOf(a.readRef()),"target").execute(f.repository){cancelled}.single().status)
        assertTrue(f.writes.isEmpty())
    }

    @Test fun cancellationDuringWritePreservesCompletedItemAndStopsNext() {
        val f=Fake();val a=media("a");val b=media("b");f.items["a"]=a;f.items["b"]=b
        var cancelled=false;f.write={cancelled=true}
        val result=LibraryBatchMove(listOf(a.readRef(),b.readRef()),"target").execute(f.repository){cancelled}
        assertEquals(listOf(BatchMoveStatus.MOVED,BatchMoveStatus.CANCELLED_BEFORE_START),result.map{it.status})
        assertEquals("target",f.items["a"]!!.folderId);assertEquals(b,f.items["b"])
    }

    @Test fun exceptionAfterCommitRemainsUnconfirmedAndNextItemContinues() {
        val f=Fake();val a=media("a");val b=media("b");f.items["a"]=a;f.items["b"]=b
        f.write={if(it.itemId=="a")throw IllegalStateException("private metadata")}
        val result=LibraryBatchMove(listOf(a.readRef(),b.readRef()),"target").execute(f.repository){false}
        assertEquals(listOf(BatchMoveStatus.FAILED_UNCONFIRMED,BatchMoveStatus.MOVED),result.map{it.status})
        assertEquals("target",f.items["a"]!!.folderId)
        assertFalse(result.toString().contains("private metadata"))
    }

    @Test fun consumesSnapshotOnceAndDefensivelyCopiesInput() {
        val f=Fake();val a=media("a");f.items["a"]=a
        val input=mutableListOf(a.readRef());val batch=LibraryBatchMove(input,"target");input.clear()
        batch.execute(f.repository){false}
        try { batch.execute(f.repository){false};fail("Duplicate execution") } catch(_:IllegalStateException) { }
        assertEquals(1,f.writes.size)
    }

    @Test fun rejectsEmptyDuplicateAndOversizedConsent() {
        val a=media("a").readRef()
        for(refs in listOf(emptyList(),listOf(a,a),(0..100).map{media("i$it").readRef()})) {
            try { LibraryBatchMove(refs,"target");fail("Invalid batch") } catch(_:IllegalArgumentException) { }
        }
    }

    companion object {
        private fun media(id:String)=MediaItem(id,1,1,null,id,1,MediaDateKind.SAVED,10,null,null,null)
    }
}
