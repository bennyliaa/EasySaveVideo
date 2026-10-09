plugins { kotlin("jvm"); `java-library` }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies { api(project(":discovery")); testImplementation("junit:junit:4.13.2") }

// Offline/owned-fixture interoperability only. Never participates in app production wiring.
tasks.register("writeFixtureClasspath") {
    dependsOn(tasks.testClasses)
    doLast {
        layout.buildDirectory.file("fixture-classpath.txt").get().asFile.writeText(
            sourceSets.test.get().runtimeClasspath.asPath + "\n")
    }
}
