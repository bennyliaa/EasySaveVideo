#!/usr/bin/env python3
"""Independent A4b ordinary-folder host SQLite review; synthetic data only.

Executes actual Kotlin DDL and bounded SQL projections, without importing another
suite. Migration sequencing and admission writes are explicit Python contract
models, checked against the owner's production branch. Host SQLite is executed;
Android SQLiteOpenHelper, Kotlin codecs, OS permission revocation/persistence,
provider I/O, media playback and devices are UNEXECUTED. Version 1 contains only a
failed Save: legacy readable-media backfill is not represented as tested.

Requires the complete frozen candidate before and after tests. --provisional is
an explicit absent-manifest-only development mode, never frozen acceptance.
"""
from pathlib import Path
import argparse
import ast
import hashlib
import json
import re
import sqlite3
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / 'app/src/main/kotlin/com/easysavevideo/app'
MANIFEST = ROOT / 'docs/searxng-discovery/evidence/frozen-review-source-manifest.json'
INPUTS = set()


def read(relative):
    INPUTS.add(relative)
    return (ROOT / relative).read_text()


def source(relative):
    return read('app/src/main/kotlin/com/easysavevideo/app/' + relative)


def literals(text):
    result = [a or b for a, b in re.findall(r'db\.execSQL\((?:"""(.*?)"""|"([^"\n]+)")\)', text, re.S)]
    assert all('$' not in sql for sql in result), 'Interpolated SQL requires explicit review'
    return result


def function(text, name):
    tail = text[text.index('fun ' + name + '('):]
    following = re.search(r'\n    (?:private |internal )?fun ', tail[1:])
    return tail if following is None else tail[:following.start() + 1]


fixture = ast.parse(read('scripts/verify-editor-sqlite.py'))
V3 = next(ast.literal_eval(n.value) for n in fixture.body if isinstance(n, ast.Assign)
          and any(isinstance(t, ast.Name) and t.id == 'V3_DDL' for t in n.targets))
EDIT = source('editor/storage/EditSchema.kt')
SAVE = source('save/storage/AndroidSaveStorage.kt')
JOURNAL = source('library/destination/AndroidOrdinaryDestinationJournal.kt')
GRANTS = source('library/destination/AndroidFolderGrantJournal.kt')
REGISTRY = source('library/destination/AndroidFolderGrantRegistry.kt')
SCHEMA = source('library/destination/FolderDestinationSchema.kt')
V6_DDL = literals(source('library/destination/OrdinaryDestinationSchema.kt'))
FOLDER_DDL = literals(SCHEMA)
SEGMENTS = literals(source('recording/segments/RecordedSegmentSchema.kt'))
EDIT_JOB = literals(function(EDIT, 'create'))
NEW_SOURCES = literals(function(EDIT, 'createSources'))
LIBRARY_MIGRATION = literals(function(EDIT, 'migrateLibrary'))
SOURCE_MIGRATION = literals(function(EDIT, 'migrateRecordedSources'))
V4_SOURCE = read('app/src/test/resources/recorded-segments/frozen-v4-edit-source.sql')
V5 = read('app/src/test/resources/destination/frozen-v5-schema.sql')
LIBRARY_CREATE = literals(source('library/storage/LibrarySchema.kt').split('fun backfill')[0])
EXPORT_CREATE = literals(source('library/export/ExportSchema.kt'))
PENDING = re.search(r'PENDING_COPY_SQL = "([^"]+)"', JOURNAL).group(1)
READABLE = re.search(r'val readable = db.rawQuery\("""(.*?)"""', JOURNAL, re.S).group(1)
FOLDER_READ = re.search(r'fun get\(db: SQLiteDatabase, id: String\).*?rawQuery\("""(.*?)"""', GRANTS, re.S).group(1)
ROW_READ = re.search(r'fun get\(db: SQLiteDatabase, creationId: String\).*?rawQuery\("""(.*?)"""', JOURNAL, re.S).group(1)
SELECTION_READ = re.search(r'override fun selection\(\).*?rawQuery\("""(.*?)"""', JOURNAL, re.S).group(1)


def candidate_membership():
    # Current full membership is strictly larger than the historical snapshot;
    # the SQL cases below and exact equality in check_snapshot are unchanged.
    from scope_support import candidate_membership as current_membership
    return current_membership()


def fingerprint(path):
    file = ROOT / path
    assert file.is_file() and not file.is_symlink(), ('FILE_TYPE', path)
    data = file.read_bytes()
    return {'path': path, 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
            'executable': bool(file.stat().st_mode & 0o111)}


