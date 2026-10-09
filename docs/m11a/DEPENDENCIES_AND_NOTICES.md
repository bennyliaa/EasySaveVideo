# Exact74 dependency and notice facts

## Current facts, historical layers and traceability

`third_party/admitted-runtime.tsv` has **74 unique external runtime artifacts**:
72 official third-party components and two internally authorized SkinKit 0.0.1
artifacts. The M10-A debug/release inventories independently match those exact
coordinate/filename/SHA-256 triples after excluding the nine project-module JARs
and deduplicating identical resolution rows. Raw inventory line count is not the
runtime-artifact count. No new dependency resolution or admission occurs here.

The machine index `DEPENDENCY_FACTS.json` maps every admitted artifact to source/
POM/license evidence and the application's existing notice marker:

- 49 coordinates remain covered by `third_party/current-runtime/SOURCE_INDEX.json`
  (including the two SkinKit entries). This is the preserved recovered 55-component
  baseline. Some upstream source archives were inspected during recovery but were
  not persisted; retrieval hashes/URLs and source-header evidence remain. The index
  does not pretend those archives are present or freshly fetched.
- 22 exact coordinates use the immutable Media3 1.11.1 candidate audit. Its historical
  23-component delta includes Error Prone 2.28.0, subsequently replaced. Its proposed
  combined 74 included two obsolete empty Kotlin compatibility JARs not in the
  measured M3-B graph of 72. Candidate reports are historical, not current resolution.
- Three coordinates use the Tink admission: Tink Android 1.23.0, Gson 2.13.2 and Error
  Prone annotations 2.41.0. The Tink import map binds 66 exact mapped candidate files.
  Tink adds two net artifacts to 72 because Error Prone is a replacement.
- `docs/m10a/evidence/{debug,release}RuntimeClasspath-inventory.tsv`,
  `tink-admission-checks.json`, `check-stream-admission.log` and
  `strict-offline-aggregate.log` are the already executed current-base checks.

The project-authored root NOTICE formerly described the M3-B graph as the current
70-official-plus-two graph. This docs-only commit corrects that summary to74 and
adds the existing Tink evidence directory. It does **not** change upstream license
texts, admitted hashes or `app/src/main/assets/third-party-notices.txt`.

## What the existing bundled text contains

The application asset is 210,385 UTF-8 bytes, below the viewer's 512 KiB character
limit. It has 71 exact current GAV labels plus the named/versioned three-component
Tink supplement at line 3574. Absence of a Maven-style GAV for those three is not
absence of their attribution. All 74 have a mapped notice marker; marker coverage
alone is not a legal sufficiency test.

Preserved evidence includes Apache texts and copyright attributions; Kotlin
ThreeTenBP BSD/source-scope distinctions; Media3/transitive license exceptions,
including jsr305 Brian Goetz CC BY 2.5; Guava and other bundled notices; Tink's
embedded protobuf 4.33.6 BSD, Curve25519-donna BSD, Jackson NOTICE and identified
Square/Moshi/AOSP/public-domain ancestry. Applicable complete text checks and exact
Tink import hashing are in the prior admission gate. No unshaded protobuf, KMS,
alternate crypto provider, payment SDK or new extraction provider is admitted.

`ThirdPartyNotices.kt:11–40` reads that local asset in bounded display chunks;
`MainActivity.kt` exposes the settings entry. This is an offline notice viewer,
not evidence that the whole application is offline. The viewer has an explicit
unavailable/do-not-distribute message if reading fails. Earlier merged-asset
byte checks passed; no final APK/AAB exists to inspect or install.

## Remaining release gates, not newly invented license failures

No new missing notice text was found in this bounded index/preserved-evidence
consistency check. Final package inclusion, shrink/DEX/resource behavior, installed
reachability and actual release configuration are **NOT_RUN**, so no final package
notice clearance is claimed. If a future package omits required text, that is a
release blocker even when these source-level checks pass.

SkinKit's internal reuse is authorized; an external distribution ownership/
contribution record still needs acceptance. No MIT/Apache/public license is assigned
to user-owned ESV or SkinKit. Final product terms and DRM must not override retained
third-party rights. Per-file Commons content licensing is separate from software
licenses: source-reported credits/license labels are not media-use permission or a
blanket CC0 grant. Then-current market/Provider/legal/privacy review remains pending.
