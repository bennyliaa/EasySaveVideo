package com.easysavevideo.app.editor.verification

/** AAC AudioSpecificConfig is checked independently; HE-AAC/SBR/PS or explicit extensions fail. */
internal object EditAacConfigPolicy {
    private val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    fun requireLc(bytes: ByteArray, sampleRate: Int, channels: Int) {
        // AAC LC with frameLengthFlag=0, dependsOnCoreCoder=0, extensionFlag=0, no implicit extension.
        if (bytes.size != 2) unsupportedEditOutput()
        val bits = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
        val objectType = bits ushr 11
        val frequencyIndex = (bits ushr 7) and 15
        val channelCount = (bits ushr 3) and 15
        if (objectType != 2 || rates.getOrNull(frequencyIndex) != sampleRate || channelCount != channels || bits and 7 != 0) unsupportedEditOutput()
    }
}
