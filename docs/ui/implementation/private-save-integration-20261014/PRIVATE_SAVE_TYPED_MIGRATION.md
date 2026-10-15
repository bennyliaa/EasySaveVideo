# Private Save typed-source migration contract

Status: design only, frozen for independent review on 2026-10-14. No production source changes are authorized by this document alone. This refines PRIVATE_SAVE_CONTRACT.md and PRIVATE_RECORDING_EXCEPTION.md; it does not reopen ordinary/MOV dependency or device-acceptance gates.

## 1. Scope and invariants

- Private Direct ①, HLS ② and recording ③ remain intended. No method is silently disabled to avoid implementing privacy.
- Durable private network input has a distinct type with no URL accessor. Do not put a fake/sentinel URL in SelectedMediaResource.
- No private URL, origin, URL-derived hash, redirect hash, raw server ETag, browser profile/session identity, cookie, authorization header, user-agent, title, or source-derived candidate/variant identity reaches a journal, SQLite record/column/WAL/SHM, normalization proof, filename, error or diagnostic.
- A private network task gets one opaque 256-bit cryptographically random source identity. It is stable for that task's idempotent submissions, retries and output proofs, and different for another task even when both save the same URL. Generate it once before task submission, never during durable conversion or codec encoding.
- Actual content-integrity hashes, decoded media constraints, byte accounting, task/artifact/commit identities, verification, receipts and explicitly saved media remain durable. The user's selected media content can itself contain embedded metadata; this is not permission to journal extra browsing metadata.
- Durable data and random identity are not session, browser, capture, HTTP or execution authority. Decoding a record never issues a live source binding or reconstructs an actual private session.
- Ordinary NETWORK/CAPTURE codec layouts and versions 1–7 remain readable. The source-type migration does not relax controlled MOV evidence, Claim/Runtime, local-gate, release or commit checks.

## 2. Minimal core type contract

The following names define the intended shared contract. Implementation may use immutable classes instead of data classes to keep constructors/factories and redacted toString explicit.

### Source metadata shared by output-only consumers

    interface NetworkSourceMetadata {
        val sourceIdentity: String
        val expectedAudio: Boolean
    }

This interface intentionally has no URL, origin, expiry or request-context accessor. SelectedMediaResource and PrivateDurableSource provide these two fields. Output validation must use metadata, not require a SelectedMediaResource.

### Private durable values

    class PrivateTaskSourceIdentity private constructor(val encoded: String)
    class PrivateSelectionIdentity private constructor(val encoded: String)
    data class PrivateDurableSource(
        val identity: PrivateTaskSourceIdentity,
        val candidateId: PrivateSelectionIdentity,
        val variantId: PrivateSelectionIdentity,
        override val expectedAudio: Boolean,
    ) : NetworkSourceMetadata

PrivateDurableSource.sourceIdentity returns identity.encoded. It has no SourceUrl, request lease, expiry, profile/session identifier, title or browser reference. The existing SaveRequest/SaveRecord owns task/operation/output transaction identifiers; do not duplicate browser ownership into this source.

- PrivateTaskSourceIdentity generation uses SecureRandom.nextBytes on 32 bytes, encoded as exactly 64 lowercase hexadecimal characters. Never derive it from a URL, session, time, UUID, request header or media content.
- PrivateSelectionIdentity values are independently generated local canonical UUIDs. They replace, rather than encode/hash, the real candidate/variant values in durable private records. HLS parser selection identities remain memory-only.
- Codec restoration validates exact encoding/length and returns durable value objects only. It cannot mathematically prove entropy of arbitrary attacker-supplied bytes; randomness is guaranteed by the production minting path and end-to-end privacy tests, not by a regex. No restored value can be promoted to a live binding by an import constructor.
- Value equality is by encoded contents; toString is redacted. Do not use these values in logs.

