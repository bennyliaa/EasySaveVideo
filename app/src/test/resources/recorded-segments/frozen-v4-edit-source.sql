-- Frozen byte-preserving SQL literal from M6-A 9b25083134221bb4a86d71e76601cc65b7d10937
CREATE TABLE edit_source(operation_id TEXT NOT NULL REFERENCES edit_job(operation_id),sequence INTEGER NOT NULL CHECK(sequence BETWEEN 0 AND 7),
            item_id TEXT NOT NULL REFERENCES library_item(item_id),revision INTEGER NOT NULL CHECK(revision>0),generation INTEGER NOT NULL CHECK(generation>0),
            start_us INTEGER NOT NULL CHECK(start_us>=0),end_us INTEGER NOT NULL CHECK(end_us>start_us),
            execution_start_us INTEGER,execution_end_us INTEGER,frame_count INTEGER,
            size_bytes INTEGER NOT NULL CHECK(size_bytes>0),sha256 TEXT NOT NULL CHECK(length(sha256)=64 AND sha256 NOT GLOB '*[^a-f0-9]*'),
            CHECK((execution_start_us IS NULL)=(execution_end_us IS NULL)),CHECK((execution_start_us IS NULL)=(frame_count IS NULL)),
            CHECK(execution_start_us IS NULL OR (execution_start_us>=start_us AND execution_end_us<=end_us AND execution_end_us>execution_start_us AND frame_count>0 AND frame_count<=108000)),
            PRIMARY KEY(operation_id,sequence));
