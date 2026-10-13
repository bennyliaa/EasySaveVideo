# Discovery shared filter panel: explicit presentation role

Original UI 01 §5 distinguishes surface/elevated colors and control/card/sheet corners.
The earlier semantic-token slice deliberately deferred Discovery's shared filter shell.
The real sidebar and existing Dialog now explicitly supply INLINE/FLOATING roles to a
small pure mapper: INLINE uses surface/cardCorner; FLOATING uses elevated/sheetCorner.
The current detached SkinKit values are consumed rather than re-created as fixed constants.
No provider, token, dependency, resource loading or dynamic-color path is added.

Only the outer background/corner changes. The original spacingLarge padding and inner
control shapes remain. One remembered movableContent receives Modifier and presentation;
source-editor drafts/claims, scroll state, filter state, dismissal, BackHandler, query-clear
ownership, shown-result blocking, page visibility and all callbacks remain unchanged.
The shared content is not duplicated or keyed by the role.

The actual floating host remains a Dialog. This is not a BottomSheet implementation and
not a declaration that all UI-A..E or original visual specifications are accepted. Inline
cardCorner follows the existing semantic panel convention; the original document does not
separately prescribe an exact sidebar radius.

App 1870 JVM tests actually executed PASS, including six new tests (two visual-value and
four source-wiring fixtures), with zero failures, errors or skips. Debug/Release Kotlin and
both lint tasks executed PASS in build1; each lint retains 60 warnings / 8 hints / 0 errors.
Runtime admission verifies 90 exact artifacts. Theme module tests were not rerun. All
492 other original production/config inputs match pre-slice hashes. The preceding typed
destination freeze remains intact. This source-verified slice awaits publication.
See artifacts/ui/discovery-filter-role-20261013 for sanitized logs, actual JVM census,
new-test XML, lint reports, source pins and independent review.

The two pure-value fixtures cover actual Light/Dark defaults at 200% configuration and
non-default colors/card13/sheet21 through a single pinned/detached snapshot. Four source
fixtures verify actual hosts, sole movableContent, container consumption and preserved
ownership/gates. Existing low-height/font-scale responsive policy fixtures remain in the
full App test run. None of these fixtures renders Android UI.

NOT_RUN: APK, phone/emulator, actual layout/200% clipping, open-panel window transitions,
source-editor draft/scroll preservation on device, TalkBack, screenshots or PPT. The original
PPT binary remains unavailable. Actual bottom-Sheet behavior and broader semantic state
coverage remain separate work.