### Private preparation seed and once-bound task identity

    sealed interface PrivateNetworkSource {
        val sourceIdentity: PrivateTaskSourceIdentity
        val candidateId: PrivateSelectionIdentity
        val variantId: PrivateSelectionIdentity
        fun isLive(): Boolean
    }
    class PrivateNetworkSourceSeed private constructor(...) : PrivateNetworkSource {
        fun bindTask(taskId: String): PrivateNetworkSourceBinding
        fun revoke()
        companion object { fun create(): PrivateNetworkSourceSeed }
    }
    class PrivateNetworkSourceBinding private constructor(...) : PrivateNetworkSource {
        val taskId: String
        fun isFrom(seed: PrivateNetworkSourceSeed): Boolean
        fun revoke()
    }

The seed factory generates all three random identifiers once, before private source preparation or an alternative proposal. bindTask performs one atomic, once-only attachment to the final taskId and returns a binding with **unchanged** random identifiers. A second bind (including another task) is rejected; idempotent submission reuses the already returned binding. Seed and task binding share one monotonic atomic revocation cell, so seed retirement cannot be bypassed by binding it later. Neither creation nor binding accepts restored identifiers or a callback that can later become live again. Codec restoration produces durable values, never a seed or binding.

This is an in-process source identity/fence, **not** an alternative BrowserSession permission issuer. Production creates/registers the seed only after capturing the actual browser partition lease and atomically registering the exact work owner with that lease. Task binding still requires the exact live preparation/selection and actual app session registration. Knowing random values, calling create, or binding a taskId never substitutes for real PrivateSessionLease authority. Browser references remain outside core.

SelectedMediaResource adds nullable privateSource: PrivateNetworkSource. It still carries a real SourceUrl for actual in-memory transport. For ordinary live sources, sourceIdentity remains url.identity(); for private live sources, sourceIdentity equals privateSource.sourceIdentity.encoded and durableReference is false. A preparation may carry a seed; final Save submission requires a PrivateNetworkSourceBinding for the exact SaveRequest.requestId. An unbound seed cannot be persisted/submitted as a task. The live candidateId/variantId may retain parser values only in memory; they are not copied into PrivateDurableSource.

Expected-audio constraints are finalized from the selected candidate/variant before submission. Conversion uses the bound seed's fixed random IDs plus selected expectedAudio. It never regenerates identity. HLS preparation precedes destination/task creation, so it initially carries a task-unbound seed; its exact one-time task binding protocol is below. Do not move destination/task-ID creation earlier merely to satisfy this type.

### Save input and accessors

Add only one durable input branch:

    SaveInput.PrivateNetwork(val source: PrivateDurableSource)

Keep SaveInput.Network(resource) and SaveInput.Capture(descriptor).

SaveRequest exposes:
- networkSourceMetadata: NetworkSourceMetadata? for both Network and PrivateNetwork; null only for Capture.
- networkResource: SelectedMediaResource? retains its current true-resource meaning. It returns null for PrivateNetwork and Capture. Do not manufacture a SelectedMediaResource to satisfy old call sites.
- isPrivateNetwork: true for PrivateNetwork or live Network with privateSource.

SaveRequest.asDurableReference converts live private Network to PrivateNetwork. Ordinary conversion stays unchanged. SelectedMediaResource.asDurableReference retains its ordinary return type but explicitly rejects private resources; use a new toDurableInput helper or the request-level conversion for private values.

SaveRecord invariants reject live private Network. PrivateNetwork requires method DIRECT or STREAM, checkpoint == null, and all output/source-binding proofs matching its random sourceIdentity. Existing ordinary/capture invariants remain.

Coordinator.submit rejects direct submission of durable PrivateNetwork, just as it rejects an ordinary durableReference. Actual submission must contain the real live source and its live task binding. Private identity is never remapped to another task by withTaskId; same-task repeat submission must reuse the original request/binding. A distinct private task needs a new binding.

## 3. Codec v8, no database-schema fork

SaveRecordCodec.VERSION becomes 8. SaveRecord.schemaVersion stays 1; SQLite tables remain unchanged because the existing bounded record BLOB carries the new input discriminator. Keep normalization workspace codec v2 and evidence codec v4 unless some independent change actually requires an extension.

