package com.easysavevideo.app.editor.verification

import com.easysavevideo.app.media.verification.AvcAacAacConfigPolicy

internal object EditAacConfigPolicy {
    fun requireLc(bytes: ByteArray, sampleRate: Int, channels: Int) =
        editVerification { AvcAacAacConfigPolicy.requireLc(bytes, sampleRate, channels) }
}
