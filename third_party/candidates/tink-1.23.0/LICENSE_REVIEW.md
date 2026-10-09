# Exact candidate license review

Scope: unmodified Maven Central Android binary `com.google.crypto.tink:tink-android:1.23.0`, new Gson 2.13.2, replacement error_prone_annotations 2.41.0, and embedded/source-derived material in these artifacts. Existing unchanged admitted dependencies retain their existing obligations. This file is an evidence-based compatibility assessment; the candidate remains unadmitted until the project owner accepts the exact scope and all outstanding gates.

## Artifact results

| Runtime artifact | SHA-256 | License and scope |
|---|---|---|
| tink-android-1.23.0.jar | c656918451b01c45ce5b20c7b6d4c388f956f61b3a3528e769048c8944c42f9e | Tink Apache-2.0; embedded protobuf-javalite BSD-3-Clause; identified source-derived scope below |
| gson-2.13.2.jar | dd0ce1b55a3ed2080cb70f9c655850cda86c206862310009dcb5e5c95265a5e0 | Apache-2.0, including identified Apache-licensed source-derived material |
| error_prone_annotations-2.41.0.jar | a56e782b5b50811ac204073a355a21d915a2107fce13ec711331ad036f660fcc | Apache-2.0; replaces admitted 2.28.0, not an additional side-by-side artifact |
| embedded protobuf-javalite 4.33.6 | Standalone evidence JAR: eb6c82249573b12e2029ca0a9c25c6220cd3d243cb030882027b5f8ef8640b8a | BSD-3-Clause. Not a separate proposed app dependency. Embedded bytes are covered by the Tink binary hash, not this standalone evidence-JAR hash |

Primary binary/source/POM URLs and SHA-256 values appear in `SOURCE_INDEX.json`. Tink's Android POM has runtime dependencies only on annotation-jvm 1.8.2, jsr305 3.0.2, Gson 2.13.2 and error-prone 2.41.0. Gson adds error-prone 2.41.0. Other Gson/error-prone listed dependencies are test scope; protobuf-javalite has no POM dependencies. The source tag's broader Bazel development graph contains build/test tools and optional providers, which are not automatically shipped runtime dependencies.

## Embedded and derived source scope

1. **Protobuf-javalite 4.33.6:** 539 classes are relocated to `com.google.crypto.tink.shaded.protobuf`. Tink's source JAR does not include these shaded sources, so standalone exact-version source/POM/binary evidence was fetched separately. Android build rules, `MODULE.bazel` version 33.6/4.33.6, shading rules and matching class-name sets support the mapping. Its 94 nongenerated runtime Java files explicitly reference the BSD-style license, and the exact v33.6 LICENSE contains BSD-3-Clause terms. Generated protobuf code follows the license of its input schema. Tink schemas are Apache-2.0; Google's built-in protobuf schema/runtime scope is BSD-3-Clause. Full protobuf assets from the standalone JAR are not all shipped in Tink; the Tink binary's only nonclass resources are the manifest and protobuf proguard rules.
2. **Curve25519-donna:** Tink `internal/Curve25519.java`, `internal/Field25519.java` and `subtle/X25519.java` explicitly identify this source ancestry. The identified upstream C file has Google 2008 BSD-3-Clause text and attribution to Adam Langley, with Daniel J. Bernstein public-domain ancestry. Retain this BSD text conservatively in distribution notices in addition to the Apache Tink header. The exact original imported donna revision is not recorded by Tink; this audit preserves the stated ancestry, a pinned official upstream snapshot and its license, rather than inventing a vendored version.
3. **Poly1305-donna:** Tink `aead/internal/Poly1305.java` credits Andrew Moon and says the source is public domain. Official upstream README offers MIT or public domain. Record the public-domain branch and the Tink Apache-2.0 attribution; do not fabricate a nonexistent upstream LICENSE file (the probed path returned 404). A pinned README is retained.
4. **Ed25519/ref10:** Tink `internal/Ed25519.java` identifies NaCl ref10 ancestry. The original NaCl and Ed25519 project pages explicitly state public-domain licensing. Retain attribution and the applicable Tink Apache header. No separate NaCl native code or binary is shipped. The exact historical upstream imported revision is not stated by Tink.
5. **Android Open Source Project:** Tink `subtle/Base64.java` and Gson `internal/LinkedTreeMap.java` retain AOSP Apache-2.0 headers; the latter identifies Android 4.1 TreeMap ancestry. Include the source notices, not just a generic Google credit.
6. **Gson/Jackson:** Gson `internal/bind/util/ISO8601Utils.java` identifies Jackson databind 2.8 date parsing. The referenced official Jackson branch LICENSE identifies Apache-2.0; NOTICE and source were retrieved. Include the relevant Jackson notice/credit, even though no Jackson Maven artifact enters the graph.
7. **Gson/Moshi:** Gson `internal/ReflectionAccessFilterHelper.java` links exact Moshi commit `3c108919ee1cce88a433ffda04eeeddc0341eae7`. The referenced source and license are Apache-2.0 (Square). Preserve its credit. No Moshi runtime dependency is added.
8. **Protobuf/Guava snippets:** Protobuf ByteString and Utf8 identify copied Guava methods; preserve Apache-2.0/Google attribution alongside protobuf's BSD terms. Guava is already an admitted dependency but that does not erase embedded-code obligations.
9. **Headerless Gson files:** Strictness.java, TroubleshootingGuide.java and NumberLimits.java lack per-file license headers. They exactly match the official 2.13.2 source tag; module POM and exact-tag root license give Apache-2.0 with no detected file-specific exception. This is an explicit reviewed module-scope conclusion, not an assumption that every repository asset has the root license.

