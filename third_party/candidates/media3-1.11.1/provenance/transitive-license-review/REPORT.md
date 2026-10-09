# Non-Media3 transitive license evidence review

Date: 2026-10-06 UTC. Scope: the 12 new non-Media3 runtime coordinates confirmed in the parent's combined Gradle resolution. No app/repository changes, builds, Gradle runs, dependency substitutions, or TLS/trust changes were made. This is an engineering license-evidence review, not a legal opinion.

## Result

All 12 fixed artifacts have an identified license path compatible with closed-source distribution **subject to the obligations below**. This is not permission to distribute before packaging and legal conditions are verified. The integration/release gate remains `DENY_UNTIL_NOTICES_AND_REQUIRED_CONDITIONS_VERIFIED`; new unknown code, changed coordinates/hashes, or missing required evidence must remain DENY.

Most consequential findings:

1. **jsr305 3.0.2 is mixed Apache-2.0 AND CC-BY-2.5**, not Apache-only, BSD-only, or a license choice. Four actual binary classes originate in explicitly CC-BY-2.5 sources. Do not assume annotation retention or a future shrinker removes them.
2. **checker-qual 3.43.0 is MIT**, supported by the exact LICENSE carried inside both its binary and sources JARs. No GPL checker-engine obligation is inferred from project identity.
3. **Guava 33.3.1-android contains Apache, explicit CC0, and public-domain code**. Failureaccess does not contain those Guava exceptions; its two source files are Apache. No embedded MIT license was found in either fixed artifact. MIT is applicable to the separate checker-qual dependency.
4. **Coroutines 1.9.0 has an upstream NOTICE absent from its Maven JARs**. Preserve its JetBrains/contributors attribution in the distribution bundle. All 172 published Java/Kotlin source files for the three selected coroutines artifacts byte-match files in the exact upstream 1.9.0 source archive.
5. **listenablefuture 9999 is genuinely empty code-wise**. Sources 404 is explained by the actual JAR containing only manifest and Maven metadata, not by undocumented code. Its exact parent POM declares Apache.

## Per-artifact conclusions

The file `summary.tsv` is the machine-readable table; `conclusions.json` includes all individual archive entries, hashes, source hits, licenses, and URLs.

| Coordinate | Evidence-backed scope | Source files / class entries | Obligations |
|---|---|---:|---|
| androidx.concurrent:concurrent-futures:1.2.0 | Apache-2.0 POM and all Java headers | 4 / 19 | A |
| androidx.exifinterface:exifinterface:1.3.6 | Apache-2.0 POM and both Java headers | 2 / 13 | A |
| com.google.guava:guava:33.3.1-android | Exact parent POM Apache; file-level CC0/public-domain exceptions | 615 / 1940 | A, G |
| com.google.guava:failureaccess:1.0.2 | Exact parent 26.0-android Apache; both Java headers Apache | 2 / 2 | A |
| com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava | Exact parent 26.0-android Apache; empty-class JAR | 0 / 0 | A if redistributing JAR/metadata; inventory placeholder |
| com.google.code.findbugs:jsr305:3.0.2 | Apache POM plus four explicit CC-BY-2.5 source headers | 31 / 35 | A, C |
| com.google.errorprone:error_prone_annotations:2.28.0 | Apache POM and all 28 Java headers | 28 / 28 | A |
| com.google.j2objc:j2objc-annotations:3.0.0 | Apache POM and all 15 Java headers | 15 / 18 | A |
| org.checkerframework:checker-qual:3.43.0 | MIT POM plus identical full embedded MIT LICENSEs | 349 / 370 | M |
| org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0 | Apache POM; exact tagged source, LICENSE, NOTICE | 2 / 9 | A, K |
| org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0 | Apache POM; exact tagged source, LICENSE, NOTICE | 167 / 826 | A, K |
| org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0 | Apache POM; exact tagged source, LICENSE, NOTICE | 3 / 8 | A, K |

