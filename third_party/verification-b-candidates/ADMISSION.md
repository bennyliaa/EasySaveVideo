# B component review — exact candidate scope

User authorized fixed-version review and local implementation before Android integration.
Official Maven Central POMs and JARs were retrieved over verified HTTPS. No upstream code was copied into ESV source.

Jsoup 1.21.2: MIT; full exact copyright/license is embedded at META-INF/jsoup/LICENSE.
POM direct production runtime dependencies: none. jspecify 1.0.0 is provided scope;
JUnit Jupiter, Gson and Jetty are test/build dependencies, not admitted runtime additions.
Approved here only for the JVM-only static parser evaluation module. Preserve the exact MIT notice
before future Android integration. This module is not in the app runtime graph or APK.

OkHttp 4.12.0: Apache-2.0 code plus the embedded MPL-2.0 Public Suffix List data.
Okio -> okio-jvm 3.6.0: Apache-2.0. Declared Kotlin minimums are 1.8.21/1.9.10;
the project already admits Kotlin stdlib 2.2.10. A resolved Android graph and exact bytes
must be reviewed, rather than adding older stdlibs beside it.
The Public Suffix List NOTICE and corresponding data/source obligation must be preserved;
an Apache-only notice would be incomplete. OkHttp/Okio remain candidates, not app dependencies.

Official license references: https://jsoup.org/license and
https://www.mozilla.org/en-US/MPL/2.0/FAQ/ (Q5/Q8/Q11; read actual license for distribution).
No new signed agreement, account, service, publication or runtime redistribution was performed.
Do not treat this internal evaluation admission as full Android distribution acceptance.

2026-10-09 continuation: Exact preferred PSL source commit ab0530155af1fe4b58194fe6792c4ec9731666bf recovered from its official upstream. The 9499 ordinary and 8 exception rules match the embedded bytes exactly (PSL-PROVENANCE.json). Internal B implementation admission now includes OkHttp 4.12.0, Okio JVM 3.6.0 and Jsoup 1.21.2 with full licenses, notice and exact corresponding PSL source retained offline. MPL covered files remain MPL; ESV original source remains independently closed-source. Distribution is still unperformed. Runtime Kotlin dependencies are excluded from the Square edge only and supplied by the existing project Kotlin 2.2.10; resolved graph evidence must verify this. No artifact was modified.
