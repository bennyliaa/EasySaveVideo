package com.easysavevideo.app.browser.privacy

import android.view.inputmethod.EditorInfo
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi

@RequiresApi(36)
internal object PrivateEditorInfoApi36 {
    @DoNotInline fun clear(editorInfo: EditorInfo) { editorInfo.setAutofillId(null); editorInfo.setWritingToolsEnabled(false) }
}
