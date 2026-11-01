package com.easysavevideo.app.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Checks scope copy and unchanged consent wiring; does not claim runtime verification acceptance. */
class VerificationCandidateScopeContractTest {
    private fun ui() = File("src/main/kotlin/com/easysavevideo/app/ui/VerificationServiceSettings.kt").readText()
    private fun resources(locale: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val nodes = factory.newDocumentBuilder().parse(File("src/main/res/$locale/verification_candidate_scope.xml"))
            .getElementsByTagName("string")
        return (0 until nodes.length).associate { index -> nodes.item(index).let {
            it.attributes.getNamedItem("name").nodeValue to it.textContent
        } }
    }
    @Test fun oldOneCandidateAndNoTemporaryAdmissionClaimsAreRemoved() {
        val source = ui()
        for (old in listOf("每批最多尝试验证一个候选", "不自动接纳搜索结果或跳转目标", "默认没有来源准入",
            "尚无已批准的本地验证来源；需要先确定具体来源及访问授权")) assertFalse(old, source.contains(old))
        val en = resources("values"); val zh = resources("values-zh-rCN")
        for (term in listOf("explicit search", "bounded number", "public HTTPS", "shared search-session budget", "Robots", "login", "DRM", "SSRF"))
            assertTrue(term, en.getValue("verification_candidate_bounded_search").contains(term))
        for (term in listOf("主动查找", "共享预算", "有限数量", "公共 HTTPS", "robots", "登录", "DRM", "SSRF"))
            assertTrue(term, zh.getValue("verification_candidate_bounded_search").contains(term))
        assertTrue(en.getValue("verification_candidate_empty_catalog").contains("does not mean local candidate verification"))
        assertTrue(zh.getValue("verification_candidate_empty_catalog").contains("不表示主动搜索的本地候选验证不可用"))
        assertTrue(en.getValue("verification_candidate_evidence_limits").contains("not guaranteed"))
        assertTrue(zh.getValue("verification_candidate_evidence_limits").contains("验证成功不作保证"))
    }
    @Test fun resourcePairsAreUsedAndRegisteredAsContractInputs() {
        val en = resources("values"); val zh = resources("values-zh-rCN")
        val source = ui(); val formats = Regex("%(?:[0-9]+\\$)?[dsf]")
        assertEquals(en.keys, zh.keys); assertEquals(9, en.size)
        en.keys.forEach { key ->
            assertTrue(key, source.contains("R.string.$key"))
            assertEquals(key, formats.findAll(en.getValue(key)).map { it.value }.toList(),
                formats.findAll(zh.getValue(key)).map { it.value }.toList())
        }
        val build = File("build.gradle.kts").readText()
        assertTrue(build.contains("\"verification_candidate_scope.xml\""))
        assertTrue(build.contains("\"src/main/kotlin/com/easysavevideo/app/ui/VerificationServiceSettings.kt\""))
    }
    @Test fun remoteSessionConsentAndCompiledSelectionControlsRemainExplicit() {
        val source = ui()
        for (required in listOf("controller.configureLocalSources(controller.claim(), localIds, localAcknowledged)",
            "controller.disable(controller.claim())", "current && valid && acknowledged",
            "if (controller.enable(claim, url, dynamic, acknowledged)) dismiss()",
            "不会附加你的搜索词、浏览器 Cookie 或登录凭据", "本开关不授权部署、付费或绕过网站限制"))
            assertTrue(required, source.contains(required))
    }
}
