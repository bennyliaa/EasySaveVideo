package com.easysavevideo.app.ui.shell

import com.easysavevideo.core.model.*
import org.junit.Assert.*
import org.junit.Test

class BrowserSettingsActionsTest {
    private fun apply(s:AppSettings,e:SettingsEdit.BrowserEdit,ready:Boolean=true,foreground:Boolean=true,
        alive:Boolean=true,route:EsvDestination=EsvDestination.SETTINGS,expected:EsvDestination=EsvDestination.SETTINGS)=
        applyBrowserSettingsEdit(s,e,ready,foreground,alive,route,expected)
    @Test fun javaScriptChangesOnlyItsField() {
        val s=AppSettings(browser=BrowserSettings(true,4,false,true),save=SaveSettings(true))
        assertEquals(s.copy(browser=s.browser.copy(javaScriptEnabled=false)),apply(s,SettingsEdit.BrowserJavaScript(false)))
    }
    @Test fun cookiesChangeOnlyTheirField() {
        val s=AppSettings(browser=BrowserSettings(false,1,false,false))
        assertEquals(s.copy(browser=s.browser.copy(acceptThirdPartyCookies=true)),apply(s,SettingsEdit.BrowserThirdPartyCookies(true)))
    }
    @Test fun restoringChangesOnlyItsField() {
        val s=AppSettings(browser=BrowserSettings(false,2,true,true))
        assertEquals(s.copy(browser=s.browser.copy(restoreTabs=false)),apply(s,SettingsEdit.BrowserRestoreTabs(false)))
    }
    @Test fun repeatedDeltaUsesLiveOwnerAndClampsEveryBoundary() {
        var s=AppSettings()
        repeat(20){s=apply(s,SettingsEdit.BrowserActiveViews(1))!!;assertTrue(s.browser.maxActiveWebViews in 1..4)}
        assertEquals(4,s.browser.maxActiveWebViews)
        repeat(20){s=apply(s,SettingsEdit.BrowserActiveViews(-1))!!;assertTrue(s.browser.maxActiveWebViews in 1..4)}
        assertEquals(1,s.browser.maxActiveWebViews)
    }
    @Test fun unsupportedDeltasAreRejected() {
        for(d in listOf(Int.MIN_VALUE,-2,0,2,Int.MAX_VALUE))try {SettingsEdit.BrowserActiveViews(d);fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun alternatingEntrypointsAndUnrelatedEditsKeepEachField() {
        var s=AppSettings()
        s=apply(s,SettingsEdit.BrowserJavaScript(false),route=EsvDestination.BROWSER,expected=EsvDestination.BROWSER)!!
        s=SettingsEdit.Language(AppLanguage.CHINESE).applyTo(s)
        s=SettingsEdit.Theme(ThemeMode.DARK).applyTo(s)
        s=SettingsEdit.WifiOnly(true).applyTo(s)
        s=SettingsEdit.DiscoveryColumns(1).applyTo(s)
        s=apply(s,SettingsEdit.BrowserThirdPartyCookies(true))!!
        assertFalse(s.browser.javaScriptEnabled);assertTrue(s.browser.acceptThirdPartyCookies)
        assertTrue(s.save.wifiOnly);assertEquals(AppLanguage.CHINESE,s.appearance.language)
        assertEquals(ThemeMode.DARK,s.appearance.themeMode);assertEquals(3,s.discovery.columns)
    }
    @Test fun everyUnreadyInactiveDeadOrWrongRouteCombinationRejects() {
        for(ready in listOf(false,true))for(fg in listOf(false,true))for(alive in listOf(false,true))
            for(expected in EsvDestination.entries)for(route in EsvDestination.entries) {
                val result=apply(AppSettings(),SettingsEdit.BrowserJavaScript(false),ready,fg,alive,route,expected)
                assertEquals(ready&&fg&&alive&&route==expected&&expected in setOf(EsvDestination.SETTINGS,EsvDestination.BROWSER),result!=null)
            }
    }
    @Test fun retiredViewCannotEditEvenAfterReturningToSameRoute() {
        var active=true;val edits=mutableListOf<SettingsEdit.BrowserEdit>();val a=BrowserSettingsActions({active}){edits+=it}
        a.edit(SettingsEdit.BrowserJavaScript(false));a.close();active=false;active=true
        a.edit(SettingsEdit.BrowserThirdPartyCookies(true));assertEquals(1,edits.size)
        val b=BrowserSettingsActions({active}){edits+=it};b.edit(SettingsEdit.BrowserActiveViews(1));assertEquals(2,edits.size)
    }
    @Test fun callbackChecksCurrentReadinessAndBackgroundEveryTime() {
        var active=false;var count=0;val a=BrowserSettingsActions({active}){count++}
        a.edit(SettingsEdit.BrowserRestoreTabs(false));active=true;a.edit(SettingsEdit.BrowserRestoreTabs(false))
        active=false;a.edit(SettingsEdit.BrowserRestoreTabs(true));assertEquals(1,count)
    }
    @Test fun closingIsIdempotentAndNeverDispatches() {
        var count=0;val a=BrowserSettingsActions({true}){count++};a.close();a.close();assertEquals(0,count)
    }
    @Test fun delayedLoadRetainsAllFourBrowserValuesAfterEarlyAppearanceEdits() {
        val loaded=AppSettings(browser=BrowserSettings(false,1,false,true))
        val current=SettingsEdit.Theme(ThemeMode.DARK).applyTo(SettingsEdit.Language(AppLanguage.ENGLISH).applyTo(AppSettings()))
        val merged=mergeLoadedBrowserSettings(current,loaded,true)
        assertEquals(loaded.browser,merged.browser);assertEquals(current.appearance,merged.appearance)
        assertEquals(current.discovery,merged.discovery);assertEquals(current.save,merged.save)
    }
    @Test fun failedLoadDoesNotPretendDefaultsWereRestoredOrEnableEditing() {
        val current=AppSettings(browser=BrowserSettings(false,2,false,true))
        assertSame(current,mergeLoadedBrowserSettings(current,AppSettings(),false))
        assertNull(apply(current,SettingsEdit.BrowserJavaScript(true),ready=false))
    }
    @Test fun unavailableAndRetiredActionsNeverReadDispatchOrWriteSettings() {
        val a=BrowserSettingsActions({false}){error("must not dispatch")};a.edit(SettingsEdit.BrowserActiveViews(1));a.close()
        a.edit(SettingsEdit.BrowserJavaScript(false))
    }
    @Test fun sequentialOppositeBooleanEditsFollowLatestIntent() {
        var s=AppSettings();for(v in listOf(false,true,false))s=apply(s,SettingsEdit.BrowserRestoreTabs(v))!!
        assertFalse(s.browser.restoreTabs);assertFalse(s.browser.acceptThirdPartyCookies)
    }
}
