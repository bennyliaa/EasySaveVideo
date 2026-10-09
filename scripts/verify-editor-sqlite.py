#!/usr/bin/env python3
"""Synthetic host SQLite/storage contracts for the ordinary-library M6-A editor.

Production Kotlin SQL literals are extracted and executed in host Python SQLite. The
v3 fixture is frozen independently below; it is never synthesized from the v4 DDL.
Temporary synthetic bytes are NOT media validation evidence. Python publication,
file-I/O, and failure-injection models are explicitly contract doubles. This suite
is NOT an Android, Kotlin storage/codec, Media3, playable-media, or device integration
pass. No real media is read or modified. Run: python3 scripts/verify-editor-sqlite.py
"""
from pathlib import Path
import hashlib
import json
import re
import sqlite3
import tempfile
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/kotlin/com/easysavevideo/app'

# Frozen 2026-10-06 pre-M6 v3 DDL. Existing v1/v2 migration suites remain separate.
V3_DDL = ['CREATE TABLE save_tasks(task_id TEXT PRIMARY KEY NOT NULL,idempotency_key TEXT UNIQUE NOT NULL,\n'
 '                revision INTEGER NOT NULL,state TEXT NOT NULL,commit_id TEXT UNIQUE NOT NULL,record BLOB '
 'NOT NULL)',
 'CREATE TABLE save_owned(commit_id TEXT PRIMARY KEY NOT NULL REFERENCES save_tasks(commit_id),\n'
 '                task_id TEXT UNIQUE NOT NULL REFERENCES save_tasks(task_id),artifact_id TEXT UNIQUE NOT '
 'NULL,\n'
 '                media_id TEXT UNIQUE NOT NULL,state TEXT NOT NULL,evidence BLOB)',
 'CREATE TABLE save_media(media_id TEXT PRIMARY KEY NOT NULL,commit_id TEXT UNIQUE NOT NULL REFERENCES '
 'save_owned(commit_id),\n'
 '                artifact_id TEXT UNIQUE NOT NULL,size_bytes INTEGER NOT NULL CHECK(size_bytes>0),sha256 '
 'TEXT NOT NULL,\n'
 '                visible INTEGER NOT NULL DEFAULT 0 CHECK(visible IN (0,1)))',
 'CREATE TABLE library_state(singleton INTEGER PRIMARY KEY CHECK(singleton=1),revision INTEGER NOT NULL '
 "CHECK(revision>=0),delete_cursor TEXT NOT NULL DEFAULT '' CHECK(length(delete_cursor)<=128))",
 'INSERT INTO library_state(singleton,revision) VALUES(1,0)',
 'CREATE TABLE library_folder(folder_id TEXT PRIMARY KEY NOT NULL CHECK(length(folder_id) BETWEEN 1 AND '
 '128),parent_id TEXT CHECK(parent_id IS NULL OR length(parent_id) BETWEEN 1 AND 128) REFERENCES '
 'library_folder(folder_id),\n'
 '            revision INTEGER NOT NULL CHECK(revision>0),display_name TEXT NOT NULL '
 'CHECK(length(CAST(display_name AS BLOB)) BETWEEN 1 AND 255),name_key TEXT NOT NULL '
 'CHECK(length(CAST(name_key AS BLOB)) BETWEEN 1 AND 1024))',
 "CREATE UNIQUE INDEX library_folder_sibling ON library_folder(IFNULL(parent_id,''),name_key)",
 'CREATE TABLE library_item(item_id TEXT PRIMARY KEY NOT NULL CHECK(length(item_id) BETWEEN 1 AND '
 '128),origin_commit_id TEXT UNIQUE NOT NULL CHECK(length(origin_commit_id) BETWEEN 1 AND 128) REFERENCES '
 'save_tasks(commit_id),\n'
 '            revision INTEGER NOT NULL CHECK(revision>0),lifecycle TEXT NOT NULL CHECK(lifecycle IN '
 "('READY','DELETE_PENDING','DELETED')),\n"
 "            privacy_class TEXT NOT NULL CHECK(privacy_class='ORDINARY'),folder_id TEXT CHECK(folder_id IS "
 'NULL OR length(folder_id) BETWEEN 1 AND 128) REFERENCES library_folder(folder_id),\n'
 '            representation_id TEXT UNIQUE NOT NULL CHECK(length(representation_id) BETWEEN 1 AND 128) '
 'REFERENCES media_representation(representation_id) DEFERRABLE INITIALLY DEFERRED,\n'
 '            generation INTEGER NOT NULL CHECK(generation>0),display_name TEXT CHECK(display_name IS NULL '
 'OR length(CAST(display_name AS BLOB)) BETWEEN 1 AND 255),\n'
 '            name_key TEXT CHECK(name_key IS NULL OR length(CAST(name_key AS BLOB)) BETWEEN 1 AND '
 '1024),created_at INTEGER CHECK(created_at IS NULL OR created_at>=0),\n'
 "            date_kind TEXT CHECK(date_kind IN ('SAVED','IMPORTED')),duration_millis INTEGER "
 'CHECK(duration_millis IS NULL OR duration_millis>0),width INTEGER CHECK(width IS NULL OR width>0),height '
 'INTEGER CHECK(height IS NULL OR height>0),\n'
 '            CHECK((width IS NULL)=(height IS NULL)),\n'
 "            CHECK(lifecycle='DELETED' OR (display_name IS NOT NULL AND name_key IS NOT NULL AND created_at "
 'IS NOT NULL AND created_at>=0 AND date_kind IS NOT NULL)))',
 "CREATE UNIQUE INDEX library_item_sibling ON library_item(IFNULL(folder_id,''),name_key) WHERE "
 "lifecycle='READY'",
 'CREATE INDEX library_item_page ON library_item(folder_id,lifecycle,created_at,item_id)',
 'CREATE TABLE media_representation(representation_id TEXT PRIMARY KEY NOT NULL '
 'CHECK(length(representation_id) BETWEEN 1 AND 128),item_id TEXT NOT NULL CHECK(length(item_id) BETWEEN 1 '
 'AND 128) REFERENCES library_item(item_id),\n'
 '            generation INTEGER NOT NULL CHECK(generation>0),kind TEXT NOT NULL '
 "CHECK(kind='ORDINARY_FILE_V1'),\n"
 '            opaque_locator TEXT UNIQUE CHECK(opaque_locator IS NULL OR length(opaque_locator)=36),state '
 "TEXT NOT NULL CHECK(state IN ('READY','DELETE_PENDING','DELETED')),\n"
 '            size_bytes INTEGER,sha256 TEXT CHECK(sha256 IS NULL OR (length(sha256)=64 AND sha256 NOT GLOB '
 "'*[^a-f0-9]*')),UNIQUE(item_id,generation),\n"
 "            CHECK(state='DELETED' OR (opaque_locator IS NOT NULL AND size_bytes IS NOT NULL AND sha256 IS "
 'NOT NULL AND size_bytes>0 AND length(sha256)=64)))',
 'CREATE TABLE library_operation(operation_id TEXT PRIMARY KEY NOT NULL CHECK(length(operation_id) BETWEEN 1 '
 'AND 128),kind TEXT NOT NULL CHECK(length(kind) BETWEEN 1 AND 32),target_id TEXT NOT NULL '
 'CHECK(length(target_id) BETWEEN 1 AND 128),\n'
 '            expected_revision INTEGER NOT NULL,argument_fingerprint TEXT NOT NULL '
 'CHECK(length(argument_fingerprint)=64),\n'
 '            result_revision INTEGER NOT NULL CHECK(result_revision>0),phase TEXT NOT NULL CHECK(phase IN '
 "('DELETE_PENDING','COMPLETE')))",
 'CREATE INDEX library_delete_recovery ON library_operation(phase,operation_id)',
 'CREATE TABLE library_export(operation_id TEXT PRIMARY KEY NOT NULL CHECK(length(operation_id) BETWEEN 1 '
 'AND 128),\n'
 '            fingerprint TEXT NOT NULL CHECK(length(fingerprint)=64 AND fingerprint NOT GLOB '
 "'*[^a-f0-9]*'),\n"
 '            phase TEXT NOT NULL CHECK(phase IN '
 "('PREPARED','CREATE_INTENT','COPYING','VERIFYING','PUBLISH_INTENT','SUCCEEDED','COPIED_UNVERIFIED','CLEANUP_PENDING','FAILED','CANCELLED','RECONCILIATION_REQUIRED')),\n"
 '            cancelled INTEGER NOT NULL CHECK(cancelled IN (0,1)),record BLOB NOT NULL CHECK(length(record) '
 'BETWEEN 8 AND 4096))',
 'CREATE INDEX library_export_recovery ON library_export(phase,operation_id)',
 'CREATE TABLE library_export_target(operation_id TEXT PRIMARY KEY NOT NULL REFERENCES '
 'library_export(operation_id),\n'
 "            kind TEXT NOT NULL CHECK(kind IN ('MEDIA_STORE','SAF')),grant_id TEXT NOT NULL "
 'CHECK(length(grant_id) BETWEEN 1 AND 128),\n'
 '            nonce TEXT UNIQUE NOT NULL CHECK(length(nonce)=36),uri TEXT CHECK(uri IS NULL OR '
 'length(CAST(uri AS BLOB)) BETWEEN 1 AND 8192),\n'
 '            grant_flags INTEGER NOT NULL DEFAULT 0 CHECK(grant_flags BETWEEN 0 AND 3))',
 'CREATE TABLE library_export_state(singleton INTEGER PRIMARY KEY CHECK(singleton=1),recovery_cursor TEXT '
 "NOT NULL DEFAULT '' CHECK(length(recovery_cursor)<=128))",
 'INSERT INTO library_export_state(singleton) VALUES(1)']


