# ESV Error Prone 2.41.0 exact-runtime static admission review

Result: PASS for the inspected static compatibility scope. No unresolved Error Prone class, member, annotation element, element value type, enum constant, or missing required annotation element was found. This is dependency-admission evidence, not Android/device, crypto, shrinking, packaging, or distribution certification.

## Scope and input integrity

- Immutable source baseline: f184cc91c7f3e31d7b9b39219ee8b9c9d2139fab.
- Read the baseline admission manifest and both committed M5-A3 debug/release runtime inventories directly with git show. Deduplicated the repeated identical androidx.core:core artifact row. The two baseline graph sets match.
- Verified actual local bytes against all 72 admitted external runtime artifact SHA-256 values and all nine baseline project runtime JAR SHA-256 values before parsing. The project app class output itself is not a RuntimeClasspath dependency and is not included.
- The three actual imported candidate binaries match their sealed candidate manifest SHA-256 values. The manifest SHA-256 is pinned in the script. The repository import map resolves retained evidence under third_party/candidates/tink-1.23.0 and candidate payloads under vendor/tink-maven. Their origin verification is inherited from the supplied reviewed candidate bundle; this independent pass did not download or rebuild publisher artifacts.
- Scope includes JAR class entries, multi-release entries, and each AAR's classes.jar plus libs/*.jar. AAR lint.jar is build tooling, not runtime payload, and is excluded.
- Total: 17,635 classfiles: 15,114 baseline dependency classfiles, 409 project classfiles, and 2,112 candidate classfiles. This count includes module-info entries and the old Error Prone artifact solely for comparison.
- Projected external graph after substitution: 74 artifacts, 17,198 classfile entries, 17,188 unique non-module classes, zero duplicate classes. No separate protobuf runtime is added.

## Final actual debug/release graph binding

The final pass opens the actual final inventory artifact paths, rather than only projected/candidate paths. Both debug and release inventories have SHA-256 430c8a3b6a4e75917bfd704725159a157f69a6c94d9fced52e1fbf3de7e9f058. Each has 84 rows, representing 83 unique artifacts after deduplicating the repeated identical androidx.core:core entry: 74 external dependencies and nine project JARs. The actual external coordinate/filename/hash set exactly matches the expected substitution. Every actual artifact SHA-256 is rechecked before classfile parsing. All nine final project hashes also match the baseline on this run.

The actual graph contains 17,607 classfile entries. Its 3,651 Error Prone type-reference occurrences, 21 member/enum occurrences, and 3,567 annotation instances all resolve. All 838 actual explicit annotation elements match the replacement API, and all 463 omitted/defaulted elements have defaults. The old 2.28.0 comparison JAR contributes the remaining 28 classfiles, 85 type references, 21 member/enum references, three annotation instances, three explicit elements, and three defaulted elements to the combined counts below; it is not on the actual graph.

The candidate import-map SHA-256 is 2d47e6b16b1a96b662f01c034ef39f502badf050705c2f04077de587270b005f. The sealed manifest anchor is 9e0e22f2fe8c12d8ff2d2ba3647c3c6378ba80af9f9d3ec1f5a844cf8b114b19. Both are recorded in summary.json.

## Stronger compatibility evidence

The auditor parses classfile constant pools, member descriptors, class/member signatures, annotations, annotation defaults, parameter annotations, type annotations, and annotations nested in Code attributes. It scans constant-pool class/member references, NameAndType/MethodType descriptors, enum-valued and class-valued annotation elements, nested annotations, array values, and all CP UTF8 strings as a conservative residual cross-check. The complete encountered attribute-name inventory is in summary.json. Unknown/non-reference attributes are counted; their CP-backed names still receive the UTF8 fallback scan.

All 3,736 recorded Error Prone type-reference occurrences resolve in the replacement-only API model. There are 884 artifact/class occurrences with references, including the old and new API itself. There are 828 consumer classes outside Error Prone. The nine project JARs contain no Error Prone references. No Error Prone reflection-name string literals or unresolved residual UTF8 names were found.

All 42 member/enum-reference occurrences resolve to exact owner/name/descriptor matches (19 unique kind/owner/name/descriptor combinations). These occur inside the old/new Error Prone artifacts themselves. No baseline consumer, Tink, or Gson class has an Error Prone CP field/method reference; their uses are annotation metadata.

All 3,570 annotation instances resolve, including 3,564 instances outside Error Prone. All 841 explicitly encoded annotation element occurrences match the replacement method name, return descriptor, and encoded value type. Arrays are checked against their declared component types. Enum values are checked against exact enum field descriptors and enum/static flags. All 466 omitted/defaulted element occurrences have replacement defaults. An omitted required element would fail the audit.

