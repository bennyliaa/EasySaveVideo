package com.easysavevideo.app.library

import androidx.compose.foundation.checkScrollableContainerConstraints
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.unit.Constraints
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Real Foundation checker + source hierarchy guard; not an instrumented Compose render. */
class LibraryBatchDialogScrollTest {
    @Test fun nestedVerticalScrollContentHeightReproducesReportedFoundationException() {
        try {
            checkScrollableContainerConstraints(Constraints(maxHeight=Constraints.Infinity),Orientation.Vertical)
            fail("Nested scroll must fail with the parent's unbounded content height")
        } catch (failure:IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("infinity maximum height"))
        }
    }
    @Test fun boundedDialogViewportAcceptsVerticalScrollConstraints() {
        for (height in listOf(160,360,720)) {
            checkScrollableContainerConstraints(Constraints(maxHeight=height),Orientation.Vertical)
        }
    }
    @Test fun batchDialogHasExactlyOneBoundedScrollOwner() {
        fun source(name:String):String {
            val relative="src/main/kotlin/com/easysavevideo/app/library/$name"
            val path=Path.of(relative).takeIf { Files.exists(it) } ?: Path.of("app",relative)
            return String(Files.readAllBytes(path), Charsets.UTF_8)
        }
        val parent=source("LibraryScreen.kt").substringAfter("internal fun LibraryDialog(").substringBefore("/** Foundation field")
        assertTrue(parent.contains("heightIn(max = 720.dp)"))
        assertEquals(1,Regex("\\.verticalScroll\\(").findAll(parent).count())
        val child=source("LibraryBatchExportDialog.kt")
        assertTrue(child.contains("LibraryDialog(onDismiss)"))
        assertFalse(Regex("verticalScroll\\s*\\(").containsMatchIn(child))
        assertFalse(child.contains("LazyColumn("))
    }
}