- NETWORK payload order stays exactly as versions 1–7 expect. Decode legacy implicit NETWORK (versions 1–3) and explicit NETWORK/CAPTURE (versions 4–7) unchanged.
- PRIVATE_NETWORK is accepted only when version >= 8. Payload order: random source identity; random candidate ID; random variant ID; expectedAudio Boolean. No placeholder URL, expiry, durableReference flag or optional credentials slot exists in this payload.
- The rest of the SaveRecord wire layout remains v7-compatible, including transfer/evidence/receipt fields. Private records must encode checkpoint's presence flag as false.
- Both encode and decode call the same private invariant validation. Decoding missing/unknown/wrong-version discriminators, truncated/extra payload or invalid private values fails closed; never fall back to NETWORK.
- Both AndroidSaveStorage and AndroidNormalizedCommitJournal use SaveRecordCodec. Neither may bypass validation for publication or recovery writes.
- For private network transfer, responseMimeType must already be the canonical lowercase bare allowlisted token (video/mp4, application/mp4, application/octet-stream, and video/quicktime). This is a static durable format allowlist: codec encode/decode never consult the current MOV admission flag, device capability or current entitlement. Live method/profile admission applies only to creation/new network execution. A historically valid complete private QuickTime transfer/proof remains readable while MOV admission is disabled and can follow only the existing exact local recovery rules. Reject MIME parameters/arbitrary strings at the private durable boundary. Production Direct already canonicalizes/allowlists MIME; HLS returns fixed video/mp4. Do not alter a persisted raw tuple after sourceTransferFingerprint/proof has been formed.
- Keep existing size caps: Save record 32 KiB, evidence 4 KiB, normalization workspace 2 KiB. New branches must fit tested maxima.

## 4. Checkpoint and HLS secondary channels

### Direct checkpoints

Existing DownloadCheckpoint is not private-safe: resourceIdentity is SHA-256 of the final/redirect URI and strongEtag is untrusted server text. Removing only ETag, or substituting only the initial source hash, is insufficient.

Private durable checkpoint is always null. During a still-live operation, optionally retain a checkpoint only in a Runtime-owned memory slot bound to the exact private binding/random identity and current transport owner. The transport's final-target comparison and ETag may remain inside that slot; they never become durable proof fields. A private callback follows the same actual Runtime/transferContext checks as ordinary checkpoints, then updates only this memory slot. Byte charges remain durably reserved before bytes are accepted.

- On in-process transient retry, reuse a memory validator only when the exact same private binding and source authority are still live and the prior transport has positively released.
- On source/session revocation, explicit cancel, Runtime exit, source expiry or replacement: atomically make the slot unusable and clear it. A late callback cannot recreate it.
- Release of an old owner is not permission to resurrect a cleared validator. Explicit later Resume may start without a range validator only while the same actual private session/source authority remains valid; charge repeated bytes normally. Never replay a saved URL or recover validators after process death.
- onRestart clears the memory checkpoint and preserves existing lifetime byte-budget semantics. Never allow a normalizer/complete-raw local failure to trigger an HTTP restart.
- If no memory checkpoint is retained, private retry can use existing full-restart semantics while authority is live. Do not silently disable private Direct saving.

### HLS

AndroidHlsStreamPort.select currently writes selected.identity into resource.variantId. HlsParser and HlsSelection.directMedia derive that identity from manifest/playlist/variant URI hashes. Persisting it would violate private source privacy even with a randomized sourceIdentity.

Keep that exact selection identity solely in HlsPreparation and the live SelectedMediaResource; the private durable conversion uses binding.variantId. Do not weaken selection matching to the random durable ID alone.

**Exact HLS seed → task-binding contract, preserving PR12:** preparation happens before destination choice, and final admission.creationId/taskId is established only at chooseHlsVariantSelected. HlsPreparation.source is a val; prepareStreamAlternative/StreamAlternativeProposal also freezes sourceIdentity before final submission. Therefore mint the private random seed **before** constructing that prepared source/proposal. Do not initially use a URL hash and replace it later.

