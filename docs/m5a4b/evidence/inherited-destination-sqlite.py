#!/usr/bin/env python3
"""Synthetic host SQLite contracts for ordinary destinations (v6).

Executes extracted production schema/selectors and a frozen a3a028d v5 fixture
against real host SQLite, including on-disk close/reopen and injected rollback.
Publication/admission statements below are explicit SQL contract doubles, not an
Android SQLiteOpenHelper, Kotlin adapter, MediaStore, or playable-media pass.
No device, permission, original-media file or real account is used.
"""
from pathlib import Path
import re
import sqlite3
import tempfile
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / 'app/src/main/kotlin/com/easysavevideo/app'
SCHEMA = (BASE / 'library/destination/OrdinaryDestinationSchema.kt').read_text()
JOURNAL = (BASE / 'library/destination/AndroidOrdinaryDestinationJournal.kt').read_text()
SAVE = (BASE / 'save/storage/AndroidSaveStorage.kt').read_text()
EDIT = (BASE / 'editor/storage/AndroidEditStorage.kt').read_text()
FROZEN_V5 = (ROOT / 'app/src/test/resources/destination/frozen-v5-schema.sql').read_text()
DDL = [a or b for a, b in re.findall(r'db\.execSQL\((?:"""(.*?)"""|"([^"\n]+)")\)', SCHEMA, re.S)]
assert len(DDL) == 8 and all('$' not in sql for sql in DDL)
PENDING = re.search(r'PENDING_COPY_SQL = "([^"]+)"', JOURNAL).group(1)
ADMISSION_EXISTS = re.search(r'rawQuery\("""(SELECT 1 FROM save_tasks.*?)"""', JOURNAL, re.S).group(1)


def uid(number):
    return str(uuid.UUID(int=number))


def v5(db):
    db.execute('PRAGMA foreign_keys=ON')
    db.executescript(FROZEN_V5)


def migrate(db):
    for sql in DDL:
        db.execute(sql)
    db.execute('PRAGMA user_version=6')


def save_item(db, identity, state='SUCCEEDED'):
    db.execute('INSERT INTO save_tasks VALUES(?,?,?,?,?,?)',
               ('task-' + identity, 'key-' + identity, 7, state, 'commit-' + identity, b'save-fixture'))
    db.execute('INSERT INTO save_owned VALUES(?,?,?,?,?,?)',
               ('commit-' + identity, 'task-' + identity, 'artifact-' + identity, identity, 'INDEXED', b'evidence-fixture'))
    db.execute('INSERT INTO save_media VALUES(?,?,?,?,?,?)',
               (identity, 'commit-' + identity, 'artifact-' + identity, 16, 'a' * 64, int(state == 'SUCCEEDED')))
    db.execute('''INSERT INTO library_item(item_id,origin_commit_id,revision,lifecycle,privacy_class,
        representation_id,generation,display_name,name_key,created_at,date_kind)
        VALUES(?,?,7,'READY','ORDINARY',?,3,?,?,0,'SAVED')''',
               (identity, 'commit-' + identity, 'rep-' + identity, identity, identity))
    db.execute('INSERT INTO media_representation VALUES(?,?,?,?,?,?,?,?)',
               ('rep-' + identity, identity, 3, 'ORDINARY_FILE_V1', uid(len(identity) + sum(map(ord, identity))), 'READY', 16, 'a' * 64))


def admission(db, creation='new', choice='ALBUM_COPY', bound=None):
    db.execute('INSERT INTO ordinary_destination VALUES(?,?,?,?,?,?,?)',
               (creation, choice, 'op-' + creation, 'grant-' + creation, bound, 7 if bound else None, 3 if bound else None))
    db.execute('INSERT INTO ordinary_destination_publication VALUES(?,?)', (creation, creation))


def export(db, creation, phase):
    db.execute('INSERT INTO library_export VALUES(?,?,?,?,?)',
               ('op-' + creation, 'e' * 64, phase, 0, b'export-fixture'))


