# M11-A: current code facts and release gates

Date: 2026-10-07 UTC. This is a finite documentation-only inventory, **not Android
release acceptance, a privacy policy, legal advice, or a current store-policy review**.

The tested implementation is `bdf31387ec99c86c25847f5bbadd1eff8908f45d`, tag
`m10a-purchase-preparation-dev-2026-10-07`. The later documentation commit only adds
this folder and corrects the project-authored root NOTICE's old runtime count/index.
No source, asset, manifest, Gradle setting, dependency, permission or production
configuration changes. The bundled application notice text is byte-identical.

## Read in this order

1. [One-page release blockers](RELEASE_BLOCKERS.md)
2. [Milestone and capability status](MILESTONE_STATUS.md)
3. [Permissions and network](PERMISSIONS_AND_NETWORK.md)
4. [Local storage and cleanup](LOCAL_DATA_AND_RETENTION.md)
5. [Dependencies and notice evidence](DEPENDENCIES_AND_NOTICES.md)
6. Machine-readable [code facts](CODE_FACTS.json), [74-artifact index](DEPENDENCY_FACTS.json)

## Source of authority and limits

- `docs/reference/03_Development_Milestones_update1.md`, M11, requires review of
  then-current store rules, licenses, privacy and Provider updates. This inventory
  prepares code facts for that future work; it does not perform that review.
- `docs/reference/01_Product_Requirements.md`, PR-05/06/16/18/25 and §§4,7–11;
  `04_Technical_Requirements.md`, TR-11/12/13/18 and §§9–11;
  `05_Test_and_Acceptance_Strategy.md`, T-D/T-S/T-R/T-L/T-E/T-P and device gates,
  define requirements, not proof that each requirement has been implemented.
- The later API31 / Android12 phone-and-tablet instruction overrides the original
  API30 wording. Update1 milestone numbering overrides the old numbering. The
  original eight files, including UTF-16LE Architecture update_1, remain unchanged.
- Restored project history starts with the documented reconstruction, not recovery
  of the missing old M0 commit. No integrated Scrapling/NewPipe/Yue/Captord exists.
- M7-D and Vault candidates remain isolated. No review, retry, alternate probe,
  device use, APK/AAB assembly, new network research or feature work is started here.
- The ESV market question is already pending. SkinKit's market or emulator approval
  does not decide or authorize ESV work. This slice stops after the inventory.

## Evidence levels

`SOURCE_IMPLEMENTED` means checked-in code exists. `HOST_PASS` means the limited
host/synthetic cases in the cited historical evidence passed. `DEVICE_NOT_RUN`
means no Android execution or acceptance is established. `BLOCKED_ISOLATED` keeps
blocked candidates outside the runtime. `BUSINESS_DECISION_PENDING` is an unmade
product/channel decision. These labels are deliberately separate, and can coexist.

The exact tested base has 1,822 Gradle JVM tests, dual compile/lint, exact74 and two
cold full aggregates already recorded in
[evidence/tested-base-receipt.json](evidence/tested-base-receipt.json). Both lint
variants had 0 errors, 14 warnings and 6 hints. Independent host selections overlap
and are not added to the JVM count. This slice does **not** repeat Gradle or describe
its new documentation archives as new cold full builds.

Run the bounded, read-only facts check from any extracted source tree:

    python3 docs/m11a/verify_facts.py

It validates protected source/evidence bytes, declarations, dependency/notice
indices and finite statuses. It does not run the app, fetch remote sources,
interpret legal obligations, or prove native behavior. The external restore receipt
binds the later docs commit, both archive hashes, cold bytes/modes/refs and fsck.

## Historical or inherited wording to avoid reusing as current facts

`docs/m2/IMPLEMENTATION_AND_ACCEPTANCE.md:5` describes its then-zero-provider
Release; later source admits configured Commons. `A/editor/ui/EditorScreen.kt:81`
(where A is app/src/main/kotlin/com/easysavevideo/app) still has inherited wording
about lacking actual screenshot selection, although M6-C adds an independently
authorized-frame selector with explicit limitations. Both are recorded here without
modifying frozen runtime text or historical evidence. The latter UI wording needs
correction in a separately tested runtime revision before shipping.
