plugins {
    kotlin("jvm") version "2.2.10" apply false
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
allprojects {
    group = "com.easysavevideo"; version = "0.3.2-commons-dev"
    dependencyLocking { lockAllConfigurations() }
}
