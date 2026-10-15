package com.easysavevideo.app.editor.verification

import com.easysavevideo.app.media.verification.AvcAacAvcConfigPolicy
import com.easysavevideo.app.media.verification.AvcAacMp4Probe
import com.easysavevideo.app.media.verification.AvcAacMp4Track
import com.easysavevideo.library.edit.EditOutputExpectation
import java.nio.channels.FileChannel

internal typealias EditMp4Track = AvcAacMp4Track

internal object EditMp4Probe {
    fun read(channel: FileChannel, size: Long, expected: EditOutputExpectation,
        checkpoint: () -> Unit = {}): List<EditMp4Track> = editVerification {
        AvcAacMp4Probe.read(channel, size, expected.inspectionExpectation(), checkpoint)
    }
}

internal object EditAvcConfigPolicy {
    fun requireSupported(config: ByteArray, expectedWidth: Int, expectedHeight: Int): Int =
        editVerification { AvcAacAvcConfigPolicy.requireSupported(config, expectedWidth, expectedHeight) }
}
