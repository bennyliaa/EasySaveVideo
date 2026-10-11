package com.easysavevideo.app.ui.shell

/** Finite compile-time destinations. Arguments never contain page addresses or media. */
enum class EsvDestination(val route: String) {
    DISCOVER("discover"), TASKS("tasks"), RECORD("record"), LIBRARY("library"),
    SETTINGS("settings"), BROWSER("browser"), SOURCES("sources"), EDITOR("editor");

    val root: EsvDestination get() = when (this) {
        BROWSER -> DISCOVER
        EDITOR -> LIBRARY
        SETTINGS, SOURCES -> DISCOVER
        else -> this
    }
    companion object {
        val roots = listOf(DISCOVER, TASKS, RECORD, LIBRARY)
        fun fromRoute(route: String?) = entries.firstOrNull { it.route == route } ?: DISCOVER
    }
}
