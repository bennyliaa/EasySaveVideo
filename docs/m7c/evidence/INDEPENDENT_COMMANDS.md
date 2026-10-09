# Independent M7-C host commands

The reviewer uses only previously cached compiler/runtime/JUnit artifacts and the
existing JDK launcher. No Gradle, dependency fetch, live HTTP, Git mutation,
Android build/install or production identity is used by these commands.

Moving-tree diagnostics:

    bash docs/m7c/evidence/run-independent-provider-host.sh /tmp/esv-m7c-independent-diagnostic-N > docs/m7c/evidence/independent-diagnostic-N-console.log 2>&1

Diagnostic 1: 277 attempted, 76 failures. Independent tokens and javap identify
the Long/Int policy-version fallback defect. Source changes also invalidate hashes.
Diagnostic 2: compiler saw a transient size-Int/Long change; no tests ran.
Diagnostic 3: controller rollback signature changed during fixture preparation;
no tests ran. The fixture was updated to check the explicit generation.
Diagnostic 4: 294 actual tests passed; one false JUnit initialization error from
production ProviderFixedSelfTest matched by the inherited class-name glob. Runner
now derives suites from test source inputs. Source changed during the run.
Diagnostic 5: 317/317 passed, but one source changed during the run. Diagnostic only.

The first final-candidate run attempted 324 tests with one reviewer assertion
failure: a non-session backend cancellation correctly reported ACTION_REJECTED /
CANCELLED, while the fixture required LOCAL_UNAVAILABLE. The fixture now checks
that truthful code plus absent completion/snapshot. The failed log and exact
manifest remain as independent-prefinal-*; no production change was needed.

A subsequent 324/324 run passed all 59 inputs unchanged, then was superseded to
include the new pure repository-owner main source and its three tests. That exact
passing revision remains as independent-prior324-*.

Final frozen commands:

    python3 docs/m7c/evidence/review-manifest.py freeze
    bash docs/m7c/evidence/run-independent-provider-host.sh /tmp/esv-m7c-independent-final > docs/m7c/evidence/independent-final-host-console.log 2>&1
    python3 docs/m7c/evidence/review-manifest.py verify
    diff -u docs/m7b/evidence/independent-final-toolchain-sha256.txt docs/m7c/evidence/independent-final-toolchain-sha256.txt

Final exit: 0. JUnit: 327/327 (223 Commons + 37 app management/owner + 34 previous
independent + 33 new independent). All 61 compiled input hashes remained unchanged.
The runner first clears only its own non-symlinked OUT/classes directory to exclude
stale compilation output, including when a full/cold aggregate reuses an output.

Manifest: 95 entries, SHA-256
5467d271820eae5da4253e6722942cbd2eeb9e49005f47764af0ae8ad5b3a265.
Independent verification at 2026-10-07T01:27:01.927327Z found all extents/digests
matching and no mismatches. The eight compiler/runtime/JUnit artifact manifests
are identical to M7-B. Physical Android/device/power-loss stages remain NOT_RUN.

A second independent recheck at 2026-10-07T01:27:43.844336Z also matched all 95 entries.
