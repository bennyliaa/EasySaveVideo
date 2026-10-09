package com.easysavevideo.library

import org.junit.Assert.*
import org.junit.Test

class LibraryFolderPolicyTest {
    @Test fun rootIsNullAndOnlyRealFoldersCanMove() {
        fails(LibraryError.NOT_FOUND) { LibraryFolderPolicy.requireMoveAllowed("root", null, emptyList()) }
        fails(LibraryError.INVALID_ID) { LibraryFolderPolicy.requireMoveAllowed("", null, emptyList()) }
        fails(LibraryError.NOT_FOUND) { LibraryFolderPolicy.requireMoveAllowed("a", "missing", listOf(folder("a"))) }
    }

    @Test fun cannotMoveFolderIntoItselfOrAnyDescendant() {
        val folders = listOf(folder("a"), folder("b", "a"), folder("c", "b"))
        listOf("a", "b", "c").forEach { parent ->
            fails(LibraryError.FOLDER_CYCLE) { LibraryFolderPolicy.requireMoveAllowed("a", parent, folders) }
        }
    }

    @Test fun permitsMovingSubtreeToRootAndAcrossUnrelatedBranches() {
        val folders = listOf(folder("a"), folder("b", "a"), folder("c"))
        LibraryFolderPolicy.requireMoveAllowed("b", null, folders)
        LibraryFolderPolicy.requireMoveAllowed("a", "c", folders)
    }

    @Test fun createHonorsDepthLimitExactly() {
        val folders = chain(16)
        LibraryFolderPolicy.requireCreateAllowed("folder15", "New", folders)
        fails(LibraryError.DEPTH_LIMIT) { LibraryFolderPolicy.requireCreateAllowed("folder16", "New", folders) }
    }

    @Test fun moveCountsWholeSubtreeHeight() {
        val folders = chain(15) + folder("moving") + folder("child", "moving")
        LibraryFolderPolicy.requireMoveAllowed("moving", "folder14", folders)
        fails(LibraryError.DEPTH_LIMIT) { LibraryFolderPolicy.requireMoveAllowed("moving", "folder15", folders) }
    }

    @Test fun oversizedTreeIsHandledWithoutRecursiveStackGrowth() {
        fails(LibraryError.DEPTH_LIMIT) { LibraryFolderPolicy.requireMoveAllowed("folder1", null, chain(1_000)) }
    }

    @Test fun createHonorsFolderCountLimit() {
        val folders = (1..1_000).map { folder("folder$it") }
        fails(LibraryError.FOLDER_LIMIT) { LibraryFolderPolicy.requireCreateAllowed(null, "New", folders) }
        LibraryFolderPolicy.requireCreateAllowed(null, "New", folders.take(999))
    }

    @Test fun namesAreUniqueAmongSiblingsByCanonicalKey() {
        val folders = listOf(folder("a", name = "Café"), folder("b"))
        fails(LibraryError.ALREADY_EXISTS) { LibraryFolderPolicy.requireCreateAllowed(null, "CAFE\u0301", folders) }
        LibraryFolderPolicy.requireCreateAllowed("b", "CAFE\u0301", folders)
        LibraryFolderPolicy.requireUniqueName(null, "CAFÉ", folders, "a")
    }

    @Test fun moveAlsoChecksDestinationSiblingNames() {
        val folders = listOf(folder("a", name = "Trip"), folder("b"), folder("c", "b", "trip"))
        fails(LibraryError.ALREADY_EXISTS) { LibraryFolderPolicy.requireMoveAllowed("c", null, folders) }
    }

    @Test fun corruptedCyclesAndMissingAncestorsFailClosed() {
        val corrupt = listOf(folder("a", "b"), folder("b", "a"), folder("moving"))
        fails(LibraryError.RECOVERY_REQUIRED) { LibraryFolderPolicy.requireMoveAllowed("moving", "a", corrupt) }
        fails(LibraryError.RECOVERY_REQUIRED) { LibraryFolderPolicy.requireCreateAllowed("a", "New", corrupt) }
        fails(LibraryError.RECOVERY_REQUIRED) {
            LibraryFolderPolicy.requireMoveAllowed("moving", "a", listOf(folder("a", "missing"), folder("moving")))
        }
    }

    @Test fun duplicateIdsInSnapshotAreRejected() {
        fails(LibraryError.RECOVERY_REQUIRED) { LibraryFolderPolicy.requireMoveAllowed("a", null, listOf(folder("a"), folder("a"))) }
    }

    private fun chain(count: Int) = (1..count).map { folder("folder$it", if (it == 1) null else "folder${it - 1}") }
    private fun folder(id: String, parentId: String? = null, name: String = id) = LibraryFolder(id, 1, parentId, name)
    private fun fails(error: LibraryError, action: () -> Unit) {
        assertEquals(error, assertThrows(LibraryFailure::class.java, action).code)
    }
}
