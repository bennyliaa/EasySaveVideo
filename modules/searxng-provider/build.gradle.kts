plugins { kotlin("jvm"); `java-library` }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies { api(project(":discovery")); implementation("org.jsoup:jsoup:1.21.2"); testImplementation("junit:junit:4.13.2") }
