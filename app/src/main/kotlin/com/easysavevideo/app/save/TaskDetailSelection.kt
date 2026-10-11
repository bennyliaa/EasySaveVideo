package com.easysavevideo.app.save

import com.easysavevideo.save.SaveRecord

/** Resolve from the current snapshot; never retain an old record or guess another task. */
internal fun selectedTaskDetail(records: List<SaveRecord>, selectedTaskId: String?): SaveRecord? =
    if (selectedTaskId == null) null else records.singleOrNull { it.task.taskId == selectedTaskId }
