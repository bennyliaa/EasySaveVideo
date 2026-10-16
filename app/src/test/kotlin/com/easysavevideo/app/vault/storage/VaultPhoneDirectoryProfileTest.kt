package com.easysavevideo.app.vault.storage

import org.junit.Assert.*
import org.junit.Test

class VaultPhoneDirectoryProfileTest {
    private val provider = VaultPhoneFilesProfile.Provider(VaultPhoneFilesProfile.AUTHORITY,
        VaultPhoneFilesProfile.PACKAGE, VaultPhoneFilesProfile.CLASS, true, true, true, true, true,
        VaultPhoneFilesProfile.MANAGE_DOCUMENTS, VaultPhoneFilesProfile.MANAGE_DOCUMENTS)
    private val directory = VaultPhoneFilesProfile.Document("primary:Movies", VaultPhoneFilesProfile.DIRECTORY_MIME, false, true)
    @Test fun exactPrimaryDirectoryMustSupportCreateAndNeverBeVirtual() {
        assertTrue(VaultPhoneFilesProfile.acceptsDirectory(provider, directory))
        listOf(directory.copy(id = "primary:"), directory.copy(id = "other:Movies"), directory.copy(virtual = true),
            directory.copy(directoryCreate = false), directory.copy(mime = "video/mp4"), directory.copy(id = "primary:Movies/../Other"))
            .forEach { assertFalse(VaultPhoneFilesProfile.acceptsDirectory(provider, it)) }
        assertFalse(VaultPhoneFilesProfile.acceptsDirectory(provider.copy(androidSignature = false), directory))
    }
    @Test fun ancestryDoesNotProveImmediateParent() {
        assertTrue(VaultPhoneFilesProfile.directChild("primary:Movies", "primary:Movies/file.mp4"))
        listOf("primary:Movies/sub/file.mp4", "primary:Movies2/file.mp4", "primary:Other/file.mp4", "primary:Movies/.",
            "primary:Movies/../file.mp4", "primary:Movies//file.mp4", "primary:Movies/", "primary:Movies")
            .forEach { assertFalse(VaultPhoneFilesProfile.directChild("primary:Movies", it)) }
    }
    @Test fun childSnapshotBoundsIncludeTotalUtf8Bytes() {
        assertEquals(1000, VaultPhoneFilesProfile.boundedChildren("primary:Movies", List(1000) { "primary:Movies/$it" }).size)
        try {
            VaultPhoneFilesProfile.boundedChildren("primary:Movies", List(200) { "primary:Movies/$it" + "x".repeat(8000) })
            fail("Expected total-byte rejection")
        } catch (_: IllegalArgumentException) { }
    }
}
