#!/usr/bin/env python3
"""Current Save DB v8 contract gate; historical scripts stay byte-for-byte unchanged.

Executes production-extracted SQL on temporary on-disk host SQLite. The transaction /
helper-dispatch model is explicit, checked against the current Kotlin source, and is
NOT Android SQLiteOpenHelper execution. Frozen v1/v3/v5 DDL and v4 source-table DDL
are read as data only; composed v2/v4/v6/v7 baselines are SYNTHETIC, not recovered
historical databases. Opaque seed blobs are NOT codec or playable-media evidence.
v1 covers empty/non-published backfill only; real legacy publication decoding remains
in the separately executed JVM tests. No files outside TemporaryDirectory are changed.
"""
from pathlib import Path
import ast
import hashlib
import json
import re
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/kotlin/com/easysavevideo/app'
INPUTS = {}


def read(path):
    data = path.read_bytes()
    INPUTS[str(path.relative_to(ROOT))] = hashlib.sha256(data).hexdigest()
    return data.decode()


def frozen_assignment(path, name):
    # Do not import historical modules or bypass their guards. Only read their frozen literal.
    tree = ast.parse(read(path))
    nodes = [node.value for node in tree.body if isinstance(node, ast.Assign)
             and any(isinstance(target, ast.Name) and target.id == name for target in node.targets)]
    assert len(nodes) == 1, name
    value = ast.literal_eval(nodes[0])
    assert isinstance(value, list) and all(isinstance(x, str) for x in value)
    return value


