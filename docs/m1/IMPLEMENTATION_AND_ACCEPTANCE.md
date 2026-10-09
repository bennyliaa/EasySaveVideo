# M1 development candidate — implementation and acceptance

## Scope and trace

The implementation follows the original eight documents, with Architecture update_1 and Milestones update1 governing the updated route. The later user decision changes minimum Android to 12/API31 and includes both phone and tablet layouts. Update1 milestones are M0–M12; M3 is Direct/Stream, M4 is Record, and real payments are M10.

| Requirement | M1 implementation | Evidence boundary |
|---|---|---|
| PR-01 / TR-01 clean native foundation | com.easysavevideo.app; Kotlin, Compose Foundation, min31/target36; independent Core modules | Debug/Release compilation; no package assembly or launch |
| Browser / TR-05 | HTTP(S) address, native WebView page rendering, back/forward/reload/stop; add/close/switch up to 12 tabs; 1–4 live WebViews | Android compile and policy tests; browser/device scenarios NOT_RUN |
| Core Ports / TR-02 | OperationContext, cancellation/deadline/budgets, ErrorEnvelope, BrowserSessionController, TaskSnapshot/commands/observation, Recovery ports | 30 Core JVM tests |
| Settings | JS, third-party cookies, restore addresses, active-view cap, explicit shared-cookie clearing, cache clear | Compile, codec tests; system CookieManager operation NOT_RUN |
| Entitlements / TR-13 | free Browser command gate, fine-grained FeatureGate, EntitlementManager; StoreAdapter; store-side ProductMapping; Debug-only Fake source/controls | Core matrix tests; Release class scan |
| Reliability / TR-14–15 | ERROR enums only, no console forwarding/debug WebContents, 32-entry bounded logger queue; max five 51KiB daily buckets, seven-day expiry; startup IO off main with 2-second shell deadline | Static review; runtime lifecycle/stress NOT_RUN |
| SkinKit integration | true internally owned Contract/Default 0.0.1; 22 required tokens covering seven categories; full light/dark data, pin/release snapshot Compose bridge | 9 theme JVM tests and original artifact hashes |
| Provider independence | No provider library, service, account, or server needed by build/Browser/startup | Runtime graph and imports; no real Provider implemented |

## Browser/security decisions

- Only valid HTTP(S) URLs are accepted, without userinfo, control characters or file/content/intent/javascript schemes. Plain HTTP remains an explicit normal browser capability; mixed HTTP content inside HTTPS is forbidden.
- TLS errors always cancel. HTTP-auth dialogs, camera, microphone and location requests are denied with a stable source-scoped message. No JavaScript/native bridge exists. Normal HTML/JS website login remains a WebView capability, untested with real accounts here.
- Ordinary tabs intentionally share the platform CookieManager store. Third-party cookies default off. No incognito switch or per-tab cookie-isolation claim is exposed.
- File inputs require an origin-displaying app confirmation and ACTION_OPEN_DOCUMENT. Only selected content URIs, at most eight, return to the original tab/epoch/origin callback. Navigation, tab switching, close or destruction cancels pending callbacks. The picker lifecycle is distinguished from ordinary backgrounding. No camera capture or broad storage permission is requested.
- Every controlled navigation/reload/back/forward creates a fresh WebView instance and restores a bounded in-memory native history. Callback entry identity fences the old View. NavigationAdmission additionally requires the expected/approved redirected main-frame start before finish/error acceptance. The main-frame start callback does not assume WebView.getUrl has already advanced. Back/forward admits the exact target from the native history list.
- Inactive WebViews are evicted under the configured live-view cap; native history is limited to 128KiB per tab, addresses to 12 tabs. Persistent checkpoints contain addresses/IDs only, not cookies, native history or titles. Changing Activity configuration or restarting requires restoring addresses; live playback/form continuity has not been claimed.
- Each renderer-gone callback removes and destroys its exact failed View once. Android supplies callbacks for all Views affected by a shared renderer. Healthy tabs are not guessed to be dead. Reload explicitly recreates the failed tab; there is no automatic crashing-page reload loop.
- Fullscreen callbacks are bound to the current Entry generation. Back/exit releases the custom View and restores system bars/keep-screen-on. Foreground creation resumes global WebView timers so Activity recreation cannot leave the renderer permanently paused.
- WebView downloads show an honest M3-not-implemented notice. There are no fake saved files, recording buttons or successful media tasks.

