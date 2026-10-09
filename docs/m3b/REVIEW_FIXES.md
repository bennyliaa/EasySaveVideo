# M3-B source review findings and fixes

This record documents concrete source/host checks; it is not device acceptance.

1. Scratch cleanup could set a release fence in `finally` while returning successful transfer evidence. Cleanup failure now throws; coordinator also checks all release fences after transfer, after verification and immediately before publication. An injected late-fence test proves no commit.
2. Stale preflight callback A could cancel newer B through a global cancellation call. Identity-targeted cancellation and an atomic session-slot compare-and-set preserve the newer session. Selection owns a single reserved lane until submit/launch; queue failure releases its own reservation.
3. Variant handoff and stale dialog dismissal/background could cancel an accepted task through the original preflight token. UI ownership is cleared synchronously; old callbacks can cancel only their captured preparation. Accepted background work is signaled through the coordinator pause token.
4. Background/user-cancel signaling previously depended on a bounded control queue. Memory-only immediate signals now precede queued durable actions, including accepted-but-not-yet-executing tasks. A launch racing background rechecks foreground. Rejected UI/control commands clear pending/refresh flags.
5. Recovery could accept a stored STREAM transfer without Stream output expectations and use Direct-style validation. Model, codec and recovered execution now reject method/expectation mismatches. Legacy v1 tests cover present transfer, verification and receipt, not merely empty records.
6. Real fixed Media3 reader emits three-byte Annex-B SPS initialization prefixes; validation now handles the actual pinned representation. `FLAG_DETECT_ACCESS_UNITS` conflicted with mandatory AUD; actual-reader tests pin the correct no-DETECT behavior.
7. An initial source-only concern about final-AUD double flushing was withdrawn after running the actual pinned reader: final-only AUD/EOS is necessary for the narrow tested input profile, and no-tail input loses its last video sample. Positive and negative pinned-jar fixtures retain this evidence.
8. Media3 suppresses one ExoPlayer release-timeout callback. Export completion or `cancel()` return is not accepted as resource-release proof. Owned source/muxer/executor/thread evidence gates lane reuse; a result is completed only after the release state is updated.
9. Local file identity and NOFOLLOW reads are enforced across profile checks, extraction and playback. Every sample's relative timestamp and flags, as well as payload boundaries/digest and AV origin offset, are bound across extraction→mux. Output metadata/seek writes are bounded through an owned muxer output.
10. Audio expectation is a lower bound. A real optional audio track may not be dropped merely because the page did not declare it. Final Stream audio is fully decoded under the same deadline and rejects rate/channel changes or implicit expansion.

Remaining native/device/publication limits are listed in IMPLEMENTATION_AND_ACCEPTANCE.md and DEVICE_ACCEPTANCE_CHECKLIST.md.