def function(source, name):
    match = re.search(r'\bfun\s+' + re.escape(name) + r'\(', source)
    assert match, name
    start = source.index('{', match.end())
    depth = 0
    for token in re.finditer(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|//[^\n]*|/\*[\s\S]*?\*/|[{}]', source[start:]):
        if token.group() == '{':
            depth += 1
        elif token.group() == '}':
            depth -= 1
            if depth == 0:
                return source[start + 1:start + token.start()]
    raise AssertionError('Unclosed function ' + name)


def sql(source):
    result = [a or b for a, b in re.findall(r'db\.execSQL\((?:"""(.*?)"""|"([^"\n]+)")\)', source, re.S)]
    assert all('$' not in item and '\\' not in item for item in result), 'Unsupported SQL escaping/interpolation'
    return result


def dispatch_shape(source):
    # Keep conditions/delegation order while replacing only extracted literal SQL calls.
    return compact(re.sub(r'db\.execSQL\((?:""".*?"""|"[^"\n]+")\)', 'SQL;', source, flags=re.S))


def compact(source):
    return re.sub(r'\s+', '', re.sub(r'//[^\n]*|/\*[\s\S]*?\*/', '', source))


SAVE = read(BASE / 'save/storage/AndroidSaveStorage.kt')
LIBRARY = read(BASE / 'library/storage/LibrarySchema.kt')
EDIT = read(BASE / 'editor/storage/EditSchema.kt')
EXPORT = read(BASE / 'library/export/ExportSchema.kt')
RECORD = read(BASE / 'recording/segments/RecordedSegmentSchema.kt')
DESTINATION = read(BASE / 'library/destination/OrdinaryDestinationSchema.kt')
FOLDER = read(BASE / 'library/destination/FolderDestinationSchema.kt')
BATCH = read(BASE / 'library/export/DirectoryBatchSchema.kt')
JOURNAL = read(BASE / 'library/export/AndroidDirectoryBatchJournal.kt')
V1 = frozen_assignment(ROOT / 'scripts/verify-library-sqlite.py', 'LEGACY')
V3 = frozen_assignment(ROOT / 'scripts/verify-editor-sqlite.py', 'V3_DDL')
V5 = read(ROOT / 'app/src/test/resources/destination/frozen-v5-schema.sql')
V4_SOURCE = read(ROOT / 'app/src/test/resources/recorded-segments/frozen-v4-edit-source.sql')
LIB_DDL = sql(function(LIBRARY, 'create'))
EXP_DDL = sql(function(EXPORT, 'create'))
REC_DDL = sql(function(RECORD, 'create'))
EDIT_JOB_DDL = sql(function(EDIT, 'create'))
EDIT_SOURCE_DDL = sql(function(EDIT, 'createSources'))
EDIT_LIBRARY_DDL = sql(function(EDIT, 'migrateLibrary'))
SOURCE_MIGRATION = sql(function(EDIT, 'migrateRecordedSources'))
DEST_DDL = sql(function(DESTINATION, 'create'))
FOLDER_DDL = sql(function(FOLDER, 'migrate'))
BATCH_DDL = [a or b for a, b in re.findall(r'"""(.*?)"""|"([^"\n]+)"', BATCH.split('val statements=listOf(', 1)[1].split('fun create', 1)[0], re.S)]
assert [len(x) for x in (LIB_DDL, EXP_DDL, REC_DDL, EDIT_JOB_DDL, EDIT_SOURCE_DDL, EDIT_LIBRARY_DDL, SOURCE_MIGRATION, DEST_DDL, FOLDER_DDL, BATCH_DDL)] == [10, 5, 3, 2, 1, 6, 3, 8, 26, 9]
assert all('$' not in x and '\\' not in x for x in BATCH_DDL)
BACKFILL_SELECTOR = re.search(r'db.rawQuery\("""(SELECT m.rowid,.*?)"""', LIBRARY, re.S).group(1)


def execute_all(db, statements):
    for statement in statements:
        db.execute(statement)


def tables(db):
    return [x[0] for x in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]


def snapshot(db):
    return (db.execute('PRAGMA user_version').fetchone()[0],
            tuple(db.execute('SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name')),
            {table: tuple(db.execute('SELECT rowid,* FROM ' + table + ' ORDER BY rowid')) for table in tables(db)})


def schema_signature(db):
    # SQLite rewrites quotes during ALTER TABLE RENAME. Compare semantics, not that spelling.
    objects = tuple((kind, name, table, re.sub(r'\s+', '', ddl or '').replace('"', ''))
                    for kind, name, table, ddl in db.execute('SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name'))
    return objects


def rows_and_columns(db):
    return {table: ([x[1] for x in db.execute('PRAGMA table_info(' + table + ')')],
                    list(db.execute('SELECT rowid,* FROM ' + table + ' ORDER BY rowid')))
            for table in tables(db)}


def foreign_keys_clean(db):
    assert list(db.execute('PRAGMA foreign_key_check')) == []


def clean(db):
    foreign_keys_clean(db)
    assert db.execute('PRAGMA quick_check(1)').fetchone() == ('ok',)


def create_baseline(db, version):
    db.execute('PRAGMA foreign_keys=OFF' if version == 4 else 'PRAGMA foreign_keys=ON')
    if version in (5, 6, 7):
        db.executescript(V5)
    else:
        db.execute('BEGIN')
        if version == 1:
            execute_all(db, V1)
        elif version == 2:
            # Explicit composed v2 from frozen v3's pre-export prefix, not a frozen v2 artifact.
            export_start = next(i for i, statement in enumerate(V3) if statement.startswith('CREATE TABLE library_export('))
            execute_all(db, V3[:export_start])
        elif version in (3, 4):
            execute_all(db, V3)
        else:
            raise AssertionError(version)
        if version == 4:
            execute_all(db, EDIT_JOB_DDL)
            db.execute(V4_SOURCE.strip().split(';')[0])
            execute_all(db, EDIT_LIBRARY_DDL)
        db.commit()
    db.execute('BEGIN')
    if version >= 6:
        execute_all(db, DEST_DDL)
    if version >= 7:
        execute_all(db, FOLDER_DDL)
    db.execute('PRAGMA user_version=' + str(version))
    db.commit()
    db.execute('PRAGMA foreign_keys=ON')
    clean(db)


def insert(db, table, **values):
    db.execute('INSERT INTO ' + table + '(' + ','.join(values) + ') VALUES(' + ','.join('?' for _ in values) + ')', tuple(values.values()))


def seed(db, version):
    # Synthetic opaque bytes deliberately are not claimed to decode as production records.
    with db:
        insert(db, 'save_tasks', task_id='task', idempotency_key='key', revision=7,
               state='RUNNING' if version == 1 else 'SUCCEEDED', commit_id='commit', record=b'synthetic-save-record')
        insert(db, 'save_owned', commit_id='commit', task_id='task', artifact_id='artifact',
               media_id='00000000-0000-0000-0000-000000000001', state='INDEXED', evidence=b'synthetic-proof')
        insert(db, 'save_media', media_id='00000000-0000-0000-0000-000000000001', commit_id='commit',
               artifact_id='artifact', size_bytes=16, sha256='a' * 64, visible=int(version > 1))
        if version >= 2:
            insert(db, 'library_folder', folder_id='folder', parent_id=None, revision=3, display_name='Synthetic', name_key='synthetic')
            insert(db, 'library_item', item_id='item', origin_commit_id='commit', revision=7, lifecycle='READY',
                   privacy_class='ORDINARY', folder_id='folder', representation_id='rep', generation=3,
                   display_name='Synthetic', name_key='synthetic', created_at=123, date_kind='IMPORTED', duration_millis=1000, width=16, height=16)
            insert(db, 'media_representation', representation_id='rep', item_id='item', generation=3, kind='ORDINARY_FILE_V1',
                   opaque_locator='00000000-0000-0000-0000-000000000001', state='READY', size_bytes=16, sha256='a' * 64)
            db.execute('UPDATE library_state SET revision=9,delete_cursor=\'historical-cursor\'')
            insert(db, 'library_operation', operation_id='historical-op', kind='RENAME_ITEM', target_id='item', expected_revision=6, argument_fingerprint='d' * 64, result_revision=7, phase='COMPLETE')
        if version >= 3:
            insert(db, 'library_export', operation_id='export', fingerprint='b' * 64, phase='CLEANUP_PENDING', cancelled=0, record=b'synthetic-export')
            insert(db, 'library_export_target', operation_id='export', kind='MEDIA_STORE', grant_id='export-grant',
                   nonce='00000000-0000-0000-0000-000000000002', uri='content://synthetic/old', grant_flags=0)
            db.execute("UPDATE library_export_state SET recovery_cursor='export'")
        if version >= 4:
            insert(db, 'edit_job', operation_id='edit', fingerprint='c' * 64, artifact_id='00000000-0000-0000-0000-000000000003',
                   media_id='00000000-0000-0000-0000-000000000004', phase='FAILED', request=b'synthetic-edit', created_at=123)
            insert(db, 'edit_source', operation_id='edit', sequence=0, item_id='item', revision=7, generation=3,
                   start_us=0, end_us=1000000, execution_start_us=0, execution_end_us=1000000, frame_count=30, size_bytes=16, sha256='a' * 64)
        if version >= 5:
            insert(db, 'recording_session', session_id='session', generation=2, config=b'synthetic-config', state='RETAINED', created_at=124)
            insert(db, 'recording_segment', segment_id='00000000-0000-0000-0000-000000000005', session_id='session', sequence=0, state='ALLOCATED', effective_start_us=0)
        if version >= 6:
            insert(db, 'ordinary_destination', creation_id='task', choice='ALBUM_COPY', operation_id='destination-export',
                   grant_id='destination-grant', source_item='item', source_revision=7, source_generation=3)
            insert(db, 'ordinary_destination_publication', publication_id='task', creation_id='task')
            db.execute("UPDATE ordinary_destination_state SET preference='ASK',copy_cursor='task'")
        if version >= 7:
            insert(db, 'ordinary_folder_grant', folder_grant_id='folder-grant', tree_uri='content://synthetic/tree', flags=3, retired=0, invalidated=0)
            db.execute("UPDATE ordinary_destination_state SET preference='FOLDER_COPY',selected_folder_id='folder-grant',selection_revision=4")
    clean(db)


def upgrade_steps(old):
    result = []
    if old == 1:
        result += LIB_DDL
        # Only selector/no-candidate backfill in this gate. Never fake Kotlin proof decoding.
        result += ['BACKFILL_EMPTY_CONTRACT']
    if old < 3:
        result += EXP_DDL
    if old < 5:
        result += REC_DDL
    if old < 4:
        result += EDIT_JOB_DDL + EDIT_SOURCE_DDL + EDIT_LIBRARY_DDL
    elif old == 4:
        result += SOURCE_MIGRATION[:1] + EDIT_SOURCE_DDL + SOURCE_MIGRATION[1:]
    if old < 6:
        result += DEST_DDL
    if old < 7:
        result += FOLDER_DDL
    return result + BATCH_DDL


class Fault(RuntimeError):
    pass


def upgrade(db, old, new=8, fail_at=None):
    if old not in range(1, 8) or new != 8:
        raise ValueError('Unsupported upgrade contract')
    assert not db.in_transaction
    db.execute('PRAGMA foreign_keys=' + ('OFF' if old <= 3 else 'ON'))
    checkpoints = []
    def checkpoint(name):
        checkpoints.append(name)
        if fail_at == name:
            raise Fault(name)
    try:
        db.execute('BEGIN IMMEDIATE'); checkpoint('begin')
        for i, statement in enumerate(upgrade_steps(old)):
            if statement == 'BACKFILL_EMPTY_CONTRACT':
                assert list(db.execute(BACKFILL_SELECTOR, (0,))) == [], 'Published v1 backfill needs real codec/media validation'
            else:
                db.execute(statement)
            checkpoint('sql-' + str(i))
        foreign_keys_clean(db); checkpoint('integrity')
        db.execute('PRAGMA user_version=8'); checkpoint('version')
        db.commit()
    except BaseException:
        db.rollback()
        raise
    finally:
        db.execute('PRAGMA foreign_keys=ON')
    clean(db)
    return checkpoints


def fresh(db):
    db.execute('PRAGMA foreign_keys=ON'); db.execute('BEGIN')
    execute_all(db, sql(function(SAVE, 'onCreate')) + LIB_DDL + EXP_DDL + REC_DDL + EDIT_JOB_DDL + EDIT_SOURCE_DDL + EDIT_LIBRARY_DDL + DEST_DDL + FOLDER_DDL + BATCH_DDL)
    db.execute('PRAGMA user_version=8'); db.commit(); clean(db)


class CurrentStorageContracts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='esv-v8-contract-')
        self.path = Path(self.temp.name) / 'synthetic.db'
        self.db = sqlite3.connect(self.path)

    def tearDown(self):
        self.db.close(); self.temp.cleanup()

    def reopen(self):
        self.db.close(); self.db = sqlite3.connect(self.path)
        self.db.execute('PRAGMA foreign_keys=ON'); clean(self.db)

    def test_source_dispatch_is_exactly_current_contract_not_only_version_string(self):
        expected = '''
            if (oldVersion !in 1..7 || newVersion != 8) throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
            try {
                if (oldVersion == 1) {
                    LibrarySchema.create(db)
                    LibrarySchema.backfill(db, System.currentTimeMillis(), OrdinaryMediaFiles(File(file.parentFile, "media"), {}))
                }
                if (oldVersion < 3) ExportSchema.create(db)
                if (oldVersion < 5) RecordedSegmentSchema.create(db)
                if (oldVersion < 4) { EditSchema.create(db); EditSchema.migrateLibrary(db) }
                else if (oldVersion == 4) EditSchema.migrateRecordedSources(db)
                if (oldVersion < 6) OrdinaryDestinationSchema.create(db)
                if(oldVersion<7)FolderDestinationSchema.migrate(db)
                DirectoryBatchSchema.create(db)
                EditSchema.requireForeignKeysValid(db)
            } catch (_: Exception) { throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE) }
        '''
        self.assertEqual(compact(expected).replace(';', ''), compact(function(SAVE, 'onUpgrade')).replace(';', ''))
        self.assertIn('file.absolutePath,null,8,', compact(SAVE))
        calls = re.findall(r'(\w+Schema\.\w+)\(db\)', function(SAVE, 'onCreate'))
        self.assertEqual(['LibrarySchema.create', 'ExportSchema.create', 'RecordedSegmentSchema.create', 'EditSchema.create',
                          'EditSchema.migrateLibrary', 'OrdinaryDestinationSchema.create', 'FolderDestinationSchema.migrate', 'DirectoryBatchSchema.create'], calls)
        self.assertEqual([compact(x) for x in V1], [compact(x) for x in sql(function(SAVE, 'onCreate'))])
        self.assertIn('db.setForeignKeyConstraintsEnabled(db.version !in 1..3)', function(SAVE, 'onConfigure'))
        self.assertIn('db.setForeignKeyConstraintsEnabled(true)', function(SAVE, 'onOpen'))
        self.assertIn('EditSchema.requireForeignKeysValid(db)', function(SAVE, 'onOpen'))
        self.assertIn('PRAGMA quick_check(1)', function(SAVE, 'onOpen'))
        self.assertIn('throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)', function(SAVE, 'onDowngrade'))
        self.assertEqual('statements.forEach(db::execSQL)', function(BATCH, 'create').strip())
        self.assertEqual(compact('''''''''
            db.setForeignKeyConstraintsEnabled(db.version !in 1..3)
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            db.execSQL("PRAGMA synchronous=FULL")
        '''''''''), compact(function(SAVE, 'onConfigure')))
        self.assertEqual(compact('''''''''
            db.setForeignKeyConstraintsEnabled(true)
            db.rawQuery("PRAGMA foreign_keys", null).use { check(it.moveToFirst() && it.getInt(0) == 1) }
            EditSchema.requireForeignKeysValid(db)
            db.rawQuery("PRAGMA quick_check(1)", null).use { c ->
                if (!c.moveToFirst() || c.getString(0) != "ok") throw SaveFailure(SaveError.JOURNAL_UNAVAILABLE)
            }
        '''''''''), compact(function(SAVE, 'onOpen')))

    def test_nested_schema_dispatch_is_bound_to_actual_production_call_order(self):
        # The host plan must not silently supply an omitted or conditional delegated table.
        for source, name, count, tail in [
            (LIBRARY, 'create', 10, ''), (EXPORT, 'create', 5, ''),
            (RECORD, 'create', 3, ''), (EDIT, 'create', 2, 'createSources(db)'),
            (EDIT, 'createSources', 1, ''), (EDIT, 'migrateLibrary', 6, 'requireForeignKeysValid(db)'),
            (DESTINATION, 'create', 8, '')]:
            self.assertEqual('SQL;' * count + tail, dispatch_shape(function(source, name)), name)
        self.assertEqual('SQL;createSources(db)SQL;SQL;requireForeignKeysValid(db)', dispatch_shape(function(EDIT, 'migrateRecordedSources')))
        self.assertEqual('check(db.inTransaction())' + 'SQL;' * 26, dispatch_shape(function(FOLDER, 'migrate')))
        self.assertEqual('SQL;' * 3 + ''.join(name + '(db)' for name in [
            'LibrarySchema.create', 'ExportSchema.create', 'RecordedSegmentSchema.create', 'EditSchema.create',
            'EditSchema.migrateLibrary', 'OrdinaryDestinationSchema.create', 'FolderDestinationSchema.migrate', 'DirectoryBatchSchema.create']),
            dispatch_shape(function(SAVE, 'onCreate')))
        self.assertEqual(compact('db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) { "EDIT_MIGRATION_FOREIGN_KEY" } }'),
                         compact(function(EDIT, 'requireForeignKeysValid')))

    def test_lazy_editor_still_uses_one_owner_monitor_and_only_publishes_after_construction(self):
        self.assertIn('internal val edits: AndroidEditStorage get() = initializeEditStorage(InitializationProbe())', SAVE)
        self.assertIn('initializeEditStorage(initialization:InitializationProbe):AndroidEditStorage = synchronized(fileLock)', SAVE)
        block = function(SAVE, 'initializeEditStorage')
        self.assertIn('editorStorage ?: AndroidEditStorage(', block)
        self.assertIn('.also { editorStorage = it }', block)
        self.assertLess(block.index('AndroidEditStorage('), block.index('editorStorage = it'))
        # Static wiring only; no claim that this executes the Android constructor.

    def test_all_seven_supported_baselines_preserve_rows_reopen_and_match_fresh_schema(self):
        fresh(self.db); expected = schema_signature(self.db)
        self.db.close()
        for old in range(1, 8):
            with self.subTest(old=old):
                self.path = Path(self.temp.name) / ('v' + str(old) + '.db')
                self.db = sqlite3.connect(self.path); create_baseline(self.db, old); seed(self.db, old)
                before = rows_and_columns(self.db)
                upgrade(self.db, old)
                self.reopen()
                self.assertEqual(8, self.db.execute('PRAGMA user_version').fetchone()[0])
                self.assertEqual(expected, schema_signature(self.db))
                for table, (columns, rows) in before.items():
                    self.assertEqual(rows, list(self.db.execute('SELECT rowid,' + ','.join(columns) + ' FROM ' + table + ' ORDER BY rowid')), table)
                self.assertEqual(0, self.db.execute('SELECT COUNT(*) FROM library_directory_batch').fetchone()[0])
                self.assertEqual(0, self.db.execute('SELECT COUNT(*) FROM library_directory_item').fetchone()[0])
                if old == 1:
                    self.assertEqual(0, self.db.execute('SELECT COUNT(*) FROM library_item').fetchone()[0])
                if old < 6:
                    self.assertEqual(0, self.db.execute('SELECT COUNT(*) FROM ordinary_destination').fetchone()[0])
                self.db.close()
        self.db = sqlite3.connect(self.path)

    def test_every_ddl_backfill_check_integrity_and_version_boundary_rolls_back(self):
        self.db.close()
        for old in range(1, 8):
            self.path = Path(self.temp.name) / ('rollback-' + str(old) + '.db')
            self.db = sqlite3.connect(self.path); create_baseline(self.db, old); seed(self.db, old)
            before = snapshot(self.db)
            boundaries = ['begin'] + ['sql-' + str(i) for i in range(len(upgrade_steps(old)))] + ['integrity', 'version']
            for boundary in boundaries:
                with self.subTest(old=old, boundary=boundary):
                    with self.assertRaises(Fault):
                        upgrade(self.db, old, fail_at=boundary)
                    self.reopen()
                    self.assertEqual(before, snapshot(self.db))
            upgrade(self.db, old); self.reopen()
            self.assertEqual(8, self.db.execute('PRAGMA user_version').fetchone()[0])
            self.db.close()
        self.db = sqlite3.connect(self.path)

    def test_unsupported_upgrade_and_downgrade_model_preserves_database(self):
        create_baseline(self.db, 7); seed(self.db, 7); before = snapshot(self.db)
        for old, new in [(0, 8), (8, 8), (9, 8), (7, 7), (7, 9), (8, 7)]:
            with self.assertRaises(ValueError): upgrade(self.db, old, new)
            self.assertEqual(before, snapshot(self.db))

    def test_corrupt_foreign_key_is_not_silently_repaired_or_version_advanced(self):
        create_baseline(self.db, 3); seed(self.db, 3)
        self.db.execute('PRAGMA foreign_keys=OFF')
        self.db.execute("UPDATE library_item SET folder_id='missing'"); self.db.commit()
        before = snapshot(self.db)
        with self.assertRaises(AssertionError): upgrade(self.db, 3)
        self.assertEqual(before, snapshot(self.db))
        self.assertNotEqual([], list(self.db.execute('PRAGMA foreign_key_check')))

    def test_v1_visible_succeeded_selector_is_bounded_and_rejects_unmodelled_backfill(self):
        create_baseline(self.db, 1); seed(self.db, 1)
        self.assertEqual([], list(self.db.execute(BACKFILL_SELECTOR, (0,))))
        for state, visible, matches in [('RUNNING', 1, 0), ('FAILED', 1, 0), ('SUCCEEDED', 0, 0), ('SUCCEEDED', 1, 1)]:
            self.db.execute('UPDATE save_tasks SET state=?', (state,)); self.db.execute('UPDATE save_media SET visible=?', (visible,)); self.db.commit()
            self.assertEqual(matches, len(list(self.db.execute(BACKFILL_SELECTOR, (0,)))))
        self.assertIn('LIMIT 64', BACKFILL_SELECTOR)
        before = snapshot(self.db)
        with self.assertRaisesRegex(AssertionError, 'real codec/media validation'): upgrade(self.db, 1)
        self.assertEqual(before, snapshot(self.db))

    def batch(self):
        fresh(self.db)
        with self.db:
            insert(self.db, 'ordinary_folder_grant', folder_grant_id='grant', tree_uri='content://synthetic/tree', flags=3, retired=0, invalidated=0)
            insert(self.db, 'library_directory_batch', batch_id='batch', folder_grant_id='grant', phase='ADMITTED')
            insert(self.db, 'library_directory_item', operation_id='operation', batch_id='batch', ordinal=0, item_id='item', revision=7, generation=3, grant_id='child')

    def rejected(self, statement, args=()):
        before = snapshot(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db: self.db.execute(statement, args)
        self.assertEqual(before, snapshot(self.db))

    def test_batch_admission_requires_existing_active_grant_and_never_changes_default(self):
        self.batch(); before = list(self.db.execute('SELECT * FROM ordinary_destination_state'))
        for grant in ['missing', 'retired', 'invalidated']:
            if grant != 'missing':
                with self.db: insert(self.db, 'ordinary_folder_grant', folder_grant_id=grant, tree_uri='content://synthetic/' + grant,
                                     flags=3, retired=int(grant == 'retired'), invalidated=int(grant == 'invalidated'))
            self.rejected('INSERT INTO library_directory_batch VALUES(?,?,?)', ('new', grant, 'ADMITTED'))
        self.assertEqual(before, list(self.db.execute('SELECT * FROM ordinary_destination_state')))
        with self.db: self.db.execute("UPDATE ordinary_folder_grant SET retired=1 WHERE folder_grant_id='grant'")
        self.reopen()
        self.assertEqual(('grant',), self.db.execute("SELECT folder_grant_id FROM library_directory_batch WHERE batch_id='batch'").fetchone())

    def test_batch_and_item_identity_replay_history_and_result_are_immutable(self):
        self.batch()
        for column, value in [('batch_id', 'other'), ('folder_grant_id', 'other')]:
            self.rejected('UPDATE library_directory_batch SET ' + column + '=?', (value,))
        for column, value in [('operation_id', 'other'), ('batch_id', 'other'), ('ordinal', 1), ('item_id', 'other'),
                              ('revision', 8), ('generation', 4), ('grant_id', 'other')]:
            self.rejected('UPDATE library_directory_item SET ' + column + '=?', (value,))
        with self.db: self.db.execute('UPDATE library_directory_item SET started=1')
        self.rejected('UPDATE library_directory_item SET started=0')
        with self.db: self.db.execute("UPDATE library_directory_item SET outcome='SUCCEEDED'")
        self.rejected("UPDATE library_directory_item SET outcome='FAILED'")
        self.rejected('UPDATE library_directory_item SET outcome=NULL')
        self.rejected('DELETE FROM library_directory_item')
        self.rejected('DELETE FROM library_directory_batch')
        self.reopen(); self.assertEqual((1, 'SUCCEEDED'), self.db.execute('SELECT started,outcome FROM library_directory_item').fetchone())

    def test_stopped_and_finished_batches_cannot_start_or_resume_and_completed_item_cannot_start(self):
        self.batch()
        with self.db: self.db.execute("UPDATE library_directory_batch SET phase='STOPPED'")
        for phase in ['ADMITTED', 'FINISHED']: self.rejected('UPDATE library_directory_batch SET phase=?', (phase,))
        self.rejected('UPDATE library_directory_item SET started=1')
        self.reopen(); self.rejected('UPDATE library_directory_item SET started=1')
        with self.db:
            insert(self.db, 'library_directory_batch', batch_id='finished', folder_grant_id='grant', phase='FINISHED')
            insert(self.db, 'library_directory_item', operation_id='op2', batch_id='finished', ordinal=0, item_id='i2', revision=1, generation=1, grant_id='g2')
            insert(self.db, 'library_directory_batch', batch_id='active', folder_grant_id='grant', phase='ADMITTED')
            insert(self.db, 'library_directory_item', operation_id='op3', batch_id='active', ordinal=0, item_id='i3', revision=1, generation=1, grant_id='g3', outcome='CANCELLED')
        self.rejected("UPDATE library_directory_batch SET phase='ADMITTED' WHERE batch_id='finished'")
        self.rejected("UPDATE library_directory_item SET started=1 WHERE operation_id='op2'")
        self.rejected("UPDATE library_directory_item SET started=1 WHERE operation_id='op3'")

    def test_failed_child_admission_rolls_back_parent_and_prior_children_together(self):
        self.batch(); before = snapshot(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                insert(self.db, 'library_directory_batch', batch_id='new', folder_grant_id='grant', phase='ADMITTED')
                insert(self.db, 'library_directory_item', operation_id='new-op', batch_id='new', ordinal=0, item_id='first', revision=1, generation=1, grant_id='new-child')
                insert(self.db, 'library_directory_item', operation_id='duplicate-child', batch_id='new', ordinal=1, item_id='second', revision=1, generation=1, grant_id='child')
        self.assertEqual(before, snapshot(self.db)); self.reopen()
        self.assertEqual(before, snapshot(self.db))

    def test_production_interruption_sql_stops_uncertain_finished_batches_without_restarting_items(self):
        self.batch()
        with self.db:
            for index, phase in enumerate(['PREPARED', 'CREATE_INTENT', 'COPYING', 'VERIFYING', 'PUBLISH_INTENT', 'CLEANUP_PENDING', 'RECONCILIATION_REQUIRED', 'SUCCEEDED', 'COPIED_UNVERIFIED', 'FAILED', 'CANCELLED']):
                batch = 'b' + str(index); operation = 'op' + str(index)
                insert(self.db, 'library_directory_batch', batch_id=batch, folder_grant_id='grant', phase='FINISHED')
                insert(self.db, 'library_directory_item', operation_id=operation, batch_id=batch, ordinal=0, item_id='i' + str(index), revision=1, generation=1, grant_id='g' + str(index), started=1, outcome='UNAVAILABLE')
                insert(self.db, 'library_export', operation_id=operation, fingerprint='a' * 64, phase=phase, cancelled=0, record=b'synthetic-export')
        block = function(JOURNAL, 'stopInterrupted')
        statements = sql(block)
        self.assertEqual(1, len(statements))
        queries = re.findall(r'db.rawQuery\("([^"\n]+)"', block)
        self.assertEqual(2, len(queries))
        before_items = list(self.db.execute('SELECT * FROM library_directory_item ORDER BY operation_id'))
        before_exports = list(self.db.execute('SELECT * FROM library_export ORDER BY operation_id'))
        with self.db:
            active = [x[0] for x in self.db.execute(queries[0])]
            self.assertEqual(['batch'], active)
            stop_sql = re.search(r'database\(\).execSQL\("([^"\n]+)"', function(JOURNAL, 'stop')).group(1)
            for identity in active: self.db.execute(stop_sql, (identity,))
            self.db.execute(statements[0])
            self.assertEqual({'op' + str(i) for i in range(7)}, {x[0] for x in self.db.execute(queries[1])})
        self.reopen()
        self.assertEqual(before_items, list(self.db.execute('SELECT * FROM library_directory_item ORDER BY operation_id')))
        self.assertEqual(before_exports, list(self.db.execute('SELECT * FROM library_export ORDER BY operation_id')))
        self.assertEqual({'batch'} | {'b' + str(i) for i in range(7)}, {x[0] for x in self.db.execute("SELECT batch_id FROM library_directory_batch WHERE phase='STOPPED'")})
        # This executes selection/stopping SQL only. Kotlin coordinator cancellation is separate.

    def test_batch_bounds_unique_source_ordinal_and_grant_fail_atomically(self):
        self.batch()
        statement = 'INSERT INTO library_directory_item(operation_id,batch_id,ordinal,item_id,revision,generation,grant_id) VALUES(?,?,?,?,?,?,?)'
        for values in [('op2', 'missing', 1, 'i2', 1, 1, 'g2'), ('op2', 'batch', 100, 'i2', 1, 1, 'g2'),
                       ('op2', 'batch', -1, 'i2', 1, 1, 'g2'), ('op2', 'batch', 0, 'i2', 1, 1, 'g2'),
                       ('op2', 'batch', 1, 'item', 1, 1, 'g2'), ('op2', 'batch', 1, 'i2', 1, 1, 'child'),
                       ('op2', 'batch', 1, 'i2', 0, 1, 'g2'), ('op2', 'batch', 1, 'i2', 1, 0, 'g2')]:
            self.rejected(statement, values)
        with self.db:
            for i in range(1, 100): self.db.execute(statement, ('op' + str(i), 'batch', i, 'item' + str(i), 1, 1, 'g' + str(i)))
        self.assertEqual(100, self.db.execute('SELECT COUNT(*) FROM library_directory_item').fetchone()[0])
        self.reopen()
        self.assertEqual(list(range(100)), [x[0] for x in self.db.execute('SELECT ordinal FROM library_directory_item ORDER BY ordinal')])


if __name__ == '__main__':
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(CurrentStorageContracts)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    print(json.dumps({'kind': 'CURRENT_V8_HOST_SQLITE_CONTRACT', 'sqlite_version': sqlite3.sqlite_version,
                      'tests': result.testsRun, 'failures': len(result.failures), 'errors': len(result.errors),
                      'rollback_fault_boundaries': sum(len(upgrade_steps(v)) + 3 for v in range(1, 8)),
                      'baseline_provenance': {'1': 'frozen LEGACY literal', '2': 'synthetic frozen-v3 pre-export prefix',
                        '3': 'frozen V3_DDL literal', '4': 'synthetic v3 plus current edit-job/library DDL and frozen v4 source table',
                        '5': 'frozen a3a028d v5 DDL', '6': 'synthetic v5 plus production v6 DDL', '7': 'synthetic v6 plus production v7 DDL'},
                      'v1_published_media_backfill': 'NOT_EXECUTED_REAL_CODEC_MEDIA_REQUIRED',
                      'kotlin_codec': 'SEPARATE_JVM_GATE', 'android_sqlite_runtime': 'NOT_RUN',
                      'input_sha256': INPUTS}, indent=2))
    raise SystemExit(not result.wasSuccessful())
