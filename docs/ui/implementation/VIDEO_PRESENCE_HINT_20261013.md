# Browser lightweight video-presence hint

## Requirement and scope

This slice implements the missing separation in the original Product §5.1 third bullet
(line 128), PR-09 (line 102), Technical §4/TR-05 Detector (lines 110–120), and
Acceptance T-S01/02 (lines 131–132). It preserves the new-tab input implementation.

Before this change, BrowserScreen enabled a permanent Inspect button on every READY
page. PageSaveDetector already performed full media inspection only after the user's
click; it is unchanged. READY was not evidence of video presence.

The native Browser now schedules bounded advisory top-document/light-DOM video-element
checks. PRESENT displays Inspect and Recording options. Inspect follows the unchanged
AndroidSaveRuntime user-click/candidate-selection chain. Recording options only navigates
to the existing Record screen; it neither launches capture consent nor starts recording.
More controls always retains an explicit manual Inspect action for a READY page, including
UNKNOWN/NOT_OBSERVED and JavaScript-disabled pages. It preserves existing scanning and
candidate/stream preparation gates.

This is element-presence evidence, not playback, media identity, export support, rights,
or an authorization decision. A VIDEO element with no source is still element evidence.
False means NOT_OBSERVED in the inspected top-level light DOM at that time, never a claim
that the webpage contains no video. Frames, shadow roots, late insertion beyond the two
checks, very large documents and unsupported rendering can remain unobserved/UNKNOWN.

## Privacy and boundaries

The fixed script visits at most 512 DOM nodes with SHOW_ALL TreeWalker, counting text and
comment nodes too. It reads nodeType/localName only and returns true, false or null.
It does not read media URLs, currentSrc, duration, mediaKeys, titles, text, credentials,
Cookie, location or frame/shadow contents. No network API, bridge, observer, page timer,
Provider, dependency, permission or persistence is added. Native page identity checks use
the host's existing page-address ownership, never media-resource extraction.

Page APIs/content remain untrusted and may falsify an advisory result. Nothing returned
by this script can select resources, create a save task, obtain credentials or grant
permissions. The node cap bounds our loop; it is not a guarantee that a hostile renderer
can be forcibly interrupted. Native timeout drops the result, not execution inside an
already unresponsive renderer.

## Engineering budgets and lifecycle

The following are conservative engineering configuration values, not newly decided
product settings: two attempts per actual document, four attempts per rolling 30 seconds
across the host, at least 1.5 seconds between attempts, one-second native response timeout,
one active operation. PRESENT ends automatic rechecking. Negative/unknown may receive
one delayed recheck within the remaining budgets. Exhaustion never starts a permanent
cooldown or background polling loop.

A document-local budget lives in its Entry and is replaced on onPageStarted. Tab switches,
route changes, pause/resume, settings dialogs and history updates never refund attempts.
Actual dispatch counts even if it times out or is retired. Global accounting stores at most
four timestamps. No result/budget is persisted.

Automatic checks require current selected READY Entry/View, current request, accepted
native page address, JavaScript enabled, Activity foreground and visible interactive Browser
surface. An independent Activity-supplied route/preview callback is also checked so an old
composition cannot reopen detection during route exit. Recording previews and hidden routes do not start checks. Native execution and
response receipt revalidate target ownership. A generation fences cancel/resume even when
the same target identity returns. Target also contains owner identity, document budget
identity, epoch, interaction revision and Entry-local navigation revision.

The Entry-local revision avoids an independently reviewed defect in the initial candidate:
background-tab completion/history must not clear foreground-tab evidence or consume its
remaining attempts. The initial fixed time window was also replaced with a rolling window
to avoid bursts across a window boundary.

Navigation start/stop/failure, close, renderer loss, selected View replacement/eviction,
settings changes, same-document history, foreground changes and route visibility changes
retire stale work through the host's state synchronization. Closing removes native delayed
work/timeouts and prevents restart. Full media scanning and its own fencing remain separate.

## Validation boundary

Pure Kotlin controller tests exercise budgets, timing, cancellation, owner/document/epoch/
revision replacement, duplicate/late callbacks, exceptions and close. Source/resource
contracts check native/Compose wiring, independent gates, manual fallback, recording-only
navigation, bounded script and both locales. Node executes the production script against
owned synthetic DOM fixtures with throwing forbidden getters/network/timer APIs.

These do not constitute Android/WebView/Compose runtime acceptance. No device, emulator,
real website, login, permission grant, APK build/install or recording is exercised here.
Existing same-document full-save limitation is unchanged: SaveInspectionPage may reject
inspection when tab.address differs from a history-updated view.url. Presence tests do not
claim that separate save flow passed. Late dynamic content/manual fallback remains a stated
coverage limitation, not a claim that no video exists.

Final executed verification and evidence hashes are recorded in the adjacent artifact
validation report. No new public-site/source/browser acceptance or dependency admission is
implied by synthetic tests or existing corpus results.

## Final verification result

Final serialized offline rerun: **BUILD SUCCESSFUL, 91 actionable tasks / 91 executed**.
App **1653 tests, 0 failures/errors/skips**, including 14 new controller tests and 7 new
source/resource contracts. Discovery 307, core-domain 44, core-model 3, Commons 247,
SearxNG 25, verification-client 44 passed. Verification-local reports 34 cases with
**2 private-corpus cases skipped because their corpus was not configured**; these are
not passes. Both Kotlin variants and both lint variants completed; each lint has
0 errors, 60 warnings, 8 hints (same counts as the predecessor). Synthetic script
22 checks and boundary checks passed. Exact 77-artifact admission was verified.

An intermediate rolling-window test failed because its fixture waited past the native
timeout before replying, producing an allowed retry. The fixture was corrected to reply
at dispatch, retaining the rolling-window assertion; this final full rerun passed.

Evidence: `artifacts/ui/video-presence-20261013/validation.json`, code hashes, sanitized
build output, the two new JUnit suites, script/boundary reports and independent review.
