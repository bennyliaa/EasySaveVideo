package com.easysavevideo.app.save

import com.easysavevideo.core.model.TaskState

/** UI admission hints use the bounded unfinished-task query, never the recent history length. */
internal object SaveTaskCapacity {
    fun visibleTaskIds(unfinished: List<String>, recent: List<String>): List<String> = (unfinished + recent).distinct()
    fun activeCount(states: Iterable<TaskState>): Int = states.count { !it.isTerminal }
    fun hasRoom(activeCount: Int, maxActive: Int): Boolean {
        require(activeCount >= 0 && maxActive > 0)
        return activeCount < maxActive
    }
}
