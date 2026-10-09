package com.easysavevideo.app.storage

import com.easysavevideo.core.model.RepeatPolicy
import com.easysavevideo.discovery.*
import java.io.*

/** Versioned, bounded functional data. Not Java serialization or a diagnostic payload. */
object DiscoveryCheckpointCodec {
    const val MAX_BYTES = 64 * 1024 * 1024
    private const val MAGIC = 0x45534432
    fun encode(value: DiscoveryCheckpoint): ByteArray {
        validate(value)
        val bytes = object : ByteArrayOutputStream() {
            override fun write(value: Int) { require(count < MAX_BYTES); super.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { require(length <= MAX_BYTES - count); super.write(bytes, offset, length) }
        }
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(if (value.sourceConfigurationKey == null && value.queryAuthorized) 3 else 4)
            out.text(value.stamp.sessionId); out.writeLong(value.stamp.epoch)
            val profile = value.profile
            out.text(profile.profileId); out.text(profile.query); out.text(profile.locale)
            out.texts(profile.filters.sourceNamespaces.sorted()); out.optionalLong(profile.filters.minimumDurationMs)
            out.optionalLong(profile.filters.maximumDurationMs); out.writeBoolean(profile.filters.includeUnknownDuration)
            out.writeBoolean(profile.filters.safeSearch); out.text(profile.historyPolicy.name)
            out.text(profile.sortOrder.name)
            out.writeLong(value.pool.viewRevision); out.writeLong(value.pool.nextOrder)
            out.writeInt(value.pool.entries.size)
            value.pool.entries.forEach { entry ->
                val r = entry.result
                out.text(r.fingerprint); out.text(r.canonicalPageUrl); out.optionalText(r.title); out.text(r.sourceName)
                out.optionalText(r.sourceNamespace); out.optionalText(r.sourceVideoId); out.optionalText(r.thumbnailUrl)
                out.optionalLong(r.durationMs); out.optionalText(r.description); out.optionalLong(r.publishedAtEpochMillis)
                out.texts(r.provenance.sorted()); out.texts(r.identityAliases.sorted()); out.writeInt(r.identityVersion)
                val rights = RightsCodec.encode(r.rights); out.writeInt(rights.size); out.write(rights)
                out.writeLong(entry.discoveredAtEpochMillis); out.writeLong(entry.order)
            }
            out.writeInt(value.pool.pages.size)
            value.pool.pages.forEach { page ->
                out.writeLong(page.revision); out.writeInt(page.pageIndex); out.writeInt(page.pageSize); out.texts(page.orderedResultIds)
            }
            if (value.sourceConfigurationKey != null || !value.queryAuthorized) {
                out.optionalText(value.sourceConfigurationKey); out.writeBoolean(value.queryAuthorized)
            }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): DiscoveryCheckpoint {
        require(bytes.size <= MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC)
            val version = input.readInt(); require(version in 1..4)
            val stamp = SessionStamp(input.text(128), input.readLong())
            val profileId = input.text(128); val query = input.text(512); val locale = input.text(64)
            val filters = SearchFilters(input.textSet(100, 128), input.optionalLong(), input.optionalLong(), input.boolean(), input.boolean())
            val historyPolicy = RepeatPolicy.valueOf(input.text(32))
            val sortOrder = if (version >= 3) ResultSort.valueOf(input.text(32)) else ResultSort.RELEVANCE
            val profile = SearchProfile(profileId, query, locale, filters, historyPolicy, sortOrder)
            val revision = input.readLong(); val nextOrder = input.readLong()
            val entries = List(input.count(500)) {
                val result = VideoSearchResult(input.text(128), input.text(8192), input.optionalText(512), input.text(128),
                    input.optionalText(128), input.optionalText(512), input.optionalText(8192), input.optionalLong(),
                    input.optionalText(4096), input.optionalLong(), input.textSet(32, 128), input.textSet(128, 128), input.readInt(),
                    rights = if (version == 1) ResultRights() else {
                        val size = input.count(RightsCodec.MAX_BYTES); require(size <= input.available())
                        RightsCodec.decode(ByteArray(size).also(input::readFully))
                    })
                PoolEntry(result, input.readLong(), input.readLong())
            }
            val pages = List(input.count(500)) {
                val pageRevision = input.readLong(); val index = input.readInt(); val size = input.readInt()
                PageSnapshot(stamp, pageRevision, index, input.texts(100, 128), size)
            }
            // Version4 always contains at least the optional-key flag and query-authority flag.
            if (version >= 4) require(input.available() >= 2)
            val configurationKey = if (version >= 4) input.optionalText(64) else null
            val queryAuthorized = if (version >= 4) input.boolean() else true
            require(input.available() == 0)
            DiscoveryCheckpoint(stamp, profile, PoolState(entries, pages, revision, nextOrder), configurationKey, queryAuthorized).also(::validate)
        }
    }
    fun validate(value: DiscoveryCheckpoint) {
        // Re-run constructors because callers can supply mutable collections and mutate them later.
        value.profile.copy(filters = value.profile.filters.copy(sourceNamespaces = value.profile.filters.sourceNamespaces.toSet()))
        require(listOf(value.profile.query, value.profile.locale).all(::validUnicode))
        require(value.sourceConfigurationKey == null || value.sourceConfigurationKey!!.matches(Regex("[a-f0-9]{64}")))
        val p = value.pool
        require(p.entries.size <= 500 && p.pages.size <= 500 && p.viewRevision >= 0 && p.nextOrder >= 0)
        require(p.entries.map { it.result.resultId }.toSet().size == p.entries.size)
        require(p.entries.map { it.order }.toSet().size == p.entries.size)
        val ids = p.entries.map { it.result.resultId }.toSet()
        p.entries.forEach { entry ->
            val r = entry.result
            require(listOfNotNull(r.title, r.sourceName, r.sourceNamespace, r.sourceVideoId, r.description).all(::validUnicode))
            RightsCodec.validate(r.rights)
            require(entry.discoveredAtEpochMillis >= 0 && entry.order >= 0 && entry.order < p.nextOrder)
            require(r.fingerprint.matches(Regex("[a-f0-9]{64}")) && r.identityVersion == 1)
            require(ResultNormalizer.canonicalUrl(r.canonicalPageUrl) == r.canonicalPageUrl)
            require(r.thumbnailUrl == null || ResultNormalizer.canonicalUrl(r.thumbnailUrl!!) == r.thumbnailUrl)
            require(r.title == null || r.title!!.length <= 512)
            require(r.sourceName.length in 1..128 && (r.sourceNamespace?.length ?: 0) <= 128 && (r.sourceVideoId?.length ?: 0) <= 512)
            require((r.description?.length ?: 0) <= 4096 && (r.durationMs == null || r.durationMs!! > 0))
            require(r.publishedAtEpochMillis == null || r.publishedAtEpochMillis!! >= 0)
            require(r.provenance.size in 1..32 && r.provenance.all { it.matches(Regex("[A-Za-z0-9_.-]{1,128}")) })
            require(r.identityAliases.size in 1..128 && r.fingerprint in r.identityAliases)
            require(r.identityAliases.all { it.matches(Regex("[a-f0-9]{64}")) })
        }
        val pageIds = p.pages.flatMap { it.orderedResultIds }
        require(pageIds.size == pageIds.toSet().size)
        p.pages.forEachIndexed { index, page ->
            require(page.stamp == value.stamp && page.revision == p.viewRevision && page.pageIndex == index)
            require(page.pageSize in 1..100 && page.orderedResultIds.size in 1..100 && page.orderedResultIds.all { it in ids })
        }
    }
    private fun DataOutputStream.text(value: String) { require(validUnicode(value)); val bytes = value.toByteArray(Charsets.UTF_8); require(bytes.size <= 32768); writeInt(bytes.size); write(bytes) }
    private fun DataOutputStream.optionalText(value: String?) { writeBoolean(value != null); value?.let { text(it) } }
    private fun DataOutputStream.optionalLong(value: Long?) { writeBoolean(value != null); value?.let(::writeLong) }
    private fun DataOutputStream.texts(values: Collection<String>) { writeInt(values.size); values.forEach { text(it) } }
    private fun DataInputStream.text(maxCharacters: Int): String {
        val size = readInt(); require(size in 0..minOf(32768, maxCharacters * 4) && size <= available()); val bytes = ByteArray(size); readFully(bytes)
        val value = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); require(value.length <= maxCharacters); return value
    }
    private fun DataInputStream.optionalText(maxCharacters: Int): String? = if (boolean()) text(maxCharacters) else null
    private fun DataInputStream.optionalLong(): Long? = if (boolean()) readLong() else null
    private fun DataInputStream.boolean(): Boolean = when (readUnsignedByte()) {
        0 -> false; 1 -> true; else -> throw IllegalArgumentException("CHECKPOINT_BOOLEAN")
    }
    private fun validUnicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char.isHighSurrogate()) {
                if (index == value.length || !value[index++].isLowSurrogate()) return false
            } else if (char.isLowSurrogate()) return false
        }
        return true
    }
    private fun DataInputStream.count(max: Int): Int = readInt().also { require(it in 0..max) }
    private fun DataInputStream.textSet(max: Int, characters: Int): Set<String> = texts(max, characters).let {
        require(it.distinct().size == it.size); it.toSet()
    }
    private fun DataInputStream.texts(max: Int, characters: Int): List<String> = List(count(max)) { text(characters) }
}
