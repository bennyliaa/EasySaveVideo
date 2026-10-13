import java.security.MessageDigest
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.easysavevideo.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.easysavevideo.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 8
        versionName = "0.8.0-ui-dev"
        testInstrumentationRunner = "com.easysavevideo.app.validation.OwnedVerificationInstrumentation"
        // Explicit build identity only. A channel does not select billing or authorize release.
        val distributionChannel = providers.gradleProperty("esv.distribution.channel").orElse("unconfigured").get()
        require(distributionChannel in setOf("unconfigured", "google_play", "huawei", "xiaomi", "oppo", "vivo", "tencent_myapp")) {
            "Unsupported ESV distribution channel; use an exact documented build value"
        }
        buildConfigField("String", "DISTRIBUTION_CHANNEL", "\"$distributionChannel\"")
        val commonsContact = providers.gradleProperty("esv.commons.contact")
            .orElse(providers.environmentVariable("ESV_COMMONS_CONTACT")).orElse("").get()
        require(commonsContact.isEmpty() || (commonsContact.length <= 256 &&
            commonsContact.all { it.code in 33..126 && it !in "()\\\"" })) { "Invalid Commons service contact" }
        buildConfigField("String", "COMMONS_CONTACT", "\"$commonsContact\"")
        buildConfigField("boolean", "COMMONS_ENABLED", (commonsContact.isNotEmpty() &&
            providers.gradleProperty("esv.commons.enabled").orElse("true").get() == "true").toString())
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true; checkReleaseBuilds = true }
}
dependencies {
    debugImplementation("com.squareup.okhttp3:okhttp:4.12.0") {
        // Already reviewed runtime bytes; only Debug adapter compilation needs direct visibility.
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation(project(":core-model"))
    implementation(project(":core-domain"))
    implementation(project(":discovery"))
    implementation(project(":commons-provider"))
    implementation(project(":searxng-provider"))
    implementation(project(":verification-client"))
    implementation(project(":verification-local"))
    implementation(project(":save"))
    implementation(project(":recording"))
    implementation(project(":library"))
    implementation(project(":hls"))
    // Exact reviewed dependency admission only. Vault remains unimplemented and disabled.
    implementation("com.google.crypto.tink:tink-android:1.23.0")
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    // Align all app compilation/runtime configurations with the four audited runtime migrations.
    // These constraints do not add modules or force an older dependency over Media3's requirements.
    constraints {
        implementation("androidx.concurrent:concurrent-futures:1.2.0")
        implementation("com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    }
    implementation(project(":ui-theme"))
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.compose.material3:material3-window-size-class:1.3.2")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    testImplementation("junit:junit:4.13.2")
}


// JVM contract tests also read these project files directly, outside their classpath.
// Keep this list aligned with the resource/source contract tests in src/test/kotlin.
// final_*.xml is intentionally a tree: FinalResourceContractTest enumerates that set.
val contractResourceInputs = fileTree("src/main/res") {
    for (locale in listOf("values", "values-zh-rCN")) {
        for (resource in listOf(
            "save_runtime_notice.xml", "save_authentication_navigation.xml", "save_display.xml", "discovery_body.xml", "input_clear.xml",
            "ordinary_destination.xml", "ordinary_destination_display.xml", "browser_copy.xml", "browser_permission_notice.xml", "save_wifi.xml", "editor_defaults.xml", "video_presence.xml", "browser_js_dialog.xml", "new_tab_search.xml", "directory_batch.xml", "discovery_copy.xml", "discovery_layout.xml",
            "discovery_power.xml", "filter_copy.xml", "finite_i18n.xml", "save_failure_guidance.xml",
            "library_batch_export.xml", "library_multi.xml", "provider_copy.xml",
            "provider_dialog.xml", "region_validation.xml", "responsive_panes.xml", "final_*.xml"
        )) include("$locale/$resource")
    }
}
val contractSourceInputs = files(
    "src/main/kotlin/com/easysavevideo/app/save/SaveRuntimeNotice.kt",
    "src/main/kotlin/com/easysavevideo/app/save/SaveRuntimeNoticeResources.kt",
    "src/main/kotlin/com/easysavevideo/app/recording/RecordingScreen.kt",
    "src/main/kotlin/com/easysavevideo/app/theme/EsvDiagnosticNotice.kt",
    "src/main/kotlin/com/easysavevideo/app/save/SaveAuthenticationNavigation.kt",
    "src/main/kotlin/com/easysavevideo/app/save/SaveAuthenticationGuidance.kt",
    "src/main/kotlin/com/easysavevideo/app/save/SaveDisplayFormatting.kt",
    "src/main/kotlin/com/easysavevideo/app/save/SaveDisplayResources.kt",
    "src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryBodyPresentation.kt",
    "src/main/kotlin/com/easysavevideo/app/discovery/DiscoveryFilterPresentationPolicy.kt",
    "src/main/kotlin/com/easysavevideo/app/ui/InputClearButton.kt",
    "src/main/kotlin/com/easysavevideo/app/ui/InputClearPolicy.kt",
    "src/main/kotlin/com/easysavevideo/app/theme/EsvComposeTheme.kt",
    "src/main/kotlin/com/easysavevideo/app/ui/shell/EsvAppShell.kt",
    "src/main/kotlin/com/easysavevideo/app/ui/shell/ShellNavigationLayout.kt",
    "src/main/kotlin/com/easysavevideo/app/ui/PendingCandidateCards.kt",
    "src/main/kotlin/com/easysavevideo/app/editor/ui/EditorScreen.kt",
    "src/main/kotlin/com/easysavevideo/app/recording/ui/RecordingSegmentsCard.kt",
    "src/main/kotlin/com/easysavevideo/app/ui/ProviderManagementScreen.kt",
    "src/main/kotlin/com/easysavevideo/app/library/destination/OrdinaryDestinationUi.kt",
    "src/main/kotlin/com/easysavevideo/app/library/destination/OrdinaryDestinationResources.kt",
    "src/main/kotlin/com/easysavevideo/app/library/destination/OrdinaryDestinationPresentation.kt",
    "src/main/kotlin/com/easysavevideo/app/library/destination/AndroidOrdinaryDestinationRuntime.kt",
    "src/main/kotlin/com/easysavevideo/app/save/SaveTaskScreen.kt",
    "src/main/kotlin/com/easysavevideo/app/storage/AndroidStores.kt",
    "src/main/kotlin/com/easysavevideo/app/storage/BrowserPersistence.kt",
    "src/main/kotlin/com/easysavevideo/app/save/AndroidSaveNetworkBackend.kt",
    "src/main/kotlin/com/easysavevideo/app/save/stream/AndroidHlsStreamPort.kt",
    "src/main/kotlin/com/easysavevideo/app/editor/AndroidEditRuntime.kt",
    "src/main/kotlin/com/easysavevideo/app/discovery/AndroidDiscoveryController.kt",
    "src/main/kotlin/com/easysavevideo/app/ui/DiscoveryScreen.kt",
    "src/main/kotlin/com/easysavevideo/app/browser/SystemBrowserHost.kt",
    "src/main/kotlin/com/easysavevideo/app/browser/BrowserInspectionDocument.kt",
    "src/main/kotlin/com/easysavevideo/app/browser/BrowserVideoPresenceScript.kt",
    "src/main/kotlin/com/easysavevideo/app/ui/BrowserScreen.kt",
    "src/main/kotlin/com/easysavevideo/app/ui/BrowserNoticeResources.kt",
    "src/main/kotlin/com/easysavevideo/app/ui/shell/EsvLocaleHost.kt",
    "src/main/kotlin/com/easysavevideo/app/ui/BrowserJsDialog.kt",
    "src/main/kotlin/com/easysavevideo/app/save/AndroidSaveRuntime.kt",
    "src/main/kotlin/com/easysavevideo/app/save/PageSaveDetector.kt",
    "src/main/kotlin/com/easysavevideo/app/save/PageSaveCandidateIndices.kt",
    "src/main/kotlin/com/easysavevideo/app/save/SaveFailureGuidanceUi.kt",
    "src/main/kotlin/com/easysavevideo/app/MainActivity.kt",
    "src/main/kotlin/com/easysavevideo/app/ui/shell/EsvSettingsScreen.kt",
    "src/main/kotlin/com/easysavevideo/app/library/LibraryScreen.kt",
    "src/main/kotlin/com/easysavevideo/app/library/LibraryBatchExportDialog.kt",
    "src/main/kotlin/com/easysavevideo/app/licenses/ThirdPartyNotices.kt",
    "src/main/assets/third-party-notices.txt"
)
tasks.withType<Test>().configureEach {
    inputs.files(contractResourceInputs).withPropertyName("contractResourceInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(contractSourceInputs).withPropertyName("contractSourceInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register("runtimeInventory") {
    dependsOn(":core-model:jar", ":core-domain:jar", ":ui-theme:jar", ":discovery:jar", ":save:jar", ":recording:jar", ":library:jar", ":hls:jar", ":commons-provider:jar", ":searxng-provider:jar", ":verification-client:jar")
    doLast {
        listOf("debugRuntimeClasspath", "releaseRuntimeClasspath").forEach { name ->
            val output = layout.buildDirectory.file("reports/$name-inventory.tsv").get().asFile
            output.parentFile.mkdirs()
            output.writeText(configurations.getByName(name).resolvedConfiguration.resolvedArtifacts
                .sortedBy { it.moduleVersion.id.toString() }.joinToString("\n", postfix = "\n") { artifact ->
                    val digest = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes())
                        .joinToString("") { byte -> "%02x".format(byte) }
                    "${artifact.moduleVersion.id}\t${artifact.file.name}\t$digest\t${artifact.file.absolutePath}"
                })
            println("Runtime inventory: ${output.absolutePath}")
        }
    }
}


// Exact runtime bytes must remain in the reviewed set. A new candidate cannot silently
// enter an implementation merely because dependency resolution succeeded.
val verifyRuntimeAdmission by tasks.registering {
    doLast {
        val admitted = rootProject.file("third_party/admitted-runtime.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
        listOf("debugRuntimeClasspath", "releaseRuntimeClasspath").forEach { variant ->
            val actual = configurations.getByName(variant).resolvedConfiguration.resolvedArtifacts
                .filterNot { it.id.componentIdentifier is org.gradle.api.artifacts.component.ProjectComponentIdentifier }
                .map { artifact ->
                    val digest = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes())
                        .joinToString("") { byte -> "%02x".format(byte) }
                    "${artifact.moduleVersion.id}\t${artifact.file.name}\t$digest"
                }.toSet()
            check(actual == admitted) { "Unreviewed runtime graph/bytes for $variant. Review the exact candidate license and update admission evidence before integration." }
        }
        println("License admission: exact ${admitted.size} reviewed runtime artifacts verified for both variants")
    }
}
tasks.configureEach {
    if (name == "preBuild") dependsOn(verifyRuntimeAdmission)
}