All compiled Java source headers were scanned and relevant ancestry references checked. `source-license-scan.tsv` records the classification for 1,065 Java source files across the four evidence source JARs. Exact correspondence and resource/native inventory are separate evidence files. No new copyleft, source-offer, model/data license, font license, bundled native ABI or opaque asset requirement was found in the reviewed runtime scope. Lack of discovery is not a warranty against unknown upstream claims.

## Obligations before any binary distribution

- Ship an accessible third-party-notices/license bundle containing the full Apache-2.0 text, protobuf BSD-3-Clause text, Curve25519-donna BSD-3-Clause text, relevant copyright statements and Jackson notice/other retained upstream attribution.
- Keep the Apache copyright, patent, trademark and attribution notices that pertain to distributed material. If ESV ever modifies covered upstream files, prominently identify the changes and preserve applicable source notices when source is redistributed. This candidate proposes unmodified official artifacts.
- Apache-2.0 and BSD-3-Clause permit proprietary/closed-source applications; neither requires ESV to publish its own source. They do not grant a right to imply endorsement or use upstream trademarks freely. Observe patent-license/termination and warranty-disclaimer conditions.
- The three proposed binary JARs do **not** contain complete license/NOTICE texts. Do not assume build packaging makes those obligations disappear or is already satisfied. Produce the application's user-facing notice bundle and verify it survives the actual release packaging/shrinker path.
- Keep exact hashes, source/POM/license snapshots, source-ancestry mapping and any modification record with the import evidence. Preserve `META-INF/proguard/protobuf.pro` and Gson's proguard rules when assembling; do not erase arbitrary META-INF entries as a shortcut for duplicate handling.
- Existing jsr305/AndroidX/Guava and all other unchanged admitted artifacts retain their own already-recorded terms. The candidate evidence does not reopen or supersede those admissions.

## Remaining boundaries

No independent trusted-key PGP validation or reproducible build was performed. Exact originating revisions for Tink's historically derived donna/ref10 code are not documented upstream; license ancestry and pinned current evidence are retained and should be accepted explicitly by the admission reviewer. The projected 74-node runtime graph has not been resolved in Gradle or packaged into an APK/AAB. Any changed artifact, added provider, excluded dependency, source fork, or final packaged native/asset change requires a fresh scope comparison. Unknown new scope stays unadmitted.

## Admission classification clarification

For the exact reviewed shipped scope, applicable license terms have been identified; there are **no substantive unresolved license UNKNOWNs**. Classification: **PERMITTED for closed-source use subject to the documented Apache/BSD/attribution obligations**. This is a license-compatibility assessment, not project dependency admission, distribution approval or legal certification. Tink's omission of the historical imported donna/ref10 commit is an explained source-provenance limitation. It does not mean the applicable license was not found: the named upstreams provide identified BSD/public-domain permissions. New or changed scope must be reassessed.
