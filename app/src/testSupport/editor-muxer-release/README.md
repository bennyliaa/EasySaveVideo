# Pinned MP4 finalization after cancellation: host-only regression

Run from any directory, with the previously admitted offline toolchain/cache available:

```sh
/path/to/EasySaveVideo/app/src/testSupport/editor-muxer-release/run.sh /tmp/editor-muxer-replay
```

No Gradle invocation or prior project build is required. The script derives the repository root,
uses the restored workspace's approved JDK/Android SDK/Kotlin cache by default, and supports
`JAVA_HOME`, `JAVA_BIN`, `JAVAC_BIN`, `ANDROID_HOME`, `ANDROID_JAR`, `GRADLE_USER_HOME`, and
`KOTLIN_CACHE` overrides. `KOTLIN_CACHE` means the `caches/modules-2/files-2.1` directory.
Missing artifacts fail locally; nothing is downloaded or installed.

The script checks the selected Media3 1.11.1 AARs, Guava jars, and Kotlin standard-library bytes
against `third_party/admitted-runtime.tsv`, extracts only their existing classes, and compiles the
actual production `EditorOutputIoPolicy` with its pure contract dependencies. It adds no runtime
module, dependency, Android source-set entry, or application code.

## What this checks

The original fixture writes three four-byte synthetic AAC sample payloads through the exact
admitted `Mp4Muxer`, with sample batching disabled. After sample writes, it sets cancellation,
starts the production policy's bounded finalization phase, and invokes `Mp4Muxer.close()`.
It asserts that final-table writes happen despite cancellation and that the output descriptor and
channel really close. The one-MiB range cap stays active during finalization. A temporary fixture is
removed after a successful check. `result.log` records the result.

The synthetic payload bytes are deliberately not a playable recording. The AAC-LC configuration
is a tiny original in-memory fixture, not downloaded media or a user file. The host-only Android
`Build`, `TextUtils`, and `Pair` shims provide value/string behavior needed by the admitted Java
classes. They are kept under `testSupport`, never on an Android application or Gradle test classpath.

## What this does not check

This is an actual pinned-Java-muxer/control-policy regression, not an Android integration test.
It does not invoke `Transformer`, `MediaExtractor`, `MediaCodec`, OpenGL, an OEM implementation,
SAF, publication, or storage-owner fencing. It does not prove that the synthetic output plays.
Android/API 31+, native codec, GPU, real-device, and OEM acceptance remain **NOT_RUN**.
The companion JVM engine tests cover byte-cap failures, sticky native ownership, interrupted
cleanup waits, codec allocation classification, and terminal GL-before-codec release ordering.
