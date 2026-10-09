# M2 implementation review fixes

Independent source review and actual integration checks found and fixed:

- Replaced one whole-pool checkpoint BLOB with normalized bounded result/page rows, avoiding Android CursorWindow's row-size limit. Host SQL test deliberately uses >2MiB total across 500 individual rows.
- Kept Seen facts as separate incrementally written rows; skipped equal SQLite UPSERTs and stopped repeated same-page viewport writes. Empty inventory polling no longer attempts page transactions; core status caches inventory between mutations. Poll timers are cancelled off-route/background.
- Derived profile IDs from normalized queryKey, locale and source-query filters, preventing unrelated keywords from sharing hidden-history state. Demo uses a distinct namespace and an in-memory store.
- Hydrated actual saved display preferences before checkpoint recovery; first-load P100/HIDE_SHOWN no longer behaves like a new user view change. Activity route persists across recreation.
- Kept SHOWN behind real two-dimensional viewport geometry, foreground route, continuous dwell and no modal overlay. Page/revision membership fences reject delayed callbacks after query or history changes.
- Reopening already-materialized pages no longer starts extra refill rounds or database writes. Provider late response/cursor updates are epoch/version gated and transactionally rolled back on suspension.
- Core validates host fallback lengths, alias/provenance caps and dedup bridge behavior before the adapter, preventing malformed Provider data from masquerading as a broken database.
- Immediate lifecycle gate is atomic and independent of SQLite/coordinator locks. Provider cancellation hooks are isolated; a stuck hook does not block sibling timeouts. Save/record priority release reapplies actual lifecycle eligibility.
- Fixed first integration compilation errors (constructor error sink parameter and cross-file detached helper visibility), then reran full checks.

Final verification evidence is separately recorded. Earlier failed logs are retained for transparency, not represented as successful stages. UI/device-specific conclusions remain NOT_RUN.

The saved viewport is also epoch-keyed on load. An old keyword's selected page cannot be reused after a newer query checkpoint commits before its first viewport write.
