# SkinKit 1.0.0 selected binary admission

User explicitly authorized ESV's upgrade to SkinKit 1.0.0 and supplied the external binary Kit. ZIP SHA-256: `3b413e113008dbe0fe50e60e5305e9c8b4f4ffdab620429944bdc94c97ac0330`. Import manifest records the six exact JAR/POM/module bytes. No implementation source, demo APK extraction, other SkinKit modules, artwork, personal themes or other project files are imported.

ESV uses `dev.skinkit:skinkit-contract:1.0.0` and `dev.skinkit:skinkit-default:1.0.0`. Both JARs are byte-identical to the previously admitted foundation binaries. POM/module metadata now publish 1.0.0; Default depends on Contract 1.0.0 and both require the already admitted Kotlin stdlib 2.2.10. Thus there is no new transitive runtime binary or native library. Existing app runtime remains exactly 77 artifacts, with only the two SkinKit coordinates changed. Protocol Contract 0.0.1 remains unchanged, as does ESV's default-theme version 0.1.0 at this milestone.

The existing `EsvTheme` registry, `DefaultThemeProvider`, same-generation pin/read/release and app-owned Compose adapter are reused. No second provider, SDK source copy, settings migration, theme-pack loader or optional commerce/animation runtime is introduced. Existing settings and browser/session stores are untouched.

The full supplied engineering license draft is retained and shipped with the app notices. The owner permits this local integration; this does not assign an open-source license or claim final legal review. Licensor legal name, production identity and store distribution remain separate publication gates. Kotlin's existing Apache-2.0 notice remains applicable.

Historical evidence and old Maven recovery artifacts remain as history, not active dependencies. Actual resolution is recorded separately from old evidence. Both app lock and ui-theme lock use 1.0.0. Gradle's strict verification includes the exact supplied JAR, POM and module hashes; the exact runtime gate remains enabled.