def sql_literals(text):
    """Accept only literal execSQL statements, with no Kotlin interpolation."""
    result = [long or short for long, short in re.findall(
        r'db\.execSQL\((?:"""(.*?)"""|"([^"\n]+)")\)', text, re.S)]
    if any('$' in sql for sql in result):
        raise AssertionError('SQL extraction must not silently approximate Kotlin interpolation')
    return result


def kotlin_function(text, name):
    # Restrict extraction to the named function, before the next same-level function.
    match = re.search(r'\bfun ' + re.escape(name) + r'\(', text)
    if match is None:
        raise AssertionError('Missing production function ' + name)
    tail = text[match.start():]
    next_function = re.search(r'\n    (?:private |internal )?fun ', tail[1:])
    return tail if next_function is None else tail[:next_function.start() + 1]


def constant(text, name):
    match = re.search(re.escape(name) + r'\s*=\s*(?:"""(.*?)"""|"([^"\n]+)")', text, re.S)
    if match is None:
        raise AssertionError('Missing production SQL constant ' + name)
    value = match.group(1) or match.group(2)
    if '$' in value:
        raise AssertionError('Interpolated SQL constant must be expanded explicitly')
    return value


SCHEMA = (BASE / 'editor/storage/EditSchema.kt').read_text()
SAVE = (BASE / 'save/storage/AndroidSaveStorage.kt').read_text()
EDITOR = (BASE / 'editor/storage/AndroidEditStorage.kt').read_text()
ARTIFACTS = (BASE / 'editor/storage/EditArtifactFiles.kt').read_text()
LIBRARY = (BASE / 'library/storage/AndroidLibraryRepository.kt').read_text()
RECOVERY_SQL = constant(EDITOR, 'RECOVERY_SQL')
SOURCE_SQL = constant(EDITOR, 'SOURCE_SQL')
EXECUTION_SQL = constant(EDITOR, 'EXECUTION_SQL')
FINAL_OWNERSHIP_SQL = constant(EDITOR, 'FINAL_OWNERSHIP_SQL')
READ_SQL = re.search(r'database\(\)\.rawQuery\("""(SELECT request,.*?)"""', EDITOR, re.S).group(1)
ITEM_SELECT = constant(LIBRARY, 'ITEM_SELECT')
REPRESENTATION_SQL = re.search(r'database\(\)\.rawQuery\("""(SELECT r\.representation_id,.*?)"""', LIBRARY, re.S).group(1)
PUBLISHED_SQL = re.search(r'"(SELECT 1 FROM library_item WHERE origin_edit_id=\? LIMIT 1)"', EDITOR).group(1)
PUBLISH_CAS = re.search(r'''"(operation_id=\? AND phase='VERIFYING' AND cancelled=0)"''', EDITOR).group(1)
SUCCESS_CAS = re.search(r'''"(operation_id=\? AND phase='PUBLISH_INTENT' AND cancelled=0)"''', EDITOR).group(1)
START_SOURCE_CAS = re.search(r'''"(operation_id=\? AND sequence=\? AND execution_start_us IS NULL)"''', EDITOR).group(1)
START_JOB_CAS = re.search(r'''"(operation_id=\? AND phase='PREPARED' AND cancelled=0)"''', EDITOR).group(1)
CANCEL_WHERE = re.search(r'''"(operation_id=\? AND phase NOT IN \('PUBLISH_INTENT'.*?)"''', EDITOR).group(1)
CREATE = (sql_literals((BASE / 'recording/segments/RecordedSegmentSchema.kt').read_text()) +
          sql_literals(kotlin_function(SCHEMA, 'create')) + sql_literals(kotlin_function(SCHEMA, 'createSources')))
MIGRATE = sql_literals(kotlin_function(SCHEMA, 'migrateLibrary'))
assert CREATE and MIGRATE, 'Production editor schema extraction is empty'


class InjectedFailure(RuntimeError):
    pass


class ContractRejected(RuntimeError):
    pass


def insert(db, table, values):
    names = list(values)
    return db.execute(f'INSERT INTO {table}({",".join(names)}) VALUES({",".join("?" for _ in names)})',
                      tuple(values[name] for name in names))


def uid(number):
    return str(uuid.UUID(int=number))


