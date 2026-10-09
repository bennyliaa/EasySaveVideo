package com.easysavevideo.app.editor.storage

import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.edit.*
import java.io.File

/** Run against the unchanged historical production sources, never the current writer. */
fun main(args: Array<String>) {
    val target = File(args[0]); target.mkdirs()
    val request = EditRequest("legacy-edit", listOf(
        EditSegment(MediaReadRef("first", 3, 7), 0, 1_000_000, NormalizedCrop(0.1, 0.2, 0.9, 0.8)),
        EditSegment(MediaReadRef("second", 5, 9), 1_000_000, 2_000_000)), EditCompression.SAVE_SPACE)
    val expected = EditOutputExpectation(1_000_000, 320, 240, 30, EditAudioProfile.Silent, 33334, 0)
    val proof = EditVerificationEvidence(EditOutputRef("legacy-edit", "legacy-artifact"), expected,
        1024, "a".repeat(64), 1_000_000, 320, 240, 0, "video/avc", null, null, null,
        0, 966666, 30, 0, 30, 0, true, false, true, true)
    File(target, "request.bin").writeBytes(EditRecordCodec.request(request))
    File(target, "expectation.bin").writeBytes(EditRecordCodec.expectation(expected))
    File(target, "evidence.bin").writeBytes(EditRecordCodec.evidence(proof))
}
