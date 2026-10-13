package com.easysavevideo.app.library.destination
import com.easysavevideo.app.library.export.DirectoryBatchConfirmation
import com.easysavevideo.app.library.export.DirectoryConfirmationDecision
import com.easysavevideo.app.library.export.consumeDirectoryBatchConfirmation
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
/** Model of actual callback order, not an Android/OEM device test. */
class FolderPickerResultLifecycleTest {
    private class Flow {
        val owner=Any();var foreground=true;var exactSource=true;var cancelled=false
        var permission=true;var staged=false;var copies=0;var errors=0;var launches=0
        val lifecycle=DirectoryPickerLifecycle()
        val confirmation=DirectoryBatchConfirmation()
        val worker=ArrayDeque<() -> Unit>();val ui=ArrayDeque<() -> Unit>()
        val bridge=FolderPickerAsyncBridge<Any,Boolean>({}, {worker.add(it)}, {ui.add(it)}, {42000})
        fun launch() {
            bridge.launch(owner,current={foreground && exactSource && !cancelled},
                launch={launches++},failed={errors++},
                result={ok->if(ok && lifecycle.receive())staged=true},
                related={!cancelled && lifecycle.active()})
            worker.removeFirst()();ui.removeFirst()()
        }
        fun pause(){foreground=false;bridge.cancelUnlaunched(owner)}
        fun result(){bridge.dispatch(owner,42000,true)}
        fun resume(){foreground=true}
        fun cancel(){cancelled=true;lifecycle.cancel()}
        fun canConfirm()=staged && lifecycle.canConfirm(foreground && exactSource && !cancelled)
        fun confirm():Boolean {
            when(consumeDirectoryBatchConfirmation(confirmation,confirmation,lifecycle) {
                staged && foreground && exactSource && !cancelled
            }) {
                DirectoryConfirmationDecision.IGNORED -> return false
                DirectoryConfirmationDecision.STALE -> {cancel();return false}
                DirectoryConfirmationDecision.CONFIRMED -> Unit
            }
            // Model of existing worker registry validation, not an actual Android grant.
            if(!permission)return false
            copies++;return true
        }
    }
    @Test fun launchPauseResultOkResumeExplicitConfirmStagesBeforeForegroundWithoutCopy() {
        val f=Flow();f.launch();assertEquals(1,f.launches);f.pause();f.result()
        assertTrue(f.staged);assertEquals(0,f.errors);assertEquals(0,f.copies);assertFalse(f.canConfirm())
        f.resume();assertEquals(0,f.copies);assertTrue(f.confirm());assertEquals(1,f.copies);assertFalse(f.confirm())
        assertFalse(f.cancelled);assertTrue(f.lifecycle.active());assertEquals(1,f.copies)
    }
    @Test fun cancelledBeforeResultCannotStageOrCopyAfterResume() {
        val f=Flow();f.launch();f.pause();f.cancel();f.result();f.resume()
        assertFalse(f.staged);assertFalse(f.confirm());assertEquals(0,f.copies)
    }
    @Test fun cancelledAfterStagedResultCannotConfirm() {
        val f=Flow();f.launch();f.pause();f.result();f.cancel();f.resume()
        assertTrue(f.staged);assertFalse(f.confirm());assertEquals(0,f.copies)
    }
    @Test fun changedSourceResultStagesButConfirmationRefuses() {
        val f=Flow();f.launch();f.pause();f.exactSource=false;f.result();f.resume()
        assertTrue(f.staged);assertEquals(0,f.errors);assertFalse(f.confirm());assertEquals(0,f.copies)
    }
    @Test fun recreationAbandonsOldOwnerAndLateResultCannotStage() {
        val f=Flow();f.launch();f.pause();f.bridge.abandon(f.owner)
        f.bridge.dispatch(Any(),42000,true);f.resume();assertFalse(f.staged);assertFalse(f.confirm());assertEquals(0,f.copies)
    }
    @Test fun revokedPermissionAfterResultStopsWorkerCopyWithoutFakeSuccess() {
        val f=Flow();f.launch();f.pause();f.result();f.permission=false;f.resume()
        assertTrue(f.staged);assertFalse(f.confirm());assertEquals(0,f.copies)
    }
    @Test fun sourceChangeAfterResumeIsRecheckedAtExplicitConfirmation() {
        val f=Flow();f.launch();f.pause();f.result();f.resume();f.exactSource=false
        assertFalse(f.confirm());assertEquals(0,f.copies)
    }
    @Test fun foregroundAndSourceRemainMandatoryBeforeLaunch() {
        val f=Flow();f.foreground=false;f.launch();assertFalse(f.staged);assertEquals(1,f.errors);assertEquals(0,f.launches)
        val g=Flow();g.exactSource=false;g.launch();assertEquals(1,g.errors);assertEquals(0,g.launches)
    }
}
