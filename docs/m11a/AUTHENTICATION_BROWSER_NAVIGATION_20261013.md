# Authentication-waiting task: explicit Browser navigation

Original UI 05 §8 includes an OpenOriginForLogin intent; 01 §4.3 and 03 UI-R13 require
recoverable waiting states. The existing FAILED-task guidance does not render for
WAITING_USER/AUTHENTICATION. This slice adds a bounded navigation seam to that waiting
state for DIRECT/STREAM tasks, in both existing compact cards and expanded selected detail.
It does not fully implement automatic origin resolution: the label says Return to browser,
and the explanation says to open/sign in on the source webpage manually and then follow
current task guidance. It does not promise a resumable task or successful authentication.

Only a user click may invoke the supplied current Browser route callback. No URL is inferred,
opened, persisted or copied from the media resource. No authentication request, credential
lease, scan, resume, cancel, task submission or selection mutation is performed. Existing
FAILED guidance, manual resume, same-origin checks, recovery and all media logic remain intact.

The detached claim contains task ID/revision/state/wait reason/method/error, without source
or credential data. A composition-owned one-shot command is retired on disposal, and checks
its captured foreground generation during acceptance. A latest callback plus live MainActivity
route/focus/lifecycle/modal gate prevents stale navigation from hidden screens. Runtime checks
one unique current row, existing coordinator memory consistency when available, initialization,
foreground, pending commands/media/expiry, scanning/selection/stream, native and recording owners.
The observable mediaTaskActivity also drives button availability; clicking still rechecks the
actual owners, rather than authorizing from a possibly delayed aggregate snapshot.

Independent review found that existing cold-recovered waiting rows may have no coordinator
memory snapshot. The bridge therefore preserves the existing FAILED-navigation compatibility:
a present cached snapshot must match; absent cache permits only this navigation if the current
published row and other gates match. Absence does not prove a fresh disk read, source identity
or permission to resume. Core recovery was not changed, and clicking performs no disk I/O.
Pure fixtures cover cold-cache compatibility and stale cached revisions, not actual Android
process-restart acceptance.

Final build2: App 1857 JVM tests actually executed PASS, including 22 new tests
(authentication policy 10, source wiring 5, destination resource contract 7); zero failures,
errors or skips. Debug/Release Kotlin compilation and both lint tasks executed PASS.
Both lint reports retain 60 warnings / 8 hints / 0 errors; runtime admission verifies 90 exact
artifacts. Theme tests were not rerun in this group. All 478 other original production/config
files match pre-slice hashes. build1 also passed before the final independent-review refinements.
See artifacts/ui/auth-destination-copy-20261013 for sanitized logs, actual JVM census,
new-test XML, lint reports, source pins and independent review. Latest published baseline is
3176bcc0be84325be5b278823995761f17049dd8; this source-verified group awaits publication.

App contract-input registration now includes this slice and the earlier save_display,
discovery_body/input_clear resources and directly read source files. This prevents future
copy/source-only changes being hidden by an unchanged compiled classpath. No dependency,
SDK version, permission, package metadata or runtime-admission rule was changed.

NOT_RUN: APK, device/emulator, actual WebView login/navigation, real recovery/source access,
Compose event ordering, TalkBack, 200% rendering, PPT/screenshots. No authentication or real
media acceptance is claimed. Existing OPPO editing/share failures remain unresolved.
