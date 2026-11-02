plugins { kotlin("jvm"); `java-library` }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    api(project(":discovery"))
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0") {
        // Reuse the project's reviewed Kotlin runtime, without legacy JDK bridge artifacts.
        exclude(group = "org.jetbrains.kotlin")
    }
    constraints {
        // Match the already admitted Android graph for the same HTTP implementation on the JVM.
        implementation("com.squareup.okio:okio:3.15.0")
        implementation("com.squareup.okio:okio-jvm:3.15.0")
    }
    testImplementation("junit:junit:4.13.2")
}
