package com.easysavevideo.library

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

object LibraryIds {
    private val pattern = Regex("[A-Za-z0-9_-]{1,128}")
    fun requireValid(value: String) {
        if (!pattern.matches(value)) throw LibraryFailure(LibraryError.INVALID_ID)
    }
}

/**
 * Ordinary display names use NFC, then an NFC-normalized Locale.ROOT lowercase sibling key.
 * This is deterministic case normalization, not full Unicode linguistic equivalence/case folding.
 * Names are never file paths. Leading/trailing whitespace and invisible controls are rejected.
 */
object LibraryNames {
    fun validate(value: String): String {
        val normalized = normalize(value, LibraryLimits.MAX_NAME_UTF8_BYTES, LibraryError.INVALID_NAME)
        if (normalized.isBlank() || normalized != normalized.trim() || normalized == "." || normalized == ".." ||
            normalized.any { it == '/' || it == '\\' }) throw LibraryFailure(LibraryError.INVALID_NAME)
        return normalized
    }

    fun key(value: String): String = caseKey(validate(value))

    /** Literal substring key; a SQL adapter must bind and escape LIKE wildcards independently. */
    fun searchKey(value: String): String = caseKey(normalize(value, LibraryLimits.MAX_SEARCH_UTF8_BYTES, LibraryError.INVALID_QUERY))

    private fun caseKey(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFC)

    private fun normalize(value: String, maxBytes: Int, error: LibraryError): String {
        // Bound work before normalization and before conversion to UTF-8.
        if (value.length > maxBytes) throw LibraryFailure(error)
        var index = 0
        while (index < value.length) {
            val current = value[index]
            if (Character.isLowSurrogate(current) ||
                (Character.isHighSurrogate(current) && (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])))) {
                throw LibraryFailure(error)
            }
            val codePoint = Character.codePointAt(value, index)
            val category = Character.getType(codePoint)
            if (category == Character.CONTROL.toInt() || category == Character.FORMAT.toInt() ||
                category == Character.LINE_SEPARATOR.toInt() || category == Character.PARAGRAPH_SEPARATOR.toInt() ||
                codePoint in 0xFDD0..0xFDEF || (codePoint and 0xFFFF) in 0xFFFE..0xFFFF) {
                throw LibraryFailure(error)
            }
            index += Character.charCount(codePoint)
        }
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
        if (normalized.toByteArray(Charsets.UTF_8).size > maxBytes) throw LibraryFailure(error)
        return normalized
    }
}

/** Pure helpers shared by in-memory fakes and storage adapters. */
object LibraryQueryPolicy {
    fun fingerprint(query: LibraryQuery): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Length prefixes avoid delimiter ambiguities. A root folder has a distinct null tag.
        fun field(value: String?) {
            if (value == null) {
                digest.update(ByteBuffer.allocate(4).putInt(-1).array())
            } else {
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
            }
        }
        field("ordinary-library-query-v1")
        field(query.folderId); field(LibraryNames.searchKey(query.text))
        field(query.sort.name); field(query.direction.name); field(query.pageSize.toString())
        return digest.digest().joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xFF) }
    }

    fun requireCurrentCursor(query: LibraryQuery, catalogVersion: Long) {
        require(catalogVersion >= 0)
        query.cursor?.let { cursor ->
            if (cursor.catalogVersion != catalogVersion || cursor.queryKey != fingerprint(query)) {
                throw LibraryFailure(LibraryError.STALE_PAGE)
            }
        }
    }

    /** Unknown duration sorts last in either direction; opaque ID always breaks ties ascending. */
    fun comparator(sort: LibrarySort, direction: SortDirection): Comparator<MediaItem> = Comparator { left, right ->
        val base = when (sort) {
            LibrarySort.NAME -> LibraryNames.key(left.displayName).compareTo(LibraryNames.key(right.displayName))
            LibrarySort.DATE -> left.createdAtMillis.compareTo(right.createdAtMillis)
            LibrarySort.SIZE -> left.sizeBytes.compareTo(right.sizeBytes)
            LibrarySort.DURATION -> when {
                left.durationMillis == null && right.durationMillis == null -> 0
                left.durationMillis == null -> return@Comparator 1
                right.durationMillis == null -> return@Comparator -1
                else -> left.durationMillis.compareTo(right.durationMillis)
            }
        }
        val directed = if (direction == SortDirection.ASCENDING) base else -base
        if (directed == 0) left.itemId.compareTo(right.itemId) else directed
    }

    /** Bounded output helper for tests/small fakes; production storage should page inside SQL. */
    fun page(items: Collection<MediaItem>, query: LibraryQuery, catalogVersion: Long): LibraryPage {
        requireCurrentCursor(query, catalogVersion)
        val search = LibraryNames.searchKey(query.text)
        val ordered = items.asSequence()
            .filter { it.lifecycle == MediaLifecycle.READY && it.folderId == query.folderId }
            .filter { LibraryNames.key(it.displayName).contains(search) }
            .sortedWith(comparator(query.sort, query.direction)).toList()
        val offset = query.cursor?.offset ?: 0
        if (offset > ordered.size) throw LibraryFailure(LibraryError.STALE_PAGE)
        val end = minOf(ordered.size.toLong(), offset.toLong() + query.pageSize).toInt()
        val next = if (end < ordered.size) LibraryCursor(catalogVersion, fingerprint(query), end) else null
        return LibraryPage(ordered.subList(offset, end).toList(), catalogVersion, next)
    }
}

