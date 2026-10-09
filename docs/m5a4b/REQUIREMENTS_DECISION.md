# M5-A4b: 普通媒体记住外部文件夹

Date: 2026-10-07. Baseline: `046209b39d932f08910a8b79f33d4d02d6183ccd`.

## Product boundary

This is the next bounded ordinary-media slice of D-31 / PR-17 / TR-11. It adds **App媒体库并另存一份到所选文件夹** to A4a's App Library, ASK and album settings. It applies only to newly admitted Direct/HLS/basic recording/advanced recording/editor creations already integrated in A4a. No historical catalog scan, backfill, changed in-flight destination, external-only original or move is introduced.

Every creation still commits its verified private ordinary-media original first. The optional folder operation is a distinct copy with a stable operation/grant identity and exact initial source reference. Success, failure, cancellation, uncertain create/write/publication and cleanup failure never delete or roll back that private original. Deleting an original remains a separate explicit operation.

Users explicitly open Android's `ACTION_OPEN_DOCUMENT_TREE` picker from ordinary destination settings. The setting discloses before selection that the selected document provider may synchronize data to a cloud service and that a partial SAF document may become visible before verification. This ordinary-media option makes no phone-local-only promise. The app neither implements direct network upload nor chooses an external folder without this user action.

## Remembering, switching, forgetting and invalidation

- The selected opaque folder registry identity is snapshotted into each admission. The per-export grant ID remains separate and unique. Later setting changes never replace either identity or source.
- Switching to another setting affects future creations only. A remembered folder can also be used in ASK. A directory is not accepted without actually offered and confirmed persistent read/write authority.
- Stopping remembrance clears the remembered folder and returns the default to App Library. It does not cancel old admitted work or revoke the OS grant. The UI says why this preserves processing/cleanup responsibilities and explains that system-settings revocation can make manual cleanup necessary.
- Observed loss of the exact persisted read/write authority irreversibly invalidates every registry row for that URI, including retired rows, and clears the remembered selection. Before reacquiring a missing grant, the old rows are invalidated durably. Reselecting the same URI then obtains a fresh opaque identity; old invalidated operations cannot revive through it. Android grant timestamps are not treated as stable generation tokens.
- No OS permission is released automatically. An old operation or another owner may still rely on it. Registry rows are retained, including failed adoptions, retired and invalidated identities. A 128-row bound fails closed; it does not evict cleanup responsibility. Repeated selection of a continuously valid, active identical URI/flags reuses its row. System revocation does not free retained registry capacity; the UI does not claim it does.

## Separate Vault clarification and excluded work

The user's Vault export clarification remains: “导出成功也保留原文件，导出的功能是把保险箱里面的文件复制到手机文件系统。” A4b is not that Vault feature. It adds neither Vault source authority nor Vault export entry points, decryption, authentication, recovery or security acceptance. The meaning of phone-file-system-only for future Vault exports remains separate from ordinary SAF provider selection.

Both blocked Vault and M7-D/rule-interpreter reviews remain blocked, disabled and isolated. They were not retried, rephrased, substituted or probed. Existing isolation gates are regression checks, not replacement reviews.

Android min31/target36, admitted dependencies, SkinKit, native media engines, SaveRequest codec, account/server/payment/distribution behavior, and all manifest permissions remain unchanged. No APK/AAB assembly, installation, real device consent, account, purchase, signing or publishing is part of this work.
