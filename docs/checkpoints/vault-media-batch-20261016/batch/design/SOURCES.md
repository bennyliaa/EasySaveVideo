# Platform facts and inspected source basis

Research date: 2026-10-16. Primary Android documentation/source only. These links support design constraints; they are not evidence of Android execution by this task.

- ACTION_OPEN_DOCUMENT_TREE supplies one chosen subtree, and installed providers may be presented. EXTRA_LOCAL_ONLY requests local availability, not a guarantee that writes cannot upload. Therefore the existing finite provider/root profile remains mandatory.
  https://developer.android.com/reference/android/content/Intent#ACTION_OPEN_DOCUMENT_TREE
  https://developer.android.com/reference/android/content/Intent#EXTRA_LOCAL_ONLY
- The ordinary grant is not safely modeled as lasting only one process. Android describes the default grant lasting until device restart, while takePersistableUriPermission is a separate capability and persisted access can still be lost after document moves/deletion. This slice does not take it.
  https://developer.android.com/training/data-storage/shared/documents-files#persist-permissions
  https://developer.android.com/reference/android/content/ContentResolver#takePersistableUriPermission(android.net.Uri,%20int)
- Directory child-creation support uses MIME_TYPE_DIR plus FLAG_DIR_SUPPORTS_CREATE, distinct from ordinary content writing.
  https://developer.android.com/reference/android/provider/DocumentsContract.Document#FLAG_DIR_SUPPORTS_CREATE
- Provider createDocument allocates a new document identity and may adjust the requested display name; client createDocument returns the actual URI or null and can fail. Its API provides neither a cross-database transaction nor an idempotency token. Treating a lost creation acknowledgement as uncertainty, rather than nonexistence, is a conservative design inference.
  https://developer.android.com/reference/android/provider/DocumentsProvider#createDocument(java.lang.String,%20java.lang.String,%20java.lang.String)
  https://developer.android.com/reference/android/provider/DocumentsContract#createDocument(android.content.ContentResolver,%20android.net.Uri,%20java.lang.String,%20java.lang.String)
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/core/java/android/provider/DocumentsContract.java
- isChildDocument means any descendant; queryChildDocuments is immediate children. IDs are generally opaque and documents may have multiple parents. URI-string-prefix containment is insufficient.
  https://developer.android.com/reference/android/provider/DocumentsContract#isChildDocument(android.content.ContentResolver,%20android.net.Uri,%20android.net.Uri)
  https://developer.android.com/reference/android/provider/DocumentsProvider#queryChildDocuments(java.lang.String,%20java.lang.String[],%20java.lang.String)
  https://developer.android.com/reference/android/provider/DocumentsContract.Document#COLUMN_DOCUMENT_ID
- For apps targeting API30+, Android 11 restricts selecting internal-storage root, certain SD roots, Download itself, and Android/data or Android/obb. Existing app targets API36; user guidance should say choose an available user subdirectory, not bypass disabled picker options.
  https://developer.android.com/about/versions/11/privacy/storage#document-access-restrictions
- Activity-result delivery can span process recreation; pending operation identity must be separately preserved or results rejected safely. No user-action authority is reconstructed from saved strings.
  https://developer.android.com/training/basics/intents/result
- Some ContentResolver APIs accept CancellationSignal; createDocument/openOutputStream do not provide transactional rollback. Cancellation/timeout cannot prove close, deletion, or absence.
  https://developer.android.com/reference/android/content/ContentResolver#openOutputStream(android.net.Uri,%20java.lang.String)
  https://developer.android.com/reference/android/os/CancellationSignal
- The fixed AOSP ExternalStorageProvider source uses unique-file creation, while its filesystem-backed document identity is not a general durable incarnation token. The contract deliberately refuses restart cleanup based only on URI/name/regrant. This is a conservative policy, not a claim of an immutable per-file ID API.
  https://android.googlesource.com/platform/frameworks/base/+/f8754ac2127f8adf40bd4731f39b16340acdb2f7/packages/ExternalStorageProvider/src/com/android/externalstorage/ExternalStorageProvider.java

Actual source references were hashed in BASELINE_SHA256.json. Key APIs inspected: VaultMediaRuntime.confirmCopy/recover/newOperation, VaultMediaJournalWire.encode/validate/requireSuccessor, AndroidVaultStore.validateBoundPhoneTarget/canCleanupPhoneTarget/writeMedia, AndroidVaultPhoneFiles.acceptResult/requireLocal/recoverTarget, AndroidExportHandles, and ordinary FolderCreateIdentity/AndroidFolderGrantRegistry for contrast. Ordinary plaintext batch rows and persisted folder registry are intentionally not reused for Vault state.
