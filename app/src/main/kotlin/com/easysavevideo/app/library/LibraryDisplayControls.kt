package com.easysavevideo.app.library

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import com.easysavevideo.app.R
import com.easysavevideo.app.theme.*
import com.easysavevideo.library.LibrarySort
import com.easysavevideo.library.SortDirection

/** Presentation only. Query ownership and all repository work stay with the library runtime. */
internal fun librarySortLabel(sort: LibrarySort): Int = when (sort) {
    LibrarySort.NAME -> R.string.finite_sort_name
    LibrarySort.DATE -> R.string.finite_sort_date
    LibrarySort.DURATION -> R.string.finite_sort_duration
    LibrarySort.SIZE -> R.string.finite_sort_size
}
internal fun libraryDirectionLabel(direction: SortDirection): Int = when (direction) {
    SortDirection.ASCENDING -> R.string.library_order_ascending
    SortDirection.DESCENDING -> R.string.library_order_descending
}
internal fun libraryReverseAction(direction: SortDirection): Int = when (direction) {
    SortDirection.ASCENDING -> R.string.library_order_descend_action
    SortDirection.DESCENDING -> R.string.library_order_ascend_action
}
internal fun libraryViewLabel(grid: Boolean): Int =
    if (grid) R.string.library_view_grid else R.string.library_view_list
internal fun libraryViewAction(grid: Boolean): Int =
    if (grid) R.string.library_view_list_action else R.string.library_view_grid_action
internal fun Modifier.librarySortSelection(sort: LibrarySort, current: LibrarySort): Modifier =
    semantics { selected = sort == current }
internal fun Modifier.libraryDisplayState(description: String): Modifier =
    semantics { stateDescription = description }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibraryDisplayControls(sort: LibrarySort, direction: SortDirection, grid: Boolean,
    interactive: Boolean, onSort: (LibrarySort) -> Unit, onReverse: () -> Unit,
    onToggleView: () -> Unit, onCreateFolder: () -> Unit) {
    val v = LocalEsvVisuals.current
    val sortName = stringResource(librarySortLabel(sort))
    val orderName = stringResource(libraryDirectionLabel(direction))
    val viewName = stringResource(libraryViewLabel(grid))
    Column(verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
        // This also makes the current state visible to sighted users, separately from next actions.
        EsvText(stringResource(R.string.library_display_summary, sortName, orderName, viewName),
            style = v.captionTextStyle, color = v.colors.muted)
        for (pair in LibrarySort.entries.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
                for (candidate in pair) {
                    val label = stringResource(librarySortLabel(candidate))
                    EsvButton(if (candidate == sort) stringResource(R.string.finite_selected, label) else label,
                        { onSort(candidate) }, enabled = interactive,
                        modifier = Modifier.weight(1f).librarySortSelection(candidate, sort))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(v.spacingSmall),
            verticalArrangement = Arrangement.spacedBy(v.spacingSmall)) {
            EsvButton(stringResource(libraryReverseAction(direction)), onReverse, enabled = interactive,
                modifier = Modifier.libraryDisplayState(orderName))
            // The original display-only toggle remains available while catalog reads are pending.
            EsvButton(stringResource(libraryViewAction(grid)), onToggleView,
                modifier = Modifier.libraryDisplayState(viewName))
            EsvButton(stringResource(R.string.flow_libraryscreen_8), onCreateFolder, enabled = interactive)
        }
    }
}
