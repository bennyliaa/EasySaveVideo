package com.easysavevideo.app.save

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Source/manifest gates only: these never stand in for executing Android instrumentation. */
class EvaluationExportInstrumentationContractTest {
    private val androidTest = File("src/androidTest")
    @Test fun phaseIsExplicitAndReturnsBeforeUnrelatedNetworkFixtures() {
        val runner=File(androidTest,"kotlin/com/easysavevideo/app/validation/OwnedVerificationInstrumentation.kt").readText()
        assertTrue(runner.contains("phase=args?.getString(\"phase\")?:\"b\""))
        val branch=runner.indexOf("if (phase == \"evaluation-export\") { EvaluationExportValidation.run(this); return }")
        assertTrue(branch >= 0)
        assertTrue(branch < runner.indexOf("val server=ServerSocket"))
    }
    @Test fun testProvidersNeverAppearInProductionManifests() {
        val factory=DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }
        val doc=factory.newDocumentBuilder().parse(File(androidTest,"AndroidManifest.xml"))
        val providers=doc.getElementsByTagName("provider")
        assertEquals(2,providers.length)
        val authorities=mutableSetOf<String>()
        for(i in 0 until providers.length) {
            val p=providers.item(i) as org.w3c.dom.Element
            val authority=p.getAttributeNS("http://schemas.android.com/apk/res/android","authorities")
            assertTrue(authority.startsWith("com.easysavevideo.app.test.evaluation."))
            assertTrue(authorities.add(authority))
            assertEquals(":evaluationOwned",p.getAttributeNS("http://schemas.android.com/apk/res/android","process"))
        }
        for (variant in listOf("main","debug","release")) {
            val f=File("src/$variant/AndroidManifest.xml")
            if(f.exists())for(authority in authorities)assertFalse(f.readText().contains(authority))
        }
    }
    @Test fun fixtureCodeHasNoNetworkRecursiveDeletionOrPersistentGrants() {
        val files=androidTest.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") && it.name.contains("Evaluation") }.toList()
        assertTrue(files.isNotEmpty())
        val text=files.joinToString("\n"){it.readText()}
        for(forbidden in listOf("deleteRecursively", "takePersistableUriPermission", "HttpURLConnection", "ServerSocket", "Runtime.getRuntime", "ProcessBuilder"))
            assertFalse(forbidden,text.contains(forbidden))
        assertTrue(text.contains("AndroidEvaluationDocumentPort.fromResult"))
        assertTrue(text.contains("SYNTHETIC_FIXTURE"))
    }
    @Test fun standaloneProviderProcessUsesOnlyPlatformAndJavaClasses() {
        val files=File(androidTest,"java").walkTopDown().filter { it.isFile && it.extension=="java" }.toList()
        assertTrue(files.isNotEmpty())
        for(file in files)for(line in file.readLines().filter { it.startsWith("import ") }) {
            assertTrue(line,line.startsWith("import android.") || line.startsWith("import java."))
        }
        assertEquals(1_048_576,com.easysavevideo.discovery.DiscoverySaveEvaluationJson.MAX_EXPORT_BYTES)
    }
    @Test fun acceptanceChecklistSeparatesCompilationFromDeviceExecution() {
        val text=File("../docs/discovery/EVALUATION_EXPORT_DEVICE_ACCEPTANCE_20261018.md").readText()
        for(required in listOf("UNEXECUTED", "NOT REPRODUCED", "rotate/recreate", "close-failure", "nonempty", "EXTRA_LOCAL_ONLY", "same-size"))
            assertTrue(required,text.contains(required))
    }
}
