package com.easysavevideo.app.save

import com.easysavevideo.app.R
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Pure XML/source fixtures only. Actual Compose rendering, live locale changes, and device layout are NOT_RUN. */
class SaveRuntimeNoticeResourcesTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val fixtures = SaveRuntimeNoticeFixtures
    private val capacityKey = "save_runtime_notice_task_capacity_reached"
    private val knownStrings = (fixtures.fixed.values + fixtures.detection.values + fixtures.failures.values)
        .associateBy { it.resource }
    private val en by lazy { resourceMap("values", "save_runtime_notice.xml") }
    private val zh by lazy { resourceMap("values-zh-rCN", "save_runtime_notice.xml") }
    private val networkEn by lazy { resourceMap("values", "save_wifi.xml") }
    private val networkZh by lazy { resourceMap("values-zh-rCN", "save_wifi.xml") }

    private fun resourceMap(locale: String, file: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = factory.newDocumentBuilder().parse(File(root, "res/$locale/$file"))
        val result = linkedMapOf<String, String>()
        val strings = document.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val node = strings.item(i)
            assertNull(result.put(node.attributes.getNamedItem("name").nodeValue, node.textContent))
        }
        val plurals = document.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val node = plurals.item(i)
            val name = node.attributes.getNamedItem("name").nodeValue
            for (j in 0 until node.childNodes.length) {
                val item = node.childNodes.item(j)
                if (item.nodeName == "item") assertNull(result.put(
                    "$name/" + item.attributes.getNamedItem("quantity").nodeValue, item.textContent))
            }
        }
        return result
    }

    private fun source(name: String) = File(root, "kotlin/com/easysavevideo/app/save/$name.kt").readText()
    private fun resolvedFixture(resource: Int, locale: Locale): String {
        val key = knownStrings.getValue(resource).key
        val current = if (locale.language == "zh") zh + networkZh else en + networkEn
        return current.getValue(key)
    }

    /** This fixture applies the pure mapper and explicit locale; it does not execute Compose or Android resources. */
    private fun renderFixture(notice: SaveRuntimeNotice, locale: Locale): String = when (notice) {
        is SaveRuntimeNotice.Fixed -> resolvedFixture(saveRuntimeFixedNoticeResource(notice.kind), locale)
        is SaveRuntimeNotice.DetectionUnavailable -> resolvedFixture(saveRuntimeDetectionNoticeResource(notice.reason), locale)
        is SaveRuntimeNotice.Failure -> resolvedFixture(saveRuntimeFailureNoticeResource(notice.code), locale)
        is SaveRuntimeNotice.TaskCapacityReached -> {
            val current = if (locale.language == "zh") zh else en
            val quantity = if (locale.language != "zh" && notice.maxUnfinishedTasks == 1) "one" else "other"
            String.format(locale, current.getValue("$capacityKey/$quantity"),
                saveDisplayInteger(notice.maxUnfinishedTasks.toLong(), locale))
        }
    }

    @Test fun exactBilingualKeySetHasSeventySixStringsAndOneCapacityPlural() {
        val expected = knownStrings.values.map { it.key }.filter { it.startsWith("save_runtime_notice_") }.toSet()
        assertEquals(76, expected.size)
        assertEquals(expected, en.keys.filter { '/' !in it }.toSet())
        assertEquals(expected, zh.keys.filter { '/' !in it }.toSet())
        assertEquals(setOf("$capacityKey/one", "$capacityKey/other"), en.keys.filter { '/' in it }.toSet())
        assertEquals(setOf("$capacityKey/other"), zh.keys.filter { '/' in it }.toSet())
        assertEquals(78, en.size)
        assertEquals(77, zh.size)
        assertEquals(78, knownStrings.size)
        for ((key, text) in en.entries + zh.entries) {
            assertTrue(key, text.isNotBlank())
            assertFalse(key, text.contains('\uFFFD'))
        }
    }

    @Test fun everyMapperRetainsItsExactResourceAndAllSeventyNineChineseGroups() {
        for ((kind, text) in fixtures.fixed) {
            assertEquals(kind.name, text.resource, saveRuntimeFixedNoticeResource(kind))
            assertEquals(kind.name, text.chinese, resolvedFixture(text.resource, Locale.SIMPLIFIED_CHINESE))
        }
        for ((reason, text) in fixtures.detection) {
            assertEquals(reason.name, text.resource, saveRuntimeDetectionNoticeResource(reason))
            assertEquals(reason.name, text.chinese, resolvedFixture(text.resource, Locale.SIMPLIFIED_CHINESE))
        }
        for ((code, text) in fixtures.failures) {
            assertEquals(code.name, text.resource, saveRuntimeFailureNoticeResource(code))
            assertEquals(code.name, text.chinese, resolvedFixture(text.resource, Locale.SIMPLIFIED_CHINESE))
        }
        assertEquals(fixtures.capacityChinese, zh.getValue("$capacityKey/other"))
        assertEquals(79, (knownStrings.values.map { it.chinese } + fixtures.capacityChinese).toSet().size)
        assertEquals(37, SaveRuntimeFixedNotice.entries.map(::saveRuntimeFixedNoticeResource).toSet().size)
        assertEquals(6, PageSaveDetection.Reason.entries.map(::saveRuntimeDetectionNoticeResource).toSet().size)
        assertEquals(35, SaveRuntimeFailureCode.entries.map(::saveRuntimeFailureNoticeResource).toSet().size)
    }

    @Test fun onlyTheTwoVerbatimNetworkResourcesAreReused() {
        assertEquals(R.string.save_wifi_required, saveRuntimeFixedNoticeResource(SaveRuntimeFixedNotice.WIFI_REQUIRED))
        assertEquals(R.string.save_wifi_settings_unavailable,
            saveRuntimeFixedNoticeResource(SaveRuntimeFixedNotice.WIFI_SETTINGS_UNAVAILABLE))
        assertEquals(R.string.save_runtime_notice_failure_wifi_required,
            saveRuntimeFailureNoticeResource(SaveRuntimeFailureCode.WIFI_REQUIRED))
        val network = resolvedFixture(R.string.save_wifi_required, Locale.SIMPLIFIED_CHINESE)
        val detailed = resolvedFixture(R.string.save_runtime_notice_failure_wifi_required, Locale.SIMPLIFIED_CHINESE)
        assertEquals("正在等待已确认的默认 Wi-Fi 网络。请连接 Wi-Fi，或关闭“仅通过 Wi-Fi 保存”后手动重试。", network)
        assertEquals("正在等待已确认的默认 Wi-Fi 网络。连接 Wi-Fi 或关闭“仅通过 Wi-Fi 保存”后手动重试。", detailed)
        assertNotEquals(network, detailed)
    }

    @Test fun matchedParametersAreConfinedToTheSingleCapturedCapacityInteger() {
        val parameter = Regex("""%[0-9]+[$]s""")
        for ((key, text) in en) {
            val counterpart = zh[key] ?: zh.getValue(key.replace("/one", "/other"))
            assertEquals(key, parameter.findAll(text).map { it.value }.toList(),
                parameter.findAll(counterpart).map { it.value }.toList())
            if (key.startsWith("$capacityKey/")) {
                assertEquals(key, listOf("%1\$s"), parameter.findAll(text).map { it.value }.toList())
                assertEquals(key, 1, text.count { it == '%' })
                assertEquals(key, 1, counterpart.count { it == '%' })
            } else {
                assertFalse(key, text.contains('%'))
                assertFalse(key, counterpart.contains('%'))
            }
        }
    }

    @Test fun capacityOneOneHundredAndOneThousandHaveCorrectPluralAndCurrentLocaleDigits() {
        val expectedDigits = mapOf(
            Locale.US to listOf("1", "100", "1000"),
            Locale.SIMPLIFIED_CHINESE to listOf("1", "100", "1000"),
            Locale.GERMANY to listOf("1", "100", "1000"),
            Locale.forLanguageTag("ar-EG") to listOf("١", "١٠٠", "١٠٠٠"),
        )
        for ((locale, digits) in expectedDigits) {
            for ((index, maximum) in listOf(1, 100, 1000).withIndex()) {
                val notice = SaveRuntimeNotice.TaskCapacityReached(maximum)
                val actual = renderFixture(notice, locale)
                assertEquals(digits[index], saveDisplayInteger(maximum.toLong(), locale))
                if (locale.language == "zh") {
                    assertEquals("最多同时保留 ${digits[index]} 条未完成任务，当前已满。已有媒体未被删除。", actual)
                } else {
                    val noun = if (maximum == 1) "task" else "tasks"
                    assertEquals("At most ${digits[index]} unfinished $noun can be retained at a time. " +
                        "Capacity is currently full. Existing media has not been deleted.", actual)
                }
                assertEquals(maximum, notice.maxUnfinishedTasks)
            }
        }
    }

    @Test fun pureLocaleFixturesKeepEveryTokenUnchangedThroughEnglishChineseEnglish() {
        val notices: List<SaveRuntimeNotice> = SaveRuntimeFixedNotice.entries.map { SaveRuntimeNotice.Fixed(it) } +
            PageSaveDetection.Reason.entries.map { SaveRuntimeNotice.DetectionUnavailable(it) } +
            SaveRuntimeFailureCode.entries.map { SaveRuntimeNotice.Failure(it) } +
            listOf(1, 100, 1000).map { SaveRuntimeNotice.TaskCapacityReached(it) }
        assertEquals(81, notices.size)
        for (notice in notices) {
            val held = notice
            val before = notice.toString()
            val hash = notice.hashCode()
            val firstEnglish = renderFixture(notice, Locale.US)
            val chinese = renderFixture(notice, Locale.SIMPLIFIED_CHINESE)
            val secondEnglish = renderFixture(notice, Locale.US)
            assertNotEquals(firstEnglish, chinese)
            assertEquals(firstEnglish, secondEnglish)
            assertSame(held, notice)
            assertEquals(before, notice.toString())
            assertEquals(hash, notice.hashCode())
        }
    }

    @Test fun englishRetainsManualRecoveryPrivacyAndMediaTruthClauses() {
        val requiredClauses = mapOf(
            "save_runtime_notice_method_disabled" to listOf("turned off in Settings", "new tasks", "Existing tasks are unchanged"),
            "save_runtime_notice_stream_entitlement_required" to listOf("valid entitlement", "development version", "no real purchase"),
            "save_runtime_notice_direct_task_accepted" to listOf("accepted", "progress"),
            "save_runtime_notice_stream_task_accepted" to listOf("selected quality fixed", "only after", "standalone MP4", "verification"),
            "save_runtime_notice_recording_restart_requires_fresh_consent" to listOf("old consent cannot be reused", "Explicitly start", "old task"),
            "save_runtime_notice_stream_reselect_required" to listOf("fixed quality", "new task starts over", "old task can be cancelled"),
            "save_runtime_notice_source_reselect_after_restart" to listOf("full source address is not retained", "detect and select", "from the beginning"),
            "save_runtime_notice_same_origin_login_required" to listOf("same-origin", "resume manually"),
            "save_runtime_notice_recording_foreground_start_rejected" to listOf("old consent has been discarded", "Start again manually"),
            "save_runtime_notice_recording_native_release_unconfirmed" to listOf("not been confirmed released", "foreground state and files", "another recording cannot start", "fully stop the app"),
            "save_runtime_notice_preview_recording_unavailable" to listOf("No preview was acquired", "recording has not started"),
            "save_runtime_notice_failure_wifi_required" to listOf("confirmed default Wi-Fi", "retry manually"),
            "save_runtime_notice_failure_cancelled" to listOf("will not automatically switch"),
            "save_runtime_notice_failure_tls_rejected" to listOf("Certificate checks were not bypassed"),
            "save_runtime_notice_failure_network_timeout" to listOf("Late results will not be committed"),
            "save_runtime_notice_failure_network_unavailable" to listOf("bounded retries"),
            "save_runtime_notice_failure_unsupported_media" to listOf("Network saving", "live streams", "encryption", "separate tracks", "format changes", "transcoding"),
            "save_runtime_notice_failure_invalid_media" to listOf("actual media verification", "not added to the library", "successfully saved"),
            "save_runtime_notice_failure_invalid_range" to listOf("resume identity", "Mismatched bytes were not joined"),
            "save_runtime_notice_failure_resource_release_unconfirmed" to listOf("Task files are retained", "late results will not be committed"),
            "save_runtime_notice_failure_commit_mismatch" to listOf("verified again", "retained for recovery"),
            "save_runtime_notice_failure_http_rejected" to listOf("No automatic switch"),
            "save_runtime_notice_failure_audio_permission_denied" to listOf("Explicitly choose", "without audio", "or cancel"),
            "save_runtime_notice_failure_capture_unavailable" to listOf("old consent will not be reused"),
            "save_runtime_notice_failure_audio_failed" to listOf("No automatic switch", "microphone", "without audio"),
            "save_runtime_notice_failure_finalization_failed" to listOf("not fully finalized", "No finished video was published"),
        )
        for ((key, clauses) in requiredClauses) {
            for (clause in clauses) assertTrue("$key: $clause", en.getValue(key).contains(clause))
        }
        assertTrue(zh.getValue("save_runtime_notice_failure_audio_permission_denied").contains("必须明确选择无声音重新开始，或取消"))
        assertFalse(zh.getValue("save_runtime_notice_failure_unsupported_media").contains("录制和转码不受支持"))
        assertTrue(zh.getValue("save_runtime_notice_failure_unsupported_media").contains("网络保存不支持直播、加密、分轨、格式变化或转码"))
    }

    @Test fun rendererUsesCurrentComposeResourcesAndHasNoStateOrTaskAuthority() {
        val text = source("SaveRuntimeNoticeResources")
        assertTrue(text.contains("@Composable\ninternal fun saveRuntimeNoticeText(notice: SaveRuntimeNotice): String"))
        assertTrue(text.contains("is SaveRuntimeNotice.Fixed -> stringResource(saveRuntimeFixedNoticeResource(notice.kind))"))
        assertTrue(text.contains("is SaveRuntimeNotice.DetectionUnavailable -> stringResource(saveRuntimeDetectionNoticeResource(notice.reason))"))
        assertTrue(text.contains("is SaveRuntimeNotice.Failure -> stringResource(saveRuntimeFailureNoticeResource(notice.code))"))
        assertTrue(text.contains("R.plurals.save_runtime_notice_task_capacity_reached,\n        notice.maxUnfinishedTasks,\n        saveIntegerText(notice.maxUnfinishedTasks.toLong())"))
        assertTrue(source("SaveDisplayResources").contains("LocalConfiguration.current.locales"))
        val imports = Regex("(?m)^import (.+)$").findAll(text).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("androidx.compose.runtime.Composable", "androidx.compose.ui.res.pluralStringResource",
            "androidx.compose.ui.res.stringResource", "com.easysavevideo.app.R"), imports)
        val model = source("SaveRuntimeNotice")
        assertEquals(setOf("com.easysavevideo.save.SaveError"),
            Regex("(?m)^import (.+)$").findAll(model).map { it.groupValues[1] }.toSet())
        for (forbidden in listOf("remember", "Locale.getDefault", "application.getString", "LocalContext", "Context", "WebView",
            "Throwable", "Exception", "SaveRecord", "SourceUrl", "SaveRequest", "SaveErrors.classify", "mutableStateOf",
            "LaunchedEffect", "SideEffect", "Handler", "Executor", "Coroutine", "Flow", "SharedPreferences", "Bundle",
            "SavedStateHandle", "JSONObject", "Log.", "println", "coerce", "activeTaskCount", "checkAdmission",
            "coordinator", "networkPolicy", "recordingService", ".resume(", ".retry(", ".submit(", ".cancel(", ".inspect(")) {
            assertFalse(forbidden, (text + model).contains(forbidden))
        }
        assertFalse(Regex("""\b(?:var|suspend)\b""").containsMatchIn(text + model))
        assertFalse(Regex("""[\u4e00-\u9fff]""").containsMatchIn(text + model))
        assertFalse(model.contains("else ->"))
        assertFalse(text.contains("else ->"))
    }
}
