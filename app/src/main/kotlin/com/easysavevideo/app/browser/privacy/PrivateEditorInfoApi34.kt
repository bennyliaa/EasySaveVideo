package com.easysavevideo.app.browser.privacy

import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi

@RequiresApi(34)
internal object PrivateEditorInfoApi34 {
    @DoNotInline fun clear(editorInfo: EditorInfo) {
        editorInfo.setSupportedHandwritingGestures(emptyList())
        editorInfo.setSupportedHandwritingGesturePreviews(emptySet())
        editorInfo.setInitialToolType(MotionEvent.TOOL_TYPE_UNKNOWN)
    }
}
