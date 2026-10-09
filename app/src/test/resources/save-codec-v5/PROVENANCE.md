# Genuine frozen v5 functional fixtures

Generated on 2026-10-06 from the compiled production `SaveRecordCodec.encode`
of M6-A commit `9b25083134221bb4a86d71e76601cc65b7d10937`, before the first M6-B
compile. Input objects come from the frozen `SaveRecordCodecTest` helpers
`record`, `provenStream`, `capturedRecord(false/true)` and `resumedRecord`.
The latter includes all 32 actual resume boundary identities. These are small
synthetic functional-state records, not media files or user data. No new-code
writer, version relabeling or removed fields were used to manufacture v5.
f12a7fdce621340f24df0ff9886fc90a73f62180c78eae75909fa3594c8cfd71  app/src/test/resources/save-codec-v5/capture-false.bin
e763e19838cf8261e7c78afd2af2a4b0669a6ea0723771ca22ceee773519af05  app/src/test/resources/save-codec-v5/capture-true.bin
87ac5278d6b6a039f89ee31f4e3226abb930f78f1b98e0ce9b33e8161ad38f54  app/src/test/resources/save-codec-v5/provenStream.bin
dd2aabafa488d267501f3e3cb10f60dabf2cdc05bfe990b3d2f9ab70bb9eb39d  app/src/test/resources/save-codec-v5/record.bin
ea37d0beb506a37822ff8817cf8bf361d81abaedcf6a34548132f2923747c8aa  app/src/test/resources/save-codec-v5/resumedRecord.bin
