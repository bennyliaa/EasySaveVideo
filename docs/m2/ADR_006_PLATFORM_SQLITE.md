# ADR-006: Platform SQLite equivalent structured store

Status: selected for the M2 development candidate, 2026-10-06.

Architecture update_1 names Room for Data storage. The original Technical Requirements TR-01 explicitly allow “Room/等价结构化数据库” and “DataStore/等价设置存储”, and prohibit putting complex objects into SharedPreferences. The project currently admits exactly 53 official runtime artifacts plus the two authorized SkinKit artifacts. Room is not silently added to that graph.

Use Android platform SQLiteOpenHelper behind the platform-neutral DiscoveryStore transaction interface. This provides actual local structured persistence with SQLite transactions and no new library/license dependency. A later switch to Room is an option requiring its own admission and migration work, not a promise or a current dependency. The original reference documents remain unchanged.

Schema v1 uses checkpoint header, normalized result rows, stable ordered page rows, provider progress, independent Seen facts and selected viewport. Upserts skip equal row content. Provider accepted results/aliases, discovery facts and cursor commit or roll back together. No full-pool BLOB is read through Android CursorWindow. Unknown versions/corruption preserve the database and isolate Discovery; Browser remains usable. No fallback deletes user state.

The bounded codec validates private headers and supports pure JVM roundtrip/corruption tests. Its 64MiB absolute decoding ceiling accommodates maximum-field 500-item test snapshots; it is not a database row size. Runtime SQLite checkpoint payload is header-only. Result rows are bounded by normalized individual fields, aliases<=128 and provenance<=32. Full-pool codec tests do not establish Android CursorWindow or battery performance.

Host SQLite tests exercise the adapter's exact DDL, transaction rollback, Seen/progress independence, unchanged UPSERT no-write behavior, and 500 wide rows totalling about 15MiB with an estimated 30KiB maximum row. These are host SQLite semantics, not Android runtime tests. Android SQLiteOpenHelper recovery, process death and actual CursorWindow behavior remain device acceptance items.
