# ESV code4 unsigned UI handoff

Source commit: d9bdf34567bf2d138e796a50560419d359385cf4.
P1 fix commit: b22ae74043c43ead639952783796c3884f589710.

Payload: esv-ui-code4-d9bdf34-release-unsigned.apk, 34,849,762 bytes.
SHA256: 827ded5c6cd6f3bb69919292062fc448015fe8a7d55925b763cf7da3cdc2b2a9.
Package com.easysavevideo.app; versionCode4; versionName0.4.0-ui-dev;
minimum Android12/API31; target36; Release variant; deliberately unsigned.

APK-RECEIPT.json records official aapt/APK signature checks and build inputs.
The expected apksigner failure confirms this payload is unsigned; it is not an
installable or accepted signed package. SkinKit/new framework notices are bundled
and fit the bounded offline viewer. Contact is empty; Commons is disabled;
distribution is unconfigured. APK has no asset media, private keys or Python /
Scrapling runtime. No new signing key or local-debug-key replacement is authorized.

Use only the existing compatible signing process. The expected existing public
certificate SHA256 is 18e3ef657615957445677de9b7f2bc18444fa1b990cf9189bcd423f9066ab1a5.
Signing changes APK bytes: provide a new signed-payload hash/receipt and verify
the actual installed emulator-5556 signer and version before any preserved-data
upgrade. OPPO was handed back and must not be operated. Do not clear data,
uninstall/downgrade, grant permissions automatically, enable microphone or record.

Scoped desktop checks:82 JVM tests,0 failures/errors/skips; Debug/Release compile
and lint PASS;217 bilingual resource pairs checked. See source artifacts/ui/
main-flow-code4-20261011. This does not replace Android click/rotation/process
restoration/font200%/TalkBack acceptance. Explicitly test P1 by repeating Discover
and app-name taps then querying; zero-source address fallback must remain usable.
Device UI, real save/playback, public-source admission and power are NOT_RUN /
NOT_MEASURED. No private media/raw device UI may be uploaded.

This artifact is transferred only through the user-authorized private ESV
delivery branch. It is not a public release, store submission or legal production
approval. The main branch contains source, documentation and scoped evidence.
