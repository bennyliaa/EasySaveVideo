# Wi-Fi-only Direct and Stream saving

## Scope

Original requirement: Product Requirements §10 lists Wi-Fi only in Saving settings;
§7.3 requires truthful waiting/interruption and recovery. Baseline:
`2986ada82ff4f0843eced900a90249d69f876671` (editor default compression).

Settings → Saving now owns one additive boolean preference, `save_wifi_only`, default false.
AppSettings remains schema v1. Existing Editor settings and both editor startup guards are retained.
This slice does not add concurrent saving, background execution, adaptive selection, a new provider,
new dependencies, new permissions, process-wide network binding, or operating-system setting changes.

## End-to-end behavior

- False keeps historical default-network transport. Until the first successful preference load or
  explicit Wi-Fi-setting choice, saving fails closed. A failed/unfinished load cannot overwrite an
  existing stored restriction through an unrelated settings write. Typed setting intents apply to the
  latest state; stale composable callbacks cannot substitute an old false Wi-Fi sibling. Application-scoped choices survive
  Activity recreation; a later stale disk load cannot replace them.
- True requires the currently active DEFAULT Network to report Wi-Fi transport, Internet capability,
  and not suspended, with its complete reported transport bit set equal to Wi-Fi. It never treats an unmetered Ethernet/cellular
  connection as Wi-Fi. Metered Wi-Fi is allowed. VPN routes are conservatively rejected because this
  slice does not establish their underlying physical route. The adapter enumerates the bounded 64-bit
  transport domain through the public hasTransport API; it uses no hidden getter or reflection. No Wi-Fi
  identity is stored or logged.
- Every HTTPS request and redirect acquires one approved route; URLConnection, DNS and manually
  created sockets use that same Network. No fallback to a new default route occurs. Existing system
  TLS/hostname validation, explicit no-proxy behavior, public-peer checks and credential scoping remain.
- Admission is checked at user action, HLS preparation, HLS selection, resume, dispatch and the
  coordinator's pre-transfer boundary. The first HLS manifest, subsequent playlists and all segments
  use the same transport policy as Direct; preparation is not an unguarded side channel.
- Each active connection rechecks its route in request/read boundaries and the existing bounded
  watchdog (up to 50 ms between normal watchdog checks). No new app-wide background poller is created.
  Route or setting changes close the active request and do not silently start a new one. A previously
  unpinned request is retired when the restriction is enabled, even if Wi-Fi is currently available.
  Socket/network binding, rather than polling alone, prevents fallback traffic over cellular.
- Native release must finish before the task becomes an ordinary NETWORK wait. Owned-object cleanup
  also handles platform linkage failures and preserves recovery when release cannot be proved. Unconfirmed release
  retains the occupied transport and the existing RECOVERY path. Explicit cancellation wins over a
  policy wait once resources are released; already-published success is never rewritten.
- WIFI_REQUIRED maps to SaveWaitReason.NETWORK, not BACKGROUND, and does not consume automatic retries.
  Direct retains its existing strong-ETag checkpoint and requires an explicit user resume with a fresh
  policy check. Existing source lifetime, expiry, authentication and restart restrictions still apply.
- HLS preparation has no durable task yet: interruption releases its session/lease and gives an honest
  retry notice. An incomplete accepted HLS task cannot resume fragments. The UI directs the user back
  to the webpage to select again; the new task starts from scratch, and the old one can be cancelled.
- Policy is scoped to network work. It is not added to the shared operation cancellation token.
  Recording, editing, local HLS remux after downloaded segments, and already-completed transfer
  verification/commit recovery remain usable without Wi-Fi.

## Security and compatibility

The application manifest is unchanged. Existing debug/release merged manifests already contain
ACCESS_NETWORK_STATE from the admitted Media3 graph. The final permission check must confirm the
unchanged effective eight-permission set. No SSID, BSSID, network address, source URL, credential or
exception message is introduced into logs, settings, diagnostics or the task journal.

WIFI_REQUIRED is serialized by enum name using the existing v6 SaveRecordCodec; no journal format
change is needed. Older current-format records remain readable. An older application version cannot
be assumed to understand a newly added error name; downgrades are not an acceptance claim.

## Validation and limits

See `artifacts/ui/save-wifi-only-20261013/validation.json` for exact commands, actual versus
UP-TO-DATE tasks, test totals, existing skipped corpus tests, unchanged permission evidence,
original admission gates and independent review findings/fixes. Fake transports exercise admission,
redirects, body/response blocking, cancellation, release failures and pinned DNS/socket construction
without live network requests. Coordinator tests check waiting, explicit resume, checkpoints,
recording exclusion, terminal-state stability and local completion. Pure settings/route tests and
source wiring contracts cover defaults, setting changes, route identity and startup connections.

No device/emulator, APK assembly/install, real network switch, real VPN, real SharedPreferences restart
or Compose accessibility/layout execution is claimed. Android compile/lint plus JVM actors do not
substitute for those device acceptance cases. No external publication is performed by this task;
the parent receives a frozen exact file whitelist and hashes.

### Android API reference

The SDK36 public API was checked with javap. AOSP marks getTransportTypes as SystemApi and exposes
hasTransport publicly; the latter rejects invalid transport indexes. This implementation uses only
that public probe over the bounded underlying bit domain, not the hidden method:
https://android.googlesource.com/platform/packages/modules/Connectivity/+/4fd27d40893aaf51abbf0fa9b0ddd4fd98017db7/framework/src/android/net/NetworkCapabilities.java
