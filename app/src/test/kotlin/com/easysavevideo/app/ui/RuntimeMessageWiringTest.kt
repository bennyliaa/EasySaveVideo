package com.easysavevideo.app.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source contracts only; these do not claim Android resource or locale instrumentation coverage. */
class RuntimeMessageWiringTest {
    private val root = listOf(File("."), File("app")).first { File(it, "src/main/kotlin").isDirectory }
    private fun source(path: String) = File(root, "src/main/kotlin/com/easysavevideo/app/$path").readText()

    @Test fun applicationAdapterResolvesResourcesAndLanguageInsideEveryRender() {
        val adapter = source("ui/RuntimeMessageResources.kt")
        val beforeRender = adapter.substringBefore("return RuntimeMessageText {")
        val render = adapter.substringAfter("return RuntimeMessageText {")
        assertTrue(beforeRender.contains("val application = context.applicationContext"))
        assertFalse(beforeRender.contains(".resources"))
        assertFalse(beforeRender.contains("language()"))
        assertTrue(render.contains("esvLocalizedContext(application, application.resources.configuration, language())"))
        assertTrue(render.contains(".getString(key.resource, *arguments)"))
        for (forbidden in listOf("AndroidSaveRuntime.get", "AndroidSettingsStore", "getSharedPreferences", "Locale.setDefault", "applicationLocales =", "Handler(", "storage."))
            assertFalse(forbidden, adapter.contains(forbidden))
    }

    @Test fun existingOwnerInjectsOnlyDisplayAdapterWithoutAnotherRuntimeOrLanguageOwner() {
        val owner = source("save/AndroidSaveRuntime.kt")
        val segment = source("recording/AndroidSegmentRecordingRuntime.kt")
        val editor = source("editor/AndroidEditRuntime.kt")
        assertTrue(owner.contains("val runtimeText = androidRuntimeMessageText(application) { notificationLanguage.current }"))
        assertTrue(owner.contains("runtimeText = runtimeText, activityChanged = ::publishMediaTaskActivity"))
        assertTrue(owner.contains("AndroidEditRuntime(application, storage, destinationRuntime, runtimeText) {"))
        assertTrue(editor.contains("private val text: RuntimeMessageText"))
        assertTrue(segment.contains("private val runtimeText: RuntimeMessageText"))
        assertTrue(segment.contains("errorText(EditFailure(result.error), runtimeText)"))
        assertFalse(editor.contains("AndroidSaveRuntime.get"))
        assertFalse(segment.contains("AndroidSaveRuntime.get"))
    }

    @Test fun editorUsesFreshGettersPreservesCleanupArgumentAndHasNoEmbeddedChineseCopy() {
        val editor = source("editor/AndroidEditRuntime.kt")
        for (name in listOf("entitlementNotice", "releaseNotice", "publicationCheckingNotice")) {
            assertTrue(name, editor.contains("private val $name get() = text.get("))
            assertFalse(name, editor.contains("private val $name ="))
        }
        assertTrue(editor.contains("if (it.interrupted + it.cleanupPending > 0)"))
        assertTrue(editor.contains("text.get(RuntimeMessageCopy.EDITOR_INTERRUPTED_RECOVERY, it.cleanupPending)"))
        assertTrue(editor.contains("outcome.exceptionOrNull()?.let { errorText(it, text) }"))
        assertTrue(editor.contains("errorText(EditFailure(result.error), text)"))
        assertFalse(Regex("[\\u4e00-\\u9fff]").containsMatchIn(editor))
    }

    @Test fun mainCallbacksUseCurrentSettingsAndComposeReadsLocaleHostResources() {
        val main = source("MainActivity.kt")
        val adapter = main.substringAfter("private val runtimeText = RuntimeMessageText {").substringBefore("\n    }")
        assertTrue(adapter.contains("esvLocalizedContext(this, resources.configuration, settings.appearance.language)"))
        assertTrue(adapter.contains(".getString(key.resource, *arguments)"))
        assertTrue(main.contains("EsvLocaleHost(settings.appearance.language)"))
        assertTrue(main.contains("stringResource(RuntimeMessageCopy.MAIN_DISPLAY_GEOMETRY_UNAVAILABLE.resource)"))
        assertTrue(main.contains("stringResource(RuntimeMessageCopy.MAIN_RECORDING_RECOVERY_NOT_READY.resource)"))
        assertTrue(main.contains("if (failed) browser.showNotice(if (persistence.sessionWritesPaused)"))
        assertFalse(Regex("[\\u4e00-\\u9fff]").containsMatchIn(main))
    }

    @Test fun allReadSourcesAndResourcesAreDeclaredGradleContractInputs() {
        val build = File(root, "build.gradle.kts").readText()
        val resources = build.substringAfter("val contractResourceInputs =").substringBefore("val contractSourceInputs =")
        val sources = build.substringAfter("val contractSourceInputs =").substringBefore("tasks.withType<Test>()")
        for (file in listOf("ui/RuntimeMessageCopy.kt", "ui/RuntimeMessageResources.kt", "MainActivity.kt",
            "editor/AndroidEditRuntime.kt", "save/AndroidSaveRuntime.kt", "recording/AndroidSegmentRecordingRuntime.kt"))
            assertTrue(file, sources.contains("\"src/main/kotlin/com/easysavevideo/app/$file\""))
        assertTrue(sources.contains("\"build.gradle.kts\""))
        assertTrue(resources.contains("\"values\", \"values-zh-rCN\""))
        assertTrue(resources.contains("\"runtime_messages.xml\""))
        assertTrue(resources.contains("\"editor_back.xml\""))
    }
}
