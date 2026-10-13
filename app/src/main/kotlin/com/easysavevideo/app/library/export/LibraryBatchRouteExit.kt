package com.easysavevideo.app.library.export

import com.easysavevideo.app.ui.shell.EsvDestination

/** Invoked for observed destinations, never navigation intent or picker Activity pauses. */
internal fun dispatchLibraryBatchRouteExit(
    previous: EsvDestination,
    current: EsvDestination,
    onLeaving: () -> Unit,
): Boolean {
    if (previous != EsvDestination.LIBRARY || current == EsvDestination.LIBRARY) return false
    onLeaving()
    return true
}
