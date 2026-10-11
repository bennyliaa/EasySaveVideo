package com.easysavevideo.app.ui.shell

import org.junit.Assert.*
import org.junit.Test

class EsvDestinationTest {
    @Test fun persistedDestinationCannotIntroduceExecutableOrSensitiveArguments() {
        assertEquals(EsvDestination.DISCOVER, EsvDestination.fromRoute(null))
        assertEquals(EsvDestination.DISCOVER, EsvDestination.fromRoute("https://example.invalid/private"))
        assertEquals(EsvDestination.DISCOVER, EsvDestination.fromRoute("editor?media=private"))
        EsvDestination.entries.forEach { assertEquals(it, EsvDestination.fromRoute(it.route)) }
    }
    @Test fun editorAndBrowserRemainSecondaryAndSettingsDoesNotBecomeAFifthRoot() {
        assertEquals(4, EsvDestination.roots.size)
        assertFalse(EsvDestination.SETTINGS in EsvDestination.roots)
        assertFalse(EsvDestination.EDITOR in EsvDestination.roots)
        assertEquals(EsvDestination.LIBRARY, EsvDestination.EDITOR.root)
        assertEquals(EsvDestination.DISCOVER, EsvDestination.BROWSER.root)
    }
}
