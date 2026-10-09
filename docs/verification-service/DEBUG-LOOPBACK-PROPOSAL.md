# Minimal debug-only Android loopback design — proposal, NOT enabled

Verified current state: main AndroidManifest.xml already declares usesCleartextTraffic=true for the existing app/browser. The production VERIFICATION client independently requires public HTTPS443 and /v1/verify. Do not describe the whole existing Release app as globally HTTPS-only. Neither policy was changed.

Minimal approval requested (recommended):
- Windows service remains exclusively127.0.0.1:8765.
- After user notification that emulator-5556 is ready, add ONLY its adb reverse mapping: `adb -s emulator-5556 reverse tcp:8765 tcp:8765`.
- A dedicated src/debug-only original verification transport/registration targets EXACTLY `http://127.0.0.1:8765/v1/verify`. It rejects any other address/port/path, redirects, cookies, auth, compression and network proxy. It remains off until explicit per-process consent; UI must identify this as LOCAL HTTP, not imply TLS. Reuse bounded identity/protocol codec; do not relax the shared production endpoint validator or SystemVerificationTransport.
- No Manifest/network-security-config exception is necessary for this existing APK policy. No Release classes, HTTPS/public-address validator, trust anchors, firewall/LAN or system security changes.

Security change requiring approval: ONLY the debug verification client's fixed-loopback HTTP exception plus device-specific reverse transport. Loopback transport is unencrypted and a local process able to access the port may observe/submit local development traffic. This carries only bounded candidate/logical fixture URL, request/generation IDs, budgetMs and dynamicAllowed plus bounded page evidence JSON; no private media, cookies, tokens, credentials or downloaded content. Only constructor-owned fixtures are admitted on the host; ordinary endpoint currently has zero sources. No accounts/credentials/paid services are created.

Rollback: close the debug transport and revoke its per-process consent; remove EXACT device mapping using `adb -s emulator-5556 reverse --remove tcp:8765`; disable/remove the src/debug registration. Stop the owned host service via its managed terminal session if requested. No app-data deletion, uninstall, trust-store change or broad `reverse --remove-all` operation. Restarting the app restores default-off consent.

Optional separate hardening (NOT part of this minimal request): src/debug network-security-config with base cleartext=false and only127.0.0.1 true. This additionally prevents the Debug Browser's other HTTP pages, so it needs a separate product/security decision. Release would remain untouched.

No debug transport exception, manifest configuration, adb mapping, APK/AAB, device install/start/poll has been implemented. Install later requires higher versionCode and the existing matching signer, preserving data; API31 minimum. No private media/microphone/recording access.

B inline-only WebView uses a synthetic reserved origin and blocked network loads, so its rendering itself needs no HTTP exception. Full Android dynamic/lifecycle/renderer/energy acceptance remains pending a device. If completion/cleanup is unproven the physical slot stays quarantined.
