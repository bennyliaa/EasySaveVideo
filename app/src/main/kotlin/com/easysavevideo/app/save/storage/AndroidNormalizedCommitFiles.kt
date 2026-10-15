package com.easysavevideo.app.save.storage

import com.easysavevideo.save.SaveError
import com.easysavevideo.save.SaveFailure
import java.io.File
import java.security.MessageDigest

/**
 * Descriptor-owned implementation for the SAME staging/workspace/media directories as ordinary
 * Save. No legacy File API is called. All syscalls run under an already installed reservation,
 * outside storage monitors. Every descriptor/DirectoryStream gets an obligation before opening.
 */
internal class AndroidNormalizedCommitFiles(
    private val root: File,
    private val reservations: NormalizationIoReservations,
    private val durable: DurableFileOps,
    private val io: NormalizationDescriptorIo = AndroidNormalizationDescriptorIo,
) : NormalizedCommitFiles {
    private data class Parents(val fixed: List<Pair<File, NormalizationDescriptorIo.Stat>>,
        val workspace: Boolean, val generation: Boolean)
    private fun staging() = File(root, "staging")
    private fun media() = File(root, "media")
    private fun workspace(key: NormalizationKey) = File(staging(), key.owner.artifactId + ".normalization")
    private fun generation(key: NormalizationKey) = File(workspace(key), key.normalizationId)
    private fun path(key: NormalizationKey, mediaId: String, role: NormalizedCommitRole): File {
        normalizationId(mediaId)
        return when (role) {
            NormalizedCommitRole.PRIMARY -> File(staging(), key.owner.artifactId + ".part")
            NormalizedCommitRole.RAW -> File(generation(key), "raw.bin")
            NormalizedCommitRole.OUTPUT -> File(generation(key), "output.mp4")
            NormalizedCommitRole.FINAL -> File(media(), "$mediaId.mp4")
        }
    }
    private fun directory(key: NormalizationKey, role: NormalizedCommitDirectory) = when (role) {
        NormalizedCommitDirectory.STAGING -> staging(); NormalizedCommitDirectory.MEDIA -> media()
        NormalizedCommitDirectory.WORKSPACE -> workspace(key); NormalizedCommitDirectory.GENERATION -> generation(key)
    }
    private fun check(key: NormalizationKey, token: NormalizationIoReservations.Token) {
        reservations.requireExact(token, owner = key.owner, key = key)
        token.requireLive()
        if (token.registry !== reservations || token.key != key || token.owner != key.owner ||
            token.stage !in setOf(NormalizationIoReservations.Stage.COMMIT, NormalizationIoReservations.Stage.PUBLICATION,
                NormalizationIoReservations.Stage.CLEANUP)) reject()
    }
    override fun inspect(key: NormalizationKey, mediaId: String, includeFinal: Boolean,
        token: NormalizationIoReservations.Token): NormalizedCommitLayout {
        check(key, token)
        val parents = parents(key, token)
        val device = parents.fixed.first().second.device
        val selected = NormalizedCommitRole.entries.map { role ->
            if (role == NormalizedCommitRole.FINAL && !includeFinal) null else path(key, mediaId, role)
        }
        val before = selected.map { leaf -> token.requireLive(); leaf?.let(io::lstat) }
        val values = selected.map { leaf -> leaf?.let { fingerprint(it, device, token) } }
        // A role hashed earlier must still be the same file after every later hash/close.
        for (index in selected.indices) {
            token.requireLive()
            if (selected[index]?.let(io::lstat) != before[index]) reject()
        }
        validateParents(parents, token)
        val after = parents(key, token) // Fresh bounded namespace enumeration after all descriptor closes.
        if (parents.fixed.map { it.first } != after.fixed.map { it.first }) reject()
        validateParents(parents, token)
        for (index in selected.indices) {
            token.requireLive()
            if (selected[index]?.let(io::lstat) != before[index]) reject()
        }
        check(key, token)
        return NormalizedCommitLayout(values[0], values[1], values[2], values[3], parents.workspace, parents.generation,
            parents.fixed.map { it.second })
    }
    private fun parents(key: NormalizationKey, token: NormalizationIoReservations.Token): Parents {
        val fixed = mutableListOf<Pair<File, NormalizationDescriptorIo.Stat>>()
        for (path in listOf(root, staging(), media())) {
            token.requireLive(); val stat = io.lstat(path) ?: reject()
            requireDirectory(stat); fixed += path to stat
        }
        val device = fixed.first().second.device
        if (fixed.any { it.second.device != device } || fixed.map { it.second.device to it.second.inode }.distinct().size != fixed.size) reject()
        for (name in listOf(key.owner.artifactId + ".restart", key.owner.artifactId + ".hls")) {
            token.requireLive(); if (io.lstat(File(staging(), name)) != null) reject()
        }
        token.requireLive(); val work = io.lstat(workspace(key))
        token.requireLive(); val gen = io.lstat(generation(key))
        if (work == null && gen != null) reject()
        if (work != null) {
            requireDirectory(work); if (work.device != device) reject(); fixed += workspace(key) to work
            val names = children(workspace(key), 1, token)
            if (names.any { it != key.normalizationId } || names.distinct().size != names.size || (gen != null) != names.contains(key.normalizationId)) reject()
        }
        if (gen != null) {
            requireDirectory(gen); if (gen.device != device) reject(); fixed += generation(key) to gen
            val names = children(generation(key), 2, token)
            if (names.any { it != "raw.bin" && it != "output.mp4" } || names.distinct().size != names.size) reject()
            for (name in listOf("raw.bin", "output.mp4")) {
                token.requireLive()
                if ((io.lstat(File(generation(key), name)) != null) != names.contains(name)) reject()
            }
        }
        if (fixed.map { it.second.device to it.second.inode }.distinct().size != fixed.size) reject()
        return Parents(fixed, work != null, gen != null).also { validateParents(it, token) }
    }
    private fun validateParents(parents: Parents, token: NormalizationIoReservations.Token) {
        for ((index, pair) in parents.fixed.withIndex()) {
            val (path, expected) = pair
            token.requireLive(); val current = io.lstat(path) ?: reject()
            requireDirectory(current)
            // Only root/staging/media are shared. The owned workspace/generation must remain
            // completely unchanged while observed; namespace mutations are checked after IO.
            if (if (index < 3) !current.sameInode(expected) else current != expected) reject()
        }
    }
    private fun children(path: File, maximum: Int, token: NormalizationIoReservations.Token): List<String> {
        val obligation = reservations.retain(token)
        var closeConfirmed = false
        try {
            val names = io.children(path, maximum, closeConfirmed = { closeConfirmed = true; reservations.release(obligation) })
            if (!closeConfirmed) { reservations.markUncertain(token, obligation); reject() }
            if (names.size > maximum) reject()
            return names
        } catch (failure: Throwable) {
            if (!closeConfirmed) reservations.markUncertain(token, failure)
            throw failure
        }
    }
    private fun fingerprint(path: File, device: Long, token: NormalizationIoReservations.Token): NormalizedFileObservation? {
        token.requireLive(); val before = io.lstat(path) ?: return null
        requireRegular(before, device)
        val result = descriptor(path, NormalizationDescriptorIo.Open.READ, token) { input ->
            token.requireLive(); if (io.fstat(input) != before) reject()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024); var at = 0L
            while (at < before.size) {
                token.requireLive()
                val count = io.pread(input, buffer, 0, minOf(buffer.size.toLong(), before.size - at).toInt(), at)
                if (count <= 0 || count > minOf(buffer.size.toLong(), before.size - at)) reject()
                digest.update(buffer, 0, count); at += count
            }
            token.requireLive()
            if (io.pread(input, buffer, 0, 1, at) > 0 || io.fstat(input) != before || io.lstat(path) != before) reject()
            NormalizedFileObservation(NormalizationFingerprint(at, digest.digest().joinToString("") { "%02x".format(it) }),
                NormalizationCleanupIdentity(before.device, before.inode))
        }
        token.requireLive(); if (io.lstat(path) != before) reject()
        return result
    }
    private fun <T> descriptor(path: File, mode: NormalizationDescriptorIo.Open,
        token: NormalizationIoReservations.Token, block: (NormalizationDescriptorIo.Handle) -> T): T {
        val obligation = reservations.retain(token)
        val input = try { io.open(path, mode) }
        catch (failure: NormalizationOpenRejected) {
            reservations.release(obligation)
            token.requireLive()
            throw failure.failure
        } catch (failure: Throwable) {
            reservations.markUncertain(token, failure); throw failure
        }
        try { return block(input) }
        finally {
            // Close has no cancellation check and is attempted exactly once by its owner.
            try { io.close(input) }
            catch (failure: Throwable) { reservations.markUncertain(token, input); reservations.markUncertain(token, failure); throw failure }
            // Positive close return is insufficient if the descriptor remains demonstrably valid.
            val stillValid = try { io.valid(input) } catch (failure: Throwable) {
                reservations.markUncertain(token, input); reservations.markUncertain(token, failure); throw failure
            }
            if (stillValid) { reservations.markUncertain(token, input); reject() }
            reservations.release(obligation)
        }
    }
    override fun syncCanonical(key: NormalizationKey, mediaId: String, final: Boolean,
        expected: NormalizedFileObservation, token: NormalizationIoReservations.Token) {
        check(key, token); val p = parents(key, token)
        val path = path(key, mediaId, if (final) NormalizedCommitRole.FINAL else NormalizedCommitRole.PRIMARY)
        if (fingerprint(path, p.fixed.first().second.device, token) != expected) reject()
        token.requireLive(); val before = io.lstat(path) ?: reject()
        descriptor(path, NormalizationDescriptorIo.Open.READ, token) { fd ->
            token.requireLive(); val stat = io.fstat(fd)
            if (stat != before) reject()
            requireRegular(stat, expected.identity.device)
            if (stat.inode != expected.identity.inode || stat.size != expected.fingerprint.size || io.lstat(path) != stat) reject()
            token.requireLive(); io.fsync(fd)
            token.requireLive(); if (io.fstat(fd) != stat || io.lstat(path) != stat) reject()
        }
        validateParents(p, token)
        token.requireLive(); if (io.lstat(path) != before) reject()
    }
    override fun promote(key: NormalizationKey, mediaId: String, expected: NormalizedFileObservation,
        token: NormalizationIoReservations.Token) {
        check(key, token); if (token.stage != NormalizationIoReservations.Stage.COMMIT) reject()
        val parents = parents(key, token)
        val before = inspect(key, mediaId, true, token)
        if (before.primary != expected || before.final != null) reject()
        validateParents(parents, token); token.requireLive()
        durable.promoteNoReplace(path(key, mediaId, NormalizedCommitRole.PRIMARY), path(key, mediaId, NormalizedCommitRole.FINAL))
        token.requireLive(); validateParents(parents, token)
        val moved = io.lstat(path(key, mediaId, NormalizedCommitRole.FINAL)) ?: reject()
        requireRegular(moved, expected.identity.device)
        if (moved.inode != expected.identity.inode || moved.size != expected.fingerprint.size ||
            io.lstat(path(key, mediaId, NormalizedCommitRole.PRIMARY)) != null) reject()
    }
    override fun delete(key: NormalizationKey, mediaId: String, role: NormalizedCommitRole,
        expected: NormalizedFileObservation, token: NormalizationIoReservations.Token) {
        check(key, token); if (token.stage != NormalizationIoReservations.Stage.CLEANUP) reject()
        val p = parents(key, token); val target = path(key, mediaId, role)
        token.requireLive(); val before = io.lstat(target) ?: reject()
        if (fingerprint(target, p.fixed.first().second.device, token) != expected) reject()
        validateParents(p, token); token.requireLive()
        if (io.lstat(target) != before) reject()
        io.unlink(target)
        token.requireLive(); if (io.lstat(target) != null) reject()
    }
    override fun sync(key: NormalizationKey, directory: NormalizedCommitDirectory, token: NormalizationIoReservations.Token) {
        check(key, token); val path = directory(key, directory)
        token.requireLive(); val before = io.lstat(path) ?: reject(); requireDirectory(before)
        descriptor(path, NormalizationDescriptorIo.Open.DIRECTORY, token) { fd ->
            token.requireLive(); if (!io.fstat(fd).sameInode(before)) reject()
            io.fsync(fd)
            token.requireLive(); if (io.lstat(path)?.sameInode(before) != true || !io.fstat(fd).sameInode(before)) reject()
        }
        token.requireLive(); if (io.lstat(path)?.sameInode(before) != true) reject()
    }
    override fun removeEmpty(key: NormalizationKey, directory: NormalizedCommitDirectory, token: NormalizationIoReservations.Token) {
        check(key, token)
        if (token.stage != NormalizationIoReservations.Stage.CLEANUP || directory !in setOf(NormalizedCommitDirectory.GENERATION, NormalizedCommitDirectory.WORKSPACE)) reject()
        val path = directory(key, directory); val p = parents(key, token)
        if (children(path, 0, token).isNotEmpty()) reject()
        validateParents(p, token); token.requireLive(); io.rmdir(path); token.requireLive()
    }
    private fun requireDirectory(stat: NormalizationDescriptorIo.Stat) {
        if (stat.kind != NormalizationDescriptorIo.Kind.DIRECTORY || stat.inode <= 0 || stat.device < 0) reject()
    }
    private fun requireRegular(stat: NormalizationDescriptorIo.Stat, device: Long) {
        if (stat.kind != NormalizationDescriptorIo.Kind.REGULAR || stat.links != 1L || stat.device != device ||
            stat.inode <= 0 || stat.size !in 0..NORMALIZATION_MAX_BYTES) reject()
    }
    private fun reject(): Nothing = throw SaveFailure(SaveError.RECOVERY_REQUIRED)
}
