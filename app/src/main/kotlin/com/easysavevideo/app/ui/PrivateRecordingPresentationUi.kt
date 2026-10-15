package com.easysavevideo.app.ui

/** Presentation only. Never carries profile, URL, credential, grant or capture authority. */
enum class PrivateRecordingStage { CONFIGURE, WAITING_SYSTEM, CONTINUE, ARMING, RECORDING, STOPPING, VERIFYING, COMMITTING, BLOCKED, SUCCEEDED, FAILED, CANCELLED }
enum class PrivateRecordingAudioChoice { SILENT, DEVICE_AUDIO }
class PrivateRecordingPresentationUi(
    /** Stable within one attempt; changes for every replacement owner. Not capture authority. */
    val presentationId: Long,
    val stage: PrivateRecordingStage,
    val audioChoice: PrivateRecordingAudioChoice,
    val canStart: Boolean,
    val canContinue: Boolean,
    val onStart: (PrivateRecordingAudioChoice) -> Unit,
    val onContinue: () -> Unit,
    val onStopSave: () -> Unit,
    val onDiscard: () -> Unit,
    val onClose: () -> Unit,
    /** Exact runtime publication state, not inferred from native drain. */
    val canDiscard: Boolean = stage in setOf(PrivateRecordingStage.ARMING, PrivateRecordingStage.RECORDING,
        PrivateRecordingStage.STOPPING, PrivateRecordingStage.VERIFYING, PrivateRecordingStage.COMMITTING),
) {
    /** Negative IDs belong to submitted runtime owners; uncertain native release cannot dismiss them. */
    val recoveryBlocked: Boolean get() = presentationId < 0 && stage == PrivateRecordingStage.BLOCKED
    fun handleBack() = when (stage) {
        PrivateRecordingStage.ARMING, PrivateRecordingStage.RECORDING, PrivateRecordingStage.STOPPING,
        PrivateRecordingStage.VERIFYING, PrivateRecordingStage.COMMITTING -> Unit
        PrivateRecordingStage.BLOCKED -> if (recoveryBlocked) Unit else onClose()
        else -> onClose()
    }
    override fun toString() = "PrivateRecordingPresentationUi($stage)"
}
