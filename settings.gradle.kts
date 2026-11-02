pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Exact official Coil core-only graph byte projection; bounded reviewed versions only.
        maven {
            url = uri("vendor/coil-maven")
            content {
                includeVersion("androidx.appcompat", "appcompat-resources", "1.7.1")
                includeVersion("androidx.core", "core-ktx", "1.15.0")
                includeVersion("androidx.core", "core", "1.15.0")
                includeVersion("androidx.exifinterface", "exifinterface", "1.4.1")
                includeVersion("androidx.profileinstaller", "profileinstaller", "1.4.1")
                includeVersion("androidx.tracing", "tracing", "1.2.0")
                includeVersion("androidx.vectordrawable", "vectordrawable-animated", "1.1.0")
                includeVersion("androidx.vectordrawable", "vectordrawable", "1.1.0")
                includeVersion("com.squareup.okio", "okio-jvm", "3.15.0")
                includeVersion("com.squareup.okio", "okio", "3.15.0")
                includeVersion("io.coil-kt.coil3", "coil-core-android", "3.3.0")
                includeVersion("io.coil-kt.coil3", "coil-core", "3.3.0")
                includeVersion("org.jetbrains.kotlinx", "kotlinx-coroutines-android", "1.10.2")
                includeVersion("org.jetbrains.kotlinx", "kotlinx-coroutines-bom", "1.10.2")
                includeVersion("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2")
                includeVersion("org.jetbrains.kotlinx", "kotlinx-coroutines-core", "1.10.2")
                includeVersion("org.jetbrains.kotlinx", "kotlinx-coroutines-guava", "1.10.2")
            }
        }
        maven { url = uri("vendor/maven") }; maven {
            url = uri("vendor/webkit-maven")
            content { includeVersion("androidx.webkit", "webkit", "1.17.1") }
        }; maven {
            url = uri("vendor/tink-maven")
            content {
                includeVersion("com.google.crypto.tink", "tink-android", "1.23.0")
                includeVersion("com.google.code.gson", "gson", "2.13.2")
                includeVersion("com.google.code.gson", "gson-parent", "2.13.2")
                includeVersion("com.google.errorprone", "error_prone_annotations", "2.41.0")
                includeVersion("com.google.errorprone", "error_prone_parent", "2.41.0")
                includeVersion("org.sonatype.oss", "oss-parent", "7")
            }
        }; maven {
            url = uri("vendor/media3-maven")
            content {
                includeVersion("androidx.concurrent", "concurrent-futures", "1.2.0")
                includeVersion("androidx.exifinterface", "exifinterface", "1.3.6")
                includeVersion("androidx.media3", "media3-common", "1.11.1")
                includeVersion("androidx.media3", "media3-container", "1.11.1")
                includeVersion("androidx.media3", "media3-database", "1.11.1")
                includeVersion("androidx.media3", "media3-datasource", "1.11.1")
                includeVersion("androidx.media3", "media3-decoder", "1.11.1")
                includeVersion("androidx.media3", "media3-effect", "1.11.1")
                includeVersion("androidx.media3", "media3-exoplayer", "1.11.1")
                includeVersion("androidx.media3", "media3-exoplayer-hls", "1.11.1")
                includeVersion("androidx.media3", "media3-extractor", "1.11.1")
                includeVersion("androidx.media3", "media3-muxer", "1.11.1")
                includeVersion("androidx.media3", "media3-transformer", "1.11.1")
                includeVersion("com.google.code.findbugs", "jsr305", "3.0.2")
                includeVersion("com.google.errorprone", "error_prone_annotations", "2.28.0")
                includeVersion("com.google.errorprone", "error_prone_parent", "2.28.0")
                includeVersion("com.google.guava", "failureaccess", "1.0.2")
                includeVersion("com.google.guava", "guava", "33.3.1-android")
                includeVersion("com.google.guava", "guava-parent", "26.0-android")
                includeVersion("com.google.guava", "guava-parent", "33.3.1-android")
                includeVersion("com.google.guava", "listenablefuture", "9999.0-empty-to-avoid-conflict-with-guava")
                includeVersion("com.google.j2objc", "j2objc-annotations", "3.0.0")
                includeVersion("org.checkerframework", "checker-qual", "3.43.0")
                includeVersion("org.jetbrains.kotlinx", "kotlinx-coroutines-android", "1.9.0")
                includeVersion("org.jetbrains.kotlinx", "kotlinx-coroutines-bom", "1.9.0")
                includeVersion("org.jetbrains.kotlinx", "kotlinx-coroutines-core", "1.9.0")
                includeVersion("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0")
                includeVersion("org.jetbrains.kotlinx", "kotlinx-coroutines-guava", "1.9.0")
                includeVersion("org.sonatype.oss", "oss-parent", "7")
                includeVersion("org.sonatype.oss", "oss-parent", "9")

            }
        }; google(); mavenCentral() }
}
rootProject.name = "EasySaveVideo"
include(":core-model", ":core-domain", ":ui-theme", ":discovery", ":save", ":recording", ":library", ":hls", ":commons-provider", ":searxng-provider", ":verification-client", ":app")
// JVM-only B parsing evaluation; not yet an Android runtime dependency.
include(":verification-local")
// Explicit local QA carrier only; never a production app dependency.
if (providers.gradleProperty("esv.navdiagnostics").orNull == "true") include(":nav-diagnostics")
// Library modules live under modules/; project paths (":core-model" etc.) are unchanged.
rootProject.children.filter { it.name != "app" }.forEach { it.projectDir = file("modules/${it.name}") }
