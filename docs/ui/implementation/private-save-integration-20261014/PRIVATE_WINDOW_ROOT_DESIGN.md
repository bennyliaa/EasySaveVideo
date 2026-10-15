# Private window guard: frozen first-slice contract

2026-10-14. Extracted from the read-only private recording integration investigation. No source, build, remote or device changes. This slice must not enable private browsing or recording by itself.

## Frozen first slice: real window and structure protection

Minimum new files:
1. `app/.../browser/privacy/PrivateContentRoot.kt`: an app-owned `FrameLayout`, structure suppression, direct opaque cover, and testable state.
2. `app/.../browser/privacy/PrivateWindowGuard.kt`: Activity-owned registration of exact Window/Decor/content-root identities, synchronous protection requests, capability reporting, and no media ownership.

### PrivateContentRoot contract

- Place all app content capable of showing private data below this real Android parent. Private protection is an explicit main-thread state; do not infer it from a Compose semantics node.
- While private/transition/capture suppression is enabled, override `dispatchProvideAutofillStructure(structure, flags)` without calling `super`: set this root's `AutofillId`, `AUTOFILL_TYPE_NONE`, and zero children. Populate no text, hints, URL, HTML information, content description, or virtual descendants. Apply this for `AUTOFILL_FLAG_INCLUDE_NOT_IMPORTANT_VIEWS` too. Outside the protected interval, delegate unchanged to `super`.
- Also suppress the private hierarchy in `dispatchProvideStructure` (an empty own node/zero children, no `super`) for Assist structure traversal. This is structured-data containment, not a promise to prevent every system screenshot or accessibility observation.
- Install `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS` and `IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS` on the real DecorView, `android.R.id.content` parent and app-owned root; store/restore prior ordinary values only after safe ordinary presentation. Check actual values on these real Views. Do not require AndroidComposeView to honor the setter: the exact admitted Compose 1.8.3 binary was independently checked in `PRIVATE_UI_REVIEW.md` and its autofill getter returns YES.
- The opaque cover must be an actual native drawing/input barrier owned by this root, not only a later Compose recomposition. Set it before hiding/reparenting/replacing the target. Cover failures leave private content hidden and readiness false.
- Cancel the existing Activity autofill session on entering/leaving private UI; do not disable the user's installed autofill service. Root traversal tests alone do not prove absence of every provider/WebView direct notification; actual autofill-service and WebView/Compose interaction testing remains a release gate.

### PrivateWindowGuard contract

- Install before first private content can attach. Start Activity recreation/cold-start paths protected; never restore an exception from Bundle or saved Compose state.
- Normal private state: `FLAG_SECURE` set, root structure suppression active, content capture explicitly disabled, safe generic task/window labels. A transient opaque cover is additionally used for transition/failure/teardown.
- Obtain `ContentCaptureManager` from the Activity context; call `setContentCaptureEnabled(false)` before private content, and keep it disabled through the temporary capture exception. A missing manager is absence of that facility, not an error. At targetSdk36 this is a documented independent control; re-review before raising target to37 because the current documentation changes that contract. Keep view-level suppression too. Do not use `removeData` as task-snapshot cleanup.
- API33+: call `Activity.setRecentsScreenshotEnabled(false)` before private display and retain it through arming, recording and protected teardown. There is no getter: evidence means exact Activity/API/invocation ownership, not fabricated readback or a device test. Restore only after safe ordinary content is presented and no private transition remains.
- API31/32: return an explicit legacy-evidence-unavailable capability. `AppTask.setExcludeFromRecents(true)` plus exact `taskId`/root-Intent flag readback is exclusion evidence only. It must never issue `LEGACY_VALIDATED_ISOLATION` on its own.
- `openException` checks exact window/root/target/presentation identity, resumed+focused Activity, all independent protections and `stillAllowed` immediately before clearing only this capture Window's `FLAG_SECURE`. Do not clear other application windows. It returns false on any uncertainty and leaves the cover/protection in place.
- `protectAndStopCapture(): Boolean` keeps its present synchronous shape: true means secure was requested and a sticky native cover/drawing-input barrier was installed on this main-thread turn before any replacement/navigation. It is not a compositor acknowledgement. The cover remains over all replacement content until a separately validated safe presentation; setting `visibility` or reading `window.attributes.flags` alone proves neither drawing nor WindowManager application. An outstanding frame acknowledgement is pending, not false and not a reason to permanently block the host. False is reserved for a real inability to install the local barrier.
- `PrivateCaptureSurfacePort.restoreProtection` uses the existing separate callback for capture-policy settlement: begin protection synchronously, then acknowledge the real owned cover draw/frame submission (or another independently reviewed actual hidden-surface condition). Keep it pending if there is no draw; never convert a timeout into protection success. A frame-commit callback still does not prove global compositor ordering or deletion of older system snapshots. Real WebView/WindowManager ordering is an Android gate. If the actual target can bypass the cover, stop and propose the smallest deferred host-transition result instead of fabricating evidence or widening the synchronous Boolean.
- Register/unregister each app Dialog/Popup as a distinct window/root before showing private contents. Keep these windows secure even while the main capture Window is temporarily nonsecure. A dialog's default inherited secure policy is insufficient when the parent is deliberately cleared. Wrap dialog/ComposePopup content in the same real private root, and control its actual window's secure policy. A missing root/window registration is a blocker for that private UI.
- For the first recording slice, setup/disclosure/record controls are inline. No app-owned Dialog/Popup is allowed during ARMING/ACTIVE: protect/revoke first, then open it. Inventory WebView-native transient presentations separately; an after-the-fact window scan does not prove pre-first-frame protection. Do not claim blanket all-window coverage until this is verified.

