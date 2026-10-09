# M6-C compilable WIP, not acceptance

Baseline: f3253eadaf8f2d50acc6e64c02aa610f5f87bda2 (M6-B).

This WIP adds a separate actual-authorized-frame selection route while retaining explicitly labelled proportional selection. It keeps ESV's current browser host visible for the initial snapshot, requests one system consent, and uses one projection/VirtualDisplay/OES source across memory-only snapshot selection and later crop encoding. First publication and recording transition use separate actual renderer-submission freshness edges. The frozen selector is secure-window scoped; background, geometry, expiry, stale session and native shutdown gates fail closed.

The bounded frame is never saved/exported/cached. No codec/output file/audio capture is started before explicit selection, actual selector disposal and a fresh uncovered browser/source frame. An existing background app or arbitrary other-app preview is not promised; the supplied pixels must be inspected and can be cancelled. System request/dimensions do not authenticate content identity.

Strict offline dual Kotlin compile, recording JVM tests and app JVM tests passed in evidence/wip-dual-compile-tests.log. Final aggregate/lint, independent ordinary-media source review and cold bundle/source restore remain pending at this checkpoint. Existing Android/SurfaceTexture/GPU/codec/OEM/audio/phone/tablet visual behavior remains NOT_RUN; no APK/AAB or actual capture was performed. Vault is disabled and untouched.
