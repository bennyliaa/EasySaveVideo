#!/usr/bin/env python3
"""Typed normalized commit SQL contract checks on temporary host SQLite.

Uses production-extracted schema and adapter WHERE/association SQL. Mutation values and
opaque blobs are synthetic: this is neither Kotlin codec execution nor Android SQLite
runtime/fsync acceptance. Kotlin owner/descriptor tests remain a separate gate.
"""
from pathlib import Path
import hashlib
import json
import re
import runpy
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/kotlin/com/easysavevideo/app'
ADAPTER_PATH = BASE / 'save/storage/AndroidNormalizedCommitJournal.kt'
ADAPTER = ADAPTER_PATH.read_text()
CURRENT = runpy.run_path(str(ROOT / 'scripts/verify-current-storage-sqlite.py'), run_name='normalized_schema_inputs')
STRINGS = [triple or plain for triple, plain in re.findall(r'"""([\s\S]*?)"""|"([^"\n]*)"', ADAPTER)]


def literal(prefix):
    found = [s for s in STRINGS if s.startswith(prefix)]
    if len(set(found)) != 1:
        raise AssertionError(('nonunique production SQL', prefix, found))
    return found[0]


INTENT_WHERE = literal("commit_id=? AND task_id=? AND artifact_id=? AND media_id=? AND state='STAGING'")
INDEX_WHERE = literal("commit_id=? AND task_id=? AND artifact_id=? AND media_id=? AND state='INTENT'")
OWNED_WHERE = literal('commit_id=? AND task_id=? AND artifact_id=? AND media_id=? AND state=?')
RECEIPT_WHERE = literal('commit_id=? AND artifact_id=? AND media_id=? AND size_bytes=? AND sha256=? AND visible=0')
TASK_WHERE = literal("task_id=? AND commit_id=? AND revision=? AND state='RUNNING'")
WORKSPACE_WHERE = literal('normalization_id=? AND task_id=? AND artifact_id=? AND commit_id=? AND revision=?')
ASSOCIATION = literal('SELECT 1 FROM library_item')
BASE_DDL = CURRENT['sql'](CURRENT['function'](CURRENT['SAVE'], 'onCreate'))
assert len(BASE_DDL) == 3
ARTIFACT = '11111111-1111-4111-8111-111111111111'
GENERATION = '22222222-2222-4222-8222-222222222222'
MEDIA = '33333333-3333-4333-8333-333333333333'
OWNED_ARGS = ('commit', 'task', ARTIFACT, MEDIA)
RECEIPT_ARGS = ('commit', ARTIFACT, MEDIA, 256, 'c' * 64)
WORKSPACE_ARGS = (GENERATION, 'task', ARTIFACT, 'commit', 5)


class Fault(Exception):
    pass


