# Screen-off correction to continuous BASIC policy
Supersedes the overly broad retention of app-initiated screen-off/lock stops in DESIGN §5 and the corresponding acceptance wording.

Android Developers states that Android 15 QPR1 and higher automatically stop media projection when the screen locks:
https://developer.android.com/about/versions/15/behavior-changes-all#media-projection-status-bar-chip
(Official page checked 2026-10-14; page updated 2026-10-01.)

That OS behavior does not justify synthesizing revocation on every supported Android version. Exact submitted BASIC owners therefore ignore the application's SCREEN_OFF stop route, and the BASIC capture loop does not stop merely because isInteractive=false or keyguard is locked. Actual MediaProjection.Callback.onStop still terminates normally, along with real resource failures, FGS loss and existing bounded limits. No OS protection is bypassed or disabled.

Region, preview and segment-specific policy is unchanged. Before accepted submission, a screen-off may still retire the setup; it is not an active recording.
Older platform results may be blank, frozen or otherwise restricted. We do not promise that locked/protected content is recordable or that OEM resource policies allow indefinite continuation. Real device validation remains necessary.

Acceptance: exact submitted BASIC ignores app broadcast; foreign/unsubmitted cannot use that exemption; BASIC polling is separated from screen-state stop; Region/preview/segment polling remains; actual onStop, free-space and byte-limit checks remain unchanged.