### Old API completeness

- Old 2.28.0: 27 package classes (26 annotations plus Modifier enum), plus module-info.
- New 2.41.0: all 27 old classes preserved, plus ImmutableTypeParameter and ThreadSafeTypeParameter, plus module-info.
- All 26 old annotation classes are byte-for-byte identical in the replacement. This directly preserves retention, targets, annotations, element names/descriptors, and default values.
- All 27 old classes have identical modeled linkage API: class access/kind, supertype and interfaces, all 13 declared fields and 25 declared methods (including private/synthetic members), member access flags, generic signatures, field constant values, declared exceptions, annotation metadata, and defaults. Public/protected subset: 12 fields and 22 methods.
- Modifier is the only old class whose bytes differ. Independent JDK javap -p -c -s disassembly is identical; modifier-javap.diff is empty. Verbose comparison shows added MethodParameters flags: mandated metadata for valueOf(String), and synthetic metadata for the private enum constructor. These reflection-visible parameter flags are outside the modeled linkage ABI and do not remove/change any class or member. The comparison does not claim every byte or every reflection property is identical.
- module-info entries were parsed for CP references but are excluded from Android class duplicate/API completeness counts; this pass is not JPMS module-resolution certification.

### Explicit annotation element contracts observed
- com/google/errorprone/annotations/CompatibleWith.value: ()Ljava/lang/String;, 20 occurrences
- com/google/errorprone/annotations/DoNotCall.value: ()Ljava/lang/String;, 125 occurrences
- com/google/errorprone/annotations/DoNotMock.value: ()Ljava/lang/String;, 30 occurrences
- com/google/errorprone/annotations/Immutable.containerOf: ()[Ljava/lang/String;, 8 occurrences
- com/google/errorprone/annotations/IncompatibleModifiers.modifier: ()[Lcom/google/errorprone/annotations/Modifier;, 6 occurrences
- com/google/errorprone/annotations/InlineMe.imports: ()[Ljava/lang/String;, 53 occurrences
- com/google/errorprone/annotations/InlineMe.replacement: ()Ljava/lang/String;, 78 occurrences
- com/google/errorprone/annotations/InlineMe.staticImports: ()[Ljava/lang/String;, 1 occurrences
- com/google/errorprone/annotations/RestrictedApi.allowedOnPath: ()Ljava/lang/String;, 117 occurrences
- com/google/errorprone/annotations/RestrictedApi.allowlistAnnotations: ()[Ljava/lang/Class;, 115 occurrences
- com/google/errorprone/annotations/RestrictedApi.explanation: ()Ljava/lang/String;, 117 occurrences
- com/google/errorprone/annotations/RestrictedApi.link: ()Ljava/lang/String;, 105 occurrences
- com/google/errorprone/annotations/concurrent/GuardedBy.value: ()Ljava/lang/String;, 66 occurrences

### Omitted/defaulted element contracts observed
- com/google/errorprone/annotations/DoNotCall.value: ()Ljava/lang/String;, 4 occurrences
- com/google/errorprone/annotations/DoNotMock.value: ()Ljava/lang/String;, 6 occurrences
- com/google/errorprone/annotations/Immutable.containerOf: ()[Ljava/lang/String;, 217 occurrences
- com/google/errorprone/annotations/IncompatibleModifiers.value: ()[Ljavax/lang/model/element/Modifier;, 6 occurrences
- com/google/errorprone/annotations/InlineMe.imports: ()[Ljava/lang/String;, 25 occurrences
- com/google/errorprone/annotations/InlineMe.staticImports: ()[Ljava/lang/String;, 77 occurrences
- com/google/errorprone/annotations/RestrictedApi.allowlistAnnotations: ()[Ljava/lang/Class;, 2 occurrences
- com/google/errorprone/annotations/RestrictedApi.allowlistWithWarningAnnotations: ()[Ljava/lang/Class;, 117 occurrences
- com/google/errorprone/annotations/RestrictedApi.link: ()Ljava/lang/String;, 12 occurrences

## Candidate embedded ProGuard resources

The following entries are present within the actual imported, hash-verified official candidate JARs opened through the final runtime inventories. Their JAR bytes match the sealed candidates exactly, confirming these resources were retained during import. This does not prove that a future D8/R8 run consumes them or that eventual release packaging is correct.

- com.google.crypto.tink:tink-android:1.23.0: META-INF/proguard/protobuf.pro, 755 bytes, SHA-256 1ba0e93c7f16e34ab1fdbfb0e30eb99782b479fa6b632a49397254a7884209fa
- com.google.code.gson:gson:2.13.2: META-INF/proguard/gson.pro, 3180 bytes, SHA-256 e4536a0b2d78966478338e1a176475ee75081eeb7d01fc204e0dbc12584de6c6

- Tink's rule keeps fields in subclasses of its shaded protobuf GeneratedMessageLite.
- Gson's resource preserves required signatures/annotation attributes and includes TypeToken, JsonAdapter, and SerializedName-related rules. Its embedded comment explicitly says app-specific rules may still be needed.
- Error Prone 2.41.0 has no embedded ProGuard resource; none is expected from its exact reviewed bytes.
- Full resource text and enclosing JAR hashes are retained in summary.json.

## Negative controls

run_negative_controls.py changes only the auditor's parsed in-memory replacement model, never an artifact or source. All five controls were rejected with exit status 1 and the intended targeted diagnostic:
- remove_class: unresolved_type (PASS)
- rename_element: unresolved_annotation_element (PASS)
- wrong_element_descriptor: annotation_element_value_type_mismatch (PASS)
- remove_default: missing_required_annotation_element (PASS)
- remove_enum_constant: unresolved_member (PASS)

## Reproduction and deliverables

Run from any directory, using the selected restored repository and its newly generated inventory directory:

    python3 audit_errorprone_runtime.py --repo /path/to/EasySaveVideo --inventory /path/to/EasySaveVideo/app/build/reports --output /path/to/audit-output
    python3 run_negative_controls.py --repo /path/to/EasySaveVideo --inventory /path/to/EasySaveVideo/app/build/reports

When copied beneath the repository, the script detects that repository by its ancestor AGENTS.md/settings.gradle.kts; --repo can always select it explicitly. --inventory defaults to REPO/app/build/reports. --actual-debug and --actual-release can select individual reports instead. --output (alias --out) defaults to the scanner's directory. No original workspace path or original candidate audit directory is hard-coded or required.

By default the scanner resolves the original candidate layout through REPO/docs/m5-vault-dependencies/evidence/candidate-import-map.json. It verifies the pinned manifest hash, each used mapping entry, each used manifest payload, and all actual runtime artifact bytes. --bundle is an optional alternate input only when intentionally inspecting the original unrelocated sealed candidate bundle. The old Error Prone comparison binary is found in REPO/vendor/media3-maven, falling back to the baseline inventory path only if needed. The baseline commit must remain readable by git show. Actual runtime inventory file paths must exist on the selected executor; regenerate those inventories after restoring the repository if their paths are stale.

The scanner does not require rebuilt project JARs to retain historical ZIP hashes: it checks their bytes against the current actual inventory, records whether they match baseline, and scans the current bytes. External artifact coordinates/filenames/hashes remain strict. Both actual variants must agree. The process writes only standalone audit output and exits zero only on success. The negative-control runner produces its own JSON/log output subdirectory; no compiled classes or duplicate JARs are produced or needed. Import only root-level scripts/reports and desired occurrence JSON files, not the repeated negative-control trees.

- audit_errorprone_runtime.py: reproducible pure-Python standard-library scanner.
- run_negative_controls.py and negative-controls.json: targeted rejection test runner/results.
- summary.json: aggregate counts, failure list, encountered classfile attribute counts, embedded ProGuard texts/hashes.
- verified-inputs.json: all 84 unique artifact paths/coordinates/hashes and per-artifact classfile counts.
- old-api.json, replacement-api.json, old-versus-replacement-api.json: detailed modeled API comparison.
- type-reference-occurrences.json, member-reference-occurrences.json, annotation-reference-occurrences.json: every inspected Error Prone reference/use site and resolution evidence.
- modifier-old/new-javap.txt, modifier-javap.diff, modifier-old/new-verbose.txt, modifier-verbose.diff: independent enum disassembly comparison.

## Limits and blockers

No blocker was found within the requested static reference/annotation compatibility scope. The parser does not load or execute any candidate class and is not a JVM/ART bytecode verifier. Reflection assembled dynamically, JNI-generated class names, arbitrary non-classfile resources, absent source-retention annotations, future code, Kotlin semantic analysis, and general non-Error-Prone dependency linkage are outside its scope. It does not certify Java platform APIs on Android, execution of Gradle resolution itself (the provided final inventories and actual bytes are verified), D8/R8, consumer-rule application, release shrinking or packaged artifact behavior. Those remain separate owner-run gates.

No Gradle, device, APK/AAB assembly, real crypto, Android provider, Keystore, source edit, dependency import, or security-setting change was performed. Only standalone audit output files were written. JDK javap was used solely to disassemble the enum class; target library classes were not initialized or executed.