1. Before preparation queues/fetches, register the exact HLS preparation owner against the actual private session lease using startQueued. Create its PrivateNetworkSourceSeed and construct the original immutable HlsPreparation.source with that seed's random sourceIdentity. There is no bound taskId and no durable Save source/task yet. Real app session registration, not possession of the seed, authorizes preparation.
2. prepareStreamAlternative captures this same random sourceIdentity in the existing immutable proposal. Preserve its existing candidateId, requestContextRef, expiry, expectedAudio, operation and failed-record matching. For private preparation, also retain the exact seed identity in the in-memory proposal, so final confirmation can require binding.isFrom(theSameSeed). This is an additional equality/liveness check, never a relaxation or reconstruction of the old proposal.
3. After destination choice fixes the taskId, under exact prepared-instance + selected-variant + still-live private-session authority, call the retained seed's bindTask(taskId) once and attach that binding to the preparation. Reject another task/binding, stale preparation/selection or revoked seed/session. No destination-ID change, rebinding or imported durable nonce is allowed.
4. Produce the final live SelectedMediaResource using the same sourceIdentity, original actual URL/candidate, real selected variant identity, finalized expectedAudio and existing request context, with privateSource changed from the exact seed to its task binding. The immutable original prepared source stays unchanged. Existing proposal.sourceIdentity equality now succeeds without weakening it or manufacturing a new proposal.
5. canContinue/transfer have an explicit private-attached check: require the final resource's binding to be the exact once-attached object originating from the retained seed, task/context to match accepted admission, and actual live URL/candidate/selected-variant/constraints to match the preparation. Keep random sourceIdentity equality as well as the original exact selection checks. A private seed without completed attachment cannot fall back to ordinary task execution. Ordinary preparation keeps its current URL-hash/selection checks.
6. Durable conversion uses only the attached seed's random source/candidate/variant IDs. After actual manifest/segment/source release and exact completed-transfer CAS, clear source-bearing references through the source-to-output handoff below. Do not retain HLS URLs just to make local output verification pass.

The same seed → task binding can implement Direct admission immediately once its taskId exists. Different private task/preparation intentions use new seeds; retries of one accepted task retain its existing binding.

HlsDurablePlan/HlsSafeSegment contain URI/content-derived identities despite their names. Current Android storage keeps the plan only in HlsPreparation.plan; preserve that boundary for private work. No manifest, segment/key URI, redirect address or plan is added to durable Save state. Current random segment filenames, discarded EXTINF titles and rejected encryption/key URIs remain unchanged. HLS preparation itself must be registered before queueing, not only after a Save task is submitted.

## 5. Exact core and app consumer migration points

### Core owned by the core writer after review

- SaveModels.kt: SelectedMediaResource, new value/binding/metadata types, SaveInput, SaveRequest conversion/accessors, SaveRecord invariant and verification constraints.
- SaveCoordinator.kt: submitLocked; idempotent comparison and withTaskId; resume's live-to-durable comparison; sourceForTask liveness; checkpoint callback/restart/transfer argument; expectedAudio and sourceIdentity checks; recover classification. Never replace a missing private metadata read with false/null and accidentally weaken verification.
- DirectNormalizationContracts.kt: selectedOutputDescriptor uses networkSourceMetadata rather than networkResource. Existing proof/source fingerprint encodings remain unchanged: their 64-hex sourceIdentity becomes the random per-task identity for private records.
- SaveMethodAlternatives.kt and Coordinator alternative confirmation: preserve exact accepted preparation/task/binding, never copy a parser URL hash into the private durable branch.
- SaveNetworkGate/RecordingVerification: ensure method/output classification does not equate networkResource == null with recording or with permission to bypass a required live network source.

### App owned by the parent/app writer