def snapshot(db):
    schema = list(db.execute("SELECT name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name"))
    tables = [name for name, in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
    return schema, {name: list(db.execute('SELECT * FROM ' + name + ' ORDER BY rowid')) for name in tables}


class OrdinaryDestinationSQLiteTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'destination.sqlite'
        self.db = sqlite3.connect(self.path)
        v5(self.db)

    def tearDown(self):
        self.db.close()
        self.temp.cleanup()

    def reopen(self):
        self.db.commit()
        self.db.close()
        self.db = sqlite3.connect(self.path)
        self.db.execute('PRAGMA foreign_keys=ON')

    def fresh(self):
        with self.db:
            migrate(self.db)
            save_item(self.db, 'original')

    def test_v5_exact_legacy_rows_and_schema_survive_v6_reopen_without_backfill(self):
        with self.db:
            save_item(self.db, 'historic')
            self.db.execute("INSERT INTO edit_job(operation_id,fingerprint,artifact_id,media_id,phase,request,created_at) VALUES('edit-old',?,?,?,'FAILED',?,0)",
                            ('b' * 64, uid(100), uid(101), b'edit-fixture'))
            self.db.execute("INSERT INTO edit_source(operation_id,sequence,item_id,revision,generation,start_us,end_us,size_bytes,sha256) VALUES('edit-old',0,'historic',7,3,0,1000,16,?)", ('a' * 64,))
            self.db.execute("INSERT INTO recording_session(session_id,generation,config,state,created_at) VALUES('session-old',2,?,'RETAINED',0)", (b'config-fixture',))
            self.db.execute("INSERT INTO recording_segment(segment_id,session_id,sequence,state,effective_start_us) VALUES(?,'session-old',0,'ALLOCATED',0)", (uid(102),))
            export(self.db, 'history', 'RECONCILIATION_REQUIRED')
            self.db.execute('INSERT INTO library_export_target VALUES(?,?,?,?,?,?)',
                            ('op-history', 'MEDIA_STORE', 'historic-grant', uid(103), 'content://synthetic/1', 0))
        before_schema, before_rows = snapshot(self.db)
        with self.db:
            migrate(self.db)
        self.reopen()
        after_schema, after_rows = snapshot(self.db)
        for name, sql in before_schema:
            self.assertIn((name, sql), after_schema)
        for name, rows in before_rows.items():
            self.assertEqual(rows, after_rows[name], name)
        self.assertEqual([], after_rows['ordinary_destination'])
        self.assertEqual([], after_rows['ordinary_destination_publication'])
        self.assertEqual([(1, 'APP_LIBRARY', '')], after_rows['ordinary_destination_state'])
        self.assertEqual((6,), self.db.execute('PRAGMA user_version').fetchone())
        self.assertEqual([], list(self.db.execute('PRAGMA foreign_key_check')))
        self.assertEqual(('ok',), self.db.execute('PRAGMA quick_check').fetchone())

    def test_every_migration_boundary_rolls_back_version_tables_and_rows(self):
        with self.db:
            save_item(self.db, 'historic')
        before = snapshot(self.db)
        for boundary in range(len(DDL) + 1):
            with self.assertRaises(RuntimeError):
                with self.db:
                    self.db.execute('BEGIN')
                    for index, sql in enumerate(DDL):
                        self.db.execute(sql)
                        if index == boundary:
                            raise RuntimeError('SYNTHETIC_FAULT')
                    self.db.execute('PRAGMA user_version=6')
                    raise RuntimeError('SYNTHETIC_COMMIT_FAULT')
            self.assertEqual(before, snapshot(self.db))
            self.assertEqual((5,), self.db.execute('PRAGMA user_version').fetchone())
        self.reopen()
        self.assertEqual(before, snapshot(self.db))

    def test_upgrade_branches_do_not_recreate_v5_recording_or_source_tables(self):
        self.assertIn('oldVersion !in 1..6 || newVersion != 7', SAVE)
        self.assertIn('if (oldVersion < 5) RecordedSegmentSchema.create(db)', SAVE)
        self.assertIn('else if (oldVersion == 4) EditSchema.migrateRecordedSources(db)', SAVE)
        self.assertEqual(2, SAVE.count('OrdinaryDestinationSchema.create(db)'))
        self.assertNotIn('backfill', SCHEMA.lower())

    def test_preference_is_durable_and_never_rebinds_existing_admission(self):
        self.fresh()
        admission(self.db, choice='APP_LIBRARY', bound='original')
        before = self.db.execute('SELECT * FROM ordinary_destination').fetchall()
        self.db.execute("UPDATE ordinary_destination_state SET preference='ALBUM_COPY'")
        self.reopen()
        self.assertEqual(('ALBUM_COPY',), self.db.execute('SELECT preference FROM ordinary_destination_state').fetchone())
        self.assertEqual(before, self.db.execute('SELECT * FROM ordinary_destination').fetchall())
        self.assertEqual([], self.db.execute(PENDING, ('', 32)).fetchall())

    def test_unknown_choice_or_preference_and_partial_source_are_rejected(self):
        self.fresh()
        for choice in ('ASK', 'UNKNOWN', 'album_copy', ''):
            with self.assertRaises(sqlite3.IntegrityError):
                admission(self.db, choice=choice)
        for preference in ('UNKNOWN', '', 'album_copy'):
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute('UPDATE ordinary_destination_state SET preference=?', (preference,))
        admission(self.db)
        for clause in ("source_item='original'", 'source_revision=1', 'source_generation=1',
                       "source_item='original',source_revision=0,source_generation=1"):
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute('UPDATE ordinary_destination SET ' + clause)

    def test_capability_choice_and_bound_source_cannot_change(self):
        self.fresh()
        admission(self.db, bound='original')
        for clause in ("creation_id='other'", "choice='APP_LIBRARY'", "operation_id='other'", "grant_id='other'",
                       'source_item=NULL,source_revision=NULL,source_generation=NULL', 'source_revision=8', 'source_generation=4'):
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute('UPDATE ordinary_destination SET ' + clause)
        self.db.execute("UPDATE ordinary_destination SET source_item='original',source_revision=7,source_generation=3")
        self.reopen()
        self.assertEqual(('new', 'ALBUM_COPY', 'op-new', 'grant-new', 'original', 7, 3), self.db.execute('SELECT * FROM ordinary_destination').fetchone())

    def test_links_are_immutable_foreign_key_checked_and_survive_reopen(self):
        self.fresh()
        admission(self.db, 'session')
        self.db.execute("INSERT INTO ordinary_destination_publication VALUES('processing-operation','session')")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO ordinary_destination_publication VALUES('other','absent')")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE ordinary_destination_publication SET publication_id='other'")
        self.reopen()
        self.assertEqual(('session',), self.db.execute("SELECT creation_id FROM ordinary_destination_publication WHERE publication_id='processing-operation'").fetchone())
        self.assertIn('val admission = get(creationId) ?: return@transaction', JOURNAL)
        self.assertIn('if (prior != creationId)', JOURNAL)

    def test_existing_ids_cannot_be_newly_admitted(self):
        self.fresh()
        self.db.execute("INSERT INTO recording_session(session_id,generation,config,state,created_at) VALUES('old-session',1,?,'RETAINED',0)", (b'config-fixture',))
        self.assertTrue(self.db.execute(ADMISSION_EXISTS, ('task-original',) * 3).fetchall())
        self.assertTrue(self.db.execute(ADMISSION_EXISTS, ('old-session',) * 3).fetchall())
        self.assertEqual([], self.db.execute(ADMISSION_EXISTS, ('new-task',) * 3).fetchall())
        self.assertIn('requireNotCreated(db, creationId)', JOURNAL)
        self.assertIn('requireNotCreated(db, publicationId)', JOURNAL)

    def test_private_publication_and_source_binding_roll_back_together(self):
        self.fresh()
        admission(self.db, 'new-save')
        self.db.commit()
        with self.assertRaises(RuntimeError):
            with self.db:
                save_item(self.db, 'candidate')
                self.db.execute("UPDATE ordinary_destination SET source_item='candidate',source_revision=7,source_generation=3 WHERE creation_id='new-save'")
                raise RuntimeError('SYNTHETIC_PUBLICATION_FAULT')
        self.assertEqual([], self.db.execute("SELECT * FROM library_item WHERE item_id='candidate'").fetchall())
        self.assertEqual((None, None, None), self.db.execute("SELECT source_item,source_revision,source_generation FROM ordinary_destination WHERE creation_id='new-save'").fetchone())
        self.reopen()
        self.assertEqual([], self.db.execute(PENDING, ('', 32)).fetchall())
        with self.db:
            save_item(self.db, 'candidate')
            self.db.execute("UPDATE ordinary_destination SET source_item='candidate',source_revision=7,source_generation=3 WHERE creation_id='new-save'")
        self.reopen()
        self.assertEqual([('new-save',)], self.db.execute(PENDING, ('', 32)).fetchall())
        self.assertEqual(('SUCCEEDED',), self.db.execute("SELECT state FROM save_tasks WHERE task_id='task-candidate'").fetchone())

    def test_production_hooks_are_inside_verified_publication_transactions(self):
        compare = SAVE.split('override fun compareAndSet', 1)[1].split('override fun listUnfinished', 1)[0]
        self.assertLess(compare.index('LibrarySchema.requireVerifiedPublication'), compare.index('destinations.bindVerifiedPublication'))
        self.assertLess(compare.index('destinations.bindVerifiedPublication'), compare.index('val changed = db.update'))
        publish = EDIT.split('fun publish(', 1)[1].split('fun finishUnpublished(', 1)[0]
        self.assertLess(publish.rindex('transaction { db ->'), publish.index('bindDestinationPublication'))
        self.assertLess(publish.index('bindDestinationPublication'), publish.index('LibrarySchema.bumpVersion'))
        self.assertIn('check(db === database() && db.inTransaction())', JOURNAL)
        self.assertIn('prior.bindVerifiedSource(source)', JOURNAL)

    def test_pending_excludes_private_unbound_terminal_and_ambiguous_exports(self):
        self.fresh()
        admission(self.db, 'unbound')
        admission(self.db, 'private', 'APP_LIBRARY', 'original')
        admission(self.db, 'none', bound='original')
        phases = ('PREPARED', 'CREATE_INTENT', 'COPYING', 'VERIFYING', 'PUBLISH_INTENT', 'SUCCEEDED',
                  'COPIED_UNVERIFIED', 'CLEANUP_PENDING', 'FAILED', 'CANCELLED', 'RECONCILIATION_REQUIRED')
        for phase in phases:
            admission(self.db, phase, bound='original')
            export(self.db, phase, phase)
        self.reopen()
        self.assertEqual([('PREPARED',), ('none',)], self.db.execute(PENDING, ('', 32)).fetchall())
        self.assertEqual(len(phases), self.db.execute('SELECT COUNT(*) FROM library_export').fetchone()[0])

    def test_rotating_bounded_pending_scan_persists_cursor_across_reopen(self):
        self.fresh()
        for i in range(75):
            admission(self.db, f'creation-{i:03}', bound='original')
        seen = set()
        for _ in range(3):
            cursor = self.db.execute('SELECT copy_cursor FROM ordinary_destination_state').fetchone()[0]
            rows = self.db.execute(PENDING, (cursor, 32)).fetchall()
            self.assertLessEqual(len(rows), 32)
            seen.update(row[0] for row in rows)
            self.db.execute('UPDATE ordinary_destination_state SET copy_cursor=?', (rows[-1][0],))
            self.reopen()
        self.assertEqual(75, len(seen))
        cursor = self.db.execute('SELECT copy_cursor FROM ordinary_destination_state').fetchone()[0]
        self.assertEqual([], self.db.execute(PENDING, (cursor, 32)).fetchall())
        self.assertEqual(32, len(self.db.execute(PENDING, ('', 32)).fetchall()))

    def test_external_errors_leave_exact_private_original_unchanged(self):
        self.fresh()
        admission(self.db, bound='original')
        before = {table: list(self.db.execute('SELECT * FROM ' + table)) for table in ('save_tasks', 'save_owned', 'save_media', 'library_item', 'media_representation')}
        export(self.db, 'new', 'RECONCILIATION_REQUIRED')
        self.reopen()
        for table, rows in before.items():
            self.assertEqual(rows, list(self.db.execute('SELECT * FROM ' + table)))
        self.assertEqual([], self.db.execute(PENDING, ('', 32)).fetchall())
        self.assertNotIn('DELETE ', JOURNAL)
        self.assertNotIn('MediaStore', JOURNAL)

    def test_operation_and_grant_ids_are_unique_retained_capabilities(self):
        self.fresh()
        admission(self.db, bound='original')
        for operation, grant in (('op-new', 'other'), ('other', 'grant-new')):
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute("INSERT INTO ordinary_destination VALUES('other','ALBUM_COPY',?,?,NULL,NULL,NULL)", (operation, grant))
        with self.assertRaises(sqlite3.IntegrityError):
            admission(self.db, bound='original')
        self.assertEqual((1,), self.db.execute('SELECT COUNT(*) FROM ordinary_destination').fetchone())


if __name__ == '__main__':
    unittest.main(verbosity=2)
