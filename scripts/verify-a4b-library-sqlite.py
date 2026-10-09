#!/usr/bin/env python3
# M5-A4b current helper version assertions only; inherited historical SQLite cases unchanged.
"""Host SQLite contracts against production-extracted v3 DDL and read/recovery selectors.

Models SQLiteOpenHelper's atomic version/publication transactions with synthetic records/files.
This does NOT execute Android SQLiteOpenHelper, CursorWindow, or the Kotlin codec. Real codec/
legacy proof validation and production file policy are covered by app JVM tests separately.
No app-owned or real media is accessed; every database and file is in TemporaryDirectory.
"""
from pathlib import Path
import hashlib
import re
import sqlite3
import tempfile
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/kotlin/com/easysavevideo/app'
SCHEMA = (BASE / 'library/storage/LibrarySchema.kt').read_text()
REPO = (BASE / 'library/storage/AndroidLibraryRepository.kt').read_text()
STORAGE = (BASE / 'save/storage/AndroidSaveStorage.kt').read_text()
EVIDENCE = (BASE / 'library/storage/LibraryPublicationEvidence.kt').read_text()


def statements(text):
    return [long or short for long, short in re.findall(r'db\.execSQL\((?:"""(.*?)"""|"([^"\n]+)")\)', text, re.S)]


CREATE = statements(SCHEMA.split('fun backfill', 1)[0])
assert len(CREATE) == 10, len(CREATE)
EXPORT_CREATE = statements((BASE / 'library/export/ExportSchema.kt').read_text())
assert len(EXPORT_CREATE) == 5
assert 'null, 7,' in STORAGE
assert 'oldVersion !in 1..6 || newVersion != 7' in STORAGE
assert 'if (oldVersion == 1)' in STORAGE
assert 'private const val VERSION = 6' in (BASE / 'save/storage/SaveRecordCodec.kt').read_text()
BACKFILL = re.search(r'db.rawQuery\("""(SELECT m.rowid,.*?)"""', SCHEMA, re.S).group(1)
OWNED = re.search(r'db.rawQuery\("""(SELECT o.task_id,.*?)"""', SCHEMA, re.S).group(1)
DELETE_BATCH = re.search(r'DELETE_BATCH_SQL = "([^"]+)"', REPO).group(1)
assert "m.visible=1 AND t.state='SUCCEEDED'" in BACKFILL
assert 'LibraryPublicationEvidence.decode(c.getBlob(0), legacy = true)' in SCHEMA
assert 'record.task.state == TaskState.SUCCEEDED' in EVIDENCE
assert 'if (!legacy) require(evidence.isValidFor' in EVIDENCE
assert 'mediaFiles.readable(receipt.mediaId, receipt.sizeBytes)' in SCHEMA
assert 'dbCall(block: () -> T): T = synchronized(fileLock)' in STORAGE
INIT = STORAGE.split('override fun findByIdempotencyKey', 1)[0]
assert 'releaseStorageOwner(initializingHelper)' in INIT
assert 'if (failedStorageOwner != null) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)' in INIT
assert 'releaseStorageOwner(helper)' in STORAGE
assert 'if (failedStorageOwner?.second === ownerLease) throw SaveFailure(SaveError.RESOURCE_RELEASE_UNCONFIRMED)' in STORAGE
assert 'finally { ownerLease.close() }' not in STORAGE
RELEASE = (BASE / 'library/storage/StorageOwnerRelease.kt').read_text()
assert RELEASE.index('database?.close()') < RELEASE.index('directoryOwner.close()')
assert 'if (queryRecord("commit_id=?", arrayOf(commitId))?.task?.state?.isTerminal == true) return@fileCall null' in STORAGE

