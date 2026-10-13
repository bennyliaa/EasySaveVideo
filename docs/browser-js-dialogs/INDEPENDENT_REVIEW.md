# Independent read-only review

2026-10-13. Separate reviewer inspected source only; no builds, devices or network operations.

Five P2 findings were raised and corrected before the final freeze:

1. Background-tab events canceled the selected tab's prompt. Pending requests now own a WebView identity; entry-specific cancellation is scoped. Added owner regression.
2. Fixed title/actions could starve the message/input of height in short windows or large fonts. A single bounded outer scroller now contains content and controls. Added actual Foundation ScrollNode measurement with synthetic heights, not claims of rendered text/device coverage.
3. Origin canonicalization could throw for oversized DNS labels. Local nullable canonicalization catches failures both on admission and completion. The first focused run actually reproduced this failure; evidence is preserved.
4. `load(request)` retained a global cancellation path for background script navigation. It now resolves an exact request context and cancels only its view. Added explicit integration-source regression.
5. Consecutive dialogs could retain the previous scroller's bottom position. The dialog subtree is keyed by request identity. Added source wiring regression.

Final independent re-review reported no remaining definite P1/P2 in the slice. It recommended retaining inline native-only result completion rather than introducing a posted queue with new lifecycle revocation races. A final non-blocking test assertion-strengthening suggestion was applied and reverified; see verification.

Limits: this is source review, not end-to-end Android/WebView, website, login, account, permission or accessibility acceptance. See ANDROID_ACCEPTANCE_NOT_RUN.md.