## Persistence and startup safety

Settings use primitive private SharedPreferences; complex browsing state uses a bounded, versioned, validated binary checkpoint and AtomicFile. All IO is off main; writes are coalesced, not one queued write per WebView callback. The shell appears without waiting for recovery. Late startup results never replace a user-edited session or settings.

A failed or timed-out session recovery pauses future checkpoint writes, preserving the original unread/corrupt file. The user can explicitly reset saved addresses by turning off the setting whose text explains deletion. A storage write failure displays a fixed warning that next startup may retain the old state. No failed recovery deletes media or a database; no media database exists in M1.

The two-second startup deadline bounds the UI's wait, not kernel IO execution. Local recovery reads are size-bounded; a blocked filesystem call cannot be forcibly preempted by the Core port. No claim is made that all native failures are catchable.

## Verification completed

- Separate Gradle modules compile: core-model, core-domain, ui-theme, app Debug and Release.
- 51 JVM tests: Core 30, theme 9, browser policy/admission 6, private checkpoint codec 6.
- Debug and Release lint: zero errors. Fixed target/compile API36 is retained rather than silently adopting API37; remaining warnings/hints are recorded in evidence.
- Exact runtime admission: 53 reviewed official components plus two internally reusable SkinKit artifacts, identical Debug/Release bytes. A preBuild task fails if any runtime GAV, filename or SHA-256 differs. Duplicate identical androidx.core inventory entries are deduplicated.
- Fresh dependency locks and SHA-256 verification metadata are generated from recovered official HTTPS artifacts. These are new verification records, not invented old hashes.
- Release output contains no FakeEntitlementSource; no development preset strings or real purchase SDK.
- Original specs and the new 100-file license evidence bundle were verified byte-for-byte against their supplied manifests.

## Not run / acceptance pending

No APK/AAB was assembled, no emulator/device was launched, and nothing was installed, uploaded to a store, published, paid for or deployed. Consequently these remain NOT_RUN: Android12 and current-device launch, phone/tablet visual and accessibility inspection, real WebView playback and full-screen, JS/form upload with a document provider, cookies/logins, Activity rotation, repeated navigation with real callback timing, shared renderer crash injection, system-back flows, low-memory/device-storage failure and live Logcat privacy canaries.

The current tag is a **M1 development code baseline**, not formal M1 device acceptance or a release. Compilation and JVM tests do not replace those platform checks.

## M2 executable next scope

1. Add platform-neutral `discovery` models and state: SearchProfile/session+epoch, CandidateUrlPool, validated VideoSearchResult, bounded ResultPool, immutable pages, independent ProviderProgress and Seen facts.
2. Define replaceable Provider and Validation ports, cancellation/deadline/schema/field-length/domain budgets; use deterministic synthetic test providers first. No Scrapling/NewPipe/SearXNG code is admitted implicitly.
3. Implement Normalize/Filter/Deduplicate, foreground-only Scheduler, low/high-water and 500-candidate caps, per-provider timeout/backoff and late-epoch rejection. Persist progress+pool atomically only after successful acceptance; no incognito persistence.
4. Add responsive Discovery grid/pagination/settings next to the existing address path, preserving zero-Provider empty states and browser availability.
5. Select an actual lawful API/provider separately with exact license/terms and source evidence before wiring it. Real search results and M2 exit cannot be claimed from fake fixtures alone.

Ownership suggestion: new discovery/core packages and tests in an independent worker; app Discovery UI/storage/platform wiring and root Gradle by one integrator. Keep existing Browser/SkinKit interfaces stable. Continue code work while the explicitly deferred device acceptance remains tracked.

### Final evidence details

Final strict offline compile/test/lint completed successfully with the regenerated locks enforced. The 51 JUnit XML results have zero failures/errors. Each lint variant has four warnings (fixed target36, newer compile SDK available, two optional KTX suggestions) and two state-autoboxing hints, no errors. The real Gradle admission task was also negatively tested by temporarily removing one approved row: it failed with the expected unreviewed-runtime diagnostic, and the exact allowlist SHA-256 was restored before the final pass. Read `evidence/verification-summary.json` and `evidence/final-strict-offline.log`.

The Android startup path now invokes the platform-independent RecoveryManager using bounded Settings/BrowserSession probes on its IO executor; the shell deadline/cancellation remains enforced independently on main, with failed session writes paused as described above.
