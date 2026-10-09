package com.easysavevideo.app.storage

import com.easysavevideo.app.browser.BrowserInputPolicy
import com.easysavevideo.core.model.*
import java.io.*

/** Bounded private functional format; never used for diagnostics or arbitrary object deserialization. */
object BrowserSessionCodec {
    const val MAX_BYTES = 128 * 1024
    fun encode(checkpoint: BrowserSessionCheckpoint): ByteArray {
        require(checkpoint.tabs.size <= 12)
        require(checkpoint.tabs.map { it.tabId }.distinct().size == checkpoint.tabs.size)
        require(checkpoint.selectedTabId == null || checkpoint.tabs.any { it.tabId == checkpoint.selectedTabId })
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(0x45535631); out.writeInt(1); out.writeInt(checkpoint.tabs.size)
                out.writeUTF(checkpoint.selectedTabId.orEmpty())
                checkpoint.tabs.forEach { tab ->
                    require(tab.tabId.matches(Regex("[a-zA-Z0-9-]{1,80}")))
                    out.writeUTF(tab.tabId); out.writeBoolean(tab.address != null)
                    tab.address?.let { out.writeUTF(it.value) }
                }
            }
            bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
        }
    }
    fun decode(bytes: ByteArray): BrowserSessionCheckpoint {
        require(bytes.size <= MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 0x45535631 && input.readInt() == 1)
            val count = input.readInt(); require(count in 0..12)
            val selected = input.readUTF().takeIf { it.isNotEmpty() }
            val tabs = List(count) {
                val id = input.readUTF(); require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
                val address = if (input.readBoolean()) requireNotNull(BrowserInputPolicy.address(input.readUTF())) else null
                RestorableTab(id, address)
            }
            require(input.available() == 0)
            require(tabs.map { it.tabId }.distinct().size == tabs.size)
            require(selected == null || tabs.any { it.tabId == selected })
            BrowserSessionCheckpoint(tabs, selected)
        }
    }
}
