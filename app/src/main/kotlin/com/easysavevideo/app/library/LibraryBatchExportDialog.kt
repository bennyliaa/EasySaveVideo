package com.easysavevideo.app.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.easysavevideo.app.R
import com.easysavevideo.app.library.share.SharePolicy
import com.easysavevideo.app.theme.*

@Composable
internal fun LibraryBatchExportDialog(selection:LibraryBatchSelection,current:Boolean,enabled:Boolean,
    onChoose:(LibraryExportDestination)->Unit,onDismiss:()->Unit) {
    val v=LocalEsvVisuals.current
    LibraryDialog(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(v.spacingSmall)) {
            EsvText(stringResource(R.string.library_batch_transfer_title),style=v.titleTextStyle)
            selection.items.forEach { EsvText(it.displayName,maxLines=2) }
            if(!current)EsvText(stringResource(R.string.library_batch_transfer_stale),color=v.colors.error)
            EsvText(stringResource(R.string.library_batch_gallery_warning),style=v.captionTextStyle)
            EsvButton(stringResource(R.string.library_batch_gallery),{onChoose(LibraryExportDestination.GALLERY)},enabled=enabled && current)
            EsvText(stringResource(R.string.library_batch_share_warning),style=v.captionTextStyle)
            val shareEligible=selection.items.size<=SharePolicy.MAX_COPIES &&
                selection.items.all { it.sizeBytes in 1..SharePolicy.MAX_COPY_BYTES } && selection.items.sumOf { it.sizeBytes }<=SharePolicy.MAX_TOTAL_BYTES
            EsvButton(stringResource(R.string.library_batch_share),{onChoose(LibraryExportDestination.SHARE)},enabled=enabled && current && shareEligible)
            if(!shareEligible)EsvText(stringResource(R.string.library_batch_share_limit),style=v.captionTextStyle,color=v.colors.muted)
            EsvText(stringResource(R.string.library_batch_files_pending),style=v.captionTextStyle,color=v.colors.muted)
            EsvButton(stringResource(R.string.library_batch_dismiss),onDismiss)
        }
    }
}
