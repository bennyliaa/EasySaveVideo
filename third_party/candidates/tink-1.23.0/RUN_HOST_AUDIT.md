# Optional authorized host rerun

The supplied log is already complete. Do not rerun without permission for synthetic host crypto execution in the chosen environment. No Gradle or Android device is needed. Use an existing approved Java 21 compiler/runtime; do not install a toolchain just for this package.

From the bundle directory, set CP to the three binary JARs under candidate-maven (exclude -sources.jar), compile StreamingAudit.java with javac -cp "$CP" -d a separate temporary directory, and run java -Xmx256m -cp "that temporary directory:$CP" StreamingAudit. Where standalone javac is absent, the existing approved launcher java -m jdk.compiler/com.sun.tools.javac.Main works. Keep generated classes/logs outside this immutable evidence bundle so hash verification remains valid.

This exercises fresh ephemeral in-memory test keys and synthetic patterns only. The 2 GiB sink retains selected ciphertext segments and discards the rest. It does not use user media, Keystore, production secrets, an APK or a device. Expected final result: TOTAL PASS=30.