def snapshot(db):
    tables = [row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
    return (db.execute('PRAGMA user_version').fetchone()[0],
            tuple(db.execute('SELECT name,sql FROM sqlite_master ORDER BY name')),
            {table: tuple(db.execute('SELECT * FROM ' + table + ' ORDER BY rowid')) for table in tables})


def file_snapshot(root):
    # All files here were created by this suite beneath TemporaryDirectory.
    return {str(path.relative_to(root)): ('symlink', str(path.readlink())) if path.is_symlink()
            else ('regular', path.read_bytes()) for path in root.rglob('*') if path.is_symlink() or path.is_file()}


def create_v3(db):
    db.execute('PRAGMA foreign_keys=ON')
    db.execute('BEGIN IMMEDIATE')
    for sql in V3_DDL:
        db.execute(sql)
    db.execute('PRAGMA user_version=3')
    db.commit()


def foreign_keys_clean(db):
    if db.execute('PRAGMA foreign_key_check').fetchone() is not None:
        raise ContractRejected('FOREIGN_KEY_CHECK_FAILED')


def migrate(db, fail_at=None, legacy=True, check_constraints=True):
    """Model helper configure/upgrade/open order, not Android SQLiteOpenHelper itself."""
    checkpoints = []

    def checkpoint(name):
        checkpoints.append(name)
        if name == fail_at:
            raise InjectedFailure(name)

    if db.in_transaction:
        raise AssertionError('FK configure must occur before the helper transaction')
    db.execute('PRAGMA foreign_keys=' + ('OFF' if legacy else 'ON'))
    try:
        db.execute('BEGIN IMMEDIATE')
        checkpoint('transaction-start')
        for index, sql in enumerate(CREATE + MIGRATE):
            db.execute(sql)
            checkpoint('sql-' + str(index))
        if check_constraints:
            foreign_keys_clean(db)
        checkpoint('constraints-checked')
        db.execute('PRAGMA user_version=5')
        checkpoint('version-set')
        db.commit()
    except BaseException:
        db.rollback()
        raise
    finally:
        # The host test connection is reusable after a thrown open; production closes
        # its failed helper/owner instead of exposing an enforcement-disabled handle.
        db.execute('PRAGMA foreign_keys=ON')
    foreign_keys_clean(db)
    if db.execute('PRAGMA foreign_keys').fetchone() != (1,):
        raise ContractRejected('FOREIGN_KEYS_NOT_REENABLED')
    return checkpoints


def seed_source(db, files, number, lifecycle='READY', revision=7, folder=None):
    item_id, representation = uid(number), uid(10_000 + number)
    payload = (f'synthetic-source-{number}:'.encode() * 8)
    sha = hashlib.sha256(payload).hexdigest()
    row = dict(item_id=item_id, revision=revision, generation=1,
               representation_id=representation, locator=item_id,
               commit=f'commit-{number}', task=f'task-{number}', artifact=f'artifact-{number}',
               size=len(payload), sha=sha, bytes=payload)
    insert(db, 'save_tasks', dict(task_id=row['task'], idempotency_key=f'key-{number}', revision=9,
           state='SUCCEEDED', commit_id=row['commit'], record=f'synthetic-save-record-{number}'.encode()))
    insert(db, 'save_owned', dict(commit_id=row['commit'], task_id=row['task'], artifact_id=row['artifact'],
           media_id=item_id, state='INDEXED', evidence=f'synthetic-save-evidence-{number}'.encode()))
    insert(db, 'save_media', dict(media_id=item_id, commit_id=row['commit'], artifact_id=row['artifact'],
           size_bytes=len(payload), sha256=sha, visible=1))
    deleted = lifecycle == 'DELETED'
    insert(db, 'library_item', dict(item_id=item_id, origin_commit_id=row['commit'], revision=revision,
           lifecycle=lifecycle, privacy_class='ORDINARY', folder_id=None if deleted else folder,
           representation_id=representation, generation=1,
           display_name=None if deleted else f'Source {number}', name_key=None if deleted else f'source {number}',
           created_at=None if deleted else 1000 + number, date_kind=None if deleted else 'SAVED',
           duration_millis=None if deleted else 30_000, width=None if deleted else 640,
           height=None if deleted else 480))
    insert(db, 'media_representation', dict(representation_id=representation, item_id=item_id, generation=1,
           kind='ORDINARY_FILE_V1', opaque_locator=None if deleted else item_id, state=lifecycle,
           size_bytes=None if deleted else len(payload), sha256=None if deleted else sha))
    if not deleted:
        (files / (item_id + '.mp4')).write_bytes(payload)
    return row


class EditorSqlContracts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='esv-editor-synthetic-')
        self.root = Path(self.temp.name)
        self.media = self.root / 'media'
        self.stage = self.root / 'edit-staging'
        self.media.mkdir()
        self.stage.mkdir()
        self.path = self.root / 'contract.sqlite'
        self.db = sqlite3.connect(self.path)
        create_v3(self.db)
        with self.db:
            insert(self.db, 'library_folder', dict(folder_id='folder-1', parent_id=None, revision=2,
                   display_name='Synthetic folder', name_key='synthetic folder'))
            insert(self.db, 'library_folder', dict(folder_id='folder-2', parent_id='folder-1', revision=3,
                   display_name='Nested', name_key='nested'))
            self.sources = [seed_source(self.db, self.media, 1, folder='folder-2'),
                            seed_source(self.db, self.media, 2),
                            seed_source(self.db, self.media, 3, lifecycle='DELETE_PENDING'),
                            seed_source(self.db, self.media, 4, lifecycle='DELETED')]
            insert(self.db, 'library_operation', dict(operation_id='delete-source-3', kind='DELETE_ITEM',
                   target_id=self.sources[2]['item_id'], expected_revision=6, argument_fingerprint='a' * 64,
                   result_revision=7, phase='DELETE_PENDING'))
            insert(self.db, 'library_export', dict(operation_id='old-export', fingerprint='b' * 64,
                   phase='CLEANUP_PENDING', cancelled=0, record=b'synthetic-export-record'))
            insert(self.db, 'library_export_target', dict(operation_id='old-export', kind='SAF',
                   grant_id='synthetic-grant', nonce=uid(90), uri='content://synthetic/export', grant_flags=3))
            self.db.execute("UPDATE library_state SET revision=71,delete_cursor='delete-source-2'")
            self.db.execute("UPDATE library_export_state SET recovery_cursor='old-export'")
        foreign_keys_clean(self.db)
        self.source_files = file_snapshot(self.media)

    def tearDown(self):
        self.db.close()
        self.temp.cleanup()

    def reopen(self):
        self.db.close()
        self.db = sqlite3.connect(self.path)
        self.db.execute('PRAGMA foreign_keys=ON')
        foreign_keys_clean(self.db)

    def assert_sources_unchanged(self):
        self.assertEqual(self.source_files, file_snapshot(self.media))

    def test_v3_v5_preserves_every_original_column_reference_and_source_byte(self):
        before = snapshot(self.db)
        migrate(self.db)
        after = snapshot(self.db)
        self.assertEqual(5, after[0])
        for table, rows in before[2].items():
            if table == 'library_item':
                columns = [row[1] for row in self.db.execute('PRAGMA table_info(library_item)')
                           if row[1] != 'origin_edit_id']
                self.assertEqual(rows, tuple(self.db.execute(
                    'SELECT ' + ','.join(columns) + ' FROM library_item ORDER BY rowid')))
                self.assertEqual([(None,)] * 4, self.db.execute('SELECT origin_edit_id FROM library_item').fetchall())
            else:
                self.assertEqual(rows, after[2][table], table)
        foreign_keys_clean(self.db)
        self.assert_sources_unchanged()
        self.reopen()
        self.assertEqual(after, snapshot(self.db))
        # FK definitions still target the public table, never a renamed migration table.
        self.assertEqual({'library_item'}, {row[2] for row in self.db.execute('PRAGMA foreign_key_list(media_representation)')})
        self.assertEqual([], list(self.db.execute("SELECT name FROM sqlite_master WHERE name LIKE '%_v4'")))

    def test_each_migration_statement_and_version_boundary_rolls_back_exact_v3(self):
        original = snapshot(self.db)
        boundaries = ['transaction-start'] + ['sql-' + str(i) for i in range(len(CREATE + MIGRATE))]
        boundaries += ['constraints-checked', 'version-set']
        for boundary in boundaries:
            with self.subTest(boundary=boundary):
                with self.assertRaises(InjectedFailure):
                    migrate(self.db, fail_at=boundary)
                self.assertEqual(original, snapshot(self.db))
                self.assertEqual((1,), self.db.execute('PRAGMA foreign_keys').fetchone())
                self.assert_sources_unchanged()
                self.reopen()
                self.assertEqual(original, snapshot(self.db))
        migrate(self.db)
        self.assertEqual((5,), self.db.execute('PRAGMA user_version').fetchone())

    def test_empty_fresh_database_can_build_v4_with_foreign_keys_enabled(self):
        db = sqlite3.connect(':memory:')
        self.addCleanup(db.close)
        create_v3(db)
        migrate(db, legacy=False)
        self.assertEqual((1,), db.execute('PRAGMA foreign_keys').fetchone())
        self.assertEqual((5,), db.execute('PRAGMA user_version').fetchone())
        foreign_keys_clean(db)

    def test_preexisting_orphan_blocks_upgrade_before_version_commit(self):
        self.db.execute('PRAGMA foreign_keys=OFF')
        self.db.execute("UPDATE media_representation SET item_id='missing' WHERE item_id=?", (self.sources[0]['item_id'],))
        self.db.commit()
        before = snapshot(self.db)
        with self.assertRaises(ContractRejected):
            migrate(self.db)
        self.assertEqual(before, snapshot(self.db))
        self.assertEqual((3,), self.db.execute('PRAGMA user_version').fetchone())
        self.assert_sources_unchanged()

    def test_production_helper_places_fk_controls_outside_upgrade_transaction(self):
        configure = SAVE.split('override fun onConfigure', 1)[1].split('override fun onCreate', 1)[0]
        opened = SAVE.split('override fun onOpen', 1)[1].split('override fun onUpgrade', 1)[0]
        upgrade = SAVE.split('override fun onUpgrade', 1)[1].split('override fun onDowngrade', 1)[0]
        self.assertIn('null, 5,', SAVE)
        self.assertIn('setForeignKeyConstraintsEnabled(db.version !in 1..3)', configure)
        self.assertNotIn('setForeignKeyConstraintsEnabled', upgrade)
        self.assertIn('oldVersion !in 1..4 || newVersion != 5', upgrade)
        self.assertIn('if (oldVersion == 1)', upgrade)
        self.assertIn('LibrarySchema.backfill', upgrade)
        self.assertIn('if (oldVersion < 3) ExportSchema.create(db)', upgrade)
        self.assertLess(upgrade.index('EditSchema.create(db)'), upgrade.index('EditSchema.migrateLibrary(db)'))
        self.assertGreater(upgrade.index('EditSchema.requireForeignKeysValid(db)'),
                           upgrade.index('EditSchema.migrateLibrary(db)'))
        self.assertLess(opened.index('setForeignKeyConstraintsEnabled(true)'),
                        opened.index('EditSchema.requireForeignKeysValid(db)'))
        self.assertIn('it.getInt(0) == 1', opened)
        self.assertIn('PRAGMA foreign_key_check', SCHEMA)
        self.assertNotIn('PRAGMA defer_foreign_keys=OFF', SCHEMA)

    def test_plain_deferred_parent_rebuild_is_not_a_safe_migration_substitute(self):
        # Counterexample documents why production disables enforcement BEFORE the
        # helper transaction and explicitly checks the complete graph before commit.
        db = sqlite3.connect(':memory:')
        self.addCleanup(db.close)
        db.execute('PRAGMA foreign_keys=ON')
        db.executescript('''CREATE TABLE parent(id TEXT PRIMARY KEY);
            CREATE TABLE child(parent_id TEXT REFERENCES parent(id));
            INSERT INTO parent VALUES('one'); INSERT INTO child VALUES('one');''')
        db.execute('BEGIN IMMEDIATE')
        db.execute('PRAGMA defer_foreign_keys=ON')
        db.execute('CREATE TABLE replacement(id TEXT PRIMARY KEY,extra TEXT)')
        db.execute('INSERT INTO replacement SELECT id,NULL FROM parent')
        db.execute('DROP TABLE parent')
        db.execute('ALTER TABLE replacement RENAME TO parent')
        self.assertEqual([], db.execute('PRAGMA foreign_key_check').fetchall())
        with self.assertRaises(sqlite3.IntegrityError):
            db.commit()
        db.rollback()

    def test_prepare_job_and_all_ordered_sources_are_one_transaction(self):
        migrate(self.db)
        before = snapshot(self.db)
        sources = [self.sources[1], self.sources[0], self.sources[1]]
        for fail_after in range(4):
            with self.subTest(fail_after=fail_after):
                job = synthetic_job(99, sources=sources)
                with self.assertRaises(InjectedFailure):
                    with self.db:
                        insert(self.db, 'edit_job', job)
                        if fail_after == 0:
                            raise InjectedFailure('after-job')
                        for sequence, source in enumerate(sources):
                            insert(self.db, 'edit_source', synthetic_source(job['operation_id'], sequence, source))
                            if sequence + 1 == fail_after:
                                raise InjectedFailure('after-source')
                self.assertEqual(before, snapshot(self.db))
                self.assertEqual([], list(self.stage.iterdir()))
                self.assert_sources_unchanged()

    def test_typed_job_bounds_states_and_evidence_gate(self):
        migrate(self.db)
        for index, phase in enumerate(('PREPARED', 'EXECUTING', 'VERIFYING', 'PUBLISH_INTENT', 'SUCCEEDED',
                                     'FAILED', 'CANCELLED', 'INTERRUPTED', 'CLEANUP_PENDING')):
            row = synthetic_job(100 + index, operation=phase, phase=phase)
            with self.db:
                insert(self.db, 'edit_job', row)
        for column, value in [('operation_id', ''), ('operation_id', 'x' * 129),
                              ('fingerprint', 'g' * 64), ('artifact_id', 'x' * 35),
                              ('media_id', 'x' * 37), ('phase', 'FAKE_SUCCESS'),
                              ('cancelled', 2), ('request', b'x' * 7),
                              ('request', b'x' * 16385), ('expectation', b'x' * 1025),
                              ('evidence', b'x' * 4097), ('created_at', -1), ('error', 'x' * 65)]:
            with self.subTest(column=column, value=str(value)[:20]):
                row = synthetic_job(200, operation='invalid')
                row[column] = value
                with self.assertRaises(sqlite3.IntegrityError):
                    with self.db:
                        insert(self.db, 'edit_job', row)
        for phase in ('PUBLISH_INTENT', 'SUCCEEDED'):
            for expectation, evidence, cancelled in [(None, b'proof-model', 0),
                                                    (b'plan-model', None, 0),
                                                    (b'plan-model', b'proof-model', 1)]:
                with self.subTest(phase=phase, cancelled=cancelled):
                    row = synthetic_job(201, operation='invalid-proof', phase=phase)
                    row.update(expectation=expectation, evidence=evidence, cancelled=cancelled)
                    with self.assertRaises(sqlite3.IntegrityError):
                        with self.db:
                            insert(self.db, 'edit_job', row)

    def test_exactly_one_typed_origin_and_durable_foreign_keys(self):
        migrate(self.db)
        item_id = self.sources[0]['item_id']
        with self.db:
            insert(self.db, 'edit_job', synthetic_job(202))
        for commit, edit in [(None, None), (self.sources[0]['commit'], 'edit-202'),
                             (None, 'missing-edit'), ('missing-save', None)]:
            with self.subTest(commit=commit, edit=edit):
                before = snapshot(self.db)
                with self.assertRaises(sqlite3.IntegrityError):
                    with self.db:
                        self.db.execute('UPDATE library_item SET origin_commit_id=?,origin_edit_id=? WHERE item_id=?',
                                        (commit, edit, item_id))
                self.assertEqual(before, snapshot(self.db))
        # A typed origin retains provenance to its own job, not a fake Save FK.
        with self.db:
            self.db.execute('UPDATE library_item SET origin_commit_id=NULL,origin_edit_id=? WHERE item_id=?',
                            ('edit-202', item_id))
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute("DELETE FROM edit_job WHERE operation_id='edit-202'")
        self.assert_sources_unchanged()

    def test_ordered_source_snapshots_survive_current_metadata_revisions(self):
        migrate(self.db)
        sources = [self.sources[1], self.sources[0], self.sources[1]]
        job = synthetic_job(203, sources=sources)
        with self.db:
            insert(self.db, 'edit_job', job)
            for sequence, source in enumerate(sources):
                insert(self.db, 'edit_source', synthetic_source(job['operation_id'], sequence, source))
        before = self.db.execute('SELECT * FROM edit_source ORDER BY sequence').fetchall()
        with self.db:
            self.db.execute('UPDATE library_item SET revision=revision+1 WHERE item_id=?', (self.sources[1]['item_id'],))
        self.reopen()
        self.assertEqual(before, self.db.execute('SELECT * FROM edit_source ORDER BY sequence').fetchall())
        ordered = self.db.execute('SELECT item_id,revision,generation FROM edit_source WHERE operation_id=? ORDER BY sequence',
                                  (job['operation_id'],)).fetchall()
        self.assertEqual([(source['item_id'], source['revision'], source['generation']) for source in sources], ordered)
        self.assertEqual(job['request'], self.db.execute('SELECT request FROM edit_job').fetchone()[0])
        self.assertEqual(3, len(json.loads(job['request'])['sources']))
        self.assert_sources_unchanged()

    def test_source_ranges_ordinals_bounds_and_foreign_keys(self):
        migrate(self.db)
        with self.db:
            insert(self.db, 'edit_job', synthetic_job(204))
        for column, value in [('operation_id', 'missing'), ('item_id', 'missing'),
                              ('sequence', -1), ('sequence', 8), ('revision', 0),
                              ('generation', 0), ('start_us', -1), ('end_us', 0),
                              ('size_bytes', 0), ('sha256', 'g' * 64)]:
            row = synthetic_source('edit-204', 0, self.sources[0])
            row[column] = value
            with self.subTest(column=column, value=value):
                with self.assertRaises(sqlite3.IntegrityError):
                    with self.db:
                        insert(self.db, 'edit_source', row)
        with self.db:
            for sequence in range(8):
                insert(self.db, 'edit_source', synthetic_source('edit-204', sequence, self.sources[sequence % 2]))
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                insert(self.db, 'edit_source', synthetic_source('edit-204', 0, self.sources[0]))
        self.assertEqual((8,), self.db.execute('SELECT COUNT(*) FROM edit_source').fetchone())

    def test_source_and_output_namespaces_do_not_create_save_rows(self):
        migrate(self.db)
        before = {table: tuple(self.db.execute('SELECT * FROM ' + table))
                  for table in ('save_tasks', 'save_owned', 'save_media')}
        with self.db:
            job = synthetic_job(205, sources=self.sources[:2])
            insert(self.db, 'edit_job', job)
            for sequence, source in enumerate(self.sources[:2]):
                insert(self.db, 'edit_source', synthetic_source(job['operation_id'], sequence, source))
        for table, rows in before.items():
            self.assertEqual(rows, tuple(self.db.execute('SELECT * FROM ' + table)))
        self.assertNotIn(job['media_id'], {source['item_id'] for source in self.sources})
        self.assert_sources_unchanged()


    def assert_original_sources_present(self):
        actual = file_snapshot(self.media)
        for name, value in self.source_files.items():
            self.assertIn(name, actual)
            self.assertEqual(value, actual[name])

    def test_current_read_reference_rejects_stale_revision_generation_and_tombstone(self):
        migrate(self.db)
        require_current_sources(self.db, self.sources[:2])
        item = self.sources[0]['item_id']
        for field, value in [('revision', 8), ('generation', 2), ('lifecycle', 'DELETE_PENDING')]:
            with self.subTest(field=field):
                self.db.execute('BEGIN IMMEDIATE')
                self.db.execute('UPDATE library_item SET ' + field + '=? WHERE item_id=?', (value, item))
                with self.assertRaises(ContractRejected):
                    require_current_sources(self.db, self.sources[:2])
                self.db.rollback()
        self.assert_sources_unchanged()

    def test_publication_model_atomic_at_every_catalog_boundary(self):
        migrate(self.db)
        catalog_before = {table: tuple(self.db.execute('SELECT * FROM ' + table + ' ORDER BY rowid'))
                          for table in ('library_item', 'media_representation', 'library_state',
                                        'save_tasks', 'save_owned', 'save_media')}
        boundaries = ('before-intent', 'intent-committed', 'before-promotion', 'promoted', 'publication-begin',
                      'item-inserted', 'representation-inserted', 'job-succeeded', 'catalog-revision-bumped', 'before-commit')
        for number, boundary in enumerate(boundaries, 300):
            with self.subTest(boundary=boundary):
                job = model_prepare(self.db, self.stage, self.media, self.sources[:2], number)
                proof = model_verified_candidate(self.db, self.stage, job)
                with self.assertRaises(InjectedFailure):
                    model_publish(self.db, self.stage, self.media, job['operation_id'], proof,
                                  self.sources[:2], fail_at=boundary)
                for table, rows in catalog_before.items():
                    self.assertEqual(rows, tuple(self.db.execute('SELECT * FROM ' + table + ' ORDER BY rowid')), table)
                self.assertEqual([], self.db.execute(PUBLISHED_SQL, (job['operation_id'],)).fetchall())
                self.assert_original_sources_present()
                self.reopen()
                model_recover(self.db, self.stage, self.media)
                self.assertEqual('INTERRUPTED', read_job(self.db, job['operation_id'])['phase'])
                self.assertFalse((self.stage / (job['artifact_id'] + '.part')).exists())
                self.assertFalse((self.media / (job['media_id'] + '.mp4')).exists())
                self.assert_sources_unchanged()

    def test_committed_output_survives_restart_and_never_forges_save_provenance(self):
        migrate(self.db)
        save_before = {table: tuple(self.db.execute('SELECT * FROM ' + table))
                       for table in ('save_tasks', 'save_owned', 'save_media')}
        job = model_prepare(self.db, self.stage, self.media, [self.sources[1], self.sources[0]], 320)
        proof = model_verified_candidate(self.db, self.stage, job)
        with self.assertRaises(InjectedFailure):
            model_publish(self.db, self.stage, self.media, job['operation_id'], proof,
                          [self.sources[1], self.sources[0]], fail_at='after-commit')
        self.reopen()
        self.assertEqual([], model_recover(self.db, self.stage, self.media))
        self.assertEqual('SUCCEEDED', read_job(self.db, job['operation_id'])['phase'])
        self.assertEqual((None, job['operation_id']), self.db.execute(
            'SELECT origin_commit_id,origin_edit_id FROM library_item WHERE item_id=?', (job['media_id'],)).fetchone())
        self.assertEqual((72,), self.db.execute('SELECT revision FROM library_state').fetchone())
        self.assertTrue((self.media / (job['media_id'] + '.mp4')).is_file())
        self.assertFalse(model_cleanup(self.db, self.stage, self.media, job['operation_id']))
        for table, rows in save_before.items():
            self.assertEqual(rows, tuple(self.db.execute('SELECT * FROM ' + table)))
        with self.assertRaises(ContractRejected):
            model_publish(self.db, self.stage, self.media, job['operation_id'], proof,
                          [self.sources[1], self.sources[0]])
        self.assertEqual((1,), self.db.execute('SELECT COUNT(*) FROM library_item WHERE origin_edit_id=?',
                                             (job['operation_id'],)).fetchone())
        self.assert_original_sources_present()

    def test_cancelled_and_unverified_jobs_cannot_publish(self):
        migrate(self.db)
        for number, mode in enumerate(('cancelled', 'absent', 'engine-only', 'wrong-artifact'), 330):
            with self.subTest(mode=mode):
                job = model_prepare(self.db, self.stage, self.media, self.sources[:1], number)
                proof = model_verified_candidate(self.db, self.stage, job)
                if mode == 'cancelled':
                    with self.db:
                        self.assertEqual(1, self.db.execute('UPDATE edit_job SET cancelled=1 WHERE ' + CANCEL_WHERE,
                                                          (job['operation_id'],)).rowcount)
                elif mode == 'absent':
                    proof = None
                elif mode == 'engine-only':
                    proof['independent_verified'] = False
                else:
                    proof['artifact'] = uid(999)
                with self.assertRaises(ContractRejected):
                    model_publish(self.db, self.stage, self.media, job['operation_id'], proof, self.sources[:1])
                self.assertEqual([], self.db.execute(PUBLISHED_SQL, (job['operation_id'],)).fetchall())
                model_recover(self.db, self.stage, self.media)
                self.assert_sources_unchanged()

    def test_context_cancellation_fences_intent_promotion_and_catalog_publication(self):
        migrate(self.db)
        for number, fence in enumerate(('before-intent', 'before-promotion', 'publication-begin'), 340):
            job = model_prepare(self.db, self.stage, self.media, self.sources[:1], number)
            proof = model_verified_candidate(self.db, self.stage, job)
            with self.subTest(fence=fence):
                with self.assertRaises(ContractRejected):
                    model_publish(self.db, self.stage, self.media, job['operation_id'], proof,
                                  self.sources[:1], cancelled_at=fence)
                self.assertEqual([], self.db.execute(PUBLISHED_SQL, (job['operation_id'],)).fetchall())
                self.reopen()
                model_recover(self.db, self.stage, self.media)
                self.assert_sources_unchanged()

    def test_source_changed_after_execution_blocks_new_output_publication(self):
        migrate(self.db)
        job = model_prepare(self.db, self.stage, self.media, self.sources[:1], 350)
        proof = model_verified_candidate(self.db, self.stage, job)
        with self.db:
            self.db.execute('UPDATE library_item SET revision=revision+1 WHERE item_id=?', (self.sources[0]['item_id'],))
        with self.assertRaises(ContractRejected):
            model_publish(self.db, self.stage, self.media, job['operation_id'], proof, self.sources[:1])
        self.assertEqual([], self.db.execute(PUBLISHED_SQL, (job['operation_id'],)).fetchall())
        self.assertEqual((7,), self.db.execute('SELECT revision FROM edit_source').fetchone())
        model_recover(self.db, self.stage, self.media)
        self.assert_sources_unchanged()

    def test_restart_never_autopublishes_any_nonterminal_phase(self):
        migrate(self.db)
        phases = ('PREPARED', 'EXECUTING', 'VERIFYING', 'PUBLISH_INTENT', 'CLEANUP_PENDING')
        for number, phase in enumerate(phases, 360):
            job = model_prepare(self.db, self.stage, self.media, self.sources[:1], number)
            proof = model_verified_candidate(self.db, self.stage, job)
            if phase == 'PUBLISH_INTENT':
                with self.assertRaises(InjectedFailure):
                    model_publish(self.db, self.stage, self.media, job['operation_id'], proof,
                                  self.sources[:1], fail_at='promoted')
            else:
                with self.db:
                    self.db.execute('UPDATE edit_job SET phase=? WHERE operation_id=?', (phase, job['operation_id']))
        self.reopen()
        self.assertEqual(5, len(model_recover(self.db, self.stage, self.media)))
        self.assertEqual((0,), self.db.execute('SELECT COUNT(*) FROM library_item WHERE origin_edit_id IS NOT NULL').fetchone())
        self.assertEqual([(phase,) for phase in ['INTERRUPTED'] * 5], self.db.execute('SELECT phase FROM edit_job').fetchall())
        self.assertEqual([], list(self.stage.iterdir()))
        self.assert_sources_unchanged()

    def test_cleanup_preserves_catalog_owned_and_unknown_collision_files(self):
        migrate(self.db)
        unknown = self.media / (uid(30_370) + '.mp4')
        unknown.write_bytes(b'unrelated-preexisting-file')
        with self.assertRaises(ContractRejected):
            model_prepare(self.db, self.stage, self.media, self.sources[:1], 370)
        self.assertEqual((0,), self.db.execute('SELECT COUNT(*) FROM edit_job').fetchone())
        self.assertEqual(b'unrelated-preexisting-file', unknown.read_bytes())
        job = model_prepare(self.db, self.stage, self.media, self.sources[:1], 371)
        proof = model_verified_candidate(self.db, self.stage, job, self.sources[0]['bytes'])
        # Deliberate corrupt ownership row: even matching bytes/proof cannot confer
        # permission to delete an existing source/catalog-owned file.
        with self.db:
            self.db.execute('UPDATE edit_job SET media_id=?,phase=\'PUBLISH_INTENT\',evidence=? WHERE operation_id=?',
                            (self.sources[0]['item_id'], json.dumps(proof).encode(), job['operation_id']))
        self.assertFalse(model_cleanup(self.db, self.stage, self.media, job['operation_id']))
        self.assertEqual('CLEANUP_PENDING', read_job(self.db, job['operation_id'])['phase'])
        self.assert_original_sources_present()
        self.assertEqual(b'unrelated-preexisting-file', unknown.read_bytes())

    def test_unknown_or_tampered_final_candidate_is_never_deleted(self):
        migrate(self.db)
        for number, mode in enumerate(('no-proof', 'wrong-bytes'), 380):
            job = model_prepare(self.db, self.stage, self.media, self.sources[:1], number)
            proof = model_verified_candidate(self.db, self.stage, job)
            path = self.media / (job['media_id'] + '.mp4')
            path.write_bytes(b'unknown-or-tampered-file')
            if mode == 'wrong-bytes':
                (self.stage / (job['artifact_id'] + '.part')).unlink()
                with self.db:
                    self.db.execute("UPDATE edit_job SET phase='PUBLISH_INTENT',evidence=? WHERE operation_id=?",
                                    (json.dumps(proof).encode(), job['operation_id']))
            self.assertFalse(model_cleanup(self.db, self.stage, self.media, job['operation_id']))
            self.assertEqual(b'unknown-or-tampered-file', path.read_bytes())
            self.assertEqual('CLEANUP_PENDING', read_job(self.db, job['operation_id'])['phase'])
        self.assert_original_sources_present()

    def test_owned_final_cleanup_retry_keeps_evidence_and_never_republishes(self):
        migrate(self.db)
        job = model_prepare(self.db, self.stage, self.media, self.sources[:1], 390)
        proof = model_verified_candidate(self.db, self.stage, job)
        with self.assertRaises(InjectedFailure):
            model_publish(self.db, self.stage, self.media, job['operation_id'], proof,
                          self.sources[:1], fail_at='promoted')
        evidence = read_job(self.db, job['operation_id'])['evidence']
        self.assertFalse(model_cleanup(self.db, self.stage, self.media, job['operation_id'], fail_final_unlink=True))
        self.assertEqual('CLEANUP_PENDING', read_job(self.db, job['operation_id'])['phase'])
        self.assertEqual(evidence, read_job(self.db, job['operation_id'])['evidence'])
        self.reopen()
        model_recover(self.db, self.stage, self.media)
        self.assertEqual('INTERRUPTED', read_job(self.db, job['operation_id'])['phase'])
        self.assertEqual([], self.db.execute(PUBLISHED_SQL, (job['operation_id'],)).fetchall())
        self.assert_sources_unchanged()

    def test_symlink_and_unconfirmed_native_owners_block_cleanup_or_publication(self):
        migrate(self.db)
        job = model_prepare(self.db, self.stage, self.media, self.sources[:1], 400)
        proof = model_verified_candidate(self.db, self.stage, job)
        before = file_snapshot(self.stage)
        with self.assertRaises(ContractRejected):
            model_publish(self.db, self.stage, self.media, job['operation_id'], proof,
                          self.sources[:1], owners_released=False)
        with self.assertRaises(ContractRejected):
            model_cleanup(self.db, self.stage, self.media, job['operation_id'], owners_released=False)
        self.assertEqual(before, file_snapshot(self.stage))
        staging = self.stage / (job['artifact_id'] + '.part')
        staging.unlink()
        staging.symlink_to(self.media / (self.sources[0]['item_id'] + '.mp4'))
        self.assertFalse(model_cleanup(self.db, self.stage, self.media, job['operation_id']))
        self.assertTrue(staging.is_symlink())
        self.assert_sources_unchanged()

    def test_production_recovery_selector_excludes_all_terminal_history_and_is_bounded(self):
        migrate(self.db)
        with self.db:
            for number in range(1_200):
                phase = ('SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED')[number % 4]
                insert(self.db, 'edit_job', synthetic_job(2_000 + number, phase=phase))
            for number in range(8):
                insert(self.db, 'edit_job', synthetic_job(5_000 + number, phase='CLEANUP_PENDING'))
        self.reopen()
        selected = self.db.execute(RECOVERY_SQL, (8,)).fetchall()
        self.assertEqual(8, len(selected))
        self.assertEqual({f'edit-{5_000 + number}' for number in range(8)}, {row[0] for row in selected})
        self.assertEqual((1_208,), self.db.execute('SELECT COUNT(*) FROM edit_job').fetchone())
        self.assertEqual(3, len(self.db.execute(RECOVERY_SQL, (3,)).fetchall()))


    def test_production_final_ownership_selector_protects_ready_and_tombstoned_sources(self):
        migrate(self.db)
        for source in self.sources:
            with self.subTest(source=source['item_id']):
                self.assertTrue(candidate_catalog_owned(self.db, source['item_id']))
        self.assertFalse(candidate_catalog_owned(self.db, uid(999_999)))
        self.assert_sources_unchanged()

    def test_captured_source_size_and_hash_must_match_current_read_identity(self):
        migrate(self.db)
        job = model_prepare(self.db, self.stage, self.media, self.sources[:1], 404)
        proof = model_verified_candidate(self.db, self.stage, job)
        for field, value in [('size_bytes', 1), ('sha256', 'a' * 64)]:
            with self.subTest(field=field):
                self.db.execute('BEGIN IMMEDIATE')
                self.db.execute('UPDATE edit_source SET ' + field + '=? WHERE operation_id=?', (value, job['operation_id']))
                with self.assertRaises(ContractRejected):
                    model_publish(self.db, self.stage, self.media, job['operation_id'], proof, self.sources[:1])
                self.db.rollback()
        self.db.execute('BEGIN IMMEDIATE')
        self.db.execute("UPDATE media_representation SET state='DELETE_PENDING' WHERE item_id=?", (self.sources[0]['item_id'],))
        with self.assertRaises(ContractRejected):
            require_current_sources(self.db, self.sources[:1])
        self.db.rollback()
        self.assert_sources_unchanged()

    def test_production_source_selector_and_recipe_model_reject_changed_or_missing_ordinals(self):
        migrate(self.db)
        job = model_prepare(self.db, self.stage, self.media, [self.sources[1], self.sources[0], self.sources[1]], 405)
        self.assertEqual(3, len(model_source_snapshots(self.db, job)))
        for field, value in [('item_id', self.sources[0]['item_id']), ('revision', 8),
                             ('generation', 2), ('start_us', 1), ('end_us', 999_999)]:
            with self.subTest(field=field):
                self.db.execute('BEGIN IMMEDIATE')
                self.db.execute('UPDATE edit_source SET ' + field + '=? WHERE operation_id=? AND sequence=0',
                                (value, job['operation_id']))
                with self.assertRaises(ContractRejected):
                    model_source_snapshots(self.db, job)
                self.db.rollback()
        self.db.execute('BEGIN IMMEDIATE')
        self.db.execute('DELETE FROM edit_source WHERE operation_id=? AND sequence=1', (job['operation_id'],))
        with self.assertRaises(ContractRejected):
            model_source_snapshots(self.db, job)
        self.db.rollback()
        self.db.execute('PRAGMA ignore_check_constraints=ON')
        self.db.execute('BEGIN IMMEDIATE')
        for sequence in range(3, 103):
            insert(self.db, 'edit_source', synthetic_source(job['operation_id'], sequence, self.sources[0]))
        self.assertEqual(34, len(self.db.execute(SOURCE_SQL, (job['operation_id'],)).fetchall()))
        with self.assertRaises(ContractRejected):
            model_source_snapshots(self.db, job)
        self.db.rollback()
        self.db.execute('PRAGMA ignore_check_constraints=OFF')
        self.assert_sources_unchanged()

    def test_production_bounded_record_selector_filters_oversized_blob_before_return(self):
        migrate(self.db)
        with self.db:
            insert(self.db, 'edit_job', synthetic_job(410))
        self.assertEqual(1, len(self.db.execute(READ_SQL, ('edit-410',)).fetchall()))
        self.db.execute('PRAGMA ignore_check_constraints=ON')
        for column, value in [('request', b'x' * 7), ('request', b'x' * 16_385),
                              ('expectation', b'x' * 1025), ('evidence', b'x' * 4097)]:
            self.db.execute('BEGIN IMMEDIATE')
            self.db.execute('UPDATE edit_job SET ' + column + '=?', (value,))
            self.assertEqual([], self.db.execute(READ_SQL, ('edit-410',)).fetchall())
            self.db.rollback()
        self.db.execute('PRAGMA ignore_check_constraints=OFF')

    def test_prepare_collision_and_recovery_never_scan_or_claim_unknown_staging(self):
        migrate(self.db)
        occupied = self.stage / (uid(20_420) + '.part')
        occupied.write_bytes(b'unknown-staging')
        sentinel = self.stage / 'not-an-owned-candidate.part'
        sentinel.write_bytes(b'unknown-sentinel')
        before = file_snapshot(self.stage)
        with self.assertRaises(ContractRejected):
            model_prepare(self.db, self.stage, self.media, self.sources[:1], 420)
        self.assertEqual((0,), self.db.execute('SELECT COUNT(*) FROM edit_job').fetchone())
        job = model_prepare(self.db, self.stage, self.media, self.sources[:1], 421)
        model_verified_candidate(self.db, self.stage, job)
        model_recover(self.db, self.stage, self.media)
        self.assertEqual(before, file_snapshot(self.stage))
        self.assert_sources_unchanged()

    def test_matching_bytes_at_both_candidate_names_still_do_not_prove_final_ownership(self):
        migrate(self.db)
        job = model_prepare(self.db, self.stage, self.media, self.sources[:1], 430)
        proof = model_verified_candidate(self.db, self.stage, job)
        staging = self.stage / (job['artifact_id'] + '.part')
        final = self.media / (job['media_id'] + '.mp4')
        final.write_bytes(staging.read_bytes())
        with self.db:
            self.db.execute("UPDATE edit_job SET phase='PUBLISH_INTENT',evidence=? WHERE operation_id=?",
                            (json.dumps(proof).encode(), job['operation_id']))
        before = (staging.read_bytes(), final.read_bytes())
        self.assertFalse(model_cleanup(self.db, self.stage, self.media, job['operation_id']))
        self.assertEqual(before, (staging.read_bytes(), final.read_bytes()))
        self.assertEqual('CLEANUP_PENDING', read_job(self.db, job['operation_id'])['phase'])
        self.assert_original_sources_present()

    def test_production_storage_anchors_match_synthetic_protocol_scope(self):
        publish = kotlin_function(EDITOR, 'publish')
        prepare = kotlin_function(EDITOR, 'prepare')
        recovery = kotlin_function(EDITOR, 'recover')
        finish = kotlin_function(EDITOR, 'finishUnpublished')
        cleanup = kotlin_function(ARTIFACTS, 'cleanup')
        self.assertIn('evidence.validFor(output, expected)', publish)
        self.assertIn('requireActive(output).canFinalize', publish)
        self.assertGreaterEqual(publish.count('requireCurrentSources(row.request)'), 2)
        self.assertGreaterEqual(publish.count('checkContext(context)'), 3)
        self.assertLess(publish.index('put("phase", "PUBLISH_INTENT")'), publish.index('files.promote'))
        self.assertLess(publish.index('files.promote'), publish.index('db.insertOrThrow("library_item"'))
        self.assertLess(publish.index('db.insertOrThrow("media_representation"'), publish.index('put("phase", "SUCCEEDED")'))
        self.assertIn('item.readRef() != source', EDITOR)
        read = kotlin_function(EDITOR, 'read')
        self.assertIn('sourceSnapshots(request)', read)
        self.assertIn('it.validFor(row.output, requireNotNull(row.expectation))', read)
        self.assertIn('check(result.size == request.segments.size)', EDITOR)
        self.assertIn('input.sizeBytes != sourceRows[index].first || input.sha256 != sourceRows[index].second', EDITOR)
        self.assertIn('synchronized(lock)', EDITOR)
        self.assertNotIn('insertOrThrow("save_', EDITOR)
        self.assertNotIn('SaveRecord(', EDITOR)
        self.assertIn('retainSource(ref)', prepare)
        self.assertIn('Active(leases.toList()).also { it.unconfirmed = true }', prepare)
        self.assertLess(prepare.index('files.assertFinalAbsent(mediaId)'), prepare.index('db.insertOrThrow("edit_job"'))
        self.assertLess(prepare.index('files.assertStagingAbsent(output.artifactId)'), prepare.index('db.insertOrThrow("edit_job"'))
        self.assertIn('check(active.isEmpty())', recovery)
        self.assertIn('check(!isPublished(operationId))', recovery)
        self.assertIn('requireFinalUnowned(row.mediaId)', recovery)
        self.assertIn('"INTERRUPTED"', recovery)
        self.assertNotIn('files.promote', recovery)
        self.assertNotIn('"SUCCEEDED"', recovery)
        self.assertNotIn('db.insertOrThrow', recovery)
        self.assertLess(finish.index('requireFinalUnowned(row.mediaId)'), finish.index('files.cleanup'))
        self.assertIn('row.evidence?.let { it.bytes to it.sha256 }', finish)
        self.assertIn('promotedProof == null || Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS)', cleanup)
        self.assertLess(cleanup.index('requireExact(destination'), cleanup.index('removeOwned(destination)'))
        self.assertLess(cleanup.index('removeOwned(destination)'), cleanup.index('removeOwned(source)'))
        self.assertNotIn('listFiles', ARTIFACTS)
        self.assertNotIn('walkTopDown', ARTIFACTS)
        self.assertIn('Files.isSymbolicLink(it.toPath())', ARTIFACTS)
        self.assertIn('it.canonicalFile.parentFile != parent', ARTIFACTS)


    def test_execution_sql_nullable_tuple_and_requested_bounds_constraints(self):
        migrate(self.db)
        job = model_prepare(self.db, self.stage, self.media, self.sources[:1], 440)
        values = [
            (None, 900_000, None), (0, None, None), (None, None, 1),
            (None, 900_000, 1), (0, None, 1), (0, 900_000, None),
            (-1, 900_000, 1), (0, 1_000_001, 1), (500_000, 500_000, 1),
            (500_001, 500_000, 1), (0, 900_000, 0), (0, 900_000, -1),
            (0, 900_000, 108_001),
        ]
        before = snapshot(self.db)
        for execution in values:
            with self.subTest(execution=execution):
                with self.assertRaises(sqlite3.IntegrityError):
                    with self.db:
                        self.db.execute('UPDATE edit_source SET execution_start_us=?,execution_end_us=?,frame_count=? WHERE operation_id=?',
                                        (*execution, job['operation_id']))
                self.assertEqual(before, snapshot(self.db))
        for execution in [(None, None, None), (0, 1_000_000, 108_000), (33_333, 966_666, 28)]:
            with self.db:
                self.db.execute('UPDATE edit_source SET execution_start_us=?,execution_end_us=?,frame_count=? WHERE operation_id=?',
                                (*execution, job['operation_id']))
            self.assertEqual([execution], self.db.execute(EXECUTION_SQL, (job['operation_id'],)).fetchall())
        self.assert_sources_unchanged()

    def test_execution_start_rolls_back_every_segment_and_expectation_window(self):
        migrate(self.db)
        sources = [self.sources[1], self.sources[0], self.sources[1]]
        job = model_prepare(self.db, self.stage, self.media, sources, 441)
        plan = [(33_333, 966_666, 28), (1_066_666, 1_966_666, 27), (2_100_000, 2_900_000, 24)]
        before = snapshot(self.db)
        for boundary in ('transaction-start', 'source-0', 'source-1', 'source-2', 'expectation-updated'):
            with self.subTest(boundary=boundary):
                with self.assertRaises(InjectedFailure):
                    model_start_execution(self.db, job, plan, fail_at=boundary)
                self.assertEqual(before, snapshot(self.db))
                self.reopen()
                self.assertEqual(before, snapshot(self.db))
                self.assertEqual([(None, None, None)] * 3, self.db.execute(EXECUTION_SQL, (job['operation_id'],)).fetchall())
                self.assertEqual((None, 'PREPARED'), self.db.execute('SELECT expectation,phase FROM edit_job').fetchone())
        self.assert_sources_unchanged()

    def test_snapped_execution_preserves_original_ordered_recipe_and_exact_sums_after_reopen(self):
        migrate(self.db)
        sources = [self.sources[1], self.sources[0], self.sources[1]]
        job = model_prepare(self.db, self.stage, self.media, sources, 442)
        before_recipe = self.db.execute(SOURCE_SQL, (job['operation_id'],)).fetchall()
        plan = [(33_333, 966_666, 28), (1_066_666, 1_966_666, 27), (2_100_000, 2_900_000, 24)]
        expected = model_start_execution(self.db, job, plan)
        self.assertEqual({'duration_us': 2_633_333, 'video_frame_count': 79}, expected)
        self.assertLess(expected['duration_us'], 3_000_000)
        self.reopen()
        self.assertEqual(plan, model_execution_snapshot(self.db, job, expected))
        self.assertEqual(before_recipe, self.db.execute(SOURCE_SQL, (job['operation_id'],)).fetchall())
        self.assertEqual(job['request'], self.db.execute('SELECT request FROM edit_job').fetchone()[0])
        self.assertEqual(expected, json.loads(self.db.execute('SELECT expectation FROM edit_job').fetchone()[0]))
        self.assert_sources_unchanged()

    def test_execution_read_model_rejects_duration_frame_null_and_order_tampering(self):
        migrate(self.db)
        job = model_prepare(self.db, self.stage, self.media, [self.sources[1], self.sources[0]], 443)
        plan = [(33_333, 966_666, 28), (1_066_666, 1_966_666, 27)]
        expected = model_start_execution(self.db, job, plan)
        for assignment, values in [
            ('execution_end_us=?', (966_665,)),
            ('execution_start_us=?', (33_334,)),
            ('frame_count=?', (29,)),
            ('execution_start_us=NULL,execution_end_us=NULL,frame_count=NULL', ()),
        ]:
            with self.subTest(assignment=assignment):
                self.db.execute('BEGIN IMMEDIATE')
                self.db.execute('UPDATE edit_source SET ' + assignment + ' WHERE operation_id=? AND sequence=0',
                                (*values, job['operation_id']))
                with self.assertRaises(ContractRejected):
                    model_execution_snapshot(self.db, job, expected)
                self.db.rollback()
        for key in ('duration_us', 'video_frame_count'):
            wrong = dict(expected); wrong[key] += 1
            with self.assertRaises(ContractRejected):
                model_execution_snapshot(self.db, job, wrong)
        self.db.execute('BEGIN IMMEDIATE')
        self.db.execute('DELETE FROM edit_source WHERE operation_id=? AND sequence=1', (job['operation_id'],))
        with self.assertRaises(ContractRejected):
            model_execution_snapshot(self.db, job, expected)
        self.db.rollback()
        # Bounds must also be checked on read if damaged input bypassed SQL CHECKs.
        self.db.execute('PRAGMA ignore_check_constraints=ON')
        self.db.execute('BEGIN IMMEDIATE')
        self.db.execute('UPDATE edit_source SET execution_start_us=?,execution_end_us=?,frame_count=? WHERE operation_id=? AND sequence=0',
                        (*plan[1], job['operation_id']))
        self.db.execute('UPDATE edit_source SET execution_start_us=?,execution_end_us=?,frame_count=? WHERE operation_id=? AND sequence=1',
                        (*plan[0], job['operation_id']))
        with self.assertRaises(ContractRejected):
            model_execution_snapshot(self.db, job, expected)
        self.db.rollback()
        self.db.execute('BEGIN IMMEDIATE')
        for sequence in range(2, 12):
            insert(self.db, 'edit_source', synthetic_source(job['operation_id'], sequence, self.sources[0]))
        self.assertEqual(12, len(self.db.execute(EXECUTION_SQL, (job['operation_id'],)).fetchall()))
        with self.assertRaises(ContractRejected):
            model_execution_snapshot(self.db, job, expected)
        self.db.rollback()
        self.db.execute('PRAGMA ignore_check_constraints=OFF')
        self.assert_sources_unchanged()

    def test_execution_source_cas_or_cancelled_job_cas_rolls_back_all_provisional_updates(self):
        migrate(self.db)
        job = model_prepare(self.db, self.stage, self.media, self.sources[:2], 444)
        plan = [(33_333, 966_666, 28), (1_066_666, 1_966_666, 27)]
        with self.db:
            self.db.execute('UPDATE edit_source SET execution_start_us=?,execution_end_us=?,frame_count=? WHERE operation_id=? AND sequence=1',
                            (*plan[1], job['operation_id']))
        before = snapshot(self.db)
        with self.assertRaises(ContractRejected):
            model_start_execution(self.db, job, plan)
        self.assertEqual(before, snapshot(self.db))
        with self.db:
            self.db.execute('UPDATE edit_source SET execution_start_us=NULL,execution_end_us=NULL,frame_count=NULL')
            self.db.execute('UPDATE edit_job SET cancelled=1')
        before = snapshot(self.db)
        with self.assertRaises(ContractRejected):
            model_start_execution(self.db, job, plan)
        self.assertEqual(before, snapshot(self.db))
        self.assert_sources_unchanged()

    def test_production_execution_and_single_monitor_anchors(self):
        start = kotlin_function(EDITOR, 'start')
        read = kotlin_function(EDITOR, 'read')
        self.assertIn('transaction { db ->', start)
        self.assertLess(start.index('transaction { db ->'), start.index('db.update("edit_source"'))
        self.assertLess(start.index('db.update("edit_source"'), start.index('db.update("edit_job"'))
        self.assertIn('put("execution_start_us", segment.executionStartUs)', start)
        self.assertIn('put("execution_end_us", segment.executionEndUsExclusive)', start)
        self.assertIn('put("frame_count", segment.frameCount)', start)
        self.assertIn('rawQuery(EXECUTION_SQL', read)
        self.assertIn('duration == expected.durationUs && frames == expected.videoFrameCount', read)
        self.assertIn('!execution.isNull(0) && !execution.isNull(1) && !execution.isNull(2)', read)
        self.assertIn('count == request.segments.size', read)
        self.assertIn('ORDER BY sequence LIMIT 34', EXECUTION_SQL)
        self.assertNotIn('@Synchronized', SAVE)
        self.assertIn('dbCall(block: () -> T): T = synchronized(fileLock)', SAVE)
        self.assertIn('fileCall(block: () -> T): T = synchronized(fileLock)', SAVE)
        self.assertIn('override fun close() = synchronized(fileLock)', SAVE)
        self.assertIn('internal val edits: AndroidEditStorage get() = synchronized(fileLock)', SAVE)


