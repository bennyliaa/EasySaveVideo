package com.easysavevideo.app.library

import androidx.compose.ui.Modifier
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import com.easysavevideo.app.R
import com.easysavevideo.library.LibrarySort
import com.easysavevideo.library.SortDirection
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Exercises the real resource mapper and actual Compose semantics nodes, without a device. */
@RunWith(Parameterized::class)
class LibraryDisplayControlsTest(private val sort: LibrarySort, private val direction: SortDirection, private val grid: Boolean) {
    @Test fun currentStateAndNextActionRemainDistinctAcrossEveryDisplayCombination() {
        assertEquals(when (sort) {
            LibrarySort.NAME -> R.string.finite_sort_name
            LibrarySort.DATE -> R.string.finite_sort_date
            LibrarySort.DURATION -> R.string.finite_sort_duration
            LibrarySort.SIZE -> R.string.finite_sort_size
        }, librarySortLabel(sort))
        val selected = LibrarySort.entries.filter { candidate ->
            val semantics = semantics(Modifier.librarySortSelection(candidate, sort))
            semantics[SemanticsProperties.Selected]
        }
        assertEquals(listOf(sort), selected)
        assertEquals(if (direction == SortDirection.ASCENDING) R.string.library_order_ascending else R.string.library_order_descending,
            libraryDirectionLabel(direction))
        assertEquals(if (direction == SortDirection.ASCENDING) R.string.library_order_descend_action else R.string.library_order_ascend_action,
            libraryReverseAction(direction))
        assertEquals(if (grid) R.string.library_view_grid else R.string.library_view_list, libraryViewLabel(grid))
        assertEquals(if (grid) R.string.library_view_list_action else R.string.library_view_grid_action, libraryViewAction(grid))
        for (locale in listOf("values", "values-zh-rCN")) {
            val copy = resources(locale)
            val directionText = copy.getValue(if (direction == SortDirection.ASCENDING) "library_order_ascending" else "library_order_descending")
            val viewText = copy.getValue(if (grid) "library_view_grid" else "library_view_list")
            assertEquals(directionText, semantics(Modifier.libraryDisplayState(directionText))[SemanticsProperties.StateDescription])
            assertEquals(viewText, semantics(Modifier.libraryDisplayState(viewText))[SemanticsProperties.StateDescription])
            assertNotEquals(copy.getValue(if (grid) "library_view_grid_action" else "library_view_list_action"),
                copy.getValue(if (grid) "library_view_list_action" else "library_view_grid_action"))
        }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-{1}-grid={2}")
        fun cases(): List<Array<Any>> = LibrarySort.entries.flatMap { sort ->
            SortDirection.entries.flatMap { order -> listOf(false, true).map { grid -> arrayOf<Any>(sort, order, grid) } }
        }
        private fun semantics(modifier: Modifier): SemanticsConfiguration {
            val element = modifier.foldIn<ModifierNodeElement<*>?>(null) { found, next ->
                if (next is ModifierNodeElement<*>) next else found
            }
            val node = requireNotNull(element).create() as SemanticsModifierNode
            return SemanticsConfiguration().also { config -> with(node) { config.applySemantics() } }
        }
        internal fun resources(locale: String): Map<String, String> {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/$locale/library_display.xml"))
            val nodes = document.getElementsByTagName("string")
            return (0 until nodes.length).associate { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
        }
    }
}

class LibraryDisplayResourceTest {
    @Test fun pairedResourcesHaveExactKeysAndMatchingPlaceholders() {
        val en = LibraryDisplayControlsTest.resources("values")
        val zh = LibraryDisplayControlsTest.resources("values-zh-rCN")
        assertEquals(10, en.size)
        assertEquals(en.keys, zh.keys)
        val placeholders = Regex("%[1-9]\\$[sd]")
        for (key in en.keys) {
            assertTrue(en.getValue(key).isNotBlank()); assertTrue(zh.getValue(key).isNotBlank())
            assertEquals(placeholders.findAll(en.getValue(key)).map { it.value }.toList(),
                placeholders.findAll(zh.getValue(key)).map { it.value }.toList())
        }
        assertEquals(listOf("%1\$s", "%2\$s", "%3\$s"), placeholders.findAll(en.getValue("library_display_summary")).map { it.value }.toList())
    }
}
