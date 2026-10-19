# ESV slice07 complete source handoff, part07 transfer

This directory delivers the exact original 20261018T0647Z complete handoff archive in four ordered binary parts, each no larger than 8 MiB. Splitting is only for the connector's supported payload size. No source bytes were changed. It restores independently of part01–part06.

- Full archive: `esv-slice07-handoff-20261018T0647Z.tar.gz`
- Bytes: 29,125,872
- SHA-256: `fca4b4359844f51735aceb3925241a89d10d7a8a4f4a46cafe43ce06f80a0859`
- Exact complete source: 2,872 files, manifest SHA-256 `c5f525f5c8ed6dd946814902fece15664ed8720fa5925a73a5a02cef518ab805`
- Actual test receipt: 6,066 passed in six related modules; debug/release and androidTest compilation passed. This is not an all-project test count. Device/runtime acceptance remains unexecuted; no APK was built for this handoff.

Download every file from the same immutable commit, retaining the exact filenames. Run `python3 verify_and_join.py` in this directory. The script checks each part's size and SHA-256, joins in the manifest order, verifies the original complete archive size/SHA-256, and refuses to overwrite an existing archive. It does not extract or execute the source.

Extract the resulting archive only into a NEW empty directory. Read its `README_HANDOFF.md` and `source/AGENTS.md`, then run its `verify_source.py` before building. The archive's README describes toolchain/cache prerequisites and the separate version/signing gate. The source remains versionCode 9 and is not an installable upgrade candidate.

This is a reconstructed source snapshot including slice07, not a claim that missing historical commits were restored. The archive is a transport/backup artifact. This commit does not upgrade the repository's main/dev code tree and does not establish public-provider or device acceptance. It contains no signing key, SDK/JDK/dependency cache, Git metadata, user-content database or configured provider credential. Existing project technical documents retain historical development-path metadata, including a Windows account-directory name already present in this same private repository. The unchanged source archive is not claimed to be fully redacted.
