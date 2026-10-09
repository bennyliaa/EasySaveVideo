# Bookmark Android acceptance: not run

This source-only milestone does not assemble or install a new APK/AAB. The
separately frozen `f78d7cc` preview APK does **not** contain these bookmarks; any
local preview testing of that build is not bookmark evidence.

Future separately authorized bookmark APK preparation must increase versionCode
above the new hotfix preview value **2**, identify the new commit/version
truthfully, and preserve the existing preview rather than silently replacing it.
The reviewed media-close hotfix is integrated with its versionCode 2; no
bookmark APK has been assembled or shipped. Both debug/release compilation are verification, not packaged delivery.

Still required on Android 12/API31 and a contemporary API36 phone/tablet:

1. Add/list/open/delete ordinary HTTP and HTTPS pages; duplicate normalization,
   Unicode/long/deceptive titles, capacity, explicit address/fragment handling.
2. Observe that showing/restoring the list performs no request, favicon fetch or
   WebView creation; confirm exactly one navigation after a deliberate open.
3. Rotate during load/add/remove, switch tabs away/back, navigate/redirect/reload,
   change a History API/hash URL and dynamic title after 60 seconds, kill and
   restart the process, and recreate the renderer. Old page/record identities
   must not be reused.
4. Exercise low storage, corrupt/unknown-version files, inaccessible storage,
   AtomicFile interrupted-write recovery and process termination at commit
   boundaries. No UI false-success, destructive empty fallback or competing
   Activity writers is acceptable.
5. Check narrow/wide, portrait/landscape, keyboard open, large fonts, RTL,
   TalkBack, scrolling, back/dismiss and full-screen video transitions with all
   supported SkinKit visual defaults. Open/close must not submit address text.
6. Verify clearing Cookie/cache, closing all tabs, and turning tab restoration
   off do not remove bookmarks. Confirm the private file is excluded from
   backup/device transfer and contains no Cookie/token/history payload.

Host/API-double tests establish policy, bounded codec/storage control flow and
source wiring only. They do not emulate WebView callback ordering, real Android
AtomicFile/filesystem durability, Compose measurement/accessibility or OS process
lifecycle. No UI screenshots, measured performance, device pass, overall PR-25
completion or complete browser privacy mode is claimed here.

The reviewed media-close fix from source `0c178320cd78391bfedd5f9846e3ee873f0f5fce`
is included as the combined source baseline. Its separate hotfix preview device
retest was still pending when integration was authorized; source integration is
not evidence of a native device pass.