def check_snapshot(rows, label):
    indexed = {row['path']: row for row in rows}
    assert len(indexed) == len(rows), 'Duplicate manifest path'
    expected = candidate_membership()
    assert set(indexed) == expected, ('FULL_MEMBERSHIP_MISMATCH', sorted(expected - set(indexed)), sorted(set(indexed) - expected))
    assert INPUTS <= set(indexed), ('UNFROZEN_EXECUTED_INPUT', INPUTS - set(indexed))
    for path, row in indexed.items():
        assert fingerprint(path) == row, ('FROZEN_BYTES_OR_MODE_MISMATCH', path)
    print(f'PASS {label}: full membership, bytes and executable modes for {len(rows)} files', flush=True)


def run(db, statements):
    for sql in statements:
        db.execute(sql)


def seed(db, version):
    db.execute('PRAGMA foreign_keys=OFF')
    if version >= 5:
        db.executescript(V5)
        if version == 6:
            run(db, V6_DDL)
    elif version == 4:
        run(db, V3 + EDIT_JOB)
        db.executescript(V4_SOURCE)
        run(db, LIBRARY_MIGRATION)
    else:
        run(db, V3[:3] if version == 1 else [sql for sql in V3 if 'library_export' not in sql] if version == 2 else V3)
    db.execute('PRAGMA user_version=' + str(version))
    db.execute("INSERT INTO save_tasks VALUES('history','key',7,'FAILED','commit',?)", (b'synthetic-save',))
    if version >= 2:
        db.execute("INSERT INTO library_item(item_id,origin_commit_id,revision,lifecycle,privacy_class,representation_id,generation,display_name,name_key,created_at,date_kind) VALUES('item','commit',7,'READY','ORDINARY','representation',3,'Synthetic','synthetic',0,'SAVED')")
        db.execute("INSERT INTO media_representation VALUES('representation','item',3,'ORDINARY_FILE_V1','00000000-0000-0000-0000-000000000011','READY',8,?)", ('a' * 64,))
    if version >= 3:
        export(db, 'op-history', 'FAILED')
        db.execute("INSERT INTO library_export_target VALUES('op-history','MEDIA_STORE','old-grant','00000000-0000-0000-0000-000000000021','content://synthetic/old',0)")
    if version >= 4:
        db.execute("INSERT INTO edit_job(operation_id,fingerprint,artifact_id,media_id,phase,request,created_at) VALUES('old-edit',?,'00000000-0000-0000-0000-000000000012','00000000-0000-0000-0000-000000000013','FAILED',?,0)", ('c' * 64, b'synthetic-edit'))
        db.execute("INSERT INTO edit_source(operation_id,sequence,item_id,revision,generation,start_us,end_us,size_bytes,sha256) VALUES('old-edit',0,'item',7,3,0,1000,8,?)", ('a' * 64,))
    if version >= 5:
        db.execute("INSERT INTO recording_session(session_id,generation,config,state,created_at) VALUES('old-session',2,?,'RETAINED',0)", (b'synthetic-recording',))
    if version == 6:
        for index, (name, choice, bound) in enumerate((('album-old', 'ALBUM_COPY', True), ('private-old', 'APP_LIBRARY', False), ('record-old', 'ALBUM_COPY', False))):
            db.execute('INSERT INTO ordinary_destination VALUES(?,?,?,?,?,?,?)', (name, choice, 'op-' + name, 'grant-' + name, 'item' if bound else None, 7 if bound else None, 3 if bound else None))
            db.execute('INSERT INTO ordinary_destination_publication VALUES(?,?)', (name, name))
            db.execute('UPDATE ordinary_destination SET rowid=? WHERE creation_id=?', (10 + 10 * index, name))
        db.execute("INSERT INTO ordinary_destination_publication VALUES('processing-old','record-old')")
        db.execute("UPDATE ordinary_destination_state SET preference='ASK',copy_cursor='album-old'")
    db.commit()
    db.execute('PRAGMA foreign_keys=' + ('OFF' if version <= 3 else 'ON'))
    assert not db.execute('PRAGMA foreign_key_check').fetchall()


def steps(version):
    sql = []
    if version == 1:
        sql += LIBRARY_CREATE
    if version < 3:
        sql += EXPORT_CREATE
    if version < 5:
        sql += SEGMENTS
    if version < 4:
        sql += EDIT_JOB + NEW_SOURCES + LIBRARY_MIGRATION
    elif version == 4:
        sql += SOURCE_MIGRATION[:1] + NEW_SOURCES + SOURCE_MIGRATION[1:]
    if version < 6:
        sql += V6_DDL
    return sql + FOLDER_DDL + ['PRAGMA user_version=7']