def synthetic_job(number, operation=None, phase='PREPARED', sources=()):
    request = json.dumps({'version': 1, 'sources': [
        {'item': source['item_id'], 'revision': source['revision'], 'generation': source['generation'],
         'start_us': sequence * 1_000_000, 'end_us': (sequence + 1) * 1_000_000}
        for sequence, source in enumerate(sources)], 'compression': 'KEEP_PROFILE'},
        separators=(',', ':'), sort_keys=True).encode()
    return dict(operation_id=operation or f'edit-{number}', fingerprint=hashlib.sha256(request).hexdigest(),
                artifact_id=uid(20_000 + number), media_id=uid(30_000 + number), phase=phase,
                cancelled=0, request=request, expectation=b'synthetic-plan' if phase in ('PUBLISH_INTENT', 'SUCCEEDED') else None,
                evidence=b'synthetic-proof' if phase in ('PUBLISH_INTENT', 'SUCCEEDED') else None,
                created_at=100_000 + number, error=None)


def synthetic_source(operation, sequence, source):
    return dict(operation_id=operation, sequence=sequence, item_id=source['item_id'],
                revision=source['revision'], generation=source['generation'],
                start_us=sequence * 1_000_000, end_us=(sequence + 1) * 1_000_000,
                size_bytes=source['size'], sha256=source['sha'])


