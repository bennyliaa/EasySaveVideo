# Workspace build-tool recovery

The current executor retained Gradle9.6.0, AGP9.4.0 and Kotlin2.2.10 caches, but not its former Android SDK/Compose cache or ESV checkout. The original M0 toolchain status was not assumed to remain true.

Official Google repository metadata was fetched with the environment's default HTTPS proxy and unmodified default TLS trust. Android platform36 r02 SHA-1 2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576 and build-tools36.0.0 Linux SHA-1 b0b6376977657e8ad9b969bacf4093601da2c6fb were verified before workspace-only extraction. Compose/runtime dependencies were recovered at the exact previously admitted versions from Google Maven/Maven Central.

The installed OpenJDK21.0.12.1 includes jdk.compiler, jdk.jartool, jdk.javadoc and jdk.jlink modules but lacks their CLI launchers. Workspace-only scripts invoke those existing official modules for javac/jar/javadoc/javap/jlink/jmod; unmodified installed runtime bytes are copied into that workspace toolchain so Gradle can identify its compiler capability. A real javac compile/run probe and actual Android Java/Kotlin compilation passed. This is a module-launcher recovery, **not a claim that a new complete official JDK distribution was installed**.

No system/JDK certificate store was modified; TLS was never disabled, no unknown mirror was used, and no old one-off certificate approval was reused. The script derives the current tool invocation's supplied proxy address, which changes between invocations; it does not persist credentials.

Android platform-tools are not installed. AGP may warn about the missing package/license, but the compile/test/lint tasks do not require adb. No sdkmanager license-acceptance command, package assembly or device install was run. SDK/cache/toolchain bytes are excluded from project Git and private source/Git-bundle backups.

A fresh locked run exposed Kotlin stdlib-common2.2.10 metadata missing from several runtime lock configurations. The real Gradle resolution was updated specifically with `--update-locks org.jetbrains.kotlin:kotlin-stdlib-common` across the full compile/test/lint task set. A subsequent fresh `--offline` run with unchanged locks and verification metadata passed. No manual lock row, weakened locking mode or product-code workaround was used; the 55 runtime binary inventory remained unchanged.
