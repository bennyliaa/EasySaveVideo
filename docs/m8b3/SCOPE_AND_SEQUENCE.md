# M8-B3 — Bounded injected power policy and admitted source filters

Baseline: `d20b1c9579af65977a4edb79210e5e9643daa2d9` (M8-B2).

This slice adds a pure injected battery/thermal/network policy. It only tightens
existing foreground/visibility/Save-Record/mode/round/watermark bounds. Unknown
connected inputs fail closed. Policy changes cannot create demand, replenish round
budgets or add timers. Current production Android has no trustworthy OS signal
adapter, so its dynamic policy remains explicitly NOT_CONNECTED; existing static
user-selected modes continue. Explicit transient synthetic scenarios are debug-only.
No monitoring, measured power saving, or Android system acceptance is claimed.

Source selection exposes only known, already admitted namespace mappings and
truthful unsupported/unknown metadata. Saved applied filters retain their exact
query identity. Obsolete or unknown namespaces are not silently removed or expanded.
Statistics remain count-only and never initialize or request a source.

## Sequence

1. Implement pure policy, safe application injection, source-filter UI and restore
   behavior with deterministic tests and both Kotlin compilation variants.
2. Make a compilable WIP commit and protect full Git/source snapshots privately.
3. Freeze source for independent ordinary review, correct findings, freeze again.
4. Run strict offline/dependency-verified/nonincremental full JVM aggregate,
   both compile/lint variants, exact74 and existing SQL/DOM/media/Provider hosts.
5. Final commit/tag, full Git/source archives, two independent full cold-restored
   aggregate reruns with exact tracked bytes/modes/refs verification and receipt.

Historical M7-A/B1/B2 frozen scripts remain byte-for-byte evidence. B3 uses new
scope and current-source runners; it does not rewrite earlier accepted hashes.

## Preserved boundaries

Native video SAVED and PLAYED remain UNKNOWN: page provenance is not video identity.
B1 revision, alias groups and independent event monotonicity remain authoritative.
Application Save/Record owners retain immediate priority. Shared editor/thumbnail
owners still only have existing sampling, not new instant subscriptions.

M7-D candidate/runtime/review/probes and substitutes, Vault, new sources/libraries,
production roots/endpoints/services/accounts/payment, ESV APK/AAB/device work,
SkinKit edits/upgrades and OS signal wiring/real power measurements are excluded.
Commons remains fixed schema1 with zero-provider fallback and exact74 artifacts.
M9 is described at handoff but is not started by this slice.
