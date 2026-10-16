package com.easysavevideo.app.ui

import com.easysavevideo.app.R
import com.easysavevideo.app.recording.RecordingNotificationLanguage
import com.easysavevideo.core.model.AppLanguage
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

internal fun runtimeMessageResourceName(key: RuntimeMessageCopy): String =
    if (key == RuntimeMessageCopy.EDITOR_STOP_WAITING) "editor_back_stop_waiting"
    else "runtime_message_${key.name.lowercase(Locale.ROOT)}"

internal fun runtimeMessageResources(folder: String, file: String = "runtime_messages.xml"): Map<String, String> {
    val root = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    val nodes = factory.newDocumentBuilder().parse(File(root, "$folder/$file")).getElementsByTagName("string")
    val values = linkedMapOf<String, String>()
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        val key = node.attributes.getNamedItem("name").nodeValue
        assertNull("duplicate resource $key", values.put(key, node.textContent))
    }
    return values
}

internal fun runtimeMessageFixture(folder: String): RuntimeMessageText {
    val values = runtimeMessageResources(folder) + runtimeMessageResources(folder, "editor_back.xml")
    val locale = if (folder == "values") Locale.US else Locale.SIMPLIFIED_CHINESE
    return RuntimeMessageText { key, arguments ->
        String.format(locale, values.getValue(runtimeMessageResourceName(key)), *arguments)
    }
}

class RuntimeMessageResourcesTest {
    @Test fun finiteKeysHavePairedResourcesAndExactInterpolationContracts() {
        val en = runtimeMessageResources("values")
        val zh = runtimeMessageResources("values-zh-rCN")
        assertEquals(RuntimeMessageCopy.entries.filter { it != RuntimeMessageCopy.EDITOR_STOP_WAITING }
            .map(::runtimeMessageResourceName).toSet(), en.keys)
        assertEquals(en.keys, zh.keys)
        val placeholders = Regex("%[0-9]+\\$[sd]")
        en.keys.forEach { key ->
            assertEquals(key, placeholders.findAll(en.getValue(key)).map { it.value }.toList(),
                placeholders.findAll(zh.getValue(key)).map { it.value }.toList())
            assertFalse(key, en.getValue(key).any { it in '\u4e00'..'\u9fff' })
            assertFalse(key, en.getValue(key).contains('\uFFFD'))
            assertFalse(key, zh.getValue(key).contains('\uFFFD'))
        }
        RuntimeMessageCopy.entries.forEach { key ->
            assertEquals(key.name, R.string::class.java.getField(runtimeMessageResourceName(key)).getInt(null), key.resource)
        }
    }

    @Test fun cleanupCountIsPassedUnchangedIncludingZeroAndMaximumInt() {
        for (folder in listOf("values", "values-zh-rCN")) {
            val text = runtimeMessageFixture(folder)
            for (count in listOf(0, 1, 17, Int.MAX_VALUE)) {
                val rendered = text.get(RuntimeMessageCopy.EDITOR_INTERRUPTED_RECOVERY, count)
                assertTrue(rendered, rendered.contains(count.toString()))
                assertFalse(rendered, rendered.contains('%'))
            }
        }
        assertEquals("上次编辑被中断，原件保留。未验证的新文件不会自动入库；待清理项 17 个。",
            runtimeMessageFixture("values-zh-rCN").get(RuntimeMessageCopy.EDITOR_INTERRUPTED_RECOVERY, 17))
    }

    @Test fun existingStopWaitingCopyIsReusedInBothLanguages() {
        assertEquals(R.string.editor_back_stop_waiting, RuntimeMessageCopy.EDITOR_STOP_WAITING.resource)
        for (folder in listOf("values", "values-zh-rCN")) {
            assertEquals(runtimeMessageResources(folder, "editor_back.xml").getValue("editor_back_stop_waiting"),
                runtimeMessageFixture(folder).get(RuntimeMessageCopy.EDITOR_STOP_WAITING))
            assertFalse(runtimeMessageResources(folder).containsKey("runtime_message_editor_stop_waiting"))
        }
    }

    @Test fun newMessagesReadCurrentLanguageWhilePreviouslyStoredNoticeRemainsUnchanged() {
        val language = RecordingNotificationLanguage()
        val en = runtimeMessageFixture("values")
        val zh = runtimeMessageFixture("values-zh-rCN")
        var reads = 0
        val text = RuntimeMessageText { key, arguments ->
            reads++
            (if (language.current == AppLanguage.CHINESE) zh else en).render(key, arguments)
        }
        language.update(AppLanguage.ENGLISH)
        val stored = text.get(RuntimeMessageCopy.EDITOR_RECOVERY_INCOMPLETE)
        language.update(AppLanguage.CHINESE)
        val next = text.get(RuntimeMessageCopy.EDITOR_RECOVERY_INCOMPLETE)
        assertEquals(en.get(RuntimeMessageCopy.EDITOR_RECOVERY_INCOMPLETE), stored)
        assertEquals(zh.get(RuntimeMessageCopy.EDITOR_RECOVERY_INCOMPLETE), next)
        assertNotEquals(stored, next)
        language.update(AppLanguage.ENGLISH)
        assertEquals(stored, text.get(RuntimeMessageCopy.EDITOR_RECOVERY_INCOMPLETE))
        assertEquals(3, reads)
    }

    @Test fun everyFiniteMessageRendersInBothLanguagesWithoutFormatTokens() {
        for (folder in listOf("values", "values-zh-rCN")) {
            val text = runtimeMessageFixture(folder)
            for (key in RuntimeMessageCopy.entries) {
                val rendered = if (key == RuntimeMessageCopy.EDITOR_INTERRUPTED_RECOVERY) text.get(key, 23) else text.get(key)
                assertTrue(key.name, rendered.isNotBlank())
                assertFalse(key.name, rendered.contains('%'))
            }
        }
    }
}
