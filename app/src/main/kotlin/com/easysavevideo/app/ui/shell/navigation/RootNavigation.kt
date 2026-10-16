package com.easysavevideo.app.ui.shell.navigation

/** Fixed, compile-time route policy. Auxiliary entries are discarded before saving root state.
 * This same source is compiled into the isolated diagnostic carrier; models are not device proof.
 */
object RootNavigation {
    val roots: Set<String> = setOf("discover", "tasks", "record", "library")
    private val auxiliaries = setOf("settings", "sources", "browser", "editor", "vault")
    private const val MAX_AUXILIARY_POPS = 32

    fun navigate(target: String, current: () -> String?, pop: () -> Boolean,
        navigateRoot: (preserveRootStack: Boolean) -> Unit): Boolean {
        require(target in roots)
        fun discardAuxiliaries(): Boolean {
            var count = 0
            while (current() in auxiliaries) {
                if (++count > MAX_AUXILIARY_POPS) return false
                if (!pop()) return current() !in auxiliaries
            }
            return true
        }
        if (!discardAuxiliaries()) return false
        if (current() == target) return true
        // A recreated graph can start on an auxiliary page with no root below it. Popping its
        // last entry leaves no live root state to save; previously saved root state still restores.
        navigateRoot(current() in roots)
        // A previous app version may have saved an auxiliary tail under a root's restore key.
        // Discard that tail only, retaining the restored root and its legitimate saved state.
        if (!discardAuxiliaries()) return false
        return current() == target
    }
}
