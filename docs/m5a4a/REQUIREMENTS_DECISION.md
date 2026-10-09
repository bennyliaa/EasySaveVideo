# M5-A4a: 普通媒体保存位置设置

Date: 2026-10-07. Baseline: a3a028d1e1de0ca20303ac7d14e4ed400e32a176.

## Product boundary

This is the bounded PR-17 ordinary-media destination setting, not completion of the user's Vault export requirement. The visible choices are **App媒体库**, **每次询问**, and **另存一份到相册**. They apply to new Direct downloads, fixed HLS saves, basic recordings, both advanced recording modes, and edited outputs. Every admitted operation first creates and verifies its private App Library original. Album selection authorizes a separate verified MediaStore copy, never a move or external-only original. Success, failure, cancellation, missing grants, process death and uncertain copy outcomes all preserve the internal original. No automatic original deletion or replacement is present.

Preference changes affect later creations only. ASK must be resolved before creation or capture-resource acquisition. Cancellation and stale source/UI callbacks do not create media. The choice and stable copy identity are durable before handing the new creation ID to the existing media runtime. A changed source requires a renewed selection. Historical or retained pre-A4a media never receive automatic copy authority.

A4b persisted external-folder grants are deferred. Existing explicit ordinary MediaStore/SAF/share-copy actions are preserved. No Vault destination or usable Vault-export entry is added.

## Separate Vault export clarification

The latest user clarification is: “导出成功也保留原文件，导出的功能是把保险箱里面的文件复制到手机文件系统。”

The later Vault acceptance contract is therefore an explicitly selected authenticated Vault source, a selected phone-file-system destination, a normal readable verified copy, and retention of the Vault original on every outcome. Deleting the original would require a separate explicit deletion operation. Whether phone-file-system destinations exclude cloud-backed SAF providers remains for that later scope; this clarification does not authorize uploading Vault data to cloud providers or sharing it with another app.

Vault remains disabled and isolated. Its blocked reviews, and the blocked M7-D review, were not retried, rephrased, substituted or probed. This new ordinary storage slice neither resolves those blockers nor claims Vault implementation/security acceptance.

## Other unchanged boundaries

No dependencies, SDKs, Android permissions, Save codec, native capture/verification engine, account, server, payment or distribution behavior changes. Payment strategy remains deferred. No APK/AAB assembly, install, device acceptance, store submission or publishing was performed for this ESV slice.
