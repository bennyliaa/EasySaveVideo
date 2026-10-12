# Existing Settings category/detail integration

Independent source group after code8 main ea87963. Fixed code8 input/signed/device
acceptance artifacts are not modified. No APK, device/emulator/test-directory work.
Scope selected from original project-root docs/ui01 design lines32,59,95-97 and
contract05 lines194-202 plus the integration ledger's explicit category/detail gap.

Only currently implemented items are grouped: Appearance(theme), Language,
Sources(existing management callback) and About/licenses(existing offline notices,
Vault/store unavailable captions). The original spec's other discovery/save/record/
privacy settings and provider update/rollback acceptance are NOT closed by this
slice. No new settings policy, clearing commands, price/payment, importer, Vault
or rule interpreter. Existing AppSettings owner/store and four-root NavHost remain.

Compact: category list -> existing detail -> Back to list -> original onBack exit.
Expanded: category+detail with one shared AppSettings input, default appearance
selection if none restored. Back exits auxiliary Settings; licenses Back closes
notices first and retains category. Category keys are explicit strings, not ordinal.
rememberSaveable preserves the local category across configuration/recreation;
source model tests verify key transitions, not actual Android SavedState acceptance.

The page reuses responsivePaneLayout for remaining content geometry. No new window
threshold/model list/persistence framework. Low-height/large-font fallback is one
compact scroll including heading/back; wide panes are independently bounded under
one weighted Row. Category/detail children do not scroll: no nested vertical scroll.
SkinKit-derived visuals/Esv controls and standard RadioButton/selection semantics.
EN/ZH adds two paired labels. Theme and language copy only the corresponding
appearance field; browser/discovery/schema and the other appearance field remain.

Ten new tests: stable IDs/invalid restore fallback, compact and wide Back, notice
priority, selection across geometry/font changes, exact preference-field preservation,
source scroll hierarchy and actual Foundation finite-constraint contract. These do
not establish device pixel parity, glyph wrapping, TalkBack, 200% font or wide/OEM
interaction. Existing offline notice viewer remains separate; no resource IO added
to composition. Test/build/lint outcomes recorded only after verification completes.

Changes limited to Settings screen/model/resources/tests and this documentation/
ledger, leaving MainActivity and media/initialization/directory-fix code unchanged.
Parallel urgent media changes must be coordinated separately. Source is for review;
no new package or payment/Release entitlement changes in this group.

Final10 actual tests PASS/0 failure/error/skipped; dual Kotlin executed; lint59W/8H/0E.
67 tasks:29 executed/38 up-to-date. Evidence artifacts/ui/settings-category-detail-20261012.
Code8 fixed input SHA rechecked unchanged; no APK or device/test-directory operation.
