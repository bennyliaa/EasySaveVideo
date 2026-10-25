# ESV code12 frozen source recovery

Private backup only. This path contains four ordered 8MiB archive parts, the exact payload MANIFEST, bounded reassembly helper and sanitized admission/lint evidence. No installation package or signing material is included.

Run REASSEMBLE.py in an isolated receiving directory to verify each part and reconstruct the 30,715,370-byte archive. It refuses an existing output and does not extract or execute source. Expected SHA-256: 339b2f76342399445b93ffd2cc263bf00f78b611b934b73f3b50264d6363ae0e. Review the archive MANIFEST and RECOVERY_README before applying anything.

The archive contains 2,892 byte-identical frozen code12 source files plus 16 diagnostic patch/test/metadata-admission evidence files. Apply the diagnostic patch and approved exact Groovy BOM POM patch separately; never treat a delta alone as full source. Synthetic standalone test stubs must never enter production source. Use the original authorized cloud signing environment; no signing configuration/key is transferred here.

Windows test outcome remains 7,100 unique cases: 6,869 passed, 229 failed, 2 skipped. This is not full acceptance. Existing host file-identity/directory-sync/rename limitations are not phone test results. The 5 new diagnostic JUnit tests pass. After the exact POM approval, Debug/Release lint pass with zero errors (94/104 warnings, 10 hints each); LINT_RESULT.json is supplemental and is not inside the earlier sealed archive.

The immutable frozen source retains its approved public service-contact configuration and synthetic test vectors. No runtime credentials, device/user data, raw host-path test logs, SDK/cache, .git or other project material is added. No cloud build, signing, device installation or upload destination visibility change is performed by this recovery payload. code13/latest-source status remains unknown.
