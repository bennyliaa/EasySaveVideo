# Test-only r1 review addendum

Reviewed commit: c8e908f2088491fcd8bd57096f376984bbd5d387.

The revised `test_review_pages_retain_all_unverified_warnings_after_reopen` now commits 70 synthetic SAF warning records, copies the database to a physical temporary SQLite file, closes both the disk writer and original in-memory connection, then opens a new connection to that file. The 32-row page/count assertions execute only through the reopened connection and observe all 70 distinct records. Foreign-key enforcement is reenabled for the reopened connection. Unittest closes that connection before removing its temporary directory.

Independent targeted execution: PASS, 1 test. Command: `python3 scripts/verify-export-sqlite.py Contracts.test_review_pages_retain_all_unverified_warnings_after_reopen`. Log: `sqlite-reopen-r1-test.log`.

Updated only the supporting test/guard manifests for `scripts/verify-export-sqlite.py`; the other 17 supporting hashes are unchanged. The 32-file production SHA-256 manifest is unchanged and was rechecked successfully after this test-only commit. Its SHA-256 remains ea5cef5a80591f58958b6f9b5a132268e98af069412dfa40607c8ea39e4a408f.

No new finding. This validates a synthetic host SQLite close/reopen fixture; it does not execute Android SQLiteOpenHelper, URI grants, providers, native APIs, device process death or actual user media. No Gradle was run by this reviewer. The parent's full r1 aggregate/tag remains separate evidence.