- SaveRecordCodec.kt and every record writer/reader, including AndroidSaveStorage.recordValues and AndroidNormalizedCommitJournal.recordValues.
- AndroidSaveRuntime: source construction, browser partition capture, private registration before admission/queueing/HLS preparation, resume/leaseFor, authentication-origin binding and output-preserving retirement.
- RequestContextLeaseStore and SystemHttpsDirectTransfer: actual private lease/liveness enforcement, cookie-free and redirect requests, memory-only checkpoint ownership. Header eviction alone cannot cancel an anonymous request.
- AndroidHlsStreamPort: selection mapping, preparation registration, segment/manifest request guards and actual drain.
- MOV/output metadata consumers: NormalizationAttemptOwner.active/prepare/finish; OwnedMovRemuxAttempt; AdoptedCanonicalOutputOwner.authority; AndroidSaveStorage.normalizationAuthority; DirectRawSelectionPolicy/DirectRawRecoveryPolicy; AndroidControlledDirectStorage; NormalizedCommitProtocol; AndroidNormalizedCommitJournal; DirectNormalizedAndroidVerifier; LibraryPublicationEvidence and MediaTaskActivitySaveOutcome.
- NormalizationWorkspaceCodec stores sourceTransferSha256/proofSha256, not a direct source URL identity. Keep these exact content/tuple integrity fields and include their SQL source_sha/proof_sha columns in privacy scans.
- SaveAuthenticationNavigation/Guidance/OriginGuidance and SaveTaskScreen: no original-page authentication/reselection affordance for a durable private source lacking live authority.

## 6. Authority loss, restart and recording

Cross-origin is not a blanket denial rule. A valid selected CDN resource gets the exact captured View user-agent and no cookie. Same-origin private cookies come only from that exact assigned Profile. Missing private Profile authority is an admission failure, distinct from a legitimate no-cookie result. Never fall back to the ordinary CookieManager.

Credential storage revocation alone is insufficient: redirects skip first-hop headers, and HLS segment/variant requests can be cookie-free. Actual private-generation retirement atomically rejects new work registrations, synchronously revokes source/credential bindings and queued/active **source-dependent** network work, and waits for positive resource-owner release. Every request/body guard sees revocation. Stale callbacks cannot publish a source-dependent result or reinstall a source. Retained/uncertain resources quarantine the generation; a cancelled Future or an empty field is not drain evidence.

### Source-to-output handoff, including queued retirement races

PrivateSessionLease.register returns the browser-owned registration. Before a worker is queued, production must call registration.startQueued(registerOwner); queue-owner registration and private-generation revocation are serialized by that operation. A failure/partial queue registration remains an owned draining obligation, never an untracked task. OUTPUT_ONLY work cannot register as a new private-session owner.

Private liveness must **not** be permanently AND-ed into the entire Save OperationContext. Use the exact private source fence in source acquisition, credential reuse, HTTP/manifest/segment guards and source-dependent cancellation. The outer Save/output context still represents explicit task cancellation, output processing and publication. Otherwise private exit would cancel a safely detached complete download or finalized recording and incorrectly prevent its output from being saved.

A positive detach event is required, not an inferred phase label:
1. The actual transport/preparation owner confirms source reads, connections, source-bound native/preparation resources and callbacks have released. Revoke/clear the corresponding credential lease and manifest/selection/source references that are no longer needed. Unknown release does not qualify.
2. Core first confirms **both actual transfer release and the complete fixed raw Save CAS** (including exact full-record readback after write-then-throw). Only then may it atomically clear Runtime.resource and the private checkpoint and mark sourceDetached, before local continuation. These private references and the detached/revoked decision belong to one monotonic, memory-only per-Runtime source cell (or an equivalent short source-only serialization point with no SQL/IO). sourceForTask/checkpoint installation and the private-exit handler consult this exact cell. Do not split clearing references, setting a Boolean and handling exit into unrelated operations.
   - Detach wins: sourceDetached is irreversible; a queued or concurrent private-exit handler sees OUTPUT_ONLY and must not cancel the whole Runtime/output context.
   - Exit wins: the handler marks the exact source cell revoked and signals the source-dependent task stop before durable work. Detach may not override it; retain actual source/owner drain until release is positively known.
   - Raw CAS false/unknown or release uncertain: never mark sourceDetached/OUTPUT_ONLY. Preserve existing recovery/terminal disposition; eventual failure-side source drain may release references but cannot confer output-continuation eligibility.
