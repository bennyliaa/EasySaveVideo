package com.easysavevideo.app.ui

import android.view.inputmethod.EditorInfo
import com.easysavevideo.app.browser.privacy.privateProfileImeFlags
import org.junit.Assert.*
import org.junit.Test

/** Bit policy only, not a claim about actual third-party keyboard behavior. */
class PrivateBrowserImeTest {
    @Test fun ordinaryFlagsRemainExactlyUnchanged() {
        assertEquals(EditorInfo.IME_ACTION_GO, privateBrowserImeFlags(EditorInfo.IME_ACTION_GO, false))
    }
    @Test fun privateRequestPreservesActionAndAddsOfficialNoLearningFlag() {
        val flags = privateBrowserImeFlags(EditorInfo.IME_ACTION_GO, true)
        assertEquals(EditorInfo.IME_ACTION_GO, flags and EditorInfo.IME_MASK_ACTION)
        assertTrue(flags and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }
    @Test fun webViewAndComposeUseSameIdempotentPrivatePolicy() {
        val flags = privateProfileImeFlags(EditorInfo.IME_ACTION_DONE)
        assertEquals(flags, privateProfileImeFlags(flags))
        assertEquals(flags, privateBrowserImeFlags(EditorInfo.IME_ACTION_DONE, true))
    }
}