### MainActivity composition change

Replace the current direct `ComponentActivity.setContent` host with `setContentView(PrivateContentRoot)` containing one `ComposeView`, then `ComposeView.setContent` with the unchanged app composition. Use ComponentActivity's public content-install path so ViewTree lifecycle, ViewModelStore and SavedStateRegistry owners remain installed; do not create a second Recomposer or new navigation owner. Verify owner lookup from the child and composition disposal on Activity destruction. Maintain existing insets/window behavior. A wrapper installed only around BrowserScreen cannot protect private data in separately rooted dialogs.

### Required first-slice tests

- Ordinary structure delegation unchanged; private autofill/Assist traversal has no child fields even with INCLUDE_NOT_IMPORTANT; returning ordinary restores only after safe presentation.
- Real root and Decor readback, exact Compose1.8.3 owner hierarchy, initial-frame cover, recreation, root replacement, failure while setting protection, and per-dialog registration/disposal.
- Explicit content-capture disable persists while FLAG_SECURE is cleared; independent recents API33 path; API31/32 cannot manufacture suppression evidence.
- No readiness or capture permission from pure flag tests alone. Android/framework tests are still required; no device evidence was generated here.

### Review amendment: exact frame acknowledgement and input revocation

- Every protection activation increments a main-thread protection generation and captures exact Window, DecorView, PrivateContentRoot and target/presentation identities. A completion callback is valid only for that same still-current generation and identity tuple. Replacement, disposal, a newer cover, or renewed capture invalidates earlier callbacks.
- Record in the root's actual draw path that this generation's opaque cover was drawn; only a subsequent real hardware frame submission/commit callback for that generation may acknowledge it. A pre-draw callback, an old frame callback, a frame that never drew the cover, or a software-rendering/no-op registration cannot settle protection. Unsupported/missing acknowledgement remains pending with the cover retained, or uses a separately reviewed actual hidden-surface condition. Do not turn it into success or a permanent failure merely because no frame was available. Physical compositor ordering remains the explicit Android gate.
- The cover is an input-revocation boundary, not merely a clickable child. Set its protection/input generation before callbacks can run; consume touch (including an ongoing gesture), pointer/generic-motion and key dispatch at the real root while covered. Cancel existing interactions, clear focused descendants and dismiss the IME through the owned Window. Do this for each affected root, not only the main Compose child.
- Keep the existing owner-fenced address/query/Go/clear handlers. Feed cover state/generation into their live admission predicate before clearing focus; stale or already-posted IME action callbacks must fail admission. Hiding the IME and clearFocus are best-effort presentation cleanup, not revocation evidence.
- A ViewGroup cannot intercept direct calls to an already-created child InputConnection. The Compose/WebView input owner must therefore invalidate its connection/action generation when covered and reject stale edit/action callbacks; use a small forwarding InputConnection wrapper if the current owner lacks that gate. This is an explicit integration requirement, not a claim that the root alone blocks native IME calls. Until verified, report input containment incomplete and keep overall private readiness false. Ordinary input delegation remains unchanged.
- Tests must include old/new root and generation swaps, callback-before-cover-draw, delayed older commit, software/no-op frame handling, active drag and hardware key, focused Compose and HTML input, and a retained old InputConnection/IME action delivered after covering.

## Official references

- Autofill traversal and INCLUDE_NOT_IMPORTANT override contract: https://developer.android.com/reference/android/view/View#dispatchProvideAutofillStructure(android.view.ViewStructure,int)
- Assist traversal: https://developer.android.com/reference/android/view/View#dispatchProvideStructure(android.view.ViewStructure)
- Activity-scoped explicit content-capture disable (API29, target36-or-lower contract): https://developer.android.com/reference/android/view/contentcapture/ContentCaptureManager#setContentCaptureEnabled(boolean)
- Independent recents suppression, API33: https://developer.android.com/reference/android/app/Activity#setRecentsScreenshotEnabled(boolean)
- Exact-task exclusion, only root-Intent flag modification: https://developer.android.com/reference/android/app/ActivityManager.AppTask#setExcludeFromRecents(boolean)
- Exact admitted Compose1.8.3 binary finding: companion PRIVATE_UI_REVIEW.md, section P1-2. Current upstream main was not substituted for exact binary behavior.
