package com.easysavevideo.app.library.destination

import com.easysavevideo.library.destination.OrdinaryDestinationPreference
import org.junit.Assert.*
import org.junit.Test

/** Production UI command adapter with deterministic existing-runtime ports; no Android/SAF operation. */
class DestinationSettingsActionsTest {
    private class Rig {
        var active=true
        var state=DestinationSettingsAvailability(true,false,false,false,false,true)
        var preference=OrdinaryDestinationPreference.APP_LIBRARY
        val selected=mutableListOf<OrdinaryDestinationPreference>()
        var picks=0;var cancels=0;var forgets=0;var dismisses=0
        fun actions()=DestinationSettingsActions({active},{state}, {
            selected+=it
            state=state.copy(saving=true)
        },{picks++;state=state.copy(choosingFolder=true)},
            {cancels++;state=state.copy(choosingFolder=false)},
            {forgets++;state=state.copy(saving=true)}, {dismisses++})
        val actions=actions()
        fun confirmPreference(){preference=selected.last();state=state.copy(saving=false)}
    }
    @Test fun everyExistingPreferenceIsForwardedWithoutIntroducingAnotherDestination() {
        for(pref in OrdinaryDestinationPreference.entries) {
            val r=Rig();r.actions.select(pref);assertEquals(listOf(pref),r.selected)
            assertEquals(OrdinaryDestinationPreference.APP_LIBRARY,r.preference)
            r.confirmPreference();assertEquals(pref,r.preference)
        }
    }
    @Test fun blockedAvailabilityRejectsAllPreferenceEdits() {
        for(ready in listOf(false,true))for(saving in listOf(false,true))for(choosing in listOf(false,true)) {
            val r=Rig();r.state=r.state.copy(ready=ready,saving=saving,choosingFolder=choosing)
            r.actions.select(OrdinaryDestinationPreference.ASK)
            assertEquals(ready && !saving && !choosing,r.selected.isNotEmpty())
        }
    }
    @Test fun newFolderRequiresIdleCreationAdmissionAndReadySettings() {
        for(ready in listOf(false,true))for(saving in listOf(false,true))for(choosing in listOf(false,true))
            for(asking in listOf(false,true))for(admitting in listOf(false,true)) {
                val r=Rig();r.state=DestinationSettingsAvailability(ready,saving,choosing,asking,admitting,false)
                r.actions.choose();assertEquals(if(ready&&!saving&&!choosing&&!asking&&!admitting)1 else 0,r.picks)
            }
    }
    @Test fun folderPreferenceWithoutGrantDoesNotBypassPickerAdmission() {
        for(asking in listOf(false,true))for(admitting in listOf(false,true)) {
            val r=Rig();r.state=r.state.copy(rememberedFolder=false,asking=asking,admitting=admitting)
            r.actions.select(OrdinaryDestinationPreference.FOLDER_COPY)
            assertEquals(!asking&&!admitting,r.selected.isNotEmpty())
        }
    }
    @Test fun alreadyAdmittedFolderAndNonFolderPreferenceKeepExistingFutureTaskSemantics() {
        for(pref in OrdinaryDestinationPreference.entries) {
            val r=Rig();r.state=r.state.copy(asking=true,admitting=true)
            r.actions.select(pref);assertEquals(listOf(pref),r.selected)
            assertEquals(0,r.picks);assertEquals(0,r.cancels)
        }
    }
    @Test fun repeatedPreferenceClicksWaitForActualRuntimeCompletion() {
        val r=Rig();repeat(5){r.actions.select(OrdinaryDestinationPreference.ALBUM_COPY)}
        assertEquals(listOf(OrdinaryDestinationPreference.ALBUM_COPY),r.selected)
        assertEquals(OrdinaryDestinationPreference.APP_LIBRARY,r.preference)
        r.confirmPreference();r.actions.select(OrdinaryDestinationPreference.ASK)
        assertEquals(2,r.selected.size)
    }
    @Test fun repeatedFolderClicksCannotLaunchDuplicatePicker() {
        val r=Rig();repeat(5){r.actions.choose()};assertEquals(1,r.picks)
    }
    @Test fun pendingPickerCanOnlyBeCancelledByExplicitActiveAction() {
        val r=Rig();r.actions.cancel();assertEquals(0,r.cancels)
        r.actions.choose();r.active=false;r.actions.cancel();assertEquals(0,r.cancels)
        r.active=true;r.actions.cancel();r.actions.cancel();assertEquals(1,r.cancels)
    }
    @Test fun forgottenFolderRequiresAnExistingRememberedChoiceAndIdleWriteState() {
        for(remembered in listOf(false,true))for(ready in listOf(false,true))for(saving in listOf(false,true))for(choosing in listOf(false,true)) {
            val r=Rig();r.state=r.state.copy(rememberedFolder=remembered,ready=ready,saving=saving,choosingFolder=choosing)
            r.actions.forget();assertEquals(if(remembered&&ready&&!saving&&!choosing)1 else 0,r.forgets)
        }
    }
    @Test fun repeatedForgetWaitsForRuntimeWriteAndDoesNotTouchPicker() {
        val r=Rig();repeat(3){r.actions.forget()};assertEquals(1,r.forgets)
        assertEquals(0,r.picks);assertEquals(0,r.cancels)
    }
    @Test fun backgroundAndOtherPageRejectAllEntryCommands() {
        val r=Rig();r.active=false
        r.actions.select(OrdinaryDestinationPreference.ALBUM_COPY);r.actions.choose();r.actions.forget();r.actions.dismiss()
        r.state=r.state.copy(choosingFolder=true);r.actions.cancel()
        assertTrue(r.selected.isEmpty());assertEquals(0,r.picks+r.forgets+r.cancels+r.dismisses)
    }
    @Test fun retiredCategoryCannotRegainAuthorityWhenSameScreenReturns() {
        val r=Rig();val old=r.actions;old.close();val current=r.actions()
        old.select(OrdinaryDestinationPreference.ALBUM_COPY);old.choose();old.forget();old.dismiss()
        assertTrue(r.selected.isEmpty());assertEquals(0,r.picks+r.forgets+r.dismisses)
        current.select(OrdinaryDestinationPreference.ALBUM_COPY);assertEquals(1,r.selected.size)
    }
    @Test fun disposingDuringSystemPickerDoesNotCancelAnAcceptedExplicitChoice() {
        val r=Rig();r.actions.choose();r.actions.close()
        assertEquals(1,r.picks);assertEquals(0,r.cancels);assertTrue(r.state.choosingFolder)
        // System return remains the existing owner's operation, not an old view callback.
        r.state=r.state.copy(choosingFolder=false,rememberedFolder=true)
        val returned=r.actions();returned.select(OrdinaryDestinationPreference.FOLDER_COPY)
        assertEquals(listOf(OrdinaryDestinationPreference.FOLDER_COPY),r.selected)
    }
    @Test fun newForegroundOwnerCanExplicitlyCancelTheSamePendingPickerWithoutRelaunch() {
        val r=Rig();r.actions.choose();r.actions.close();val returned=r.actions()
        returned.choose();assertEquals(1,r.picks)
        returned.cancel();assertEquals(1,r.cancels)
    }
    @Test fun disposingDuringPreferenceWriteDoesNotChangeTheAcceptedTargetOrInventCompletion() {
        val r=Rig();r.actions.select(OrdinaryDestinationPreference.ALBUM_COPY);r.actions.close()
        val returned=r.actions();returned.select(OrdinaryDestinationPreference.ASK)
        assertEquals(listOf(OrdinaryDestinationPreference.ALBUM_COPY),r.selected)
        assertEquals(OrdinaryDestinationPreference.APP_LIBRARY,r.preference)
        r.confirmPreference();assertEquals(OrdinaryDestinationPreference.ALBUM_COPY,r.preference)
    }
    @Test fun currentRuntimeGatesAreReadAtClickRatherThanRenderedSnapshot() {
        val r=Rig();val callback={r.actions.select(OrdinaryDestinationPreference.ALBUM_COPY)}
        r.state=r.state.copy(saving=true);callback();assertTrue(r.selected.isEmpty())
        r.state=r.state.copy(saving=false);callback();assertEquals(1,r.selected.size)
    }
    @Test fun dismissalHasNoConfigurationOrPickerSideEffects() {
        val r=Rig();r.actions.dismiss();assertEquals(1,r.dismisses)
        assertTrue(r.selected.isEmpty());assertEquals(0,r.picks+r.forgets+r.cancels)
        r.actions.close();r.actions.dismiss();assertEquals(1,r.dismisses)
    }
}
