# Independent review corrections

Review is source- and host-test based; no Android device or live transport success is implied.

- Equivalent normalized queries could reuse generic progress with an incompatible raw-query Commons cursor. Bridge query/language normalization now matches profile identity; all filters bind cache/cursor identity. Regression covers case, whitespace and filter changes.
- HTML numeric entities could bypass bidi/control stripping. Decoded and raw characters now share the same plain-text character policy; C0/C1 and direction-mark tests added.
- Rights observation selection with mixed known/unknown revisions was non-associative. One stable total order (observed time, revision tie-break, canonical bytes) and conservative warning union now pass six permutations and both parenthesizations. No field-level author/license blending occurs.
- ResultPool could preserve a thumbnail from another rights observation. Differing complete rights now suppress the thumbnail.
- In-deadline Retry-After was lost from generic health when cancelling a queued retry. Not-before is now retained as soon as failure arrives and survives new epoch/hide-show/manual demand.
- Throttle/denial headers could be hidden by huge/compressed/truncated/stalled error bodies. Actual adapter now short-circuits bounded 429/503/401/403 headers and tests an unreadable body matrix. Bounded definitive headers now publish independently of cleanup, with physical slots retained; an adapter-owned server gate also survives discarded responses/cancellation/late close. Shared overflow-safe date/delta parsing is used at both layers.
- Returned mutable metadata collections could change cached attribution. Cache storage and all returned pages now deep-detach results, rights restrictions and field provenance; first-return/cache-hit mutation regressions are included.
- SQLite dynamic TEXT/integer values could defeat a BLOB byte-size projection. Type and byte length are checked before fetching the rights payload, preserving invalid rows and transaction rollback.
- UI query truncation could split a surrogate. Bounded input preserves pairs and invalid strings receive an actionable notice rather than being misclassified as a corrupt database.
- Reinitializing source configuration now reapplies current Save/Record priority before opening the discovery visibility gate.

The final evidence records reviewed source hashes and exact regression results. Initial compile/assertion failures are kept separately from final successful evidence rather than silently erased.

Independent final source review found no known blockers in the frozen 55-file scope. Its sorted SHA-256 manifest is `evidence/reviewed-source.sha256`, with manifest digest `ec25e083431d513e68498d030b2290f416584f8621ad30dbe2ee6230d1e4bc2f`. This is static review, not device or live-transport acceptance.