No retry/reselection/sourceForTask path can restore source or checkpoints after this point. Merely setting transfer != null or clearing a field is not itself positive release evidence. The source cell is a resource-lifetime fence, not a new durable task state machine or worker.
3. Core reports the exact task/operation/private-binding source-scope exit to the app registration owner. This is a memory lifecycle event, not new execution/publication authority. The receiver matches its existing registration and combines it with actual port-release evidence and its own cleared source/credential/manifest references.
4. The registration atomically completes source detachment, then existing output-only processing may continue using the already accepted output/task owners. A private-retirement callback that was queued before detachment must recheck the same core source cell and registration identity when delivered; even if the app acknowledgement is queued, it cannot later call a whole-task cancel against an already sourceDetached OUTPUT_ONLY owner. If retirement wins while source-dependent, it revokes that exact source owner; no source work can be restored by a later callback.

The event contract should be an exact-binding callback such as onPrivateSourceDetached(taskId, operationId, binding), implemented by the existing app owner; a caller Boolean, taskId alone, finished Future or copied binding value is insufficient to acknowledge browser drain. Failure exits also release their source registration after actual release, but do not acquire output eligibility. A cleanup/output event is not a substitute for the controlled raw CAS/proof/verification/publication checks. CAS outcome unknown retains its existing recovery semantics; a separate failure-side release acknowledgement can retire source references after actual release but must not report an OUTPUT_ONLY handoff.

Source-only detach is separate from the core AcceptedDirectAttempt/Claim lifetime: output normalization/native/commit/cleanup may still own that execution and batch. It is also separate from the browser Profile deletion callback. Neither source detachment nor profile deletion can prematurely release a still-owned output native lane.

Private durable input is not network-resumable after session exit/process death. Partial network work must not fall through generic recover -> WAITING_USER -> reselect/authentication restore. Merely matching the same URL, random identity or decoded private values in a new session is insufficient.

Keep output-only recovery distinct from network replay: a complete validated transfer/proof/verification/provisional commit or already durable success can be reconciled by existing exact local ownership/publication rules without any SourceUrl or cookie. This source-type migration grants no new automatic normalization, retry or publication. Existing actual exit/cancel winners, release conditions, explicit recovery requirements, and unknown-outcome preservation still apply. A proven SUCCEEDED receipt remains success and its explicit user output is retained; an uncertain unpublished provisional result is not silently declared success or deleted without its exact terminal plan.

Recording ③ already uses URL-free CaptureDescriptor; keep CAPTURE layout. Audit app discovery-save links, authentication origins and source sidecars so none silently attach private browsing provenance to a capture task. Its generation/sessionId remain recording-owner values, never browser profile/session identifiers. Do not persist the private exception, selected private View/document, projection result/Intent or browser grant in it.

On private recording departure, restore protection and revoke the private surface permit immediately, then stop-and-finalize the exact capture owner. Do not call Coordinator.cancel or cancel the Save OperationContext merely to revoke browser access: AndroidRecordSavePort refuses publication after a cancelled context, including a valid Completed result. Explicit discard is a separate monotonic unpublished-output action. Wait for actual audio/video/VirtualDisplay/MediaProjection/native release before acknowledging private-generation drain. The one-shot private capture permit must precede the earliest audio or video/preview acquisition. Successful files remain in the library after private exit.

## 7. Encode/decode and behavior acceptance matrix