Counts cover artifact archive entries, not final DEX retention. AAR nested classes.jar was inspected. No native shared library was found in these 12 artifacts. There were no standalone LICENSE/NOTICE entries in the inspected binary/source archives except checker-qual; that absence does not remove license obligations.

## Distribution obligations

### A: Apache-2.0

Include the full Apache-2.0 license with recipients; preserve applicable NOTICE attributions; identify any modifications to upstream files; retain applicable source copyright/patent/trademark/attribution notices when distributing source. Proprietary code can remain closed. Do not imply trademark endorsement. Apache's patent grant is limited and subject to its termination provision. These conclusions follow [the actual license](https://www.apache.org/licenses/LICENSE-2.0), rather than the vendor or organization name.

### C: jsr305 CC-BY-2.5 exceptions

Actual source files and corresponding binary entries:

- javax/annotation/concurrent/GuardedBy.java → GuardedBy.class
- javax/annotation/concurrent/Immutable.java → Immutable.class
- javax/annotation/concurrent/NotThreadSafe.java → NotThreadSafe.class
- javax/annotation/concurrent/ThreadSafe.java → ThreadSafe.class

Each fixed source explicitly states copyright (c) 2005 Brian Goetz, CC Attribution 2.5, and jcip.net as its home. The remaining artifact-level license declaration is Apache-2.0. The package's annotation retention is CLASS, but the annotation class files themselves are in the runtime JAR.

For CC-covered portions: give attribution, supplied title/source and license URI; keep copyright/license/disclaimer notices; credit adaptation where appropriate. A closed-source collective app is possible, but its proprietary EULA and any DRM must not restrict recipients' CC rights in these portions; do not sublicense them under the proprietary license. The collective app is not automatically required to adopt CC. These conditions are from [CC BY 2.5 sections 3, 4, and 8](https://creativecommons.org/licenses/by/2.5/legalcode.en).

Suggested scope wording: “JSR305 concurrency annotations GuardedBy, Immutable, NotThreadSafe and ThreadSafe, copyright (c) 2005 Brian Goetz, included in this Android application; licensed under CC BY 2.5; https://creativecommons.org/licenses/by/2.5/; official home https://jcip.net/.” If true for the final application, additionally state that no application-author edits were made to upstream source. DEX conversion/obfuscation is not evidence of source editing, and the app should not falsely say all executable bytes are unchanged. Verify exact dependency hashes and actual transformation steps before finalizing the statement.

The current author's linked [JCIP annotation source JAR](https://jcip.net/jcip-annotations-src.jar) independently confirms CC BY 2.5 and additionally credits Tim Peierls. This is corroboration, not a replacement for the exact Maven 3.0.2 header. The JCIP website's separate public-domain statement for book examples does not relicense these annotations. No blanket inference was made from it.

### M: checker-qual MIT

Include the exact copyright and MIT permission/disclaimer text found in META-INF/LICENSE.txt. It identifies “Checker Framework qualifiers”, copyright 2004-present by the Checker Framework developers. MIT permits proprietary distribution; retaining the notice is required for copies/substantial portions. Do not misclassify the qualifiers as GPL by looking at another Checker Framework component.

### K: coroutines NOTICE

Carry `upstream/coroutines-1.9.0-NOTICE.txt`, with attribution “kotlinx.coroutines library. Copyright 2016-2024 JetBrains s.r.o and contributors”. Upstream identifies it expressly as an Apache section 4(d) NOTICE. The exact source archive includes only this NOTICE and the Apache LICENSE as separate license files. The 172 published sources match the exact tag, recorded in `coroutines-exact-source-matches.json`; omission of repeated license headers is not an unlicensed-code conclusion.

- [Exact 1.9.0 NOTICE](https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.9.0/license/NOTICE.txt)
- [Exact 1.9.0 LICENSE](https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.9.0/LICENSE.txt)

### G: Guava exceptions and source-to-runtime mapping

