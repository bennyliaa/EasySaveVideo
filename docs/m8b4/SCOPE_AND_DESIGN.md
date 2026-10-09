# M8-B4: lifecycle-owned Android Discovery observations

Date: 2026-10-07. Baseline: `bed084e46fcbf3bffff57177fc21306ddaeec2e1`.

## Delivered development scope

This is the independently scoped continuation identified by `docs/m8b3/NEXT_INDEPENDENT_SCOPE.md`, supporting PR-05 / Product Requirements §5.3, Architecture §4.1 and Technical Requirements §13. The release controller now actually constructs the Android observation source and uses its lifecycle-owned bridge. It begins with **enabled UNKNOWN** observations, which pause source requests. It never starts with fictitious healthy readings or relies on a dormant unused adapter.

- Observe the system battery level/scale and low-state broadcast, system battery-saver state, thermal status, and the app's default network capabilities/blocked status.
- Register only while the existing foreground and Discovery-visible flags are both true. Exit, pause, source failure and controller close invalidate the generation before cleanup. Late callbacks from an old generation cannot reopen it. Unique token identities have no numeric wraparound.
- Battery ratio ≤5% maps to CRITICAL; ≤20%, OS low-state, or enabled system battery saver maps to LOW (saver never hides critical/unknown battery). Missing/invalid level, scale, low-state or saver observation stays UNKNOWN. Charging does not override these conditions.
- Thermal NONE maps to NORMAL, LIGHT/MODERATE to WARM, SEVERE through SHUTDOWN to SEVERE, and unrecognized/failed reads to UNKNOWN. These are OS classifications, not app temperature measurements or OEM safety guarantees.
- A newly available default network first clears the old capabilities and blocked state. Only that network's callback values may populate them. Internet, validated, not suspended and a known unblocked state are required. Metered usable networks conserve; unusable/blocked/lost networks pause. Missing state stays UNKNOWN. The adapter does not inspect SSID, transport/location details, addresses, network names, URLs or user traffic, and does not request a network.
- Each battery, saver, thermal, capabilities and blocked observation has its own monotonic receipt/read time. Five minutes without a new observation expires that component. Updating one cannot refresh another. The capability and blocked components must both be fresh for the composite network signal.
- One removable one-shot task expires the nearest current deadline on a dedicated lane. Early dispatch re-arms only the remainder; delayed dispatch checks current elapsed time; cancelled or old-generation tasks cannot expire newer data. Queued controller commands also revalidate ages before execution. There is no periodic OS polling, worker service, wake lock or automatic registration retry.
- Healthy steady state may produce no OS callback and therefore expires normally. The UI explains this and exposes **刷新系统信号**. Refresh renews observations; after valid observations arrive the user must explicitly search, change page or request more. Refresh, better conditions and system callbacks never create a round, refill attempts or materialize pending pages.
- Every changed observation enters the existing immediate coordinator power fence before one coalesced latest-value IO delivery. Coordinator identity and its independent power generation fence protect replacement and queued ordering. The existing Save/Record priority and lifecycle fences remain independent. UI snapshot publication is also coalesced, preventing a blocked main thread from accumulating app-created power display posts.
- Opening/closing is serialized without holding the state lock across OS registration calls. The iterative reconciliation pump handles concurrent/reentrant lifecycle churn without call-stack growth. Partial registration attempts clean every possibly acquired registration. An uncertain cleanup blocks further registrations for that controller rather than accumulating leaked subscriptions. Such callbacks are already inert; actual OS cleanup is not claimed after failure.

The three registrations (one battery/saver receiver, one thermal listener, one default-network callback), one expiry task, one pending policy value and one pending UI value are bounded. Android/framework-owned delivery queues and thread scheduling are outside this bound; this is not a hard real-time freshness or cancellation guarantee. A blocked/failed OS call can delay that calling thread and requires later device responsiveness acceptance.

## Permissions and privacy

The application manifest is byte-identical to the baseline. Both prior merged manifests already contained the normal `ACCESS_NETWORK_STATE` permission from the admitted `androidx.media3:media3-exoplayer:1.11.1` manifest. The current scope deliberately uses that effective permission and checks its presence and the complete unchanged eight-permission set in both actual merged variants. The eighth is AndroidX's app-private signature `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; no new privacy permission or runtime consent is introduced.

API 33+ receiver registration explicitly uses NOT_EXPORTED; API 31–32 uses the system-broadcast-only overload. Default network callbacks do not request location-sensitive information. No signal, network identity, percentage, thermal value or observation time is persisted or logged. No new artifact, SDK, server, account, payment, signing or publishing integration was added. Debug scenarios remain process-local and absent from the release allowlist; “关闭模拟” now returns to actual OS observations in the controller.

## Official Android API basis

- [ConnectivityManager.NetworkCallback](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback): callbacks supply ordered capability/blocked updates for the selected default network; synchronous connectivity reads inside them can race. This adapter uses callback arguments and clears old-network state on availability.
- [ConnectivityManager](https://developer.android.com/reference/android/net/ConnectivityManager): default-network monitoring registration and explicit unregister lifecycle. The app observes rather than requesting or binding to a network.
- [BatteryManager](https://developer.android.com/reference/android/os/BatteryManager): battery level/scale/low extras. The app validates presence and range instead of interpreting missing values as zero.
- [PowerManager](https://developer.android.com/reference/android/os/PowerManager): thermal status listener/current status, system power-save state and its change action. Startup seeds cannot overwrite a callback already received during registration.
- [Context](https://developer.android.com/reference/android/content/Context): dynamic receiver registration, system broadcast behavior, explicit receiver flags and unregister lifecycle.
- [SystemClock](https://developer.android.com/reference/android/os/SystemClock): elapsedRealtime is a monotonic time base including deep sleep. It is the receipt-age clock, not the time at which the hardware physically sampled a condition.

## Explicitly unaccepted work

Android battery/OEM thermal correctness, default-network callback behavior, main-thread/Binder responsiveness, background/rotation/process-death behavior, real permission enforcement, threshold calibration, battery drain and measurable energy savings are **NOT RUN / NOT VERIFIED**. Host/API doubles cannot establish these. No APK/AAB was assembled, installed or run, and no real permissions were accepted.

The blocked Vault and M7-D/rule-interpreter reviews remain blocked, isolated and disabled. This is a new ordinary Discovery observation review, not a retry, replacement, renamed review or probe of either. Their historical isolation regressions remain untouched.