def snapshot(db):
    schema = list(db.execute("SELECT name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name"))
    tables = {name: (tuple(row[1] for row in db.execute('PRAGMA table_info(' + name + ')')),
                     list(db.execute('SELECT * FROM ' + name + ' ORDER BY rowid')))
              for name, in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")}
    return db.execute('PRAGMA user_version').fetchone(), schema, tables


def export(db, operation, phase):
    db.execute('INSERT INTO library_export VALUES(?,?,?,0,?)', (operation, 'b' * 64, phase, b'synthetic-export'))


def grant(db, name='folder-a', uri=None, flags=3, retired=0, invalidated=0):
    db.execute('INSERT INTO ordinary_folder_grant VALUES(?,?,?,?,?)',
               (name, uri or 'content://synthetic.documents/tree/' + name, flags, retired, invalidated))


def admit(db, name='new', choice='FOLDER_COPY', folder='folder-a', bound=True):
    db.execute('INSERT INTO ordinary_destination VALUES(?,?,?,?,?,?,?,?)',
               (name, choice, 'op-' + name, 'grant-' + name, 'item' if bound else None, 7 if bound else None, 3 if bound else None, folder))
    db.execute('INSERT INTO ordinary_destination_publication VALUES(?,?)', (name, name))


def select(db, folder='folder-a', preference='FOLDER_COPY'):
    db.execute('UPDATE ordinary_destination_state SET preference=?,selected_folder_id=? WHERE singleton=1', (preference, folder))


class IndependentFolderSQLiteReview(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'synthetic.sqlite'
        self.db = sqlite3.connect(self.path)
        seed(self.db, 5)
        with self.db:
            self.db.execute('BEGIN')
            run(self.db, steps(5))
        grant(self.db)
        self.db.commit()

    def tearDown(self):
        self.db.close()
        self.temp.cleanup()

    def reopen(self):
        self.db.commit()
        self.db.close()
        self.db = sqlite3.connect(self.path)
        self.db.execute('PRAGMA foreign_keys=ON')
        self.assertEqual([], self.db.execute('PRAGMA foreign_key_check').fetchall())
        self.assertEqual(('ok',), self.db.execute('PRAGMA quick_check').fetchone())

    def rejected(self, sql, args=()):
        before = snapshot(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(sql, args)
        self.assertEqual(before, snapshot(self.db), sql)

    def test_versions_one_through_six_preserve_rows_columns_rowids_and_reopen(self):
        for version in range(1, 7):
            with self.subTest(version=version), tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / 'migration.sqlite'
                db = sqlite3.connect(path)
                seed(db, version)
                before = snapshot(db)
                old_ids = list(db.execute('SELECT rowid,creation_id FROM ordinary_destination')) if version == 6 else []
                with db:
                    db.execute('BEGIN')
                    run(db, steps(version))
                    self.assertEqual([], db.execute('PRAGMA foreign_key_check').fetchall())
                db.close()
                db = sqlite3.connect(path)
                db.execute('PRAGMA foreign_keys=ON')
                self.assertEqual((7,), db.execute('PRAGMA user_version').fetchone())
                for table, (columns, rows) in before[2].items():
                    self.assertEqual(rows, list(db.execute('SELECT ' + ','.join(columns) + ' FROM ' + table + ' ORDER BY rowid')), table)
                self.assertEqual(old_ids, list(db.execute('SELECT rowid,creation_id FROM ordinary_destination ORDER BY rowid')))
                self.assertEqual([], db.execute('SELECT * FROM ordinary_folder_grant').fetchall())
                self.assertEqual((None, 0), db.execute('SELECT selected_folder_id,selection_revision FROM ordinary_destination_state').fetchone())
                self.assertEqual([], db.execute('PRAGMA foreign_key_check').fetchall())
                self.assertEqual(('ok',), db.execute('PRAGMA quick_check').fetchone())
                db.close()

    def test_every_migration_boundary_rolls_back_all_versions_on_disk_and_reopen(self):
        count = 0
        for version in range(1, 7):
            for boundary in range(len(steps(version)) + 1):
                with self.subTest(version=version, boundary=boundary), tempfile.TemporaryDirectory() as tmp:
                    path = Path(tmp) / 'fault.sqlite'
                    db = sqlite3.connect(path)
                    seed(db, version)
                    before = snapshot(db)
                    db.execute('BEGIN')
                    run(db, steps(version)[:boundary])
                    db.rollback()  # Injected failure before next statement / before final commit.
                    self.assertEqual(before, snapshot(db))
                    db.close()
                    db = sqlite3.connect(path)
                    self.assertEqual(before, snapshot(db))
                    self.assertEqual(('ok',), db.execute('PRAGMA quick_check').fetchone())
                    db.close()
                    count += 1
        print('Independent on-disk rollback boundaries (including before first and after version update):', count, flush=True)

    def test_owner_upgrade_sequence_matches_explicit_model_and_has_no_admission_backfill(self):
        body = SAVE.split('override fun onUpgrade', 1)[1].split('override fun onDowngrade', 1)[0]
        expected = ['oldVersion !in 1..6 || newVersion != 7', 'if (oldVersion == 1)',
                    'if (oldVersion < 3) ExportSchema.create(db)', 'if (oldVersion < 5) RecordedSegmentSchema.create(db)',
                    'if (oldVersion < 4)', 'else if (oldVersion == 4) EditSchema.migrateRecordedSources(db)',
                    'if (oldVersion < 6) OrdinaryDestinationSchema.create(db)', 'FolderDestinationSchema.migrate(db)',
                    'EditSchema.requireForeignKeysValid(db)']
        positions = [body.index(part) for part in expected]
        self.assertEqual(sorted(positions), positions)
        self.assertEqual(1, body.count('FolderDestinationSchema.migrate(db)'))
        self.assertIn('check(db.inTransaction())', SCHEMA)
        self.assertFalse(any('INSERT INTO ordinary_folder_grant' in sql for sql in FOLDER_DDL))
        self.assertEqual(26, len(FOLDER_DDL))

    def test_admission_identity_and_bound_source_are_immutable(self):
        admit(self.db)
        grant(self.db, 'folder-b')
        for column, value in (('creation_id', 'other'), ('choice', 'ALBUM_COPY'), ('operation_id', 'other'),
                              ('grant_id', 'other'), ('folder_grant_id', 'folder-b'), ('source_item', None),
                              ('source_revision', 8), ('source_generation', 4)):
            self.rejected('UPDATE ordinary_destination SET ' + column + '=? WHERE creation_id=\'new\'', (value,))
        self.db.execute('UPDATE ordinary_destination SET source_item=source_item,source_revision=source_revision')
        self.reopen()
        self.assertEqual(('folder-a', 'item', 7, 3), self.db.execute('SELECT folder_grant_id,source_item,source_revision,source_generation FROM ordinary_destination').fetchone())

    def test_unbound_source_binds_once_and_partial_or_real_sources_fail(self):
        admit(self.db, bound=False)
        for sql in ("UPDATE ordinary_destination SET source_item='item'", "UPDATE ordinary_destination SET source_revision=7",
                    "UPDATE ordinary_destination SET source_item='item',source_revision=7.5,source_generation=3",
                    "UPDATE ordinary_destination SET source_item='item',source_revision=7,source_generation=3.5"):
            self.rejected(sql)
        self.db.execute("UPDATE ordinary_destination SET source_item='item',source_revision=7,source_generation=3")
        self.rejected('UPDATE ordinary_destination SET source_item=NULL,source_revision=NULL,source_generation=NULL')

    def test_folder_choice_foreign_keys_uniqueness_and_distinct_grants(self):
        for args in [('bad', 'FOLDER_COPY', 'o', 'g', None, None, None, None),
                     ('bad', 'ALBUM_COPY', 'o', 'g', None, None, None, 'folder-a'),
                     ('bad', 'FOLDER_COPY', 'o', 'folder-a', None, None, None, 'folder-a'),
                     ('bad', 'FOLDER_COPY', 'o', 'g', None, None, None, 'missing')]:
            self.rejected('INSERT INTO ordinary_destination VALUES(?,?,?,?,?,?,?,?)', args)
        admit(self.db)
        self.rejected("INSERT INTO ordinary_destination VALUES('duplicate','FOLDER_COPY','op-new','other',NULL,NULL,NULL,'folder-a')")
        self.rejected("INSERT INTO ordinary_destination VALUES('duplicate','FOLDER_COPY','other','grant-new',NULL,NULL,NULL,'folder-a')")

    def test_publication_link_cannot_move_to_new_admission(self):
        admit(self.db)
        admit(self.db, 'second')
        self.rejected("UPDATE ordinary_destination_publication SET creation_id='second' WHERE publication_id='new'")
        self.rejected("UPDATE ordinary_destination_publication SET publication_id='changed' WHERE publication_id='new'")
        self.rejected("INSERT INTO ordinary_destination_publication VALUES('orphan','missing')")

    def test_grant_identity_is_immutable_retirement_and_invalidation_are_one_way(self):
        for column, value in [('folder_grant_id', 'changed'), ('tree_uri', 'content://synthetic/tree/other'), ('flags', 1)]:
            self.rejected('UPDATE ordinary_folder_grant SET ' + column + '=?', (value,))
        self.db.execute('UPDATE ordinary_folder_grant SET retired=1,invalidated=1')
        self.rejected('UPDATE ordinary_folder_grant SET retired=0')
        self.rejected('UPDATE ordinary_folder_grant SET invalidated=0')
        self.rejected('DELETE FROM ordinary_folder_grant')
        self.reopen()
        self.assertEqual((1, 1), self.db.execute('SELECT retired,invalidated FROM ordinary_folder_grant').fetchone())

    def test_retirement_clears_selection_preserves_rows_and_old_operation_folder(self):
        admit(self.db)
        export(self.db, 'op-new', 'PREPARED')
        select(self.db)
        before = {t: self.db.execute('SELECT * FROM ' + t).fetchall() for t in ('ordinary_destination', 'ordinary_destination_publication', 'library_export', 'library_export_target', 'library_item')}
        self.db.execute('UPDATE ordinary_folder_grant SET retired=1')
        self.reopen()
        self.assertEqual(('APP_LIBRARY', 1, None, 2), self.db.execute(SELECTION_READ).fetchone())
        for table, rows in before.items():
            self.assertEqual(rows, self.db.execute('SELECT * FROM ' + table).fetchall())
        self.assertEqual((1, 0), self.db.execute(FOLDER_READ, ('folder-a',)).fetchone()[2:])
        self.rejected("UPDATE ordinary_destination_state SET preference='FOLDER_COPY',selected_folder_id='folder-a'")
        self.rejected("INSERT INTO ordinary_destination VALUES('newer','FOLDER_COPY','op-x','grant-x',NULL,NULL,NULL,'folder-a')")

    def test_invalidate_all_matching_uri_generations_leaves_unrelated_grants(self):
        uri = 'content://synthetic.documents/tree/folder-a'
        grant(self.db, 'old', uri, retired=1)
        grant(self.db, 'read', uri, flags=1)
        grant(self.db, 'other')
        admit(self.db)
        select(self.db)
        self.db.execute('UPDATE ordinary_folder_grant SET invalidated=1 WHERE tree_uri=? AND invalidated=0', (uri,))
        self.reopen()
        self.assertEqual([('folder-a', 1), ('old', 1), ('other', 0), ('read', 1)], self.db.execute('SELECT folder_grant_id,invalidated FROM ordinary_folder_grant ORDER BY folder_grant_id').fetchall())
        self.assertEqual(('folder-a',), self.db.execute('SELECT folder_grant_id FROM ordinary_destination').fetchone())
        self.assertEqual(('APP_LIBRARY', 1, None, 2), self.db.execute(SELECTION_READ).fetchone())

    def test_same_uri_new_generation_does_not_rebind_old_admission(self):
        admit(self.db)
        self.db.execute('UPDATE ordinary_folder_grant SET invalidated=1')
        grant(self.db, 'fresh', 'content://synthetic.documents/tree/folder-a')
        select(self.db, 'fresh')
        admit(self.db, 'newer', folder='fresh')
        self.rejected("UPDATE ordinary_destination SET folder_grant_id='fresh' WHERE creation_id='new'")
        self.reopen()
        self.assertEqual([('new', 'folder-a'), ('newer', 'fresh')], self.db.execute('SELECT creation_id,folder_grant_id FROM ordinary_destination ORDER BY creation_id').fetchall())
        self.assertEqual(1, self.db.execute(FOLDER_READ, ('folder-a',)).fetchone()[3])

    def test_exact_active_uri_flags_unique_but_retained_generations_consume_capacity(self):
        uri = 'content://synthetic.documents/tree/folder-a'
        self.rejected('INSERT INTO ordinary_folder_grant VALUES(?,?,3,0,0)', ('duplicate', uri))
        grant(self.db, 'read', uri, flags=1)
        self.db.execute("UPDATE ordinary_folder_grant SET retired=1 WHERE folder_grant_id='folder-a'")
        grant(self.db, 'new-generation', uri)
        for index in range(125):
            grant(self.db, 'capacity-' + str(index), invalidated=1)
        self.assertEqual((128,), self.db.execute('SELECT count(*) FROM ordinary_folder_grant').fetchone())
        self.rejected("INSERT INTO ordinary_folder_grant VALUES('overflow','content://synthetic/tree/overflow',3,0,0)")
        self.rejected('DELETE FROM ordinary_folder_grant WHERE retired=1 OR invalidated=1')

    def test_grant_storage_types_and_uri_bounds_reject_malformed_inserts(self):
        values = [['', 'content://synthetic/tree/x', 3, 0, 0], ['x' * 129, 'content://synthetic/tree/x', 3, 0, 0]]
        values += [['bad', bad, 3, 0, 0] for bad in ('tiny', 'content://x\0bad', 'content://' + 'a' * 8192, 'content://' + '界' * 3000, b'content://synthetic/tree/x')]
        for column in (2, 3, 4):
            for bad in (-1, 1.25, 'invalid', b'1', 4 if column == 2 else 2):
                row = ['bad', 'content://synthetic/tree/x', 3, 0, 0]
                row[column] = bad
                values.append(row)
        for row in values:
            with self.subTest(row=row):
                self.rejected('INSERT INTO ordinary_folder_grant VALUES(?,?,?,?,?)', row)

    def test_corrupted_persisted_grant_projection_rejects_types_and_oversize(self):
        self.db.execute('DROP TRIGGER ordinary_folder_grant_immutable')
        self.db.execute('PRAGMA ignore_check_constraints=ON')
        for column, index, healthy, invalid in [('flags', 1, 3, (7, 1.25, 'bad', b'3')),
                                               ('retired', 2, 0, (2, -1, 0.5, b'0')),
                                               ('invalidated', 3, 0, (2, -1, 0.5, 'bad')),
                                               ('tree_uri', 0, 'content://synthetic/tree/a', ('x', b'content://synthetic/tree/a', 'content://' + '界' * 3000))]:
            for bad in invalid:
                self.db.execute('UPDATE ordinary_folder_grant SET ' + column + '=?', (bad,))
                self.assertIsNone(self.db.execute(FOLDER_READ, ('folder-a',)).fetchone()[index])
            self.db.execute('UPDATE ordinary_folder_grant SET ' + column + '=?', (healthy,))
        # URI scheme/control checks are performed by Kotlin decode, not claimed as SQLite execution.
        self.assertIn('uri.startsWith("content://")', GRANTS)
        self.assertIn('uri.none { it.isISOControl() }', GRANTS)

    def test_corrupt_source_projection_rejects_fractional_blob_negative_before_long_conversion(self):
        admit(self.db)
        self.db.execute('DROP TRIGGER ordinary_destination_immutable')
        self.db.execute('PRAGMA ignore_check_constraints=ON')
        for column, index in [('source_revision', 9), ('source_generation', 10)]:
            for bad in (7.25, b'7', 'seven', -1, 0):
                self.db.execute('UPDATE ordinary_destination SET ' + column + '=?', (bad,))
                self.assertEqual(0, self.db.execute(ROW_READ, ('new',)).fetchone()[index])
            self.db.execute('UPDATE ordinary_destination SET ' + column + '=7')
        self.assertLess(JOURNAL.index('it.getInt(9) != 1'), JOURNAL.index('if (it.isNull(5)) null else it.getLong(5)'))

    def test_corrupt_identifier_projections_fail_closed(self):
        admit(self.db)
        self.db.execute('DROP TRIGGER ordinary_destination_immutable')
        self.db.execute('PRAGMA ignore_check_constraints=ON')
        for column, index, good in [('choice', 0, 'FOLDER_COPY'), ('operation_id', 1, 'op-new'), ('grant_id', 2, 'grant-new')]:
            self.db.execute('UPDATE ordinary_destination SET ' + column + '=?', ('x' * 129,))
            self.assertIsNone(self.db.execute(ROW_READ, ('new',)).fetchone()[index])
            self.db.execute('UPDATE ordinary_destination SET ' + column + '=?', (good,))
        self.db.commit()
        self.db.execute('PRAGMA foreign_keys=OFF')  # Inject a corrupt persisted reference for the bounded read.
        self.db.execute("UPDATE ordinary_destination SET folder_grant_id='' WHERE creation_id='new'")
        self.assertEqual(0, self.db.execute(ROW_READ, ('new',)).fetchone()[7])

    def test_corrupt_v6_real_sources_abort_entire_migration_without_repair(self):
        for field in ('source_revision', 'source_generation'):
            db = sqlite3.connect(':memory:')
            seed(db, 6)
            db.execute('DROP TRIGGER ordinary_destination_immutable')
            db.execute('UPDATE ordinary_destination SET ' + field + '=7.25 WHERE creation_id=\'album-old\'')
            db.commit()
            before = snapshot(db)
            with self.assertRaises(sqlite3.IntegrityError):
                with db:
                    db.execute('BEGIN')
                    run(db, steps(6))
            self.assertEqual(before, snapshot(db))
            db.close()

    def test_selection_preferences_preserve_remembered_folder_and_reopen(self):
        self.assertEqual(('APP_LIBRARY', 1, None, 0), self.db.execute(SELECTION_READ).fetchone())
        self.rejected("UPDATE ordinary_destination_state SET preference='FOLDER_COPY'")
        select(self.db)
        for preference in ('ASK', 'ALBUM_COPY', 'APP_LIBRARY', 'FOLDER_COPY'):
            self.db.execute('UPDATE ordinary_destination_state SET preference=?', (preference,))
            self.assertEqual('folder-a', self.db.execute(SELECTION_READ).fetchone()[2])
        self.reopen()
        self.assertEqual(('FOLDER_COPY', 1, 'folder-a', 5), self.db.execute(SELECTION_READ).fetchone())
        self.rejected("UPDATE ordinary_destination_state SET preference='UNKNOWN'")
        self.rejected("UPDATE ordinary_destination_state SET selected_folder_id='missing'")

    def test_selection_revision_exactly_once_noops_cursor_and_change_back(self):
        select(self.db)
        self.assertEqual(1, self.db.execute(SELECTION_READ).fetchone()[3])
        select(self.db)
        self.db.execute("UPDATE ordinary_destination_state SET copy_cursor='new'")
        self.assertEqual(1, self.db.execute(SELECTION_READ).fetchone()[3])
        grant(self.db, 'folder-b')
        select(self.db, 'folder-b', 'ASK')
        self.assertEqual(2, self.db.execute(SELECTION_READ).fetchone()[3])
        select(self.db)
        self.assertEqual(3, self.db.execute(SELECTION_READ).fetchone()[3])
        self.db.execute("UPDATE ordinary_folder_grant SET retired=1 WHERE folder_grant_id='folder-b'")
        self.assertEqual(3, self.db.execute(SELECTION_READ).fetchone()[3])
        self.db.execute("UPDATE ordinary_folder_grant SET retired=1 WHERE folder_grant_id='folder-a'")
        self.assertEqual(4, self.db.execute(SELECTION_READ).fetchone()[3])
        self.db.execute('UPDATE ordinary_folder_grant SET invalidated=1')
        self.assertEqual(4, self.db.execute(SELECTION_READ).fetchone()[3])

    def test_selection_revision_corruption_rejected_on_write_and_bounded_read(self):
        for bad in (-1, 0.5, 'invalid', b'0'):
            self.rejected('UPDATE ordinary_destination_state SET selection_revision=?', (bad,))
        self.db.execute('PRAGMA ignore_check_constraints=ON')
        for bad in (-1, 0.5, 'invalid', b'0'):
            self.db.execute('UPDATE ordinary_destination_state SET selection_revision=?', (bad,))
            self.assertIsNone(self.db.execute(SELECTION_READ).fetchone()[3])

    def test_selection_revision_overflow_aborts_mutation_and_invalidation_atomically(self):
        select(self.db)
        grant(self.db, 'folder-b')
        self.db.execute('UPDATE ordinary_destination_state SET selection_revision=9223372036854775807')
        for sql in ("UPDATE ordinary_destination_state SET preference='ASK'",
                    "UPDATE ordinary_destination_state SET selected_folder_id='folder-b'",
                    'UPDATE ordinary_folder_grant SET invalidated=1', 'UPDATE ordinary_folder_grant SET retired=1'):
            self.rejected(sql)
        select(self.db)
        self.db.execute("UPDATE ordinary_destination_state SET copy_cursor='cursor-only'")
        self.reopen()
        self.assertEqual(9223372036854775807, self.db.execute(SELECTION_READ).fetchone()[3])

    def test_selection_and_invalidation_rollback_together(self):
        select(self.db)
        self.db.commit()
        before = snapshot(self.db)
        self.db.execute('BEGIN')
        self.db.execute('UPDATE ordinary_folder_grant SET invalidated=1')
        self.assertEqual(('APP_LIBRARY', 1, None, 2), self.db.execute(SELECTION_READ).fetchone())
        self.db.rollback()
        self.reopen()
        self.assertEqual(before, snapshot(self.db))

    def test_pending_copies_exact_route_and_phase_set_independent_of_current_settings(self):
        phases = ('PREPARED', 'CREATE_INTENT', 'COPYING', 'VERIFYING', 'PUBLISH_INTENT', 'SUCCEEDED',
                  'COPIED_UNVERIFIED', 'CLEANUP_PENDING', 'FAILED', 'CANCELLED', 'RECONCILIATION_REQUIRED')
        for phase in phases:
            for choice in ('ALBUM_COPY', 'FOLDER_COPY'):
                name = choice + '-' + phase
                admit(self.db, name, choice, 'folder-a' if choice == 'FOLDER_COPY' else None)
                export(self.db, 'op-' + name, phase)
        admit(self.db, 'folder-no-export')
        admit(self.db, 'album-no-export', 'ALBUM_COPY', None)
        admit(self.db, 'private', 'APP_LIBRARY', None)
        admit(self.db, 'unpublished', bound=False)
        expected = ['ALBUM_COPY-PREPARED', 'FOLDER_COPY-PREPARED', 'album-no-export', 'folder-no-export']
        self.assertEqual(expected, [r[0] for r in self.db.execute(PENDING, ('', '64'))])
        select(self.db)
        self.db.execute('UPDATE ordinary_folder_grant SET retired=1,invalidated=1')
        # Pending SQL retains old identity for coordinator/registry error handling; it does not authorize I/O.
        self.assertEqual(expected, [r[0] for r in self.db.execute(PENDING, ('', '64'))])
        self.reopen()
        self.assertEqual(expected, [r[0] for r in self.db.execute(PENDING, ('', '64'))])

    def test_pending_cursor_pages_wrap_without_changing_selection_revision(self):
        for name in ('a', 'b', 'c'):
            admit(self.db, name)
        select(self.db)
        page = lambda cursor: [r[0] for r in self.db.execute(PENDING, (cursor, '2'))]
        self.assertEqual(['a', 'b'], page(''))
        self.assertEqual(['c'], page('b'))
        self.assertEqual([], page('c'))
        self.assertEqual(['a', 'b'], page('c') or page(''))
        self.db.execute("UPDATE ordinary_destination_state SET copy_cursor='c'")
        self.assertEqual(1, self.db.execute(SELECTION_READ).fetchone()[3])
        self.assertIn('val ids = page(cursor).ifEmpty { page("") }', JOURNAL)

    def test_readable_source_predicate_rejects_stale_identity_and_tombstones(self):
        self.assertEqual([(1,)], self.db.execute(READABLE, ('item', 7, 3)).fetchall())
        for ref in [('missing', 7, 3), ('item', 8, 3), ('item', 7, 4)]:
            self.assertEqual([], self.db.execute(READABLE, ref).fetchall())
        for table, field in [('library_item', 'lifecycle'), ('media_representation', 'state')]:
            self.db.execute('UPDATE ' + table + ' SET ' + field + "='DELETE_PENDING'")
            self.assertEqual([], self.db.execute(READABLE, ('item', 7, 3)).fetchall())
            self.db.execute('UPDATE ' + table + ' SET ' + field + "='READY'")

    def test_source_contract_registry_never_releases_or_treats_uri_as_authority(self):
        # Static review assertions only; Android authority calls are NOT executed here.
        self.assertNotIn('releasePersistableUriPermission(', REGISTRY + GRANTS)
        self.assertIn('if (!hasPersisted(uri)) invalidate(uri.toString())', REGISTRY)
        self.assertIn('storage.folderGrants.invalidateUri(uri)', REGISTRY)
        self.assertLess(REGISTRY.index('invalidate(uri.toString())'), REGISTRY.index('resolver.takePersistableUriPermission'))
        self.assertIn('context.checkUriPermission', REGISTRY)
        self.assertIn('require(!row.invalidated)', REGISTRY)
        self.assertIn('retired=0 AND invalidated=0', GRANTS)
        self.assertIn('prior.folderGrantId?.let { if ((FolderGrantRows.get(db, it) ?: invalid()).invalidated) invalid() }', JOURNAL)
        self.assertNotIn('UPDATE library_export', SCHEMA + GRANTS + JOURNAL)
        self.assertNotIn('DELETE FROM library_export', SCHEMA + GRANTS + JOURNAL)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--provisional', action='store_true', help='Explicit development run; cannot bypass an existing manifest')
    args = parser.parse_args()
    frozen_bytes = MANIFEST.read_bytes() if MANIFEST.exists() else None
    if frozen_bytes is None:
        if not args.provisional:
            parser.error('Final frozen manifest missing. Only an explicit --provisional run is permitted.')
        mode = 'PROVISIONAL ONLY (not frozen acceptance)'
        rows = [fingerprint(path) for path in sorted(candidate_membership())]
    else:
        manifest = json.loads(frozen_bytes)
        assert manifest['baseline'] == '9e9dea537c5f6e269adfb6791b47829eb4c440de'
        assert isinstance(manifest['expected_jvm_tests'], int) and manifest['expected_jvm_tests'] > 0
        rows = manifest['files']
        mode = 'FROZEN'
    print('Independent folder SQLite mode:', mode, flush=True)
    print('Host SQLite:', sqlite3.sqlite_version, '; Android runtime/permissions/provider I/O/device/playback: UNEXECUTED', flush=True)
    check_snapshot(rows, mode + ' BEFORE')
    try:
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(IndependentFolderSQLiteReview))
    finally:
        assert (MANIFEST.read_bytes() if MANIFEST.exists() else None) == frozen_bytes, 'Manifest changed during review'
        check_snapshot(rows, mode + ' AFTER')
    sys.exit(0 if result.wasSuccessful() else 1)
