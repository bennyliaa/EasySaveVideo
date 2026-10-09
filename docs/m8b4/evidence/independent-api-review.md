# Independent Android API review basis

Reviewed 2026-10-07 for the ordinary Discovery OS signal adapter. This is public API
documentation research, not Android runtime, device, or energy acceptance.

## Official references

- [ConnectivityManager.NetworkCallback](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback): default-network callbacks track the latest best network. API 26+ orders availability before capabilities and link properties, followed by blocked status. Callback arguments must be used instead of racing synchronous connectivity reads inside callbacks.
- [NetworkCapabilities](https://developer.android.com/reference/android/net/NetworkCapabilities): `NOT_METERED`, `VALIDATED`, `INTERNET`, and `NOT_SUSPENDED` express different properties. Transport identity alone is insufficient to infer usable or unmetered access.
- [BatteryManager](https://developer.android.com/reference/android/os/BatteryManager): the battery broadcast reports level relative to scale; `EXTRA_BATTERY_LOW` is the system's low-battery indication. Missing or malformed values require an unknown observation.
- [PowerManager](https://developer.android.com/reference/android/os/PowerManager): the saver change broadcast is delivered to registered receivers; thermal observation supports a listener with an explicit executor and an initial current-status read.
- [Context](https://developer.android.com/reference/android/content/Context): dynamic receiver registration returns a matching sticky intent, if present, while registered receiver callbacks have their own delivery ordering.
- [SystemClock](https://developer.android.com/reference/android/os/SystemClock): elapsed realtime includes deep sleep and provides an appropriate local monotonic basis for age checks.

## Independent review targets

1. Start and stop are bounded/idempotent. Stop invalidates callback ownership before cleanup.
2. Separate sample ages prevent one healthy observation from refreshing another stale channel.
3. Initial getter/sticky results cannot overwrite callbacks already observed during registration.
4. A new default-network identity invalidates earlier capabilities and unblock knowledge.
5. Late callbacks and expiry tasks from an old registration cannot affect a later lifecycle.
6. Invalid data, read/registration failure, uncertain unregister, timer failure, and queue saturation fail closed.
7. Immediate restrictions precede queued policy work. Relaxation, signal refresh, and timer expiry cannot create discovery demand or reset spent request accounting.
8. Stable unchanged OS state may produce no events. Expiry therefore needs truthful snapshot-expiry UI and an explicit bounded refresh path; no claim of an OS failure follows solely from age.
9. Direct listener executors, reentrant startup callbacks, delayed/cancelled expiry delivery, and same-value sample renewal are hostile host fixtures, not claims about a particular Android vendor's callback behavior.

## Explicit exclusions

No blocked candidate or substitute review, Vault work, new libraries, external services,
accounts, APK/AAB, device install, actual OS callback acceptance, temperature calibration,
or measured energy saving belongs to this review.
