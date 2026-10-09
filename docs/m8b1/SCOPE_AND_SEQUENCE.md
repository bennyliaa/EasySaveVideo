# M8-B1 — Bounded local Discovery history

Baseline: `0762843aa22bf1907cedf5aeed84224fdb335f0f` (M8-A). This is the
independent capacity/history-control slice described in M8-A's follow-on scope.
It does not complete M8, M9, installation acceptance, or live-source acceptance.

## Closed-loop sequence

1. Agree deterministic shared retention and alias-group contracts; implement core,
   Android schema migration and local confirmation UI without new dependencies.
2. Verify ordinary core/UI JVM tests and debug/release compilation; create a
   recoverable WIP commit with full bundle/source for private parent backup.
3. Verify real host SQLite old-state migration, transactional faults, recovery,
   cross-profile isolation, alias consistency and capacity boundaries.
4. Freeze relevant inputs for independent ordinary source review; correct findings
   and re-freeze. Preserve the blocked M7-D and Vault boundaries.
5. Run the strict offline aggregate, both lint variants, exact74 admission and all
   inherited SQL/DOM/Provider/media host regressions. Commit and tag the final slice.
6. Archive full Git/source and verify two separate cold restorations with complete
   aggregate reruns and a receipt. Hand off remaining B2/B3 without starting them.

## Explicit exclusions

No M7-D candidate, interpreter review/probe or replacement of denied work; Vault
stays disabled and is not inspected/reviewed. No APK/AAB, device/emulator install,
real payment/accounts, production roots/services, live-source expansion, new code
or dependency admission, or SkinKit upgrade/repository change. B2's real Save/Record
priority and reliable event-origin wiring and B3's power/filter policy remain open.
