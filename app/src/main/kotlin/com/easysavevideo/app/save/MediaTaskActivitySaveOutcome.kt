package com.easysavevideo.app.save

import com.easysavevideo.core.model.TaskMethod
import com.easysavevideo.core.model.TaskState
import com.easysavevideo.save.SaveRecord
import com.easysavevideo.save.selectedOutputDescriptor
import com.easysavevideo.app.discovery.DiscoveryOriginHistoryOutcome
import com.easysavevideo.app.discovery.DiscoveryOriginSaveOutcome

/** Only a final coordinator record can supply this evidence; acceptance/COMMITTING are insufficient. */
internal fun mediaTaskHasCommittedSave(taskId: String, record: SaveRecord?): Boolean {
    if (record == null || record.task.taskId != taskId || record.task.state != TaskState.SUCCEEDED ||
        record.request.method !in setOf(TaskMethod.DIRECT, TaskMethod.STREAM)) return false
    val receipt = record.receipt ?: return false
    val verification = record.verification ?: return false
    val artifact = record.artifact ?: return false
    val output = record.selectedOutputDescriptor() ?: return false
    return receipt.commitId == record.commitId && artifact.commitId == record.commitId &&
        receipt.artifactId == artifact.artifactId && receipt.artifactId == verification.artifactId &&
        receipt.sizeBytes == verification.sizeBytes && receipt.sha256 == verification.sha256 &&
        verification.isValidFor(artifact, output, record.request.networkResource?.expectedAudio ?: false)
}

/** This label never replaces the coordinator's media status or requests another media execution. */
internal fun mediaTaskDiscoveryOutcomeLabel(outcome: DiscoveryOriginSaveOutcome): String = when (outcome.history) {
    DiscoveryOriginHistoryOutcome.RECORDED -> "媒体保存已完成；发现历史已记录。"
    DiscoveryOriginHistoryOutcome.STALE_OR_UNAVAILABLE -> "媒体保存已完成；发现关联已过期或不可用，本次发现历史未记录。"
    DiscoveryOriginHistoryOutcome.HISTORY_FAILED -> "媒体保存已完成；发现历史未记录。"
    DiscoveryOriginHistoryOutcome.IDENTITY_UNVERIFIED -> "媒体保存已完成；仅能确认来自此页面，发现视频的保存状态仍未知。"
}
