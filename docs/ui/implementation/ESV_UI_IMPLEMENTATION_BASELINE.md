# ESV UI implementation baseline — 2026-10-11

Current status is maintained in ESV_UI_FEATURE_INTEGRATION_LEDGER.md, reconciled
against main c0920b7 plus subsequent source-only tab-semantic wiring. This file
preserves starting decisions, not a claim that UI-A–E or the PPT are complete.
Signed code4/d9bdf34 is installed on emulator-5556 with preserved data; subsequent
resource and semantic increments are not in that package. Bounded checks are
distinct from real save/record/edit, tablet/font200/TalkBack, visual and power acceptance.

Starting source: b63a879f06131fbca905eb87c523d040c5cc8503, branch master, clean worktree. Work proceeds on ui/skinkit-1.0-integration-20261011; no existing files or history reset. Original user UI documents are outside this checkout under the project's docs/ui. Read nine Markdown specifications, the 34-slide PPT text/media and concept archive. The images are design references, not runtime assets or a pixel golden.

Android 12/API31 minimum is confirmed in AGENTS and app defaultConfig; compile/target36, BuildTools36, Kotlin2.2.10, Compose BOM2025.06.01, Activity1.10.1, JBR21 and Gradle9.6.0/AGP9.4.0. Existing M0–M12 numbering remains; UI-A–E are additional milestones.

Priority: explicit user instructions and safety boundaries, UI text/correction slide34, then conceptual images. Four roots: Discover/Tasks/Record/Library; Settings auxiliary, browser/editor/details secondary. Default page size20, not illustrative12. Incognito, not Offline. One theme/one task authority; accepted commands are not successful media output.

Conflicts: AGENTS' old SkinKit0.0.1 integration limit is superseded by explicit user authorization for binary1.0.0. Its APK/device restriction is superseded only by the current regular build/test and emulator-5556 authorization. PPT simulated Vault, payments and source-update interfaces do not authorize real Vault/interpreter/payment implementation. These remain disabled. Images' extra navigation/brand/large center button are corrected. Reference ocean photos have no independently established redistribution license and are not extracted into the app.

Implementation order: UI-A actual binary upgrade and capability ledger; UI-B central tokens/Chinese-English resources and theme preferences; UI-C official navigation/window inputs and a usable four-root slice; UI-D adapters in existing business flows; UI-E evidence and unresolved coverage. A complete UI-A binary milestone is independently recoverable before wider UI changes.

No source/provider is silently enabled. Current A endpoint and B source catalogue unconfigured; Commons disabled without approved contact. Physical-device power, public site admission, real download/playback and Vault safety remain separate unmeasured acceptance. Historical Windows full-suite115 failures remain open, not hidden by focused UI tests.
