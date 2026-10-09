#!/usr/bin/env python3
"""Original synthetic host SQL tests. Executes extracted production DDL, not Android native code.
No real recordings, decoders, permissions, devices or APKs. v4 input fixture is frozen independently.
"""
from pathlib import Path
import re
import sqlite3
import unittest
import uuid
ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/kotlin/com/easysavevideo/app'

def statements(path):
    return [long or short for long, short in re.findall(r'db\.execSQL\((?:"""(.*?)"""|"([^"\n]+)")\)', path.read_text(), re.S)]

RECORDING = statements(BASE / 'recording/segments/RecordedSegmentSchema.kt')
EDITOR = statements(BASE / 'editor/storage/EditSchema.kt')
NEW_SOURCE = next(s for s in EDITOR if s.startswith('CREATE TABLE edit_source('))
EDIT_JOB = next(s for s in EDITOR if s.startswith('CREATE TABLE edit_job('))
MIGRATE = [s for s in EDITOR if s.startswith(('ALTER TABLE edit_source', 'INSERT INTO edit_source(', 'DROP TABLE edit_source_v4'))]
OLD_SOURCE = (ROOT / 'app/src/test/resources/recorded-segments/frozen-v4-edit-source.sql').read_text()

def uid(i): return str(uuid.UUID(int=i))

def connect():
    db = sqlite3.connect(':memory:')
    db.execute('PRAGMA foreign_keys=ON')
    db.execute('CREATE TABLE library_item(item_id TEXT PRIMARY KEY)')
    db.execute(EDIT_JOB)
    return db

def job(db, op='edit'):
    db.execute("INSERT INTO edit_job(operation_id,fingerprint,artifact_id,media_id,phase,request,created_at) VALUES(?,?,?,?,?,?,0)",
               (op, 'a' * 64, uid(1), uid(2), 'PREPARED', b'x' * 8))

def session(db):
    db.execute("INSERT INTO recording_session(session_id,generation,config,state,created_at) VALUES('session',3,?,'STOPPED',0)", (b'x' * 8,))

def segment(db, index=0):
    db.execute("INSERT INTO recording_segment(segment_id,session_id,sequence,state,effective_start_us) VALUES(?, 'session',?,'ALLOCATED',?)", (uid(10+index), index, index * 1000000))

