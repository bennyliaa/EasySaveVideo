package com.easysavevideo.app.vault.storage

import org.junit.Assert.*
import org.junit.Test

/** Public fixed adapter facts only. Does not validate any Android/API/OEM provider in production. */
class VaultPhoneFilesProfileTest {
    private val accepted = VaultPhoneFilesProfile.Provider(VaultPhoneFilesProfile.AUTHORITY,
        VaultPhoneFilesProfile.PACKAGE, VaultPhoneFilesProfile.CLASS, true, true, true, true, true,
        VaultPhoneFilesProfile.MANAGE_DOCUMENTS, VaultPhoneFilesProfile.MANAGE_DOCUMENTS)
    @Test fun exactFiniteSystemPrimaryMp4ProfileIsNecessary() {
        assertTrue(VaultPhoneFilesProfile.accepts(accepted, "primary:Movies/fixed.mp4", "video/mp4", false))
    }
    @Test fun everyUnverifiedProviderPropertyFailsClosed() {
        listOf(accepted.copy(authority = "cloud.provider"), accepted.copy(packageName = "other"),
            accepted.copy(className = "other"), accepted.copy(system = false), accepted.copy(androidSignature = false),
            accepted.copy(enabled = false), accepted.copy(exported = false), accepted.copy(grantUriPermissions = false),
            accepted.copy(readPermission = null), accepted.copy(writePermission = null)).forEach {
            assertFalse(VaultPhoneFilesProfile.accepts(it, "primary:Movies/fixed.mp4", "video/mp4", false))
        }
    }
    @Test fun rootDirectoryVirtualUnknownMimeAndOtherVolumeAreRejected() {
        listOf("primary:", "secondary:Movies/fixed.mp4", "raw:/storage/a.mp4", "primary", "primary:" + "a".repeat(8192)).forEach {
            assertFalse(VaultPhoneFilesProfile.accepts(accepted, it, "video/mp4", false))
        }
        assertFalse(VaultPhoneFilesProfile.accepts(accepted, "primary:Movies", "vnd.android.document/directory", false))
        assertFalse(VaultPhoneFilesProfile.accepts(accepted, "primary:fixed.mp4", "video/mp4", true))
        assertFalse(VaultPhoneFilesProfile.accepts(accepted, "primary:fixed.mp4", null, false))
    }
}
