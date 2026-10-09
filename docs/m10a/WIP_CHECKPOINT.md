# M10-A compilable WIP checkpoint

2026-10-07 UTC. Baseline: 7731c32da8ee821087605c5b9f9097a659b71ab5.

- Before-fix targeted snapshot suite: 12 tests, 5 failures, demonstrating caller
  alias mutation. The retained log is a diagnostic, not final acceptance.
- After-fix targeted core entitlement/free-gate and mapping tests plus both app
  Kotlin variants compiled successfully (initial-compile-tests.log).
- Complete core-domain suite and app entitlement tests, including the default
  store boundary, passed with both compile variants (wip-compile-tests.log).
- Core StoreAdapter signature, manager high-watermark and FeatureGate behavior
  remain unchanged; final immutable snapshot replaces the mutable List alias.
- Full aggregate, independent execution, final freeze and dual cold restore are
  not yet certified by this WIP. WIP recovery bundle/source archive are protected
  separately from the final deliverable. No package or device build was performed.

Checkpoint commit: `8374733da4db64ba1457c21bb1423537fee4542a`. Recovery files:

- `ESV-M10A-8374733-WIP-full.bundle`, SHA-256
  `085cb97038daf34aba3b0f87f3be8ed1b2406e74653de9a0172eeb4aefd703db`
- `ESV-M10A-8374733-WIP-source.tar.gz`, SHA-256
  `d5a5b9cabd757a41c5a3eae2bcb26ec637aa724682363801533c4df2eb338b6f`

The full bundle verifies and contains the checkpoint main ref. The subsequent final
harness uses actual release bytecode for its eight store tests because this Android
plugin exposes no release unit-test task. This did not require a product/test/build
configuration change. Final complete results belong to IMPLEMENTATION_AND_ACCEPTANCE.