def read_job(db, operation):
    cursor = db.execute('SELECT * FROM edit_job WHERE operation_id=?', (operation,))
    row = cursor.fetchone()
    if row is None:
        raise ContractRejected('UNKNOWN_OPERATION')
    record = dict(zip([column[0] for column in cursor.description], row))
    model_source_snapshots(db, record)
    return record



def model_source_snapshots(db, job):
    recipe = json.loads(job['request'])['sources']
    rows = db.execute(SOURCE_SQL, (job['operation_id'],)).fetchall()
    if len(rows) != len(recipe):
        raise ContractRejected('SOURCE_CARDINALITY_MISMATCH')
    for index, (row, segment) in enumerate(zip(rows, recipe)):
        if row[:6] != (index, segment['item'], segment['revision'], segment['generation'],
                       segment['start_us'], segment['end_us']):
            raise ContractRejected('SOURCE_RECIPE_MISMATCH')
        if row[6] <= 0 or re.fullmatch('[a-f0-9]{64}', row[7]) is None:
            raise ContractRejected('INVALID_SOURCE_IDENTITY')
    return rows


def require_current_sources(db, sources):
    for source in sources:
        row = db.execute(ITEM_SELECT + ' WHERE i.item_id=?', (source['item_id'],)).fetchone()
        if row is None or row[11] != 'READY' or (row[1], row[2]) != (source['revision'], source['generation']):
            raise ContractRejected('STALE_SOURCE')
        representation = db.execute(REPRESENTATION_SQL, (source['item_id'],)).fetchone()
        if representation is None or representation[3] != 'READY' or representation[1] != source['generation']:
            raise ContractRejected('SOURCE_REPRESENTATION_UNAVAILABLE')