# Independently frozen pre-M5 schema. Never reconstruct it by editing the new schema.
LEGACY = [
    '''CREATE TABLE save_tasks(task_id TEXT PRIMARY KEY NOT NULL,idempotency_key TEXT UNIQUE NOT NULL,
       revision INTEGER NOT NULL,state TEXT NOT NULL,commit_id TEXT UNIQUE NOT NULL,record BLOB NOT NULL)''',
    '''CREATE TABLE save_owned(commit_id TEXT PRIMARY KEY NOT NULL REFERENCES save_tasks(commit_id),
       task_id TEXT UNIQUE NOT NULL REFERENCES save_tasks(task_id),artifact_id TEXT UNIQUE NOT NULL,
       media_id TEXT UNIQUE NOT NULL,state TEXT NOT NULL,evidence BLOB)''',
    '''CREATE TABLE save_media(media_id TEXT PRIMARY KEY NOT NULL,commit_id TEXT UNIQUE NOT NULL REFERENCES save_owned(commit_id),
       artifact_id TEXT UNIQUE NOT NULL,size_bytes INTEGER NOT NULL CHECK(size_bytes>0),sha256 TEXT NOT NULL,
       visible INTEGER NOT NULL DEFAULT 0 CHECK(visible IN (0,1)))''',
]


def create_legacy(db):
    for sql in LEGACY:
        db.execute(sql)
    db.execute('PRAGMA user_version=1')
    db.commit()


def insert(db, table, values):
    names = list(values)
    db.execute(f'INSERT INTO {table}({",".join(names)}) VALUES({",".join("?" for _ in names)})', [values[n] for n in names])


def seed(db, media, number=1, state='SUCCEEDED', visible=1, proof='legacy-stream'):
    item = str(uuid.UUID(int=number))
    row = dict(task=f'task{number}', commit=f'commit{number}', artifact=f'artifact{number}', item=item,
               revision=7, key=f'key{number}', size=32, sha=hashlib.sha256(bytes([number % 255]) * 32).hexdigest(),
               record=f'synthetic-{proof}-record-{number}'.encode(), evidence=f'synthetic-{proof}-evidence-{number}'.encode())
    db.execute('INSERT INTO save_tasks VALUES(?,?,?,?,?,?)', (row['task'], row['key'], 7, state, row['commit'], row['record']))
    db.execute('INSERT INTO save_owned VALUES(?,?,?,?,?,?)', (row['commit'], row['task'], row['artifact'], item, 'INDEXED', row['evidence']))
    db.execute('INSERT INTO save_media VALUES(?,?,?,?,?,?)', (item, row['commit'], row['artifact'], 32, row['sha'], visible))
    (media / f'{item}.mp4').write_bytes(bytes([number % 255]) * 32)
    db.commit()
    return row


def publish(db, row, imported=True):
    representation = str(uuid.uuid5(uuid.NAMESPACE_OID, 'synthetic:' + row['item']))
    insert(db, 'library_item', dict(item_id=row['item'], origin_commit_id=row['commit'], revision=1,
           lifecycle='READY', privacy_class='ORDINARY', folder_id=None, representation_id=representation,
           generation=1, display_name='Video ' + row['item'], name_key='video ' + row['item'],
           created_at=123456, date_kind='IMPORTED' if imported else 'SAVED', duration_millis=1000, width=16, height=16))
    insert(db, 'media_representation', dict(representation_id=representation, item_id=row['item'], generation=1,
           kind='ORDINARY_FILE_V1', opaque_locator=row['item'], state='READY', size_bytes=row['size'], sha256=row['sha']))
    db.execute('UPDATE library_state SET revision=revision+1 WHERE singleton=1')


def migrate(db, media, rows, fail_step=None):
    # Opaque bytes here are a codec contract double, not independently decoded proof.
    expected = {r['commit']: r for r in rows}
    count = 0
    def checkpoint():
        nonlocal count
        count += 1
        if count == fail_step:
            raise RuntimeError('SYNTHETIC_POWER_LOSS')
    try:
        db.execute('BEGIN IMMEDIATE')
        for sql in CREATE:
            db.execute(sql); checkpoint()
        after = 0
        while True:
            batch = list(db.execute(BACKFILL, (after,)))
            if not batch:
                break
            for rowid, commit, task, length in batch:
                assert commit and task and 8 <= length <= 32768
                record = expected[commit]
                actual_record = db.execute('SELECT record FROM save_tasks WHERE task_id=? AND length(record) BETWEEN 8 AND 32768', (task,)).fetchone()
                assert actual_record == (record['record'],)
                owned = db.execute(OWNED, (commit,)).fetchone()
                assert owned == (task, record['artifact'], record['item'], 'INDEXED', len(record['evidence']),
                                 record['artifact'], record['item'], record['size'], record['sha'], 1)
                assert db.execute('SELECT evidence FROM save_owned WHERE commit_id=? AND length(evidence) BETWEEN 1 AND 4096', (commit,)).fetchone() == (record['evidence'],)
                path = media / (record['item'] + '.mp4')
                assert not path.is_symlink() and path.is_file() and path.stat().st_size == record['size']
                publish(db, record); checkpoint()
                after = rowid
        for sql in EXPORT_CREATE:
            db.execute(sql); checkpoint()
        db.execute('PRAGMA user_version=3'); checkpoint()
        db.commit()
    except BaseException:
        db.rollback()
        raise


