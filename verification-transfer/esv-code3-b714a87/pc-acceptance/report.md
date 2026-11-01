# Code3 preserve-data emulator acceptance, 2026-10-11

The fixed signed return from `0ef80ea13e6b5b01aab98000e87186838d598401` was reconstructed on Windows, verified with official Android Build Tools 36.0.0, and installed with `adb -s emulator-5556 install -r`. The application now runs code3 on Android 12 / API31. Application payload source remains `b714a87601d0725385c11f4587704f78587938b5`; this evidence update changes no runtime code or APK version.

Package: `com.easysavevideo.app`; versionName `0.3.2-commons-dev`; minSdk31, targetSdk36. Signed APK: 39,965,102 bytes; SHA256 `b377703b0e49237b94dd7669bb79a065deaf6af74e5d60c4eaf621b40747b531`. Its public certificate SHA256 `18e3ef657615957445677de9b7f2bc18444fa1b990cf9189bcd423f9066ab1a5` matched the existing code2 certificate. No local key was generated or transferred.

## Passed

- Incoming manifest SHA256, every one of 102 UTF-8 base64 chunks, reconstructed APK size/hash, and all 192 unchanged payload entries.
- Official PC `apksigner verify` for API31–36 and independently API31; official `zipalign -c -P 16 4`.
- Same-signer code2-to-code3 replacement. Before first launch, the existing WebView preferences, discovery database and journal had unchanged hashes. This is a scoped check of those three files, not a byte audit of every application file.
- Actual search click retained the honest zero-source result; local verification admission displayed `UNCONFIGURED`.
- System WebView loaded authored HTML from a one-shot server bound only to the emulator's `127.0.0.1`. Its JavaScript changed the displayed text to `DYNAMIC_DOM_READY`. The page contained no media or external resource references.
- Save on that page displayed “当前主页面未找到可检测的视频”; the fixture was not reported as a successful save.
- A closed emulator loopback port produced “网络连接不可用”; the actual Save button parent was disabled. No TLS override was used.
- Save-task navigation exposed existing completed-recording metadata. These records predate this run and are not evidence of a new recording or successful save.
- Only the newly created test tab was closed; the preexisting selected address was restored without fetching its website. An actual cold restart without clearing data restored the saved discovery query.

## Not run / remaining acceptance

- **Media library / thumbnail / playback:** existing recordings are cataloged. `LibraryScreen` schedules foreground thumbnail work that reads video bytes, so this entry and preview were paused under the explicit prohibition on reading private images or videos. No private media was opened, copied, played or modified.
- **Real websites and production B:** search sources remain disabled/unconfigured and the production B admission catalog is empty. The owned HTML test is neither a website benchmark nor production B acceptance.
- **Successful download/save, the full Activity-recreation/cancellation suite and the same 100-case A/B benchmark:** not run in this stage.
- **Physical-phone CPU/RAM/battery/network/total-time measurements:** unmeasured. Emulator installation and UI checks do not establish phone performance or power use.

The one-shot response server exited, the test tab was removed, and ESV was left on its discovery page. No uninstall, clear-data, microphone, screen recording, permissions grant, firewall change, other-device operation, Vault work or M7-D interpreter work occurred.

## Evidence scope

`artifacts/emulator-code3-acceptance-20261011/test-receipt.json` contains sanitized outcomes and SHA256s of local raw evidence. Public signature/package logs and the install result are included alongside it. Raw UI XML/screenshots remain local because they contain existing query/address/task metadata; no raw UI data or private media is included in this Git evidence.

Setup attempts are distinguished in the receipt: an initial inline fixture command failed on quoting and was corrected using a saved script; `am kill` did not establish a cold restart and was not counted; the later explicit cold launch did. Toybox SQLite CLI was absent and no private-media workaround was attempted. No product defect was established by these limited checks, and no runtime fixes or fresh broad JVM/lint regression runs were required.