class TypedCommitSqlContracts(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='esv-normalized-sql-')
        self.db = sqlite3.connect(str(Path(self.directory.name) / 'test.db'))
        self.db.execute('PRAGMA foreign_keys=ON')
        with self.db:
            for sql in BASE_DDL + CURRENT['LIB_DDL'] + CURRENT['DEST_DDL'] + CURRENT['NORMALIZATION_DDL']:
                self.db.execute(sql)
            self.db.execute('INSERT INTO save_tasks VALUES(?,?,?,?,?,?)',
                            ('task', 'key', 12, 'RUNNING', 'commit', b'synthetic-running-committing'))
            self.db.execute('INSERT INTO save_owned VALUES(?,?,?,?,?,NULL)', (*OWNED_ARGS, 'STAGING'))
            self.db.execute('''INSERT INTO save_normalization_workspace(normalization_id,task_id,artifact_id,commit_id,
                revision,lifecycle,disposition,raw_size,raw_sha,source_sha,record) VALUES(?,?,?,?,?,?,?,?,?,?,?)''',
                            (GENERATION, 'task', ARTIFACT, 'commit', 1, 'RAW_COMPLETE', 'CONTINUE', 512,
                             'a' * 64, 'b' * 64, b'synthetic-raw-intent'))
            self.db.execute("UPDATE save_normalization_workspace SET revision=2,lifecycle='WRITING'")
            self.db.execute("UPDATE save_normalization_workspace SET revision=3,lifecycle='OUTPUT_READY',output_size=256,output_sha=?,proof_sha=?",
                            ('c' * 64, 'd' * 64))
            self.db.execute("UPDATE save_normalization_workspace SET revision=4,lifecycle='ADOPT_INTENT'")
            self.db.execute("UPDATE save_normalization_workspace SET revision=5,lifecycle='ADOPTED',record=?", (b'synthetic-adopted-proof',))
            self.db.execute('''INSERT INTO ordinary_destination(creation_id,choice,operation_id,grant_id)
                VALUES('task','APP_LIBRARY','destination-op','destination-grant')''')
            self.db.execute("INSERT INTO ordinary_destination_publication VALUES('task','task')")

    def tearDown(self):
        self.db.close()
        self.directory.cleanup()

    def snapshot(self):
        names = [r[0] for r in self.db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name!='sqlite_sequence' ORDER BY name")]
        return {name: list(self.db.execute('SELECT * FROM ' + name + ' ORDER BY rowid')) for name in names}

    def bound(self):
        return (tuple(self.db.execute('SELECT * FROM save_tasks WHERE task_id=?', ('task',))),
                tuple(self.db.execute('SELECT * FROM save_owned WHERE commit_id=?', ('commit',))),
                tuple(self.db.execute('SELECT * FROM save_normalization_workspace WHERE normalization_id=?', (GENERATION,))),
                tuple(self.db.execute('SELECT * FROM save_media WHERE commit_id=?', ('commit',))), self.linked())

    def linked(self):
        return self.db.execute(ASSOCIATION, ('commit', MEDIA, MEDIA)).fetchone() is not None

    def exactly_one(self, cursor):
        if cursor.rowcount != 1:
            raise Fault('ROW_COUNT')

    def fail_after(self, expected, current):
        if expected == current:
            raise Fault(current)

    def intent(self, fault=None):
        with self.db:
            self.exactly_one(self.db.execute('UPDATE save_owned SET state=?,evidence=? WHERE ' + INTENT_WHERE,
                                            ('INTENT', b'synthetic-full-normalized-evidence', *OWNED_ARGS)))
            self.fail_after(fault, 'intent')

    def index(self, fault=None):
        with self.db:
            self.db.execute('INSERT INTO save_media(commit_id,artifact_id,media_id,size_bytes,sha256,visible) VALUES(?,?,?,?,?,0)', RECEIPT_ARGS)
            self.fail_after(fault, 'insert-index')
            self.exactly_one(self.db.execute("UPDATE save_owned SET state='INDEXED' WHERE " + INDEX_WHERE, OWNED_ARGS))
            self.fail_after(fault, 'indexed-owned')

    def publish(self, expected, fault=None):
        with self.db:
            if self.bound() != expected:
                return False
            self.exactly_one(self.db.execute('UPDATE save_media SET visible=1 WHERE ' + RECEIPT_WHERE, RECEIPT_ARGS))
            self.fail_after(fault, 'visible')
            self.db.execute('''INSERT INTO library_item(item_id,origin_commit_id,revision,lifecycle,privacy_class,
                representation_id,generation,display_name,name_key,created_at,date_kind,duration_millis,width,height)
                VALUES(?, 'commit',1,'READY','ORDINARY','representation',1,'Synthetic','synthetic',0,'SAVED',1000,16,16)''', (MEDIA,))
            self.fail_after(fault, 'library-item')
            self.db.execute('''INSERT INTO media_representation(representation_id,item_id,generation,kind,opaque_locator,state,size_bytes,sha256)
                VALUES('representation',?,1,'ORDINARY_FILE_V1',?,'READY',256,?)''', (MEDIA, MEDIA, 'c' * 64))
            self.db.execute('UPDATE library_state SET revision=revision+1 WHERE singleton=1')
            self.fail_after(fault, 'representation')
            self.exactly_one(self.db.execute('''UPDATE ordinary_destination SET source_item=?,source_revision=1,source_generation=1
                WHERE creation_id='task' AND source_item IS NULL AND source_revision IS NULL AND source_generation IS NULL''', (MEDIA,)))
            self.fail_after(fault, 'destination')
            self.exactly_one(self.db.execute('UPDATE save_tasks SET state=?,revision=?,record=? WHERE ' + TASK_WHERE,
                                            ('SUCCEEDED', 13, b'synthetic-exact-success', 'task', 'commit', 12)))
            self.fail_after(fault, 'save-task')
            return True

    def cancel(self):
        with self.db:
            self.db.execute("UPDATE save_tasks SET state='CANCELLED',revision=13,record=?", (b'synthetic-exact-cancelled',))

    def discard(self, expected, state='INDEXED', fault=None):
        with self.db:
            if self.bound() != expected or self.linked():
                return False
            visible = self.db.execute('SELECT visible FROM save_media WHERE commit_id=?', ('commit',)).fetchone()
            if visible and visible[0] != 0:
                return False
            if self.db.execute('SELECT state FROM save_tasks WHERE task_id=?', ('task',)).fetchone()[0] not in ('FAILED', 'CANCELLED'):
                return False
            self.exactly_one(self.db.execute('''UPDATE save_normalization_workspace SET revision=6,lifecycle='CLEANUP_PENDING',
                disposition='DISCARD_ONLY',record=? WHERE ''' + WORKSPACE_WHERE, (b'synthetic-v2-inode-bound-plan', *WORKSPACE_ARGS)))
            self.fail_after(fault, 'plan')
            if visible:
                self.exactly_one(self.db.execute('DELETE FROM save_media WHERE ' + RECEIPT_WHERE, RECEIPT_ARGS))
            self.fail_after(fault, 'revoke-index')
            self.exactly_one(self.db.execute("UPDATE save_owned SET state='DISCARDED' WHERE " + OWNED_WHERE, (*OWNED_ARGS, state)))
            self.fail_after(fault, 'discard-owned')
            return True

    def test_intent_and_index_never_publish(self):
        self.intent()
        self.assertEqual([], list(self.db.execute('SELECT * FROM save_media')))
        self.index()
        self.assertEqual([(0,)], list(self.db.execute('SELECT visible FROM save_media')))
        self.assertFalse(self.linked())
        self.assertEqual('RUNNING', self.db.execute('SELECT state FROM save_tasks').fetchone()[0])

    def test_every_publish_mutation_boundary_rolls_back_all_tables(self):
        self.intent(); self.index()
        before = self.snapshot(); expected = self.bound()
        for fault in ('visible', 'library-item', 'representation', 'destination', 'save-task'):
            with self.assertRaises(Fault):
                self.publish(expected, fault)
            self.assertEqual(before, self.snapshot(), fault)
            self.assertEqual([], list(self.db.execute('PRAGMA foreign_key_check')))
        self.assertTrue(self.publish(expected))
        self.assertEqual('SUCCEEDED', self.db.execute('SELECT state FROM save_tasks').fetchone()[0])
        self.assertEqual(1, self.db.execute('SELECT visible FROM save_media').fetchone()[0])
        self.assertEqual((MEDIA, 1, 1), self.db.execute('SELECT source_item,source_revision,source_generation FROM ordinary_destination').fetchone())

    def test_every_discard_boundary_rolls_back_plan_index_and_catalog(self):
        self.intent(); self.index(); self.cancel()
        before = self.snapshot(); expected = self.bound()
        for fault in ('plan', 'revoke-index', 'discard-owned'):
            with self.assertRaises(Fault):
                self.discard(expected, fault=fault)
            self.assertEqual(before, self.snapshot(), fault)
        self.assertTrue(self.discard(expected))
        self.assertEqual([], list(self.db.execute('SELECT * FROM save_media')))
        self.assertEqual('DISCARDED', self.db.execute('SELECT state FROM save_owned').fetchone()[0])
        self.assertEqual(('CLEANUP_PENDING', 'DISCARD_ONLY'), self.db.execute('SELECT lifecycle,disposition FROM save_normalization_workspace').fetchone())

    def test_raw_staging_discard_does_not_require_an_index(self):
        self.cancel()
        self.assertTrue(self.discard(self.bound(), state='STAGING'))
        self.assertEqual([], list(self.db.execute('SELECT * FROM save_media')))
        self.assertEqual([], list(self.db.execute('PRAGMA foreign_key_check')))

    def test_intent_discard_does_not_require_an_index(self):
        self.intent(); self.cancel()
        self.assertTrue(self.discard(self.bound(), state='INTENT'))
        self.assertEqual([], list(self.db.execute('SELECT * FROM save_media')))
        self.assertEqual([], list(self.db.execute('PRAGMA foreign_key_check')))

    def test_visible_or_any_library_lifecycle_blocks_discard(self):
        self.intent(); self.index(); self.cancel()
        with self.db:
            self.db.execute('UPDATE save_media SET visible=1')
        before = self.snapshot()
        self.assertFalse(self.discard(self.bound())); self.assertEqual(before, self.snapshot())
        with self.db:
            self.db.execute('UPDATE save_media SET visible=0')
            self.db.execute('''INSERT INTO library_item(item_id,origin_commit_id,revision,lifecycle,privacy_class,representation_id,generation)
                VALUES(?,'commit',1,'DELETED','ORDINARY','tombstone-representation',1)''', (MEDIA,))
            self.db.execute('''INSERT INTO media_representation(representation_id,item_id,generation,kind,state)
                VALUES('tombstone-representation',?,1,'ORDINARY_FILE_V1','DELETED')''', (MEDIA,))
        self.assertTrue(self.linked()); before = self.snapshot()
        self.assertFalse(self.discard(self.bound())); self.assertEqual(before, self.snapshot())

    def test_full_blob_snapshot_substitution_rejects_before_writes(self):
        self.intent(); self.index(); expected = self.bound()
        with self.db:
            self.db.execute('UPDATE save_tasks SET record=?', (b'same-revision-altered-record',))
        before = self.snapshot()
        self.assertFalse(self.publish(expected)); self.assertEqual(before, self.snapshot())

    def test_generation_substitution_is_rejected_by_existing_immutable_schema(self):
        before = self.snapshot()
        with self.assertRaises(sqlite3.IntegrityError), self.db:
            self.db.execute('UPDATE save_normalization_workspace SET normalization_id=?,revision=6',
                            ('44444444-4444-4444-8444-444444444444',))
        self.assertEqual(before, self.snapshot())

    def test_commit_then_caller_throw_is_read_back_without_second_publication(self):
        self.intent(); self.index(); expected = self.bound()
        try:
            self.assertTrue(self.publish(expected))
            raise Fault('ACK_LOST_AFTER_COMMIT')
        except Fault:
            pass
        persisted = self.snapshot()
        self.assertEqual('SUCCEEDED', self.db.execute('SELECT state FROM save_tasks').fetchone()[0])
        self.assertFalse(self.publish(expected)); self.assertEqual(persisted, self.snapshot())

    def test_existing_blob_bound_and_exact_update_counts_reject_atomically(self):
        before = self.snapshot()
        with self.assertRaises(sqlite3.IntegrityError), self.db:
            self.db.execute('UPDATE save_normalization_workspace SET revision=6,record=?', (b'x' * 2049,))
        self.assertEqual(before, self.snapshot())
        with self.assertRaises(Fault), self.db:
            self.db.execute("UPDATE save_owned SET state='INTENT',evidence=?", (b'synthetic-evidence',))
            self.exactly_one(self.db.execute("UPDATE save_owned SET state='INDEXED' WHERE " + INDEX_WHERE,
                                            ('foreign', 'task', ARTIFACT, MEDIA)))
        self.assertEqual(before, self.snapshot())


if __name__ == '__main__':
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(TypedCommitSqlContracts))
    print(json.dumps({'kind': 'NORMALIZED_COMMIT_HOST_SQLITE_CONTRACT', 'tests': result.testsRun,
                      'failures': len(result.failures), 'errors': len(result.errors),
                      'adapter_sha256': hashlib.sha256(ADAPTER_PATH.read_bytes()).hexdigest(),
                      'sqlite_version': sqlite3.sqlite_version, 'kotlin_execution': 'NOT_RUN',
                      'android_sqlite_runtime': 'NOT_RUN', 'seed_blobs': 'SYNTHETIC_NOT_CODEC_EVIDENCE'}, indent=2))
    raise SystemExit(not result.wasSuccessful())