| Case | Required result |
|---|---|
| Ordinary fixtures versions 1–7, NETWORK and CAPTURE | Decode unchanged; source/phase/evidence semantics preserved; re-encode/read round-trip valid |
| v8 ordinary NETWORK/CAPTURE | Existing payload order/invariants preserved |
| v8 PRIVATE_NETWORK DIRECT/HLS | Round-trip only random IDs + expected constraints; no URL accessor or recovered live binding |
| PRIVATE_NETWORK in v1–7; unknown/missing tag; truncated/trailing bytes | Reject, never ordinary fallback |
| Wrong-length/noncanonical random value or selection UUID | Reject encode and decode |
| Live private Network passed to SaveRecord/codec; durable PrivateNetwork submitted as a live request | Reject before insertion/network/admission work |
| Private checkpoint present, including ETag-only and redirect-hash-only variants | Reject encode and decode; real private checkpoint callbacks leave journal checkpoint null |
| Private raw MIME containing parameters, arbitrary token or unsupported value | Reject before persistence/fingerprint; canonical production values retain existing behavior |
| Historical valid private QuickTime complete raw/proof while current MOV admission is false | Encode/decode remains readable independent of live flags; existing local recovery only, no fresh network/remux authorization |
| Random private identity differs from normalization/verification proof identity | Reject; exact matching private MOV proof flows through real Coordinator fake ports and production adapter fixtures |
| Same task repeated conversion/submission/retry | Same nonce/IDs and idempotent request; no fresh random value on conversion |
| Two tasks saving same URL | Different 256-bit identities and private selection IDs; no persisted common URL hash |
| Real HLS variant/parser identity contains URL/manifest hashes | Exact live-selection checks remain; durable variant is an unrelated generated ID |
| PR12 private preparation → proposal → destination → confirm | The same random sourceIdentity survives; exact seed-to-binding identity is additionally checked; no old-proposal reconstruction or matching relaxation |
| Seed bind races revoke; second bind; foreign seed on final task | Fail closed; first valid binding keeps the seed values; decoded durable values cannot create a seed/binding |
| Same-origin private, cookie-free private, cross-origin CDN, redirects and HLS segments | Correct partition/UA; no ordinary-cookie fallback; revocation still stops anonymous work |
| Cancel/exit/expiry before registration, after queueing, during body/checkpoint, after release, late callback | No escaped task or checkpoint reinstallation; positive release required before retirement |
| Private exit callback queued immediately before source detach; callback runs after OUTPUT_ONLY | No whole-task cancellation, no source resurrection, completed output still follows existing verify/commit rules |
| New OUTPUT_ONLY registration or detached state asserted without real source/credential/manifest release | Refuse; no browser-drain acknowledgement from a phase flag or copied value |
| Process restart with partial private task | No HTTP/source reconstruction, no ordinary auth navigation; retained exact recovery/terminal disposition |
| Crash around complete raw, MOV proof/adoption, commit intent, rename, indexing, final CAS | No network replay or forged source; existing exact local recovery and unknown-write outcomes remain; immutable success retained |
| Private recording stop/departure versus explicit discard | Valid finalize can publish under output ownership; discard only unpublished current output; no cancellation-token accident |
| Blocked/uncertain private audio/network/native close | Memory revocation remains immediate; generation remains quarantined; no replacement worker/lane |

End-to-end privacy fixtures use unique synthetic origin, path, query, raw and hashed selected/redirect/segment URLs, ETag, MIME parameter, title, profile name, Cookie and UA sentinels. Inspect resulting Save/catalog/evidence/normalization bytes plus actual SQLite main/WAL/SHM and relevant journals after each transition, including failed/unknown writes. Content/output SHA fields remain present but must not receive URL-derived identities through a secondary channel. Verify completed file retention and ordinary Direct/HLS/Record/MOV regression behavior separately.

## 8. Ownership and review gate

This document is the shared implementation contract, not implementation acceptance. Parent obtains independent design review before releasing source changes. Core writer owns the agreed core types/Coordinator/metadata consumers and core tests; parent owns app codec/Runtime/transport/storage/UI integration and its tests. Coordinate any extension to this list before concurrent writes. No Gradle or device run is authorized to a non-build owner by this contract. MOV default admission remains closed until its existing Android/device acceptance gate is satisfied.
