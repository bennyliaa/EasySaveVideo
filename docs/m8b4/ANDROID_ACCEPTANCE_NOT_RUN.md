# Later Android acceptance checklist — NOT RUN

This checklist grants no device, APK, permission, installation, account or publishing authorization.

1. API 31–36 phones/tablets: enter/leave Discovery, foreground/pause/resume, rotation/recreation, process death, quick repeated refresh, close while registration is in progress. Confirm no background discovery or retained active subscriptions after normal cleanup.
2. Compare actual OS battery level, missing extras, OS low-state, battery saver on/off and plugged/charging states. Calibrate or deliberately retain the provisional ≤5%/≤20% policy thresholds; they are not energy measurements.
3. Observe real OEM thermal status, unsupported/failing service behavior and transition ordering. Confirm unknown/failure stays conservative; no unsupported temperature or thermal-health guarantee appears.
4. Wi-Fi/cellular/VPN/default-network transitions, captive portal, validation changes, metering, suspended or blocked access and network loss. Verify capability/blocked callback identity and ordering without collecting location/SSID/address data.
5. Healthy steady state across five-minute expiry; delayed callbacks; sleep/wake and clock behavior; explicit refresh then explicit search. Confirm healthy callback recovery and refresh do not launch work or refund spent attempts.
6. Start Save/Record before, during and after observed changes. Confirm media priority and cancellation gates remain independent, cached local results stay usable and no callback starts media work.
7. Force service-read/registration/unregister failures and main/Binder delays where feasible. Check error text, no retry storms, bounded retained registration state and conservative behavior when cleanup is uncertain.
8. Validate Compose display/current status and repeated actions, frame responsiveness and accessibility. Native OS scheduling and UI acceptance cannot be inferred from deterministic host doubles.
9. If separately authorized, measure controlled baseline-versus-policy energy use with repeatable workload/device conditions. No measured battery-savings claim is supported by this source milestone.