class RecordedSegmentSqlTests(unittest.TestCase):
    def test_exact_v4_rows_survive_v5_migration_with_fk_enabled(self):
        db = connect(); db.executescript(OLD_SOURCE); job(db)
        db.execute("INSERT INTO library_item VALUES('source')")
        db.execute("INSERT INTO edit_source VALUES('edit',0,'source',7,9,0,1000000,0,1000000,30,1024,?)", ('a'*64,))
        original = db.execute('SELECT * FROM edit_source').fetchall(); db.commit()
        for sql in RECORDING: db.execute(sql)
        db.execute(MIGRATE[0]); db.execute(NEW_SOURCE)
        for sql in MIGRATE[1:]: db.execute(sql)
        columns='operation_id,sequence,item_id,revision,generation,start_us,end_us,execution_start_us,execution_end_us,frame_count,size_bytes,sha256'
        self.assertEqual(original, db.execute('SELECT '+columns+' FROM edit_source').fetchall())
        self.assertEqual([('CATALOG',None,None,None)], db.execute('SELECT source_kind,session_id,segment_id,segment_sequence FROM edit_source').fetchall())
        self.assertEqual([],db.execute('PRAGMA foreign_key_check').fetchall())
    def test_each_migration_boundary_rolls_back_original(self):
        for boundary in range(len(RECORDING) + len(MIGRATE) + 1):
            db=connect(); db.executescript(OLD_SOURCE); db.execute('PRAGMA user_version=4'); db.commit()
            before=list(db.execute('SELECT name,sql FROM sqlite_master ORDER BY name'))
            steps=RECORDING+[MIGRATE[0],NEW_SOURCE]+MIGRATE[1:]
            try:
                db.execute('BEGIN')
                for i,sql in enumerate(steps):
                    db.execute(sql)
                    if i==boundary: raise RuntimeError('FAULT')
            except RuntimeError: db.rollback()
            self.assertEqual(before,list(db.execute('SELECT name,sql FROM sqlite_master ORDER BY name')))
            self.assertEqual((4,),db.execute('PRAGMA user_version').fetchone())
    def fresh(self):
        db=connect()
        for sql in RECORDING: db.execute(sql)
        db.execute(NEW_SOURCE); session(db); job(db); return db
    def test_private_sources_need_owned_segment_fk_and_never_catalog_alias(self):
        db=self.fresh(); segment(db)
        values=('edit',0,'RECORDED_SEGMENT',3,'session',uid(10),0,0,1000000,1000,'a'*64)
        sql='INSERT INTO edit_source(operation_id,sequence,source_kind,generation,session_id,segment_id,segment_sequence,start_us,end_us,size_bytes,sha256) VALUES('+','.join('?'*11)+')'
        db.execute(sql,values)
        self.assertEqual((0,),db.execute('SELECT COUNT(*) FROM library_item').fetchone())
        with self.assertRaises(sqlite3.IntegrityError): db.execute("UPDATE edit_source SET item_id='fake',revision=1")
        with self.assertRaises(sqlite3.IntegrityError): db.execute('UPDATE edit_source SET segment_id=?',(uid(999),))
        with self.assertRaises(sqlite3.IntegrityError): db.execute('UPDATE edit_source SET segment_sequence=1')
    def test_closed_requires_identity_extent_timing_geometry_and_capture_and_verified_requires_proof(self):
        db=self.fresh(); segment(db)
        with self.assertRaises(sqlite3.IntegrityError): db.execute("UPDATE recording_segment SET state='CLOSED_UNVERIFIED'")
        db.execute("UPDATE recording_segment SET state='CLOSED_UNVERIFIED',file_identity='inode',effective_end_us=1000000,size_bytes=1000,sha256=?,timing_sha256=?,capture=?,geometry=?",('a'*64,'b'*64,b'x'*8,b'g'*8))
        with self.assertRaises(sqlite3.IntegrityError): db.execute("UPDATE recording_segment SET state='VERIFIED'")
        db.execute("UPDATE recording_segment SET state='VERIFIED',proof=?",(b'p'*8,))
        self.assertEqual((0,),db.execute('SELECT COUNT(*) FROM library_item').fetchone())
    def test_initial_plus_32_resumes_is_hard_bound(self):
        db=self.fresh()
        for i in range(33): segment(db,i)
        with self.assertRaises(sqlite3.IntegrityError): segment(db,33)
        self.assertEqual((33,),db.execute('SELECT COUNT(*) FROM recording_segment').fetchone())
    def test_same_ordinal_can_retry_only_after_deleted_unverified_tail(self):
        db=self.fresh(); segment(db)
        with self.assertRaises(sqlite3.IntegrityError): segment(db)
        db.execute("UPDATE recording_segment SET state='DISCARDED'")
        db.execute("DELETE FROM recording_segment WHERE state='DISCARDED' AND capture IS NULL")
        segment(db); self.assertEqual((1,),db.execute('SELECT COUNT(*) FROM recording_segment').fetchone())
    def test_partial_discard_preserves_ordinals_and_pending_cleanup_state(self):
        db=self.fresh()
        for index in range(3):
            segment(db,index)
            db.execute("UPDATE recording_segment SET state='CLOSED_UNVERIFIED',file_identity='inode',effective_end_us=?,size_bytes=1000,sha256=?,timing_sha256=?,capture=?,geometry=? WHERE sequence=?",
                ((index+1)*1000000,'a'*64,'b'*64,b'x'*8,b'g'*8,index))
        db.execute("UPDATE recording_session SET state='CANCEL_PENDING'")
        db.execute("UPDATE recording_segment SET state='DISCARDED' WHERE sequence IN (0,2)")
        db.execute("UPDATE recording_segment SET state='CLEANUP_PENDING' WHERE sequence=1")
        self.assertEqual([(0,'DISCARDED'),(1,'CLEANUP_PENDING'),(2,'DISCARDED')], list(db.execute('SELECT sequence,state FROM recording_segment ORDER BY sequence')))
        self.assertEqual(('CANCEL_PENDING',),db.execute('SELECT state FROM recording_session').fetchone())
        db.execute("UPDATE recording_segment SET state='DISCARDED' WHERE sequence=1")
        self.assertEqual((0,),db.execute("SELECT COUNT(*) FROM recording_segment WHERE state<>'DISCARDED'").fetchone())
        db.execute("UPDATE recording_session SET state='CANCELLED'")
        self.assertEqual((3,),db.execute('SELECT COUNT(*) FROM recording_segment').fetchone())
    def test_oversized_filtered_tail_is_not_a_shorter_valid_recipe(self):
        db=self.fresh(); segment(db,0); segment(db,1)
        db.execute('PRAGMA ignore_check_constraints=ON')
        db.execute("UPDATE recording_segment SET capture=? WHERE sequence=1",(b'x'*2049,))
        total=db.execute("SELECT COUNT(*) FROM recording_segment WHERE session_id='session'").fetchone()[0]
        bounded=list(db.execute("SELECT sequence FROM recording_segment WHERE session_id='session' AND (capture IS NULL OR length(capture) BETWEEN 8 AND 2048) ORDER BY sequence LIMIT 34"))
        self.assertEqual(2,total); self.assertEqual([(0,)],bounded)
        source=(BASE/'recording/segments/AndroidRecordedSegmentStorage.kt').read_text()
        self.assertIn('check(size.toLong() == count)',source)
    def test_stale_native_writer_capability_rechecks_closed_state(self):
        source=(BASE/'recording/segments/AndroidRecordedSegmentStorage.kt').read_text()
        retain=source.split('}, retainNativeWriter = {',1)[1].split('}, availableBytes',1)[0]
        self.assertIn('session(session).state == "CAPTURING"',retain)
        self.assertIn('row.state == "ALLOCATED"',retain)
        closed=source.split('fun confirmClosed(',1)[1].split('fun stopped',1)[0]
        self.assertIn('owner.idle',closed)
    def test_recorded_publication_marker_is_inside_catalog_transaction(self):
        source=(BASE/'editor/storage/AndroidEditStorage.kt').read_text()
        start=source.index('put("state", "PUBLISHED"); put("published_operation", output.operationId)')
        self.assertLess(source.rfind('transaction { db ->',0,start),start)
        self.assertLess(start,source.index('LibrarySchema.bumpVersion(db)',start))
        self.assertIn("state='PROCESSING' AND pending_operation=?",source)

if __name__=='__main__': unittest.main(verbosity=2)
