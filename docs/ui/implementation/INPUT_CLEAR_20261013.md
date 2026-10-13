# Explicit input clear actions

Original UI 01 §6 C-02 requires address/search clear, submit, error and IME behavior;
§7 requires visible meaningful labels and minimum 48dp targets. Existing submit/IME routes
already work and are deliberately preserved. This slice adds the missing explicit Clear
at the Browser address draft, new-tab address/query draft, and Discovery query draft.

All three use the existing theme-backed EsvButton, a visible current-language resource label,
a field-specific semantic label, and explicit 48dp minimum width/height. English and Simplified
Chinese resources are paired. Clearing whitespace is allowed; clearing an empty field is disabled.
No navigation, search, network request, media command or history deletion is sent by Clear.

Browser uses one composition-owned opaque-token gate for each field. Observable token bridges
force new callbacks after every field edit, address synchronization or new-tab mode change,
including A→B→A or same-value edits. Each accepted clear consumes its token before changing text.
Owner disposal and accepted new-tab submission retire the relevant gate. Tokens retain no user
text/URL and are never saved. Click-time checks revalidate tab context/epoch and the existing
saveInspectionRevision, including switching away and back. Its existing counter is now Compose
observable, so repeated invalid Go actions also refresh clear claims even without tab changes. The current interaction environment
is read through rememberUpdatedState and rejects inactive pages, preview-only hosts, file/JS
prompts, fullscreen/picker owners, settings/bookmarks/consent and save inspectors. Address Clear
sets editing=true to preserve the empty draft without navigating the live page; new-tab Clear
resets only its text/error, keeping the selected interpretation mode.

Discovery has a dedicated clearFilterQuery entry. Its old continuous IME editText intentionally
rebases and is unchanged. Clear instead requires foreground/visibility, the existing ready,
storage, busy, profile/demo/target gates, captured page lifecycle, and exact draft object identity before the existing
compare-and-set update. Identity rejects equal-content A→B→A callbacks. Only query becomes empty;
other draft filters, applied profile, materialized results and task/history state stay unchanged.
The query action rechecks current local and global modal visibility via its actual MainActivity
clear-only callback, including live save selection/preparation and destination dialogs; leaving and returning rejects old lifecycle
claims. Browser rechecks the live runtime scanning flag even before recomposition refreshes its
parameter copy. These gates were tightened after independent review and before final freeze.
The accepted user edit supersedes any waiting Browser draft through the existing draft-update
behavior; no second command or state owner is introduced.

App 1835 JVM tests actually executed PASS, including 22 new tests; zero failures,
errors or skips. Debug/Release Kotlin and both lint tasks executed PASS in final build4.
Both lint reports retain 60 warnings / 8 hints / 0 errors; runtime admission verifies 90 exact
artifacts. All 473 other original production/config files match pre-slice hashes.
The 18-file body-state freeze remains intact; only its live DiscoveryScreen is intentionally
superseded in this slice. See artifacts/ui/input-clear-20261013 for sanitized logs, test census,
new-test XML, lint reports, source pins and independent review.

The earlier Discovery body-state freeze is immutable; the combined publication intentionally
uses this final DiscoveryScreen. Existing body classification and recovery buttons are preserved.
NOT_RUN: APK assembly, phone/emulator, actual IME/keyboard/TalkBack, 200% or narrow-window pixels,
PPT/screenshot comparison. Source-wire fixtures prove the intended calls and guards, not runtime
Compose event ordering or rendered hit rectangles. No full C-02/UI-E acceptance is claimed.