def model_prepare(db, stage, media, sources, number):
    """Synthetic codec/lease model; production Kotlin codec/leases are NOT executed."""
    require_current_sources(db, sources)
    job = synthetic_job(number, sources=sources)
    staging = stage / (job['artifact_id'] + '.part')
    final = media / (job['media_id'] + '.mp4')
    if (staging.exists() or staging.is_symlink() or final.exists() or final.is_symlink()
            or candidate_catalog_owned(db, job['media_id'])):
        raise ContractRejected('CANDIDATE_COLLISION')
    with db:
        insert(db, 'edit_job', job)
        for sequence, source in enumerate(sources):
            insert(db, 'edit_source', synthetic_source(job['operation_id'], sequence, source))
    staging.write_bytes(b'')
    return job


def model_verified_candidate(db, stage, job, payload=b'synthetic-edited-output' * 16):
    """A test-only independent-proof double. These bytes are not an MP4."""
    (stage / (job['artifact_id'] + '.part')).write_bytes(payload)
    proof = dict(operation=job['operation_id'], artifact=job['artifact_id'], bytes=len(payload),
                 sha256=hashlib.sha256(payload).hexdigest(), independent_verified=True,
                 duration_us=2_000_000, width=640, height=480)
    with db:
        db.execute("UPDATE edit_job SET phase='VERIFYING',expectation=? WHERE operation_id=?",
                   (b'synthetic-output-expectation', job['operation_id']))
    return proof


