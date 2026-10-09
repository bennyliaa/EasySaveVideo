#!/usr/bin/env python3
"""A4b synthetic host SQLite v6 -> v7 contracts using production DDL/selectors.

The frozen v5 fixture plus unchanged v6 production DDL supplies the historical
baseline. Real on-disk SQLite checks transactional rebuild, every DDL rollback
boundary, reopen, strict references, selection retirement and retained exports.
Admission/selection SQL are explicit contract doubles. This is not an Android
SQLiteOpenHelper, persisted-permission, provider IO, device, or playback pass.
"""
from pathlib import Path
import importlib.util
import re
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/kotlin/com/easysavevideo/app'
_spec = importlib.util.spec_from_file_location('historical_destination_sqlite', ROOT / 'scripts/verify-destination-sqlite.py')
legacy = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(legacy)
SCHEMA = (BASE / 'library/destination/FolderDestinationSchema.kt').read_text()
JOURNAL = (BASE / 'library/destination/AndroidOrdinaryDestinationJournal.kt').read_text()
REGISTRY = (BASE / 'library/destination/AndroidFolderGrantJournal.kt').read_text()
SAVE = (BASE / 'save/storage/AndroidSaveStorage.kt').read_text()
DDL = [a or b for a, b in re.findall(r'db\.execSQL\((?:"""(.*?)"""|"([^"\n]+)")\)', SCHEMA, re.S)]
assert len(DDL) == 26 and all('$' not in sql for sql in DDL)
PENDING = re.search(r'PENDING_COPY_SQL = "([^"]+)"', JOURNAL).group(1)
SELECTION_READ = re.search(r'override fun selection\(\).*?rawQuery\("""(.*?)"""', JOURNAL, re.S).group(1)
SOURCE_READ = re.search(r'fun get\(db: SQLiteDatabase, creationId: String\).*?rawQuery\("""(.*?)"""', JOURNAL, re.S).group(1)
FOLDER_READ = re.search(r'fun get\(db: SQLiteDatabase, id: String\).*?rawQuery\("""(.*?)"""', REGISTRY, re.S).group(1)


def migrate(db):
    for sql in DDL:
        db.execute(sql)
    db.execute('PRAGMA user_version=7')


def grant(db, identity='folder-a', uri='content://synthetic.documents/tree/a', flags=3, retired=0, invalidated=0):
    db.execute('INSERT INTO ordinary_folder_grant VALUES(?,?,?,?,?)', (identity, uri, flags, retired, invalidated))


def admit(db, creation='new', choice='FOLDER_COPY', folder='folder-a', bound=None):
    db.execute('INSERT INTO ordinary_destination VALUES(?,?,?,?,?,?,?,?)',
               (creation, choice, 'op-' + creation, 'grant-' + creation, bound, 7 if bound else None, 3 if bound else None, folder))
    db.execute('INSERT INTO ordinary_destination_publication VALUES(?,?)', (creation, creation))


def select(db, folder='folder-a', preference='FOLDER_COPY'):
    db.execute('UPDATE ordinary_destination_state SET preference=?,selected_folder_id=? WHERE singleton=1', (preference, folder))


class FolderDestinationSQLiteTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'folder.sqlite'
        self.db = sqlite3.connect(self.path)
        legacy.v5(self.db)
        with self.db:
            self.db.execute('BEGIN')
            legacy.migrate(self.db)

    def tearDown(self):
        self.db.close()
        self.temp.cleanup()

    def reopen(self):
        self.db.commit()
        self.db.close()
        self.db = sqlite3.connect(self.path)
        self.db.execute('PRAGMA foreign_keys=ON')
        self.assertEqual((1,), self.db.execute('PRAGMA foreign_keys').fetchone())
        self.assertEqual([], self.db.execute('PRAGMA foreign_key_check').fetchall())
        self.assertEqual(('ok',), self.db.execute('PRAGMA quick_check').fetchone())

    def upgraded(self):
        with self.db:
            self.db.execute('BEGIN')
            migrate(self.db)
            legacy.save_item(self.db, 'original')
            grant(self.db)

    def v6_history(self):
        with self.db:
            legacy.save_item(self.db, 'historic')
            for creation, choice, bound in [('album-old', 'ALBUM_COPY', 'historic'), ('private-old', 'APP_LIBRARY', None), ('recording-old', 'ALBUM_COPY', None)]:
                legacy.admission(self.db, creation, choice, bound)
            self.db.execute("INSERT INTO ordinary_destination_publication VALUES('processing-old','recording-old')")
            legacy.export(self.db, 'album-old', 'CLEANUP_PENDING')
            self.db.execute('INSERT INTO library_export_target VALUES(?,?,?,?,?,?)',
                            ('op-album-old', 'MEDIA_STORE', 'grant-album-old', legacy.uid(301), 'content://synthetic/old', 0))
            self.db.execute("UPDATE ordinary_destination_state SET preference='ASK',copy_cursor='album-old'")
            self.db.execute("INSERT INTO edit_job(operation_id,fingerprint,artifact_id,media_id,phase,request,created_at) VALUES('edit-old',?,?,?,'FAILED',?,0)",
                            ('b' * 64, legacy.uid(302), legacy.uid(303), b'edit-fixture'))
            self.db.execute("INSERT INTO edit_source(operation_id,sequence,item_id,revision,generation,start_us,end_us,size_bytes,sha256) VALUES('edit-old',0,'historic',7,3,0,1000,16,?)", ('a' * 64,))
            self.db.execute("INSERT INTO recording_session(session_id,generation,config,state,created_at) VALUES('recording-old',2,?,'RETAINED',0)", (b'config-fixture',))
            self.db.execute("INSERT INTO recording_segment(segment_id,session_id,sequence,state,effective_start_us) VALUES(?,'recording-old',0,'ALLOCATED',0)", (legacy.uid(304),))

    def test_v6_historical_rows_ids_sources_exports_links_and_settings_survive_v7(self):
        self.v6_history()
        before_schema, before_rows = legacy.snapshot(self.db)
        rowids = self.db.execute('SELECT rowid,creation_id FROM ordinary_destination').fetchall()
        with self.db:
            self.db.execute('BEGIN')
            migrate(self.db)
        self.reopen()
        after_schema, after_rows = legacy.snapshot(self.db)
        for name, sql in before_schema:
            if not name.startswith('ordinary_destination'):
                self.assertIn((name, sql), after_schema)
        for name, rows in before_rows.items():
            if name == 'ordinary_destination':
                self.assertEqual([row + (None,) for row in rows], after_rows[name])
            elif name == 'ordinary_destination_state':
                self.assertEqual([row + (None, 0) for row in rows], after_rows[name])
            else:
                self.assertEqual(rows, after_rows[name], name)
        self.assertEqual(rowids, self.db.execute('SELECT rowid,creation_id FROM ordinary_destination').fetchall())
        self.assertEqual([], after_rows['ordinary_folder_grant'])
        self.assertEqual((7,), self.db.execute('PRAGMA user_version').fetchone())
        refs = self.db.execute('PRAGMA foreign_key_list(ordinary_destination_publication)').fetchall()
        self.assertEqual('ordinary_destination', refs[0][2])
        self.assertFalse(any('_v7' in name for name, _ in after_schema))

    def test_empty_v6_upgrade_does_not_create_historical_admissions(self):
        with self.db:
            legacy.save_item(self.db, 'historic')
            migrate(self.db)
        self.reopen()
        for table in ('ordinary_destination', 'ordinary_destination_publication', 'ordinary_folder_grant'):
            self.assertEqual([], self.db.execute('SELECT * FROM ' + table).fetchall())
        self.assertEqual([(1, 'APP_LIBRARY', '', None, 0)], self.db.execute('SELECT * FROM ordinary_destination_state').fetchall())

    def test_each_migration_boundary_rolls_back_ddl_data_links_and_version(self):
        self.v6_history()
        before = legacy.snapshot(self.db)
        for boundary in range(len(DDL) + 1):
            with self.assertRaises(RuntimeError):
                with self.db:
                    self.db.execute('BEGIN')
                    for index, sql in enumerate(DDL):
                        self.db.execute(sql)
                        if index == boundary:
                            raise RuntimeError('SYNTHETIC_DDL_FAULT')
                    self.db.execute('PRAGMA user_version=7')
                    raise RuntimeError('SYNTHETIC_VERSION_FAULT')
            self.assertEqual(before, legacy.snapshot(self.db), boundary)
            self.assertEqual((6,), self.db.execute('PRAGMA user_version').fetchone())
            self.reopen()
            self.assertEqual(before, legacy.snapshot(self.db), boundary)

    def test_corrupt_v6_choice_rejects_rebuild_and_preserves_original_version(self):
        self.v6_history()
        self.db.execute('PRAGMA ignore_check_constraints=ON')
        self.db.execute("DROP TRIGGER ordinary_destination_immutable")
        self.db.execute("UPDATE ordinary_destination SET choice='UNKNOWN' WHERE creation_id='album-old'")
        self.db.commit()
        self.db.execute('PRAGMA ignore_check_constraints=OFF')
        before = legacy.snapshot(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute('BEGIN')
                migrate(self.db)
        self.assertEqual(before, legacy.snapshot(self.db))
        self.assertEqual((6,), self.db.execute('PRAGMA user_version').fetchone())
        self.assertEqual([], self.db.execute("SELECT name FROM sqlite_master WHERE name LIKE '%_v7'").fetchall())

    def test_v7_helper_upgrade_branches_and_foreign_keys_are_explicit(self):
        self.assertIn('file.absolutePath, null, 7,', SAVE)
        self.assertIn('oldVersion !in 1..6 || newVersion != 7', SAVE)
        self.assertIn('if (oldVersion < 6) OrdinaryDestinationSchema.create(db)', SAVE)
        self.assertEqual(2, SAVE.count('FolderDestinationSchema.migrate(db)'))
        self.assertIn('db.setForeignKeyConstraintsEnabled(db.version !in 1..3)', SAVE)
        self.assertIn('check(db.inTransaction())', SCHEMA)
        self.assertNotIn('foreign_keys=OFF', SCHEMA)

    def test_folder_choice_requires_exact_folder_ref_and_distinct_operation_grant(self):
        self.upgraded()
        for choice, folder in [('FOLDER_COPY', None), ('FOLDER_COPY', 'absent'), ('APP_LIBRARY', 'folder-a'), ('ALBUM_COPY', 'folder-a'), ('ASK', None)]:
            with self.assertRaises(sqlite3.IntegrityError):
                admit(self.db, choice=choice, folder=folder)
        grant(self.db, 'grant-new', 'content://synthetic.documents/tree/b')
        with self.assertRaises(sqlite3.IntegrityError):
            admit(self.db, folder='grant-new')
        admit(self.db)
        self.reopen()
        self.assertEqual(('folder-a', 'grant-new'), self.db.execute('SELECT folder_grant_id,grant_id FROM ordinary_destination').fetchone())

    def test_snapshot_and_operation_identity_are_immutable_after_selection_changes(self):
        self.upgraded()
        admit(self.db, bound='original')
        grant(self.db, 'folder-b', 'content://synthetic.documents/tree/b')
        select(self.db, 'folder-b')
        for clause in ("folder_grant_id='folder-b'", 'folder_grant_id=NULL', "operation_id='different'", "grant_id='different'", "choice='APP_LIBRARY',folder_grant_id=NULL", 'source_revision=8'):
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute('UPDATE ordinary_destination SET ' + clause)
        self.reopen()
        self.assertEqual(('folder-a', 7, 3), self.db.execute('SELECT folder_grant_id,source_revision,source_generation FROM ordinary_destination').fetchone())
        self.assertEqual(('folder-b',), self.db.execute('SELECT selected_folder_id FROM ordinary_destination_state').fetchone())

    def test_unknown_folder_preference_missing_or_retired_selection_is_rejected(self):
        self.upgraded()
        for pref, folder in [('FOLDER_COPY', None), ('FOLDER_COPY', 'absent'), ('folder_copy', 'folder-a'), ('VAULT', None)]:
            with self.assertRaises(sqlite3.IntegrityError):
                select(self.db, folder, pref)
        self.db.execute("UPDATE ordinary_folder_grant SET retired=1")
        with self.assertRaises(sqlite3.IntegrityError):
            select(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            admit(self.db)

    def test_nonfolder_preferences_keep_remembered_folder_without_mutating_admission(self):
        self.upgraded()
        admit(self.db)
        select(self.db)
        for preference in ('APP_LIBRARY', 'ASK', 'ALBUM_COPY'):
            self.db.execute('UPDATE ordinary_destination_state SET preference=?', (preference,))
            self.reopen()
            self.assertEqual((preference, 'folder-a'), self.db.execute('SELECT preference,selected_folder_id FROM ordinary_destination_state').fetchone())
            self.assertEqual(('FOLDER_COPY', 'folder-a'), self.db.execute('SELECT choice,folder_grant_id FROM ordinary_destination').fetchone())

    def test_retirement_clears_selection_but_keeps_admitted_sources_exports_and_cleanup(self):
        self.upgraded()
        select(self.db)
        admit(self.db, bound='original')
        legacy.export(self.db, 'new', 'CLEANUP_PENDING')
        self.db.execute('INSERT INTO library_export_target VALUES(?,?,?,?,?,?)',
                        ('op-new', 'SAF', 'grant-new', legacy.uid(401), 'content://synthetic.documents/document/copy', 0))
        before = {t: self.db.execute('SELECT * FROM ' + t).fetchall() for t in ('ordinary_destination', 'ordinary_destination_publication', 'library_export', 'library_export_target', 'library_item', 'media_representation')}
        self.db.execute("UPDATE ordinary_folder_grant SET retired=1 WHERE folder_grant_id='folder-a'")
        self.reopen()
        self.assertEqual(('APP_LIBRARY', None), self.db.execute('SELECT preference,selected_folder_id FROM ordinary_destination_state').fetchone())
        self.assertEqual(('content://synthetic.documents/tree/a', 3, 1, 0), self.db.execute(FOLDER_READ, ('folder-a',)).fetchone())
        for table, rows in before.items():
            self.assertEqual(rows, self.db.execute('SELECT * FROM ' + table).fetchall(), table)
        with self.assertRaises(sqlite3.IntegrityError):
            admit(self.db, 'later')
        self.assertEqual([], self.db.execute(PENDING, ('', 32)).fetchall())

    def test_retirement_is_atomic_with_matching_selection_and_does_not_touch_other_folder(self):
        self.upgraded()
        select(self.db)
        self.db.commit()
        before = legacy.snapshot(self.db)
        with self.assertRaises(RuntimeError):
            with self.db:
                self.db.execute("UPDATE ordinary_folder_grant SET retired=1 WHERE folder_grant_id='folder-a'")
                raise RuntimeError('SYNTHETIC_FORGET_FAULT')
        self.reopen()
        self.assertEqual(before, legacy.snapshot(self.db))
        grant(self.db, 'folder-b', 'content://synthetic.documents/tree/b')
        select(self.db, 'folder-b')
        self.db.execute("UPDATE ordinary_folder_grant SET retired=1 WHERE folder_grant_id='folder-a'")
        self.reopen()
        self.assertEqual(('FOLDER_COPY', 'folder-b'), self.db.execute('SELECT preference,selected_folder_id FROM ordinary_destination_state').fetchone())

    def test_retired_prepared_operation_remains_eligible_without_rebinding_selection(self):
        self.upgraded()
        select(self.db)
        admit(self.db, bound='original')
        legacy.export(self.db, 'new', 'PREPARED')
        self.db.execute("UPDATE ordinary_folder_grant SET retired=1")
        self.reopen()
        self.assertEqual([('new',)], self.db.execute(PENDING, ('', 32)).fetchall())
        self.assertEqual(('folder-a',), self.db.execute("SELECT folder_grant_id FROM ordinary_destination WHERE operation_id='op-new'").fetchone())
        self.assertIn('prior.choice != choice || prior.folderGrantId != folderGrantId', JOURNAL)
        self.assertLess(JOURNAL.index('return@transaction prior'), JOURNAL.index('folderGrantId?.let { requireActiveFolder'))

    def test_folder_rows_require_bounded_text_exact_flags_and_monotonic_retirement(self):
        self.upgraded()
        for uri, flags, retired in [('', 3, 0), ('x' * 8193, 3, 0), ('界' * 3000, 3, 0), ('content://a\x00tail', 3, 0),
                                     (b'content://binary', 3, 0), ('content://x/y', 0, 0), ('content://x/y', 67, 0),
                                     ('content://x/y', 3, 2), ('content://x/y', 1.5, 0)]:
            with self.assertRaises(sqlite3.IntegrityError):
                grant(self.db, 'bad', uri, flags, retired)
        for clause in ("tree_uri='content://changed/tree/a'", 'flags=1', "folder_grant_id='other'"):
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute('UPDATE ordinary_folder_grant SET ' + clause)
        self.db.execute('UPDATE ordinary_folder_grant SET retired=1')
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('UPDATE ordinary_folder_grant SET retired=0')
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('DELETE FROM ordinary_folder_grant')

    def test_active_exact_uri_and_flags_are_unique_retired_rows_never_reused(self):
        self.upgraded()
        with self.assertRaises(sqlite3.IntegrityError):
            grant(self.db, 'duplicate')
        self.db.execute('UPDATE ordinary_folder_grant SET retired=1')
        grant(self.db, 'fresh-id')
        self.reopen()
        self.assertEqual([('folder-a', 1), ('fresh-id', 0)], self.db.execute('SELECT folder_grant_id,retired FROM ordinary_folder_grant ORDER BY rowid').fetchall())
        self.assertIn('findActive(db, uri, flags)?.let { return@transaction it }', REGISTRY)
        self.assertIn('fun availableCapacity(uri: String, flags: Int)', REGISTRY)

    def test_retained_row_cap_is_enforced_even_after_retirement(self):
        self.upgraded()
        for index in range(127):
            grant(self.db, 'folder-' + str(index), 'content://synthetic.documents/tree/' + str(index))
        self.db.execute('UPDATE ordinary_folder_grant SET retired=1')
        with self.assertRaises(sqlite3.IntegrityError):
            grant(self.db, 'too-many', 'content://synthetic.documents/tree/overflow')
        self.reopen()
        self.assertEqual((128,), self.db.execute('SELECT COUNT(*) FROM ordinary_folder_grant').fetchone())

    def test_folder_private_publication_and_binding_roll_back_together(self):
        self.upgraded()
        admit(self.db)
        self.db.commit()
        with self.assertRaises(RuntimeError):
            with self.db:
                legacy.save_item(self.db, 'candidate')
                self.db.execute("UPDATE ordinary_destination SET source_item='candidate',source_revision=7,source_generation=3 WHERE creation_id='new'")
                raise RuntimeError('SYNTHETIC_PUBLICATION_FAULT')
        self.reopen()
        self.assertEqual([], self.db.execute("SELECT * FROM library_item WHERE item_id='candidate'").fetchall())
        self.assertEqual((None, None, None, 'folder-a'), self.db.execute('SELECT source_item,source_revision,source_generation,folder_grant_id FROM ordinary_destination').fetchone())
        self.assertEqual([], self.db.execute(PENDING, ('', 32)).fetchall())

    def test_pending_only_routes_bound_album_or_folder_with_absent_or_prepared_export(self):
        self.upgraded()
        admit(self.db, 'unbound')
        admit(self.db, 'private', 'APP_LIBRARY', None, 'original')
        admit(self.db, 'album', 'ALBUM_COPY', None, 'original')
        admit(self.db, 'none', bound='original')
        for phase in ('PREPARED', 'CREATE_INTENT', 'COPYING', 'VERIFYING', 'PUBLISH_INTENT', 'SUCCEEDED',
                      'COPIED_UNVERIFIED', 'CLEANUP_PENDING', 'FAILED', 'CANCELLED', 'RECONCILIATION_REQUIRED'):
            admit(self.db, phase, bound='original')
            legacy.export(self.db, phase, phase)
        self.reopen()
        self.assertEqual([('PREPARED',), ('album',), ('none',)], self.db.execute(PENDING, ('', 32)).fetchall())

    def test_operation_grants_are_unique_even_when_folder_selection_is_shared(self):
        self.upgraded()
        admit(self.db, 'a')
        admit(self.db, 'b')
        self.assertEqual([('folder-a', 'grant-a'), ('folder-a', 'grant-b')], self.db.execute('SELECT folder_grant_id,grant_id FROM ordinary_destination ORDER BY rowid').fetchall())
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO ordinary_destination VALUES('c','FOLDER_COPY','op-c','grant-a',NULL,NULL,NULL,'folder-a')")

    def test_bounded_decoder_refuses_corrupt_folder_types_before_returning_text(self):
        self.upgraded()
        self.db.execute('PRAGMA ignore_check_constraints=ON')
        self.db.execute('DROP TRIGGER ordinary_folder_grant_immutable')
        for value in (b'content://binary/tree/a', 'content://' + 'x' * 8192, '界' * 3000):
            self.db.execute("UPDATE ordinary_folder_grant SET tree_uri=? WHERE folder_grant_id='folder-a'", (value,))
            self.assertIsNone(self.db.execute(FOLDER_READ, ('folder-a',)).fetchone()[0])
        self.db.execute("UPDATE ordinary_folder_grant SET tree_uri='content://synthetic/tree/a',flags=67,retired=5")
        row = self.db.execute(FOLDER_READ, ('folder-a',)).fetchone()
        self.assertIsNone(row[1])
        self.assertIsNone(row[2])
        self.db.execute('PRAGMA ignore_check_constraints=OFF')

    def test_source_version_schema_rejects_fractional_real_text_blob_and_nonpositive_values(self):
        self.upgraded()
        admit(self.db)
        for revision, generation in [(bad, 3) for bad in (7.75, 'invalid', b'7', 0, -1)] + [(7, bad) for bad in (3.75, 'invalid', b'3', 0, -1)]:
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute("UPDATE ordinary_destination SET source_item='original',source_revision=?,source_generation=?", (revision, generation))
        self.assertEqual((None, None, None), self.db.execute('SELECT source_item,source_revision,source_generation FROM ordinary_destination').fetchone())
        self.db.execute("UPDATE ordinary_destination SET source_item='original',source_revision=7,source_generation=3")
        self.reopen()
        self.assertEqual(('integer', 'integer'), self.db.execute('SELECT typeof(source_revision),typeof(source_generation) FROM ordinary_destination').fetchone())

    def test_source_decoder_flags_bad_storage_types_before_getlong_can_truncate(self):
        self.upgraded()
        admit(self.db, bound='original')
        self.db.execute('DROP TRIGGER ordinary_destination_immutable')
        self.db.execute('PRAGMA ignore_check_constraints=ON')
        for field, flag_index in [('source_revision', 9), ('source_generation', 10)]:
            for bad in (7.75, 'invalid', b'7', 0, -1):
                self.db.execute('UPDATE ordinary_destination SET ' + field + '=?', (bad,))
                self.assertEqual(0, self.db.execute(SOURCE_READ, ('new',)).fetchone()[flag_index])
            self.db.execute('UPDATE ordinary_destination SET ' + field + '=7')
        self.db.execute('UPDATE ordinary_destination SET source_item=NULL,source_revision=NULL,source_generation=NULL')
        self.assertEqual((1, 1), self.db.execute(SOURCE_READ, ('new',)).fetchone()[9:11])
        self.assertLess(JOURNAL.index('it.getInt(9) != 1'), JOURNAL.index('if (it.isNull(5)) null else it.getLong(5)'))
        self.db.execute('PRAGMA ignore_check_constraints=OFF')

    def test_v6_fractional_source_is_rejected_without_partial_v7_migration(self):
        self.v6_history()
        self.db.execute('DROP TRIGGER ordinary_destination_immutable')
        self.db.execute("UPDATE ordinary_destination SET source_revision=7.75 WHERE creation_id='album-old'")
        self.db.commit()
        before = legacy.snapshot(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute('BEGIN')
                migrate(self.db)
        self.assertEqual(before, legacy.snapshot(self.db))
        self.assertEqual((6,), self.db.execute('PRAGMA user_version').fetchone())
        self.assertEqual(('real',), self.db.execute("SELECT typeof(source_revision) FROM ordinary_destination WHERE creation_id='album-old'").fetchone())

    def test_invalidation_clears_selection_and_marks_all_matching_uri_generations(self):
        self.upgraded()
        uri = 'content://synthetic.documents/tree/a'
        grant(self.db, 'retired-a', uri, 3, 1)
        grant(self.db, 'read-only-a', uri, 1)
        grant(self.db, 'unrelated', 'content://synthetic.documents/tree/b')
        select(self.db)
        admit(self.db, bound='original')
        legacy.export(self.db, 'new', 'PREPARED')
        before = {table: self.db.execute('SELECT * FROM ' + table).fetchall() for table in ('ordinary_destination', 'ordinary_destination_publication', 'library_export', 'library_item')}
        self.db.execute('UPDATE ordinary_folder_grant SET invalidated=1 WHERE tree_uri=? AND invalidated=0', (uri,))
        self.reopen()
        self.assertEqual(('APP_LIBRARY', None), self.db.execute('SELECT preference,selected_folder_id FROM ordinary_destination_state').fetchone())
        self.assertEqual([('folder-a', 0, 1), ('retired-a', 1, 1), ('read-only-a', 0, 1), ('unrelated', 0, 0)],
                         self.db.execute('SELECT folder_grant_id,retired,invalidated FROM ordinary_folder_grant ORDER BY rowid').fetchall())
        for table, rows in before.items():
            self.assertEqual(rows, self.db.execute('SELECT * FROM ' + table).fetchall())
        with self.assertRaises(sqlite3.IntegrityError):
            select(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            admit(self.db, 'later')

    def test_same_uri_regrant_gets_fresh_identity_and_cannot_resurrect_old_operation(self):
        self.upgraded()
        uri = 'content://synthetic.documents/tree/a'
        admit(self.db, bound='original')
        self.db.execute('UPDATE ordinary_folder_grant SET invalidated=1 WHERE tree_uri=?', (uri,))
        grant(self.db, 'new-generation', uri)
        select(self.db, 'new-generation')
        admit(self.db, 'new-work', folder='new-generation')
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE ordinary_folder_grant SET invalidated=0 WHERE folder_grant_id='folder-a'")
        with self.assertRaises(sqlite3.IntegrityError):
            grant(self.db, 'duplicate-new-generation', uri)
        self.reopen()
        self.assertEqual([('new', 'folder-a'), ('new-work', 'new-generation')], self.db.execute('SELECT creation_id,folder_grant_id FROM ordinary_destination ORDER BY rowid').fetchall())
        self.assertEqual(1, self.db.execute(FOLDER_READ, ('folder-a',)).fetchone()[3])
        self.assertEqual(0, self.db.execute(FOLDER_READ, ('new-generation',)).fetchone()[3])
        self.assertIn('retired=0 AND invalidated=0', REGISTRY)
        self.assertIn('prior.folderGrantId?.let { if ((FolderGrantRows.get(db, it) ?: invalid()).invalidated) invalid() }', JOURNAL)

    def test_invalidation_rollback_restores_all_rows_and_remembered_selection(self):
        self.upgraded()
        grant(self.db, 'retired-a', retired=1)
        select(self.db)
        self.db.commit()
        before = legacy.snapshot(self.db)
        with self.assertRaises(RuntimeError):
            with self.db:
                self.db.execute("UPDATE ordinary_folder_grant SET invalidated=1 WHERE tree_uri='content://synthetic.documents/tree/a'")
                raise RuntimeError('SYNTHETIC_INVALIDATION_FAULT')
        self.reopen()
        self.assertEqual(before, legacy.snapshot(self.db))

    def test_invalidation_state_is_strict_and_retirement_alone_does_not_revoke_existing_work(self):
        self.upgraded()
        admit(self.db)
        for bad in (-1, 2, 1.5, 'invalid', b'1'):
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute('UPDATE ordinary_folder_grant SET invalidated=?', (bad,))
        self.db.execute('UPDATE ordinary_folder_grant SET retired=1')
        self.reopen()
        self.assertEqual((1, 0), self.db.execute(FOLDER_READ, ('folder-a',)).fetchone()[2:4])
        self.db.execute('PRAGMA ignore_check_constraints=ON')
        self.db.execute('DROP TRIGGER ordinary_folder_grant_immutable')
        self.db.execute('UPDATE ordinary_folder_grant SET invalidated=2')
        self.assertIsNone(self.db.execute(FOLDER_READ, ('folder-a',)).fetchone()[3])
        self.db.execute('PRAGMA ignore_check_constraints=OFF')

    def test_invalidated_rows_still_consume_capacity_and_cannot_be_deleted(self):
        self.upgraded()
        for index in range(127):
            grant(self.db, 'folder-' + str(index), 'content://synthetic.documents/tree/' + str(index))
        self.db.execute('UPDATE ordinary_folder_grant SET invalidated=1')
        with self.assertRaises(sqlite3.IntegrityError):
            grant(self.db, 'new-generation')
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('DELETE FROM ordinary_folder_grant')
        self.reopen()
        self.assertEqual((128,), self.db.execute('SELECT COUNT(*) FROM ordinary_folder_grant WHERE invalidated=1').fetchone())

    def test_selection_revision_is_monotonic_and_unchanged_settings_do_not_increment(self):
        self.upgraded()
        self.assertEqual(0, self.db.execute(SELECTION_READ).fetchone()[3])
        select(self.db)
        self.assertEqual(1, self.db.execute(SELECTION_READ).fetchone()[3])
        select(self.db)
        self.db.execute("UPDATE ordinary_destination_state SET copy_cursor='different'")
        self.assertEqual(1, self.db.execute(SELECTION_READ).fetchone()[3])
        self.db.execute("UPDATE ordinary_destination_state SET preference='ASK'")
        self.assertEqual(2, self.db.execute(SELECTION_READ).fetchone()[3])
        grant(self.db, 'folder-b', 'content://synthetic.documents/tree/b')
        select(self.db, 'folder-b')
        self.assertEqual(3, self.db.execute(SELECTION_READ).fetchone()[3])
        self.reopen()
        self.assertEqual(('FOLDER_COPY', 1, 'folder-b', 3), self.db.execute(SELECTION_READ).fetchone())

    def test_invalidation_and_retirement_increment_selection_revision_atomically(self):
        self.upgraded()
        select(self.db)
        self.db.execute("UPDATE ordinary_folder_grant SET invalidated=1 WHERE folder_grant_id='folder-a'")
        self.assertEqual(('APP_LIBRARY', 1, None, 2), self.db.execute(SELECTION_READ).fetchone())
        self.db.execute("UPDATE ordinary_folder_grant SET invalidated=1 WHERE folder_grant_id='folder-a'")
        self.assertEqual(2, self.db.execute(SELECTION_READ).fetchone()[3])
        grant(self.db, 'new-generation')
        select(self.db, 'new-generation')
        self.db.execute("UPDATE ordinary_folder_grant SET retired=1 WHERE folder_grant_id='new-generation'")
        self.reopen()
        self.assertEqual(('APP_LIBRARY', 1, None, 4), self.db.execute(SELECTION_READ).fetchone())

    def test_selection_revision_and_setting_rollback_together(self):
        self.upgraded()
        select(self.db)
        self.db.commit()
        before = self.db.execute(SELECTION_READ).fetchone()
        with self.assertRaises(RuntimeError):
            with self.db:
                self.db.execute("UPDATE ordinary_destination_state SET preference='ASK'")
                self.assertEqual(2, self.db.execute(SELECTION_READ).fetchone()[3])
                raise RuntimeError('SYNTHETIC_SETTING_FAULT')
        self.reopen()
        self.assertEqual(before, self.db.execute(SELECTION_READ).fetchone())

    def test_selection_revision_overflow_fails_closed_for_setting_and_invalidation(self):
        self.upgraded()
        select(self.db)
        self.db.execute('UPDATE ordinary_destination_state SET selection_revision=9223372036854775807')
        self.db.commit()
        before = legacy.snapshot(self.db)
        for sql in ("UPDATE ordinary_destination_state SET preference='ASK'", "UPDATE ordinary_folder_grant SET invalidated=1"):
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute(sql)
            self.assertEqual(before, legacy.snapshot(self.db))
        select(self.db)
        self.reopen()
        self.assertEqual(9223372036854775807, self.db.execute(SELECTION_READ).fetchone()[3])

    def test_selection_revision_type_and_decoder_reject_invalid_values(self):
        self.upgraded()
        for bad in (-1, 1.5, 'invalid', b'1'):
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute('UPDATE ordinary_destination_state SET selection_revision=?', (bad,))
        self.db.execute('PRAGMA ignore_check_constraints=ON')
        for bad in (-1, 1.5, 'invalid', b'1'):
            self.db.execute('UPDATE ordinary_destination_state SET selection_revision=?', (bad,))
            self.assertIsNone(self.db.execute(SELECTION_READ).fetchone()[3])
        self.db.execute('UPDATE ordinary_destination_state SET selection_revision=0')
        self.db.execute('PRAGMA ignore_check_constraints=OFF')

    def test_registry_and_journal_do_not_release_os_grants_or_rewrite_existing_export_protocol(self):
        self.assertNotIn('releasePersistableUriPermission(', REGISTRY + JOURNAL)
        self.assertNotIn('ContentResolver', REGISTRY + JOURNAL)
        self.assertNotIn('ExportSchema', SCHEMA)
        self.assertNotIn('UPDATE library_export', SCHEMA + REGISTRY + JOURNAL)
        self.assertNotIn('DELETE FROM library_export', SCHEMA + REGISTRY + JOURNAL)


if __name__ == '__main__':
    unittest.main(verbosity=2)
