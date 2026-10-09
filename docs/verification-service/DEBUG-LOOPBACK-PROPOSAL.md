# Debug-only Android loopback transport design — proposal, not enabled

The production client continues to require public HTTPS port 443 and /v1/verify. Release receives no cleartext exception, trust change, local endpoint toggle or additional permissions.

For the explicitly selected emulator-5556 only, after the user reports it ready and approves the debug transport change:
1. Keep Windows service bound exclusively to 127.0.0.1:8765.
2. Use a device-specific adb reverse mapping for tcp:8765; no LAN address or 10.0.2.2 bypass, no firewall edits. Remove that exact mapping at the end.
3. Add a src/debug-only network-security-config domain exception for 127.0.0.1 cleartext, default cleartext false. A dedicated debug transport validates exactly http://127.0.0.1:8765/v1/verify and uses the existing bounded wire grammar. It cannot reach arbitrary private endpoints.
4. Keep per-process explicit consent, exact constructor-owned fixture source admission, no cookies, credentials, download, permissions grant, microphone or screen recording. Android source candidates remain the logical fixture identity; the host resolves only constructor-owned fixtures.
5. Before installation, compare existing package signer and versionCode without replacing app data; new package must have higher versionCode and matching signer. API31 remains minimum.

No manifest/security configuration, adb mapping, package assembly/install, device start or device polling has been performed. Approval is needed before enabling steps 2–3 because they introduce a debug cleartext transport exception. Host unit tests, source/ABI checks and Kotlin compilation do not depend on this exception.

B's inline-only WebView uses a synthetic reserved origin and blocked network loads; it does not require this transport exception. Actual Android WebView execution, Activity reconstruction, renderer lifecycle and device CPU/RAM/energy remain pending device acceptance. If JS completion cannot be proven, the dynamic physical slot remains quarantined; destroy alone does not assert recovery.
