package com.easysavevideo.app.editor.engine

import com.easysavevideo.app.media.transform.*
import com.easysavevideo.library.edit.*

internal fun editorTransformerFailure(failure: Throwable): EditFailure = when (failure) {
    is EditFailure -> failure
    is TransformerStopped -> EditFailure(if (failure.reason == com.easysavevideo.core.model.OperationStopReason.CANCELLED) EditError.CANCELLED else EditError.TIMEOUT)
    is TransformerFailure -> EditFailure(when (failure.code) {
        TransformerError.CAPACITY -> EditError.CAPACITY
        TransformerError.INVALID_REQUEST -> EditError.INVALID_REQUEST
        TransformerError.INVALID_STATE -> EditError.INVALID_STATE
        TransformerError.SOURCE_LIMIT -> EditError.SOURCE_LIMIT
        TransformerError.UNSUPPORTED_PROFILE, TransformerError.NOT_ISSUED -> EditError.UNSUPPORTED_PROFILE
        TransformerError.EXECUTION_FAILED -> EditError.EXECUTION_FAILED
        TransformerError.INSUFFICIENT_SPACE -> EditError.INSUFFICIENT_SPACE
    })
    is InterruptedException -> EditFailure(EditError.CANCELLED)
    else -> EditFailure(EditError.EXECUTION_FAILED) // Preserves existing Editor unknown/future mapping.
}
