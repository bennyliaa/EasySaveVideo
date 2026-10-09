#!/usr/bin/env python3
"""Independent ordinary-destination review, using host SQLite only.

This executes the literal production migration DDL and publication-read predicate.
Migration sequencing is an explicit Python contract model checked against the
production branch text. Android SQLiteOpenHelper, Kotlin codecs, native recording,
playable media and MediaStore are NOT executed. All data is synthetic. Version 1
uses a failed Save with no legacy readable media; its media-backfill codec is not
represented as tested. No real media is read or changed.
"""
from pathlib import Path
import ast
import hashlib
import json
import re
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / 'app/src/main/kotlin/com/easysavevideo/app'

def literals(text):
    result = [a or b for a, b in re.findall(r'db\.execSQL\((?:"""(.*?)"""|"([^"\n]+)")\)', text, re.S)]
    assert all('$' not in x for x in result)
    return result

def function(text, name):
    tail = text[text.index('fun ' + name + '('):]
    next_fun = re.search(r'\n    (?:private |internal )?fun ', tail[1:])
    return tail if next_fun is None else tail[:next_fun.start()+1]

# Immutable old-v3 DDL fixture, parsed as a literal without importing its test suite.
fixture = ast.parse((ROOT / 'scripts/verify-editor-sqlite.py').read_text())
V3 = next(ast.literal_eval(n.value) for n in fixture.body if isinstance(n, ast.Assign)
          and any(isinstance(t, ast.Name) and t.id == 'V3_DDL' for t in n.targets))
EDIT = (BASE / 'editor/storage/EditSchema.kt').read_text()
SAVE = (BASE / 'save/storage/AndroidSaveStorage.kt').read_text()
JOURNAL = (BASE / 'library/destination/AndroidOrdinaryDestinationJournal.kt').read_text()
DEST = literals((BASE / 'library/destination/OrdinaryDestinationSchema.kt').read_text())
SEGMENTS = literals((BASE / 'recording/segments/RecordedSegmentSchema.kt').read_text())
EDIT_JOB = literals(function(EDIT, 'create'))
NEW_SOURCES = literals(function(EDIT, 'createSources'))
LIBRARY_MIGRATION = literals(function(EDIT, 'migrateLibrary'))
SOURCE_MIGRATION = literals(function(EDIT, 'migrateRecordedSources'))
OLD_SOURCE = (ROOT / 'app/src/test/resources/recorded-segments/frozen-v4-edit-source.sql').read_text()
V5 = (ROOT / 'app/src/test/resources/destination/frozen-v5-schema.sql').read_text()
LIBRARY_CREATE = literals((BASE / 'library/storage/LibrarySchema.kt').read_text().split('fun backfill')[0])
EXPORT_CREATE = literals((BASE / 'library/export/ExportSchema.kt').read_text())
READABLE = re.search(r'val readable = db.rawQuery\("""(.*?)"""', JOURNAL, re.S).group(1)
PENDING = re.search(r'PENDING_COPY_SQL = "([^"]+)"', JOURNAL).group(1)


def run(db, statements):
    for sql in statements:
        db.execute(sql)


def init(db, version):
    db.execute('PRAGMA foreign_keys=OFF')
    if version == 5:
        db.executescript(V5)
    elif version == 4:
        run(db, V3 + EDIT_JOB)
        db.executescript(OLD_SOURCE)
        run(db, LIBRARY_MIGRATION)
    else:
        old = V3[:3] if version == 1 else [s for s in V3 if 'library_export' not in s] if version == 2 else V3
        run(db, old)
    db.execute('PRAGMA user_version=' + str(version))
    db.execute("INSERT INTO save_tasks VALUES('history','key',7,'FAILED','commit',?)", (b'synthetic-save',))
    if version >= 2:
        db.execute("INSERT INTO library_item(item_id,origin_commit_id,revision,lifecycle,privacy_class,representation_id,generation,display_name,name_key,created_at,date_kind) VALUES('item','commit',7,'READY','ORDINARY','representation',3,'Synthetic','synthetic',0,'SAVED')")
        db.execute("INSERT INTO media_representation VALUES('representation','item',3,'ORDINARY_FILE_V1','00000000-0000-0000-0000-000000000011','READY',8,?)", ('a'*64,))
    if version >= 3:
        db.execute("INSERT INTO library_export VALUES('old-export',?,'FAILED',0,?)", ('b'*64,b'synthetic-export'))
    if version >= 4:
        db.execute("INSERT INTO edit_job(operation_id,fingerprint,artifact_id,media_id,phase,request,created_at) VALUES('old-edit',?,'00000000-0000-0000-0000-000000000012','00000000-0000-0000-0000-000000000013','FAILED',?,0)", ('c'*64,b'synthetic-edit'))
        db.execute("INSERT INTO edit_source(operation_id,sequence,item_id,revision,generation,start_us,end_us,size_bytes,sha256) VALUES('old-edit',0,'item',7,3,0,1000,8,?)", ('a'*64,))
    if version >= 5:
        db.execute("INSERT INTO recording_session(session_id,generation,config,state,created_at) VALUES('old-session',2,?,'RETAINED',0)", (b'synthetic-recording',))
    db.commit()
    db.execute('PRAGMA foreign_keys=' + ('OFF' if version <= 3 else 'ON'))
    assert not db.execute('PRAGMA foreign_key_check').fetchall()


