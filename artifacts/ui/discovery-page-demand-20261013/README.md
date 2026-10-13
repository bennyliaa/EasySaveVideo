# Bounded Discovery page-demand evidence

Source-only continuation of original Product §5.2/5.3 and Technical TR-02/TR-15.
See docs/ui/implementation/DISCOVERY_PAGE_DEMAND_20261013.md for scope and fences.

All Gradle runs used the existing serialized cloud wrapper, offline, unchanged
reviewed dependency graph. The final command requested app JVM tests, discovery
JVM tests, Debug/Release Kotlin compilation and Debug/Release lint. VERIFICATION.json
records exact actual versus cached execution. JVM_CENSUS.json records all test
suite counts; selected new-integration and resource-parity XML is included.

Run1 focused15 tests/dual compilation passed. Run2 exposed a test-fixture compiler
error: DiscoveryTimer is a regular interface, fixed with an anonymous object.
Run3 exposed the expected resource-count change276→281;1 failed of1572 tests.
That superseded run remained in lint with no new output after its failure; it was
interrupted through its exact execution session (exit130). Its lint is incomplete,
not passed. Run4 passed with one new unused old-page-label warning. The obsolete label was removed; final inputs and20 new tests are validated in run5.

No claim of OOM or process death is made: available process inspection was isolated
and did not expose that command's CPU/RSS. No lock file was deleted, no overlapping
Gradle process was launched, and the final wrapper acquired the released lock.

Logs/XML redact workspace paths and hostnames. Files contain owned synthetic test
metadata only. No ordinary browsing history, media, credentials or private user
content was accessed or included. No APK/device/emulator/native media/public-site
step was executed. Source SHA256 values match independent final reviewed files.
