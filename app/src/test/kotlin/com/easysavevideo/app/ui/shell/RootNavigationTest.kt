package com.easysavevideo.app.ui.shell

import com.easysavevideo.app.ui.shell.navigation.RootNavigation
import org.junit.Assert.*
import org.junit.Test

/** A bounded policy/port model only. Real NavController original/fixed evidence is separate. */
class RootNavigationTest {
    private data class Entry(val route: String, val marker: Int = 0)
    private class Model(vararg entries: Entry) {
        val stack = entries.toMutableList()
        val saved = mutableMapOf<String,List<Entry>>()
        val preserved = mutableListOf<Boolean>()
        val popped = mutableListOf<String>()
        var popBlocked = false
        fun go(target: String) = RootNavigation.navigate(target, { stack.lastOrNull()?.route }, {
            if(popBlocked) false else {
                popped += stack.removeLast().route
                stack.isNotEmpty()
            }
        }) { preserve ->
            preserved += preserve
            if(preserve) {
                val anchor = stack.indexOfFirst { it.route=="discover" }
                val removed = if(anchor>=0) stack.drop(anchor+1) else emptyList()
                if(removed.isNotEmpty()) {
                    check(removed.all { it.route in RootNavigation.roots }) { "Auxiliary state saved" }
                    saved[removed.first().route]=removed
                    while(stack.size>anchor+1) stack.removeLast()
                }
            } else stack.clear()
            if(stack.lastOrNull()?.route != target) stack.addAll(saved.remove(target) ?: listOf(Entry(target)))
        }
        fun current()=stack.lastOrNull()?.route
    }

    @Test fun allAuxiliaryPagesReturnToCurrentRootByPopOnly() {
        for(aux in listOf("settings","sources","browser","editor")) for(root in RootNavigation.roots) {
            val m=Model(Entry(root,17),Entry(aux));assertTrue(m.go(root))
            assertEquals(listOf(Entry(root,17)),m.stack);assertEquals(listOf(aux),m.popped);assertTrue(m.preserved.isEmpty())
        }
    }

    @Test fun auxiliaryToDifferentRootsSavesOnlyLegitimateRootState() {
        for(target in listOf("discover","record","library")) {
            val m=Model(Entry("discover",11),Entry("tasks",17),Entry("settings"),Entry("sources"))
            assertTrue(m.go(target));assertEquals(target,m.current());assertEquals(listOf("sources","settings"),m.popped)
            assertEquals(listOf(true),m.preserved)
            assertTrue(m.go("tasks"));assertEquals(17,m.stack.last().marker)
            assertEquals(11,m.stack.first().marker)
        }
    }

    @Test fun oldSavedAuxiliaryTailIsDiscardedAfterRootRestore() {
        val m=Model(Entry("discover",11),Entry("library",23),Entry("editor"))
        m.saved["tasks"]=listOf(Entry("tasks",17),Entry("settings"),Entry("sources"))
        assertTrue(m.go("tasks"));assertEquals(17,m.stack.last().marker)
        assertEquals(listOf("editor","sources","settings"),m.popped)
        assertTrue(m.go("library"));assertEquals(23,m.stack.last().marker)
    }

    @Test fun sameRootDoesNotPopOrNavigate() {
        val m=Model(Entry("discover",17));repeat(2){assertTrue(m.go("discover"))}
        assertEquals(17,m.stack.single().marker);assertTrue(m.popped.isEmpty());assertTrue(m.preserved.isEmpty())
    }

    @Test fun recreatedAuxiliaryOnlyGraphDoesNotSaveAuxiliaryAsRoot() {
        val m=Model(Entry("settings"));m.saved["library"]=listOf(Entry("library",17))
        assertTrue(m.go("library"));assertEquals(listOf(false),m.preserved);assertEquals(17,m.stack.last().marker)
    }

    @Test fun ordinaryRootSwitchStillSavesAndRestoresRootMarker() {
        val m=Model(Entry("discover",11),Entry("tasks",17))
        assertTrue(m.go("library"));m.stack[m.stack.lastIndex]=Entry("library",23)
        assertTrue(m.go("tasks"));assertEquals(17,m.stack.last().marker)
        assertTrue(m.go("library"));assertEquals(23,m.stack.last().marker)
    }

    @Test fun blockedPopDoesNotSaveAuxiliaryOrClaimSuccess() {
        val m=Model(Entry("discover",17),Entry("settings"));m.popBlocked=true
        assertFalse(m.go("discover"));assertEquals("settings",m.current());assertTrue(m.preserved.isEmpty())
    }

    @Test fun oversizedAuxiliaryChainIsBoundedAndDoesNotNavigateOrSave() {
        val m=Model(Entry("discover",17),*(0..32).map{Entry("settings")}.toTypedArray())
        assertFalse(m.go("discover"));assertEquals(32,m.popped.size);assertTrue(m.preserved.isEmpty())
    }

    @Test fun rejectsNonRootTargetWithoutChangingState() {
        val m=Model(Entry("discover",17))
        try {m.go("settings");fail("Invalid target")}catch(_:IllegalArgumentException){}
        assertEquals(listOf(Entry("discover",17)),m.stack)
    }
}
