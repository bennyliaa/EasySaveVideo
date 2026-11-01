package com.easysavevideo.app.storage

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Independently written historical bytes; intentionally does not call the current encoder. */
internal fun legacySearxngConfiguration(duplicateOrigin: Boolean = false, version: Int = 1,
    empty: Boolean = false): ByteArray {
    require(version in 1..2)
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
        output.writeInt(0x45535358); output.writeInt(version); output.writeLong(7); output.writeInt(3)
        for (index in 0..2) {
            val id = "searxng-${index + 1}".toByteArray(Charsets.UTF_8)
            output.writeInt(id.size); output.write(id)
            output.writeBoolean(!empty && index == 0)
            output.writeBoolean(!empty && index != 2)
            if (!empty && index != 2) {
                val url = (if (duplicateOrigin && index == 1) "https://SEARCH0.EXAMPLE.ORG/search"
                    else "https://search$index.example.org/search").toByteArray(Charsets.UTF_8)
                output.writeInt(url.size); output.write(url)
            }
            if (version == 2) output.writeByte(if (index == 1) 1 else 0)
        }
    }
    return bytes.toByteArray()
}
