# Pinned registered paired-WebM host tests

The coordinator first compiles current production/main and test classes. This runner verifies a supplied current source manifest, verifies the admitted artifact hashes, and runs those actual storage/attempt/pair/entry objects with the pinned Media3 binary classes and deterministic Android/syscall test seams. No stand-in storage authority is compiled. The independent webm-probe-host runner remains unchanged and rerunnable.

Required environment: JAVA_HOME, ANDROID_HOME, GRADLE_USER_HOME, ESV_MAIN_CLASSES, ESV_TEST_CLASSES, ESV_PROJECT_CLASSPATH, and ESV_SOURCE_MANIFEST. Class directories/classpath are supplied by the build owner after a successful current compile. ESV_PROJECT_CLASSPATH includes the actual core/save/library module output and JUnit runtime. Output must be outside the source checkout.

Run: bash app/src/testSupport/webm-paired-host/run.sh REPO EXTERNAL_OUTPUT

The tests use actual SaveCoordinator admission, bridge Scope, fresh WRITING preparation and owner registration through OwnedTranscodeTestFixture. Private paired factories are inspected only by test reflection to drive the pinned public ProgressiveMediaExtractor/StatsDataSource route without inventing a native run. Reflection does not add a production capability. Malformed loading tests replace fixture bytes while preserving its synthetic stat after valid anchored preflight, to test complete loading guards against same-identity byte changes. This does not claim stat checks prove stable content; final raw hashing is a separate later gate.

No native decoder, Transformer playback/export, actual native cleanup, output file, conversion proof, storage finish, independent verifier, publication or device claim follows. Direct remains Unissued. Only the coordinator runs the script.

The Handler/Looper/HandlerThread shims provide a daemon host callback queue only for null-native session cancellation. The test installs a process-wide uncaught-exception collector, drains every host dispatcher before reporting, and fails on any background exception. This is no substitute for real Android/native cleanup acceptance.
