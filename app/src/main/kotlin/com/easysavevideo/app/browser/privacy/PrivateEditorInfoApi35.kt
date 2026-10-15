package com.easysavevideo.app.browser.privacy

import android.view.inputmethod.EditorInfo
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi

@RequiresApi(35)
internal object PrivateEditorInfoApi35 {
    @DoNotInline fun clear(editorInfo: EditorInfo) { editorInfo.setStylusHandwritingEnabled(false) }
}