object LibraryFolderPolicy {
    /**
     * Validate a logical move against a consistent, bounded ordinary-folder snapshot. Root is null
     * and cannot be moved/deleted. Existing corrupt cycles/dangling parents fail closed as recovery.
     */
    fun requireMoveAllowed(folderId: String, parentId: String?, folders: Collection<LibraryFolder>) {
        LibraryIds.requireValid(folderId); parentId?.let(LibraryIds::requireValid)
        val byId = snapshot(folders)
        if (folderId !in byId) throw LibraryFailure(LibraryError.NOT_FOUND)
        if (parentId != null && parentId !in byId) throw LibraryFailure(LibraryError.NOT_FOUND)
        var cursor = parentId
        var parentDepth = 0
        val ancestors = mutableSetOf<String>()
        while (cursor != null) {
            if (cursor == folderId) throw LibraryFailure(LibraryError.FOLDER_CYCLE)
            if (!ancestors.add(cursor)) throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
            parentDepth += 1
            cursor = byId[cursor]?.parentId ?: if (cursor in byId) null else throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
        }
        val children = byId.values.groupBy { it.parentId }
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.addLast(folderId to 1)
        var subtreeHeight = 1
        while (pending.isNotEmpty()) {
            val (id, depth) = pending.removeFirst()
            if (!visited.add(id)) throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
            subtreeHeight = maxOf(subtreeHeight, depth)
            children[id]?.forEach { pending.addLast(it.folderId to depth + 1) }
        }
        if (parentDepth + subtreeHeight > LibraryLimits.MAX_FOLDER_DEPTH) throw LibraryFailure(LibraryError.DEPTH_LIMIT)
        requireUniqueName(parentId, byId.getValue(folderId).displayName, folders, folderId)
    }

    fun requireCreateAllowed(parentId: String?, displayName: String, folders: Collection<LibraryFolder>) {
        parentId?.let(LibraryIds::requireValid)
        val byId = snapshot(folders)
        if (byId.size >= LibraryLimits.MAX_FOLDERS) throw LibraryFailure(LibraryError.FOLDER_LIMIT)
        requireUniqueName(parentId, displayName, folders)
        var cursor = parentId
        var depth = 1
        val visited = mutableSetOf<String>()
        while (cursor != null) {
            if (!visited.add(cursor)) throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
            val folder = byId[cursor] ?: throw LibraryFailure(LibraryError.NOT_FOUND)
            depth += 1
            cursor = folder.parentId
        }
        if (depth > LibraryLimits.MAX_FOLDER_DEPTH) throw LibraryFailure(LibraryError.DEPTH_LIMIT)
    }

    fun requireUniqueName(parentId: String?, displayName: String, folders: Collection<LibraryFolder>, excludingFolderId: String? = null) {
        val key = LibraryNames.key(displayName)
        if (folders.any { it.parentId == parentId && it.folderId != excludingFolderId && LibraryNames.key(it.displayName) == key }) {
            throw LibraryFailure(LibraryError.ALREADY_EXISTS)
        }
    }

    private fun snapshot(folders: Collection<LibraryFolder>): Map<String, LibraryFolder> {
        if (folders.size > LibraryLimits.MAX_FOLDERS) throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
        val byId = folders.associateBy { it.folderId }
        if (byId.size != folders.size) throw LibraryFailure(LibraryError.RECOVERY_REQUIRED)
        return byId
    }
}

object LibraryItemPolicy {
    /** Call under the same storage-owner lock as lease acquisition and authoritative resolution. */
    fun requireReadable(item: MediaItem, ref: MediaReadRef) {
        if (item.itemId != ref.itemId || item.lifecycle != MediaLifecycle.READY) throw LibraryFailure(LibraryError.NOT_FOUND)
        if (item.revision != ref.revision || item.representationGeneration != ref.representationGeneration) {
            throw LibraryFailure(LibraryError.STALE_REVISION)
        }
    }

    fun requireExpectedRevision(item: MediaItem, expectedRevision: Long) {
        require(expectedRevision > 0)
        if (item.lifecycle != MediaLifecycle.READY) throw LibraryFailure(LibraryError.NOT_FOUND)
        if (item.revision != expectedRevision) throw LibraryFailure(LibraryError.STALE_REVISION)
    }

    fun requireUniqueName(folderId: String?, displayName: String, items: Collection<MediaItem>, excludingItemId: String? = null) {
        val key = LibraryNames.key(displayName)
        if (items.any { it.lifecycle == MediaLifecycle.READY && it.folderId == folderId &&
                it.itemId != excludingItemId && LibraryNames.key(it.displayName) == key }) {
            throw LibraryFailure(LibraryError.ALREADY_EXISTS)
        }
    }
}
