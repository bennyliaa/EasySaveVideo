# Remaining work after the first-layer increment

## Known inherited Commons parser validation gap

The original Commons JSON reader at commons-provider/src/main/kotlin/com/easysavevideo/commons/StrictJson.kt, unicode(), calls toIntOrNull(16) on the four characters following a JSON Unicode escape without first requiring four ASCII hexadecimal digits. Kotlin's numeric parser accepts a leading sign, so malformed escape digits such as +041 or -001 can pass that numeric conversion. Exact parser-level reproducer input: {"x":"\u+041"} and {"x":"\u-001"}. A strict JSON reader must reject both before interpretation.

This was found while reviewing the copied original pattern for the new SearXNG client. The new SearXNG parser now explicitly validates ASCII hex digits, with unit and independent regressions. The inherited Commons implementation remains byte-identical in this milestone. There is no observed live-service impact or media-authority claim from this finding; it is a known strict-parser validation gap requiring a separate bounded fix after this baseline is stable.

Follow-up scope: add malformed-escape regressions for the original Commons reader, apply the minimal digit validation there, and repeat its parser/provider tests and the established verification gates. Do not conflate this ordinary parser fix with any blocked rule-runtime or Vault work.

## Unfinished product acceptance

- Select a genuinely permitted and available SearXNG-compatible HTTPS JSON endpoint before live query testing; public HTML availability alone is insufficient
- Keep service/upstream query-sharing disclosure and unknown SafeSearch capability visible; do not assume previous Commons-only test permission authorizes a new service
- Integrate actual admitted Scrapling L1/L2 validation with typed incomplete/rejected/unavailable/cancelled outcomes and provider/page/rule-version adaptive state
- Perform the full lawful-page quality, false-positive/negative, dynamic/XHR, adaptive, resource and concurrency evaluation required by M0
- Run authorized native Android phone/tablet UI, lifecycle, rotation and failure/reopen tests; no APK or device test was part of this slice
