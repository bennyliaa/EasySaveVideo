# Remaining independent Discovery work

M8-B1 closes bounded local history and scoped controls only. The parent selects
and authorizes the next slice; this handoff does not automatically start it.

## M8-B2 — Reliable event correlation and task priority

Continue M8-A's defined B2: an explicit short-lived identity/profile/session/navigation
origin token for an opened result. Emit SAVED only from verified committed Save
outcome; PLAYED only from a defensible existing player/browser signal, otherwise
unknown. Redirect/new navigation/close/incognito/late or unrelated outcomes must
invalidate/refuse the link. Wire actual Save/Record activity to existing priority
API, including work that continues outside its screen. Do not infer activity from
screen navigation or alter media codecs/muxing.

Preserve B1 invariants: captured history-control target, persisted revision fences,
separate monotonic event columns, alias groups, capacity rollback and after-retention
commit guard. A clear invalidates prior origin/event callbacks. No callback may
recreate a cleared history association or a reset cursor from stale work.

## M8-B3 — Power policy and available filters

Continue M8-A's defined B3 as a pure bounded policy for injected battery/thermal/
network inputs before any separately scoped OS wiring. Inputs may only tighten the
existing foreground/visibility/request-round/watermark bounds. No permanent poller
or background worker. Expose only already admitted source namespaces and truthful
unknown/unsupported metadata. Local stats remain count-only and create no demand.

## Still separate or blocked

M9-A's ordinary fixed Commons fault matrix can be selected separately. No M7-D
rule candidate/runtime probe/review, Vault work, new extraction source or dependency,
production service/account/payment/root, APK/AAB or install, SkinKit upgrade or
repository modification is cleared by this handoff. Device acceptance checklist
is deferred, not evidence of authorization or successful device verification.