def upgrade_steps(version):
    steps = []
    if version == 1:
        steps += LIBRARY_CREATE  # No legacy readable media in this fixture.
    if version < 3:
        steps += EXPORT_CREATE
    if version < 5:
        steps += SEGMENTS
    if version < 4:
        steps += EDIT_JOB + NEW_SOURCES + LIBRARY_MIGRATION
    elif version == 4:
        steps += SOURCE_MIGRATION[:1] + NEW_SOURCES + SOURCE_MIGRATION[1:]
    return steps + DEST + ['PRAGMA user_version=6']


def snapshot(db):
    schema = list(db.execute("SELECT name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name"))
    return db.execute('PRAGMA user_version').fetchone(), schema, {
        name: (tuple(row[1] for row in db.execute('PRAGMA table_info('+name+')')),
               list(db.execute('SELECT * FROM '+name+' ORDER BY rowid')))
        for name, in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")}


class IndependentOrdinaryDestinationSqlTest(unittest.TestCase):
    def test_all_old_versions_upgrade_to_six_preserving_prior_columns_and_rows(self):
        for version in range(1, 6):
            with self.subTest(old_version=version), tempfile.TemporaryDirectory() as temp:
                path = Path(temp)/'synthetic.sqlite'
                db = sqlite3.connect(path)
                init(db, version)
                prior = snapshot(db)
                with db:
                    db.execute('BEGIN')
                    run(db, upgrade_steps(version))
                    self.assertEqual([], db.execute('PRAGMA foreign_key_check').fetchall())
                db.close()
                db = sqlite3.connect(path)
                db.execute('PRAGMA foreign_keys=ON')
                self.assertEqual((6,), db.execute('PRAGMA user_version').fetchone())
                for table, (columns, rows) in prior[2].items():
                    self.assertEqual(rows, list(db.execute('SELECT '+','.join(columns)+' FROM '+table+' ORDER BY rowid')), table)
                self.assertEqual([], db.execute('SELECT * FROM ordinary_destination').fetchall())
                self.assertEqual([], db.execute('SELECT * FROM ordinary_destination_publication').fetchall())
                self.assertEqual([(1,'APP_LIBRARY','')], db.execute('SELECT * FROM ordinary_destination_state').fetchall())
                self.assertEqual([], db.execute('PRAGMA foreign_key_check').fetchall())
                self.assertEqual(('ok',), db.execute('PRAGMA quick_check').fetchone())
                db.close()

    def test_every_ddl_boundary_all_old_versions_rolls_back_atomically(self):
        boundaries = 0
        for version in range(1,6):
            for index in range(len(upgrade_steps(version))):
                with self.subTest(old_version=version, boundary=index):
                    db = sqlite3.connect(':memory:')
                    init(db, version)
                    before = snapshot(db)
                    try:
                        db.execute('BEGIN')
                        run(db, upgrade_steps(version)[:index+1])
                        raise RuntimeError('SYNTHETIC_STOP')
                    except RuntimeError:
                        db.rollback()
                    self.assertEqual(before, snapshot(db))
                    boundaries += 1
                    db.close()
        print('Independent injected rollback boundaries:', boundaries)

    def test_production_exact_publication_predicate_rejects_stale_identity_revision_generation_and_tombstones(self):
        db = sqlite3.connect(':memory:'); init(db,5); run(db,upgrade_steps(5))
        self.assertEqual([(1,)], db.execute(READABLE,('item','7','3')).fetchall())
        for ref in [('other','7','3'),('item','8','3'),('item','7','4')]:
            self.assertEqual([], db.execute(READABLE,ref).fetchall())
        db.execute("UPDATE library_item SET lifecycle='DELETE_PENDING' WHERE item_id='item'")
        self.assertEqual([], db.execute(READABLE,('item','7','3')).fetchall())
        db.execute("UPDATE library_item SET lifecycle='READY' WHERE item_id='item'")
        db.execute("UPDATE media_representation SET state='DELETE_PENDING' WHERE item_id='item'")
        self.assertEqual([], db.execute(READABLE,('item','7','3')).fetchall())
        db.close()

    def test_real_branch_shape_matches_contract_model_and_cannot_backfill_destinations(self):
        upgrade = SAVE.split('override fun onUpgrade',1)[1].split('override fun onDowngrade',1)[0]
        for expected in ['oldVersion !in 1..5 || newVersion != 6', 'if (oldVersion == 1)',
                         'if (oldVersion < 3) ExportSchema.create(db)',
                         'if (oldVersion < 5) RecordedSegmentSchema.create(db)',
                         'if (oldVersion < 4)', 'else if (oldVersion == 4) EditSchema.migrateRecordedSources(db)']:
            self.assertIn(expected,upgrade)
        self.assertEqual(1,upgrade.count('OrdinaryDestinationSchema.create(db)'))
        self.assertNotIn('INSERT INTO ordinary_destination(', '\n'.join(DEST))
        self.assertNotIn('INSERT INTO ordinary_destination_publication(', '\n'.join(DEST))
        self.assertNotIn('DELETE', JOURNAL)

if __name__ == '__main__':
    unittest.main(verbosity=2)
