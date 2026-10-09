# Independent source review

Two read-only review passes were performed. The first covered consent/FGS/runtime, resource fences, recovery, publication, gates and preview. It identified controller clock-sampling and stop-cause races; foreground ownership disappearing on native timeout; a release-before-abandon race; finalized local-recovery scheduling stranded in RUNNING; and late callback correlation. Each was fixed and re-read.

The final pass covered selected B verification policy and durable resume proof. It found and closed an allocation bound during mutable-list copying and output-format identity being reset between decoded GOPs. No remaining production-source blocker was identified in the final reviewed snapshot.

The final review verifies actual mux-only 32/33 resume accounting; exact sample index and ≤100μs mux PTS tolerance; exact decoder input identity/output PTS; deduplicated first/middle/final/resume GOPs; output EOS plus actual final extractor EOF; bounded sparse AAC; v5/evidence3 legacy-null preservation and publication refusal; owned-file/index receipt fencing; and the unchanged 60-second deadline.

`evidence/independent-policy-review.json` is the exact final read-only review manifest. Its SHA-256 is `2fc73e26b5f2ea32adc133c464578ce20ac1dc31e3cdece4fc1dbe0e4463c871`. The integrator rechecked every listed production hash before freeze. Build/test evidence belongs to the serialized build owner, not the reviewer. Android/device/native behavior is NOT_RUN.
