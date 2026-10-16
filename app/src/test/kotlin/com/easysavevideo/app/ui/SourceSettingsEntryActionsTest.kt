package com.easysavevideo.app.ui

import org.junit.Assert.*
import org.junit.Test

/** Executes the production presentation gate with synthetic Activity state, not Compose/device QA. */
class SourceSettingsEntryActionsTest {
    private class Presentation {
        var generation = 0L
        var foreground = true
        var ordinary = true
        var route = Any()
        var activityAlive = true
        var covered = false
        fun capture(): CapturedPrivateActions {
            val capturedGeneration = generation
            val capturedRoute = route
            return CapturedPrivateActions(false, { error("ordinary settings cannot use private input") }) {
                capturedGeneration == generation && capturedRoute === route && foreground && ordinary && activityAlive && !covered
            }
        }
    }

    @Test fun backgroundThenSameRouteResumeNeverRevivesOldCallbacks() {
        val p = Presentation(); val old = p.capture(); var submissions = 0
        val submit = { old.run { submissions++ } }
        p.generation++; p.foreground = false
        submit(); p.foreground = true; submit()
        assertFalse(old.isCurrent()); assertEquals(0, submissions)
        p.capture().run { submissions++ }; assertEquals(1, submissions)
    }
    @Test fun routeReplacementAndSameRouteTextReturnRequireFreshCapture() {
        val p = Presentation(); val old = p.capture(); var submissions = 0
        p.route = Any(); old.run { submissions++ }
        p.route = Any(); old.run { submissions++ }
        assertEquals(0, submissions); assertFalse(old.isCurrent()); assertTrue(p.capture().isCurrent())
    }
    @Test fun currentEditorAllowsRepeatedInputAndCheckboxChangesWithoutConsumingAdmission() {
        val p = Presentation(); val actions = p.capture(); var edits = 0
        repeat(20) { actions.run { edits++ } }
        assertEquals(20, edits); assertTrue(actions.isCurrent())
        p.covered = true; actions.run { edits++ }; assertEquals(20, edits)
    }
    @Test fun disposedEditorCannotReuseLiveRouteOrAReplacementEditor() {
        val p = Presentation(); val old = p.capture(); var submissions = 0
        old.close(); old.close(); old.run { submissions += 100 }
        p.capture().run { submissions++ }
        assertEquals(1, submissions); assertFalse(old.isCurrent())
    }
    @Test fun queuedSiblingStartsAndOldDismissalsRespectTheCurrentModal() {
        val actions = Presentation().capture()
        var commonsPrompt: Any? = null
        var sourcePrompt: Any? = null
        var sourceDialogOpen = false
        val startCommons = {
            actions.run { if (commonsPrompt == null && !sourceDialogOpen) commonsPrompt = Any() }
        }
        val startSource = {
            actions.run {
                if (commonsPrompt == null && !sourceDialogOpen && sourcePrompt == null) {
                    sourcePrompt = Any(); sourceDialogOpen = true
                }
            }
        }
        fun dismissSource(prompt: Any) {
            if (sourcePrompt === prompt) { sourcePrompt = null; sourceDialogOpen = false }
        }
        // Both callbacks were captured before either modal appeared; admission reads current state.
        startSource(); val first = sourcePrompt!!
        startCommons(); assertNull(commonsPrompt); assertTrue(sourceDialogOpen)
        dismissSource(first); startCommons(); assertNotNull(commonsPrompt)
        startSource(); assertNull(sourcePrompt)
        commonsPrompt = null; startSource(); val replacement = sourcePrompt!!
        dismissSource(first)
        assertSame(replacement, sourcePrompt); assertTrue(sourceDialogOpen)
        startCommons(); assertNull(commonsPrompt)
        dismissSource(replacement); startCommons(); assertNotNull(commonsPrompt)
    }
    @Test fun privateModeOrDestroyedActivityRejectsBeforeAnyBusinessCallback() {
        val p = Presentation(); val actions = p.capture(); var submissions = 0
        p.ordinary = false; actions.run { submissions++ }
        p.ordinary = true; p.activityAlive = false; actions.run { submissions++ }
        assertEquals(0, submissions)
    }
}