All 615 published source files byte-match Git blobs in the official v33.3.1 tag tree (resolved tree SHA `df9602b6f1dd36414464229e0a441469d06e4575`); see `guava-exact-source-matches.json`. Exact child/parent metadata and [tagged LICENSE](https://raw.githubusercontent.com/google/guava/v33.3.1/LICENSE) provide the artifact-level Apache declaration. Three source files lacking repeated Apache boilerplate (InvalidatableSet, GraphsBridgeMethods, Fingerprint2011) were matched to this tag; the latter's copyright reservation does not itself declare a different license. Exceptions below were inspected independently instead of suppressed by the root license.

- cache.LongAdder and cache.Striped64; hash.LongAdder and hash.Striped64; util.concurrent.AtomicDouble and AtomicDoubleArray: headers credit Doug Lea (also Martin Buchholz for AtomicDouble), JCP JSR-166 experts, and explicitly link CC0 1.0.
- util.concurrent.MoreExecutors' invokeAnyImpl attribution: Doug Lea, JSR-166, CC0.
- util.concurrent.Striped smear attribution: Doug Lea/JSR-166 public-domain statement, using the older CC public-domain URL. Do not relabel this specific old statement as CC0 without additional evidence.
- base.SmallCharMatcher, collect.Hashing, hash.Murmur3_128HashFunction and Murmur3_32HashFunction: Austin Appleby/MurmurHash3 explicit public-domain disclaimer, alongside Apache-licensed Guava adaptations.
- LocalCache and MapMakerInternalMap credit Doug Lea ConcurrentHashMap revision 1.96; their shipped file headers remain Apache. Math files cite Hacker's Delight; Doubles cites the Double.valueOf regex. These are recorded provenance references, not separate restrictive license grants or a reason to import the entire JDK/book license into the artifact.

These special files have corresponding binary classes, so they are not discarded as source-only comments or tests. Preserve exact attributions and public-domain/CC0 references in the proposed notice bundle as a conservative practice; CC0 itself imposes no attribution condition and does not grant patent/trademark rights. [CC0 legal text](https://creativecommons.org/publicdomain/zero/1.0/legalcode.en).

There is no evidence that failureaccess contains any of these special implementations. Treating all Guava group artifacts as if they contained the same source exceptions would be wrong.

## Evidence and limits

- `artifacts/`: exact official Google Maven/Maven Central POM, binary, and sources downloads, each with URL/hash evidence. Copied files retain origin references to the parent's official artifacts.
- `raw/<coordinate>/inspection.json`: complete archive entries, individual hashes, POM license declarations, source-license/provenance hits.
- `license-headers/` and `special-source-evidence.json`: exact line-numbered special header excerpts plus source hashes.
- `upstream/`: exact Guava parent POMs/tag LICENSE/tree, coroutines tagged archive/LICENSE/NOTICE, author JCIP source archive, and Creative Commons web evidence.
- `transitive-notices-candidate.txt`: proposed text only, not installed in ESV and not proof the final APK exposes the licenses.
- `evidence-manifest.json`, `SHA256SUMS`: raw evidence hashes.

Key license SHA-256 values:

- Apache-2.0 full text / Guava tag LICENSE: `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`
- checker-qual binary/source META-INF/LICENSE.txt: `e90eaa08dea089df10f2bf7f9dba63a5f6237c01562c4741e2e86773e78582d5`
- coroutines 1.9.0 NOTICE: `421d45acbd92670b4131621a28fcbb41ba0b747d3a336cb13fca38c0685426c4`
- coroutines 1.9.0 LICENSE: `b1febe6399dffb10d19d35e7663ab16300c93cb0476a94115df1cc0097a8ffd8`

The shell download of CC legal-text endpoints returned HTTP 403; no trust/security workaround was used. Their official text was read with the web tool and saved as `creative-commons-web-evidence.json` (a rendered web capture, not mislabeled original HTTP bytes). CC-BY-2.5 permits a license URI with each copy, so the exact CC URL plus actual authorship notices supports the candidate bundle. Final legal terms/DRM compliance, actual app notice accessibility, release artifact contents, and shrinker output were not reviewed. Unknown changes or failures in those release checks must block admission/distribution rather than be guessed away.
