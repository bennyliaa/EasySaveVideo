import java.security.MessageDigest
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.easysavevideo.navdiagnostics"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { applicationId = "com.easysavevideo.navdiagnostics"; minSdk = 31; targetSdk = 36; versionCode = 1; versionName = "1-nav-qa" }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = true }
}
val admittedLines = rootProject.file("third_party/admitted-runtime.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.foundation:foundation")
    // Root KMP coordinates aligned with the reviewed Android/JVM variants; never admit old metadata.
    implementation("androidx.compose.runtime:runtime:1.8.3")
    implementation("androidx.compose.runtime:runtime-saveable:1.8.3")
    implementation("androidx.compose.ui:ui:1.8.3")
    implementation("androidx.collection:collection:1.5.0")
    constraints { admittedLines.map { it.substringBefore('\t') }.distinct().forEach { implementation(it) } }
}
val verifyDiagnosticAdmission = tasks.register("verifyDiagnosticAdmission") {
    doLast {
        val admitted = admittedLines.toSet()
        listOf("debugRuntimeClasspath", "releaseRuntimeClasspath").forEach { variant ->
            val actual = configurations.getByName(variant).resolvedConfiguration.resolvedArtifacts.map { a ->
                val digest = MessageDigest.getInstance("SHA-256").digest(a.file.readBytes()).joinToString("") { "%02x".format(it) }
                "${a.moduleVersion.id}\t${a.file.name}\t$digest"
            }.toSet()
            check(actual.isNotEmpty() && actual.all { it in admitted }) { "Diagnostic graph contains unreviewed artifact/version/bytes" }
            println("Diagnostic admitted subset: $variant ${actual.size}")
        }
    }
}
tasks.configureEach { if (name == "preBuild") dependsOn(verifyDiagnosticAdmission) }
