package com.easysavevideo.app.library.export

import com.easysavevideo.app.library.share.ShareError
import com.easysavevideo.library.MediaReadRef
import com.easysavevideo.library.export.*
import org.junit.Assert.*
import org.junit.Test

class ExportPresentationTest {
    private val request = ExportRequest("export-op", MediaReadRef("item", 1, 1), ExportDestination(ExportKind.SAF, "grant"))
    @Test fun noticesDoNotPromiseIndependentlyDeletedOriginalStillExists() {
        val success = ExportRecord(request, ExportPhase.SUCCEEDED, ExportOutput("export-op", ExportKind.SAF), ExportEvidence(1, "a".repeat(64)),
            verification = ExportVerification.DESTINATION_VERIFIED)
        assertTrue(exportNotice(success).contains("本次操作不会删除"))
        assertFalse(exportNotice(success).contains("仍保留"))
    }
    @Test fun cancelledPickerDoesNotClaimUnknownDocumentCleaned() {
        val notice = exportNotice(ExportRecord(request, ExportPhase.CANCELLED, cancelRequested = true))
        assertTrue(notice.contains("可能留下空文档"))
        assertFalse(notice.contains("已移除"))
    }
    @Test fun unverifiedRowsCarryIndependentIdentity() {
        val a = UnverifiedExportView("op-a", request.source, "First · op-a", true, "待核对")
        val b = UnverifiedExportView("op-b", request.source, "Second · op-b", false, "手动核对")
        val state = ExportViewState(unverified = listOf(a, b), unverifiedCount = 2, unverifiedPages = 1)
        assertEquals(a, state.unverified.single { it.operationId == "op-a" })
        assertFalse(state.unverified.single { it.operationId == "op-b" }.canAttemptCleanup)
        assertFalse(a.toString().contains("First"))
    }
    @Test fun copyCapacityHasCleanupActionInsteadOfPretendRecovery() {
        val notice = shareFailureNotice(ShareError.COPY_CAPACITY)
        assertTrue(notice.contains("撤销")); assertTrue(notice.contains("实际关闭"))
        assertFalse(notice.contains("重新启动"))
    }
    @Test fun oversizePointsAtOrdinaryExport() {
        assertTrue(shareFailureNotice(ShareError.SOURCE_TOO_LARGE).contains("系统视频库"))
    }
}
