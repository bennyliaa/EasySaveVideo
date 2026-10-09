# Owned file-stream close hotfix

## Scope and observed failure

Based on frozen source `f78d7cc46c92bdd461e701ac3c39f62e12cb0ce5`. On Android 12 / API 31,
DeviceA12 emulator 5556, the ordinary direct download was independently verified and
published. The first library thumbnail then left the process-wide media gate fenced,
before any preview click or MediaProjection request. No existing media is deleted,
uninstalled, reindexed or rewritten by this fix.

The task-screen draining warning is aggregate state, not proof of codec teardown
failure. Its source includes thumbnail/preview ownership and sticky uncertainty.
The deterministic defect is in the owned `FileInputStream.close` override: both
thumbnail and preview obtain `input.channel.size()`. `FileInputStream.close` closes
that channel before the descriptor; the channel invokes its parent stream's close
again. The old nested override sees a still-valid descriptor and throws, interrupting
the outer descriptor close. Read accounting remains retained and the thumbnail's
strict release check correctly fences later media work.

Primary platform evidence:
- [Android API 31 FileInputStream](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-31/+/refs/heads/main/java/io/FileInputStream.java)
- [AOSP FileChannelImpl parent-close callback](https://android.googlesource.com/platform/libcore/+/db41296f4b9b8d9fd0cded98779beaca5b109fb1/ojluni/src/main/java/sun/nio/ch/FileChannelImpl.java)

A standalone real-file JVM reproduction confirms nested close depth two, the
unconfirmed-release exception, a still-valid descriptor and an unreleased owner.
This is JVM/platform-source evidence, not an Android retest.

## Fix

A small shared owned-file-stream helper closes the associated channel first, then
the stream. The channel has already marked itself closed when it calls the parent,
so the nested stream close can actually close the descriptor before accounting is
checked. No new lock is held across either platform close.

The four mirrored ordinary wrappers use that helper:
1. Published-library read stream.
2. Save staging output stream.
3. Editor output stream.
4. Recorded-segment output stream.

Ownership changes only after the actual descriptor is invalid, at most once under
the existing storage ownership lock. Native reader/writer leases remain separately
owned. A blocked or failed close with a still-valid/unknown descriptor cannot
release ownership. Exceptions are not swallowed. Existing media-admission gates,
watchdogs, consent behavior, codec paths, schemas and dependency graph are unchanged.

Version code is 2 so authorized update installation can preserve the existing
package data. Package, minimum/target SDK and debug signing identity are verified
against the prior installed-preview APK before delivery. No downgrade/uninstall
is part of this workflow.

## Verification

The shared helper regression suite uses real JVM file streams/channels for
stream-first, channel-first, repeated and concurrent closure, with all four
ownership categories. It also checks append/data preservation, close exceptions,
unknown descriptor state and a blocked-close ownership fence. Additional source,
JVM, compile, lint and exact runtime-admission results are recorded alongside the
artifact receipt. Android media behavior remains NOT_RUN until the authorized
local executor installs the update and repeats thumbnail, preview, editor and
recording checks on retained media.