def exact_candidate(path, proof):
    if path.is_symlink() or not path.is_file():
        raise ContractRejected('UNSAFE_CANDIDATE')
    payload = path.read_bytes()
    if len(payload) != proof['bytes'] or hashlib.sha256(payload).hexdigest() != proof['sha256']:
        raise ContractRejected('CANDIDATE_EVIDENCE_MISMATCH')


def model_publish(db, stage, media, operation, proof, sources, fail_at=None,
                  cancelled_at=None, owners_released=True):
    """Models transactions around extracted CAS predicates; does not call Kotlin."""
    def checkpoint(name):
        if name == cancelled_at:
            raise ContractRejected('CANCELLED_CONTEXT')
        if name == fail_at:
            raise InjectedFailure(name)

    row = read_job(db, operation)
    if row['phase'] != 'VERIFYING' or row['cancelled'] or not owners_released:
        raise ContractRejected('NOT_FINALIZABLE')
    if proof is None or not proof.get('independent_verified') or (proof['operation'], proof['artifact']) != (operation, row['artifact_id']):
        raise ContractRejected('UNVERIFIED_OUTPUT')
    require_current_sources(db, sources)
    snapshots = model_source_snapshots(db, row)
    for source, snapshot_row in zip(sources, snapshots):
        current = db.execute(REPRESENTATION_SQL, (source['item_id'],)).fetchone()
        if (current[4], current[5]) != (snapshot_row[6], snapshot_row[7]):
            raise ContractRejected('SOURCE_IDENTITY_CHANGED')
    staging = stage / (row['artifact_id'] + '.part')
    final = media / (row['media_id'] + '.mp4')
    evidence = json.dumps(proof, separators=(',', ':'), sort_keys=True).encode()
    with db:
        checkpoint('before-intent')
        if db.execute("UPDATE edit_job SET phase='PUBLISH_INTENT',evidence=? WHERE " + PUBLISH_CAS,
                      (evidence, operation)).rowcount != 1:
            raise ContractRejected('STALE_PUBLICATION')
    checkpoint('intent-committed')
    exact_candidate(staging, proof)
    checkpoint('before-promotion')
    if final.exists() or final.is_symlink():
        raise ContractRejected('FINAL_COLLISION')
    staging.rename(final)
    checkpoint('promoted')
    exact_candidate(final, proof)
    try:
        db.execute('BEGIN IMMEDIATE')
        checkpoint('publication-begin')
        require_current_sources(db, sources)
        current = read_job(db, operation)
        if current['phase'] != 'PUBLISH_INTENT' or current['cancelled'] or current['evidence'] != evidence:
            raise ContractRejected('PUBLICATION_FENCE')
        representation = str(uuid.uuid5(uuid.NAMESPACE_OID, 'synthetic-edit:' + row['media_id']))
        insert(db, 'library_item', dict(item_id=row['media_id'], origin_commit_id=None, origin_edit_id=operation,
               revision=1, lifecycle='READY', privacy_class='ORDINARY', folder_id=None,
               representation_id=representation, generation=1, display_name='Edit ' + row['media_id'],
               name_key='edit ' + row['media_id'], created_at=456789, date_kind='SAVED',
               duration_millis=(proof['duration_us'] + 999) // 1000, width=proof['width'], height=proof['height']))
        checkpoint('item-inserted')
        insert(db, 'media_representation', dict(representation_id=representation, item_id=row['media_id'],
               generation=1, kind='ORDINARY_FILE_V1', opaque_locator=row['media_id'], state='READY',
               size_bytes=proof['bytes'], sha256=proof['sha256']))
        checkpoint('representation-inserted')
        if db.execute("UPDATE edit_job SET phase='SUCCEEDED' WHERE " + SUCCESS_CAS, (operation,)).rowcount != 1:
            raise ContractRejected('SUCCESS_CAS_FAILED')
        checkpoint('job-succeeded')
        db.execute('UPDATE library_state SET revision=revision+1 WHERE singleton=1')
        checkpoint('catalog-revision-bumped')
        foreign_keys_clean(db)
        checkpoint('before-commit')
        db.commit()
    except BaseException:
        db.rollback()
        raise
    checkpoint('after-commit')
    return row['media_id']


def candidate_catalog_owned(db, media_id):
    return db.execute(FINAL_OWNERSHIP_SQL, (media_id, media_id, media_id)).fetchone() is not None


def model_cleanup(db, stage, media, operation, fail_final_unlink=False, owners_released=True):
    row = read_job(db, operation)
    if db.execute(PUBLISHED_SQL, (operation,)).fetchone() is not None:
        return False
    if not owners_released:
        raise ContractRejected('RELEASE_UNCONFIRMED')
    for key in ('artifact_id', 'media_id'):
        if str(uuid.UUID(row[key])) != row[key]:
            raise ContractRejected('INVALID_LOCATOR')
    staging = stage / (row['artifact_id'] + '.part')
    final = media / (row['media_id'] + '.mp4')
    try:
        if candidate_catalog_owned(db, row['media_id']):
            raise ContractRejected('FINAL_CATALOG_OWNED')
        if final.exists() or final.is_symlink():
            if row['evidence'] is None or staging.exists() or staging.is_symlink():
                raise ContractRejected('FINAL_NOT_OWNED')
            proof = json.loads(row['evidence'])
            if not proof['independent_verified'] or (proof['operation'], proof['artifact']) != (operation, row['artifact_id']):
                raise ContractRejected('FINAL_NOT_OWNED')
            exact_candidate(final, proof)
            if fail_final_unlink:
                raise ContractRejected('SYNTHETIC_UNLINK_FAILED')
            final.unlink()
        if staging.exists() or staging.is_symlink():
            if staging.is_symlink() or not staging.is_file():
                raise ContractRejected('UNSAFE_STAGING')
            staging.unlink()
        phase = 'INTERRUPTED'
    except (ContractRejected, ValueError, OSError):
        phase = 'CLEANUP_PENDING'
    with db:
        db.execute('UPDATE edit_job SET phase=? WHERE operation_id=?', (phase, operation))
    return phase == 'INTERRUPTED'


def model_recover(db, stage, media):
    operations = [row[0] for row in db.execute(RECOVERY_SQL, (8,))]
    for operation in operations:
        model_cleanup(db, stage, media, operation)
    return operations



def model_start_execution(db, job, plan, fail_at=None):
    """Atomic-start model using production CAS selections and a synthetic plan codec."""
    expected = {'duration_us': sum(end - start for start, end, _ in plan),
                'video_frame_count': sum(frames for _, _, frames in plan)}
    def checkpoint(name):
        if name == fail_at:
            raise InjectedFailure(name)
    with db:
        db.execute('BEGIN IMMEDIATE')
        checkpoint('transaction-start')
        for sequence, (start, end, frames) in enumerate(plan):
            changed = db.execute('UPDATE edit_source SET execution_start_us=?,execution_end_us=?,frame_count=? WHERE ' + START_SOURCE_CAS,
                                 (start, end, frames, job['operation_id'], sequence)).rowcount
            if changed != 1:
                raise ContractRejected('EXECUTION_SOURCE_CAS_FAILED')
            checkpoint('source-' + str(sequence))
        changed = db.execute("UPDATE edit_job SET expectation=?,phase='EXECUTING' WHERE " + START_JOB_CAS,
                             (json.dumps(expected, sort_keys=True).encode(), job['operation_id'])).rowcount
        if changed != 1:
            raise ContractRejected('EXECUTION_JOB_CAS_FAILED')
        checkpoint('expectation-updated')
    return expected


def model_execution_snapshot(db, job, expected):
    """Production ordered selector plus Python equivalent of the explicit read checks."""
    requested = json.loads(job['request'])['sources']
    executions = db.execute(EXECUTION_SQL, (job['operation_id'],)).fetchall()
    if len(executions) != len(requested):
        raise ContractRejected('EXECUTION_CARDINALITY_MISMATCH')
    duration = frames = 0
    for segment, execution in zip(requested, executions):
        start, end, count = execution
        if start is None or end is None or count is None:
            raise ContractRejected('EXECUTION_NULL_TUPLE')
        if not (segment['start_us'] <= start < end <= segment['end_us'] and 1 <= count <= 108_000):
            raise ContractRejected('EXECUTION_ESCAPES_REQUEST')
        duration += end - start
        frames += count
    if duration != expected['duration_us'] or frames != expected['video_frame_count']:
        raise ContractRejected('EXECUTION_SUM_MISMATCH')
    return executions


if __name__ == '__main__':
    print(f'Synthetic host SQLite {sqlite3.sqlite_version}: {len(CREATE)} production create statements; '
          f'{len(MIGRATE)} migration statements. Android/Kotlin integration: NOT RUN.', flush=True)
    unittest.main(verbosity=2)