def snapshot(db):
    tables = [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
    return (db.execute('PRAGMA user_version').fetchone()[0],
            tuple(db.execute("SELECT name,sql FROM sqlite_master ORDER BY name")),
            {t: tuple(db.execute('SELECT * FROM ' + t + ' ORDER BY rowid')) for t in tables})


class LibrarySqlContracts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='esv-library-sql-')
        self.root = Path(self.temp.name)
        self.media = self.root / 'media'; self.media.mkdir()
        self.path = self.root / 'synthetic.db'
        self.db = sqlite3.connect(self.path)
        self.db.execute('PRAGMA foreign_keys=ON')
        create_legacy(self.db)

    def tearDown(self):
        self.db.close(); self.temp.cleanup()

    def test_verified_visible_legacy_only_and_no_provenance_rewrite(self):
        rows = [seed(self.db, self.media, 1, proof='legacy-stream'), seed(self.db, self.media, 2, proof='legacy-record')]
        for n, state, visible in [(3, 'RUNNING', 1), (4, 'RUNNING', 0), (5, 'SUCCEEDED', 0), (6, 'FAILED', 1)]:
            rows.append(seed(self.db, self.media, n, state=state, visible=visible))
        legacy = {t: tuple(self.db.execute('SELECT * FROM ' + t)) for t in ['save_tasks', 'save_owned', 'save_media']}
        migrate(self.db, self.media, rows)
        self.assertEqual([(rows[0]['item'], 'IMPORTED', 123456), (rows[1]['item'], 'IMPORTED', 123456)],
                         list(self.db.execute('SELECT item_id,date_kind,created_at FROM library_item ORDER BY item_id')))
        for table, before in legacy.items():
            self.assertEqual(before, tuple(self.db.execute('SELECT * FROM ' + table)))
        self.assertEqual([], list(self.db.execute('PRAGMA foreign_key_check')))
        self.assertNotIn('capture', ' '.join(r[1] for r in self.db.execute('PRAGMA table_info(library_item)')))

    def test_rollback_each_schema_row_and_version_window_preserves_v1(self):
        rows = [seed(self.db, self.media, 1), seed(self.db, self.media, 2)]
        before = snapshot(self.db)
        for fail in range(1, len(CREATE) + len(EXPORT_CREATE) + len(rows) + 2):
            with self.assertRaises(RuntimeError): migrate(self.db, self.media, rows, fail_step=fail)
            self.assertEqual(before, snapshot(self.db))
        migrate(self.db, self.media, rows)
        self.assertEqual(2, self.db.execute('SELECT COUNT(*) FROM library_item').fetchone()[0])

    def test_mismatched_owned_evidence_rolls_back_intact(self):
        row = seed(self.db, self.media)
        self.db.execute("UPDATE save_owned SET evidence=x'0102030405060708'"); self.db.commit()
        before = snapshot(self.db)
        with self.assertRaises(AssertionError): migrate(self.db, self.media, [row])
        self.assertEqual(before, snapshot(self.db))

    def test_oversized_record_rejected_before_blob_fetch(self):
        row = seed(self.db, self.media)
        self.db.execute('UPDATE save_tasks SET record=?', (bytes(2 * 1024 * 1024),)); self.db.commit()
        before = snapshot(self.db)
        with self.assertRaises(AssertionError): migrate(self.db, self.media, [row])
        self.assertEqual(before, snapshot(self.db))

    def test_oversized_evidence_rejected_before_blob_fetch(self):
        row = seed(self.db, self.media)
        self.db.execute('UPDATE save_owned SET evidence=?', (bytes(2 * 1024 * 1024),)); self.db.commit()
        before = snapshot(self.db)
        with self.assertRaises(AssertionError): migrate(self.db, self.media, [row])
        self.assertEqual(before, snapshot(self.db))

    def test_missing_wrong_size_and_symlink_media_preserve_database(self):
        row = seed(self.db, self.media); path = self.media / (row['item'] + '.mp4')
        before = snapshot(self.db)
        path.unlink()
        with self.assertRaises(AssertionError): migrate(self.db, self.media, [row])
        self.assertEqual(before, snapshot(self.db))
        path.write_bytes(b'wrong')
        with self.assertRaises(AssertionError): migrate(self.db, self.media, [row])
        self.assertEqual(before, snapshot(self.db))
        path.unlink(); outside = self.root / 'outside'; outside.write_bytes(bytes(32)); path.symlink_to(outside)
        with self.assertRaises(AssertionError): migrate(self.db, self.media, [row])
        self.assertEqual(before, snapshot(self.db)); self.assertEqual(bytes(32), outside.read_bytes())

    def test_success_publication_and_task_cas_roll_back_together(self):
        row = seed(self.db, self.media, state='RUNNING', visible=0)
        migrate(self.db, self.media, [row])
        before = snapshot(self.db)
        for fail in ('after-visible', 'after-library', 'stale-task', 'after-task'):
            try:
                self.db.execute('BEGIN IMMEDIATE')
                self.db.execute('UPDATE save_media SET visible=1 WHERE commit_id=? AND visible=0', (row['commit'],))
                if fail == 'after-visible': raise RuntimeError()
                publish(self.db, row, imported=False)
                if fail == 'after-library': raise RuntimeError()
                expected = 6 if fail == 'stale-task' else 7
                changed = self.db.execute("UPDATE save_tasks SET state='SUCCEEDED',revision=8 WHERE task_id=? AND revision=?", (row['task'], expected)).rowcount
                if changed != 1 or fail == 'after-task': raise RuntimeError()
                self.db.commit()
            except RuntimeError: self.db.rollback()
            self.assertEqual(before, snapshot(self.db))
        with self.db:
            self.db.execute('UPDATE save_media SET visible=1')
            publish(self.db, row, imported=False)
            self.db.execute("UPDATE save_tasks SET state='SUCCEEDED',revision=8")
        self.assertEqual(('SAVED',), self.db.execute('SELECT date_kind FROM library_item').fetchone())

    def test_unique_publication_and_foreign_keys_do_not_make_duplicate_media(self):
        row = seed(self.db, self.media); migrate(self.db, self.media, [row]); before = snapshot(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db: publish(self.db, row)
        self.assertEqual(before, snapshot(self.db))
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db: self.db.execute("UPDATE library_item SET folder_id='missing'")
        self.assertEqual(before, snapshot(self.db))

    def test_cas_and_logical_move_only_change_metadata(self):
        row = seed(self.db, self.media); migrate(self.db, self.media, [row])
        media_before = (self.media / (row['item'] + '.mp4')).read_bytes()
        with self.db:
            insert(self.db, 'library_folder', dict(folder_id='folder', parent_id=None, revision=1, display_name='Trips', name_key='trips'))
            self.assertEqual(0, self.db.execute("UPDATE library_item SET display_name='New',name_key='new',revision=2 WHERE item_id=? AND revision=0", (row['item'],)).rowcount)
            self.assertEqual(1, self.db.execute("UPDATE library_item SET folder_id='folder',revision=2 WHERE item_id=? AND revision=1", (row['item'],)).rowcount)
        self.assertEqual(media_before, (self.media / (row['item'] + '.mp4')).read_bytes())
        self.assertEqual((row['item'], 1), self.db.execute('SELECT opaque_locator,generation FROM media_representation').fetchone())

    def test_tombstone_hides_new_reads_and_retains_durable_delete(self):
        row = seed(self.db, self.media); migrate(self.db, self.media, [row])
        with self.db:
            self.db.execute("UPDATE library_item SET lifecycle='DELETE_PENDING',revision=2")
            self.db.execute("UPDATE media_representation SET state='DELETE_PENDING'")
            insert(self.db, 'library_operation', dict(operation_id='delete-one', kind='DELETE_ITEM', target_id=row['item'], expected_revision=1,
                   argument_fingerprint='a' * 64, result_revision=2, phase='DELETE_PENDING'))
        self.assertEqual(0, self.db.execute("SELECT COUNT(*) FROM library_item i JOIN media_representation r ON r.item_id=i.item_id WHERE i.lifecycle='READY' AND r.state='READY'").fetchone()[0])
        self.assertTrue((self.media / (row['item'] + '.mp4')).exists())
        self.db.close(); self.db = sqlite3.connect(self.path); self.db.execute('PRAGMA foreign_keys=ON')
        self.assertEqual([(row['item'], 'delete-one')], list(self.db.execute(DELETE_BATCH, ('', 100))))

    def test_persistent_rotation_does_not_starve_101st_intent(self):
        migrate(self.db, self.media, [])
        with self.db:
            for n in range(205):
                insert(self.db, 'library_operation', dict(operation_id=f'op{n:04d}', kind='DELETE_ITEM', target_id=f'item{n}', expected_revision=1,
                       argument_fingerprint='a' * 64, result_revision=2, phase='DELETE_PENDING'))
        seen = []
        for _ in range(3):
            cursor = self.db.execute('SELECT delete_cursor FROM library_state').fetchone()[0]
            batch = list(self.db.execute(DELETE_BATCH, (cursor, 100)))
            if not batch: batch = list(self.db.execute(DELETE_BATCH, ('', 100)))
            seen += [r[1] for r in batch]
            with self.db: self.db.execute('UPDATE library_state SET delete_cursor=?', (batch[-1][1],))
            self.db.close(); self.db = sqlite3.connect(self.path)
            # Every synthetic unlink stays failed; only the recovery cursor advances across restart.
        self.assertEqual(205, len(set(seen)))
        self.assertIn('op0100', seen)

    def test_bound_and_state_constraints_reject_malformed_catalog_rows(self):
        row = seed(self.db, self.media); migrate(self.db, self.media, [row]); before = snapshot(self.db)
        for column, value in [('display_name', 'x' * 256), ('name_key', 'x' * 1025), ('lifecycle', 'PRIVATE'), ('generation', 0), ('created_at', None), ('width', None)]:
            with self.assertRaises(sqlite3.IntegrityError):
                with self.db: self.db.execute(f'UPDATE library_item SET {column}=?', (value,))
            self.assertEqual(before, snapshot(self.db))
        for column, value in [('sha256', 'g' * 64), ('opaque_locator', '../outside'), ('kind', 'UNREVIEWED'), ('size_bytes', None), ('sha256', None)]:
            with self.assertRaises(sqlite3.IntegrityError):
                with self.db: self.db.execute(f'UPDATE media_representation SET {column}=?', (value,))
            self.assertEqual(before, snapshot(self.db))

    def test_foreign_key_text_bounds_hold_even_if_foreign_key_enforcement_was_bypassed(self):
        row = seed(self.db, self.media); migrate(self.db, self.media, [row])
        self.db.execute('PRAGMA foreign_keys=OFF')
        for table, column in [('library_item', 'folder_id'), ('library_item', 'representation_id'), ('media_representation', 'item_id')]:
            with self.assertRaises(sqlite3.IntegrityError):
                with self.db: self.db.execute(f'UPDATE {table} SET {column}=?', ('x' * 129,))
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                insert(self.db, 'library_folder', dict(folder_id='folder', parent_id='x' * 129, revision=1, display_name='Trips', name_key='trips'))

    def test_active_capacity_is_independent_of_succeeded_history(self):
        for n in range(1, 105): seed(self.db, self.media, n)
        count_sql = re.search(r'db.rawQuery\("(SELECT COUNT\(\*\) FROM save_tasks WHERE state NOT IN [^"]+)"', STORAGE).group(1)
        self.assertEqual(0, self.db.execute(count_sql).fetchone()[0])
        seed(self.db, self.media, 105, state='RUNNING', visible=0)
        self.assertEqual(1, self.db.execute(count_sql).fetchone()[0])


if __name__ == '__main__':
    unittest.main(verbosity=2)
