# Browser JavaScript dialogs — bounded native interaction

Date: 2026-10-13. Source-only compatibility slice; no APK assembly, device or public-site validation.

## Original requirement evidence

- `docs/requirement and design/01_Product_Requirements.md`, PR-03 and §5.1: basic navigation/multiple tabs remain usable with zero Providers; basic browsing includes webpage login, Cookie/Session and JavaScript.
- PR §5.1 also treats page content as untrusted. This slice displays page text but does not interpret it as app instructions, HTML actions, permissions or credentials.
- `docs/requirement and design/04_Technical_Requirements.md`, §4/TR-05/18: main-thread WebView lifecycle, callback/resource release, source/user-controlled permissions and no sensitive Session logging.
- Original requirements do not separately prescribe `alert`, `confirm` and `prompt`. Previously all three were canceled by `SystemBrowserHost`; this implementation repairs that compatibility gap. It is not a claim that general website login is validated.

## Supported behavior

Current visible, interactive browser tab may show one native alert/confirm/prompt at a time. Same-origin HTTP(S) callback/page origins are required and are canonicalized defensively. Unknown, malformed, opaque or cross-origin frame sources are rejected. Display source contains scheme/host/port only, maximum 300 UTF-16 code units; paths, query strings, fragments and userinfo do not enter the label.

App-owned localized titles and buttons are separate from untrusted plain page text. Message text is bounded to 4,096 UTF-16 code units plus a truncation marker (without splitting a surrogate pair at that boundary); control/bidirectional override/isolation characters are neutralized for display. Prompt default/input is bounded to 1,024 UTF-16 code units. An oversized default is canceled, while oversized submitted input leaves the prompt open instead of silently changing the answer. A webpage-supplied default is shown masked, not automatically submitted; no app credential source or autofill integration is used.

Only an explicit OK returns confirmation or the exact typed prompt value; cancel/back/outside dismissal returns cancellation. Empty prompt text is a valid, distinct accepted answer. “Cancel and block dialogs on this page” also blocks further dialogs for that document. Normal dialog display is limited to three per document and three per global 30-second monotonic window. There is no pending-dialog queue. A navigation can reset its document budget but cannot reset the global budget. Over-budget requests are canceled rather than automatically answered affirmatively.

The UI uses SkinKit visual primitives and a single bounded outer scroll container. Request identity keys the dialog subtree so each new source/message starts at the top. All content and actions remain in that scroll owner for short windows/IME/large fonts. Prompt state is `remember(request)`, never saveable/persisted; it uses password keyboard/visual masking, disables autocorrection, and the dialog requests a secure window. This is not a guarantee of secure memory erasure or third-party IME behavior.

## Lifecycle and once-only ownership

The pure Kotlin controller retains one opaque request identity and owning WebView identity. It clears pending presentation before native completion, ignores duplicate/stale UI actions, and rejects reentrant presentation while completing a result. The adapter only completes native JsResult; it must not synchronously evaluate page JavaScript from that callback. No asynchronous native-result queue was added: that would require an extra revocation fence for away-and-back/pause-resume races.

Acceptance rechecks active Entry identity, document generation, tab epoch, current request, source, foreground, selected tab, JavaScript setting and visible/unblocked browser surface. User navigation, tab switching, background, browser-screen disposal, app close, competing modal surfaces and disabling JavaScript cancel pending interaction. Entry-specific starts/history updates/errors/timeouts/view release/renderer loss cancel only that owning view. A background tab cannot discard the selected tab's prompt. Ordinary main-frame navigation through `load` likewise resolves the exact request context before canceling its view.

The recording-preview BrowserScreen explicitly disables dialogs. File selection, fullscreen, browser settings/bookmarks/cookie confirmation and save-selection preparation block competing JS UI. Existing HTTP authentication and permission behavior remains unchanged. Before-unload confirmation remains explicitly canceled, rather than silently inheriting a platform dialog path.

## Privacy and scope exclusions

No page message, prompt default/input, URL, token, cookie, title or exception message is logged, checkpointed or added to diagnostics. Native completion exceptions are not retried. The request/controller holders do not have generated content-bearing `toString` methods.

No dependency, network/security setting, provider, history-retention, vault, interpreter, real account or device change is included. Cross-origin iframe dialogs, before-unload and HTTP-auth prompts remain unsupported. Native WebView callback timing and real UI/device acceptance remain open and are listed separately.

## Next original independent requirement

PR §5.1 explicitly requires a new-tab “search videos” input and correctable address/keyword routing. The current new-tab screen has an address field and a Discovery navigation button, but no dedicated video-search input. A separate bounded handoff to the existing Discovery query flow can address this without new Providers. URL input must stay in the browser unless the user explicitly chooses video search; zero-Provider browsing must remain available.
