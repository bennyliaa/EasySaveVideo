package com.easysavevideo.app.ui.shell

/** Close foreground source gates only when navigation actually changes destination. */
internal fun dispatchEsvNavigation(currentRoute: String?, destination: EsvDestination,
    onLeaving: () -> Unit, onNavigate: (EsvDestination) -> Unit): Boolean {
    if (currentRoute == destination.route) return false
    onLeaving()
    onNavigate(destination)
    return true
}
