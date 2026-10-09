#!/usr/bin/env python3
"""Synthetic host SQLite contracts from production export DDL/selectors.
Does not execute Android SQLiteOpenHelper/ContentResolver/SAF/provider or device operations.
"""
from pathlib import Path
import re
import sqlite3
import tempfile
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/kotlin/com/easysavevideo/app'
SCHEMA = (BASE / 'library/export/ExportSchema.kt').read_text()
JOURNAL = (BASE / 'library/export/AndroidExportJournal.kt').read_text()
PORT = (BASE / 'library/export/AndroidExportPort.kt').read_text()
STORAGE = (BASE / 'save/storage/AndroidSaveStorage.kt').read_text()
PROVIDER = (BASE / 'library/share/LibraryShareProvider.kt').read_text()
SHARE = (BASE / 'library/share/LibraryShareOwner.kt').read_text()
MANIFEST = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
DDL = [long or short for long, short in re.findall(r'db\.execSQL\((?:"""(.*?)"""|"([^"\n]+)")\)', SCHEMA, re.S)]
assert len(DDL) == 5
RECOVERY = re.search(r'RECOVERY_SQL = "([^"]+)"', JOURNAL).group(1)


def create(db):
    db.execute('PRAGMA foreign_keys=ON')
    for sql in DDL:
        db.execute(sql)


def record(db, op='op', phase='PREPARED', cancelled=0, blob=b'journal-model'):
    db.execute('INSERT INTO library_export VALUES(?,?,?,?,?)', (op, 'a' * 64, phase, cancelled, blob))


def target(db, op='op', kind='MEDIA_STORE', uri=None):
    db.execute('INSERT INTO library_export_target VALUES(?,?,?,?,?,?)', (op, kind, 'grant', str(uuid.uuid4()), uri, 0))


class Contracts(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        create(self.db)

    def tearDown(self):
        self.db.close()

    def test_known_phases_only(self):
        for phase in ('SUCCEEDED', 'COPIED_UNVERIFIED', 'CLEANUP_PENDING', 'RECONCILIATION_REQUIRED', 'CANCELLED'):
            record(self.db, phase, phase)
        with self.assertRaises(sqlite3.IntegrityError):
            record(self.db, 'bad', 'FAKE_SUCCESS')

    def test_blob_and_identity_bounds(self):
        for op, blob in [('', b'12345678'), ('x' * 129, b'12345678'), ('small', b'1234567'), ('large', b'x' * 4097)]:
            with self.assertRaises(sqlite3.IntegrityError):
                record(self.db, op, blob=blob)
        record(self.db, 'largest', blob=b'x' * 4096)

    def test_uri_bounded_by_utf8_bytes(self):
        record(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            target(self.db, uri='中' * 3000)
        target(self.db, uri='content://provider/document/generated')

    def test_no_target_without_durable_operation(self):
        with self.assertRaises(sqlite3.IntegrityError):
            target(self.db)

    def test_operation_and_nonce_not_reused(self):
        record(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            record(self.db)
        target(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            target(self.db)

    def test_saf_output_adoption_transaction_rolls_back(self):
        record(self.db)
        target(self.db, kind='SAF')
        self.db.commit()
        try:
            with self.db:
                self.db.execute("UPDATE library_export_target SET uri='content://provider/document/new' WHERE operation_id='op'")
                self.db.execute("UPDATE library_export SET record=? WHERE operation_id='op'", (b'x' * 4097,))
        except sqlite3.IntegrityError:
            pass
        self.assertIsNone(self.db.execute('SELECT uri FROM library_export_target').fetchone()[0])
        self.assertIn('fun bindSafOutput', JOURNAL)
        adoption = JOURNAL.split('fun bindSafOutput', 1)[1].split('fun bind(', 1)[0]
        self.assertIn('transaction {', adoption)
        self.assertIn('phase = ExportPhase.PREPARED, output = ExportOutput', adoption)
        self.assertIn('journal.bindSafOutput', PORT)

    def test_rotating_bounded_recovery_no_starvation(self):
        for i in range(75):
            record(self.db, f'op-{i:03}', 'RECONCILIATION_REQUIRED')
        seen = set()
        for _ in range(3):
            cursor = self.db.execute('SELECT recovery_cursor FROM library_export_state').fetchone()[0]
            rows = self.db.execute(RECOVERY, (cursor, '32')).fetchall()
            if not rows:
                rows = self.db.execute(RECOVERY, ('', '32')).fetchall()
            self.assertLessEqual(len(rows), 32)
            seen.update(x[0] for x in rows)
            self.db.execute('UPDATE library_export_state SET recovery_cursor=?', (rows[-1][0],))
        self.assertEqual(len(seen), 75)

    def test_terminal_rows_are_retained_but_not_recovered(self):
        for phase in ('SUCCEEDED', 'COPIED_UNVERIFIED', 'FAILED', 'CANCELLED'):
            record(self.db, phase, phase)
        self.assertEqual(self.db.execute(RECOVERY, ('', '32')).fetchall(), [])
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM library_export').fetchone()[0], 4)
        self.assertNotIn('ExportLimits.MAX_RECORDS', JOURNAL)
        self.assertIn('ExportLimits.MAX_ACTIVE', JOURNAL)

    def test_thousands_of_terminal_rows_do_not_block_active_admission(self):
        for index in range(1_105):
            record(self.db, f'history-{index}', 'SUCCEEDED')
        count = self.db.execute("SELECT COUNT(*),SUM(CASE WHEN phase NOT IN ('SUCCEEDED','COPIED_UNVERIFIED','FAILED','CANCELLED') THEN 1 ELSE 0 END) FROM library_export").fetchone()
        self.assertEqual(count, (1105, 0))
        record(self.db, 'new-active')
        self.assertEqual(self.db.execute("SELECT phase FROM library_export WHERE operation_id='history-0'").fetchone()[0], 'SUCCEEDED')
        with self.assertRaises(sqlite3.IntegrityError):
            record(self.db, 'history-0')

    def test_review_pages_retain_all_unverified_warnings_after_reopen(self):
        for index in range(70):
            record(self.db, f'review-{index:03}', 'COPIED_UNVERIFIED')
            target(self.db, f'review-{index:03}', kind='SAF', uri=f'content://synthetic/document/{index}')
        self.db.commit()
        # A real on-disk reopen, not another query against the original connection.
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name) / 'synthetic-export-journal.sqlite'
        disk = sqlite3.connect(path)
        self.db.backup(disk)
        disk.close()
        self.db.close()
        self.db = sqlite3.connect(path)
        self.db.execute('PRAGMA foreign_keys=ON')
        where = re.search(r'REVIEW_WHERE = "([^\"]+)"', JOURNAL).group(1)
        total = self.db.execute(f'SELECT COUNT(*) FROM library_export e JOIN library_export_target t ON t.operation_id=e.operation_id WHERE {where}').fetchone()[0]
        seen = []
        for page in range(3):
            rows = self.db.execute(f'SELECT e.operation_id FROM library_export e JOIN library_export_target t ON t.operation_id=e.operation_id WHERE {where} ORDER BY e.operation_id LIMIT ? OFFSET ?', (32, page * 32)).fetchall()
            self.assertLessEqual(len(rows), 32)
            seen.extend(row[0] for row in rows)
        self.assertEqual(total, 70)
        self.assertEqual(len(set(seen)), 70)
        self.assertIn('while (liveSaf.size >= ExportLimits.MAX_ACTIVE', PORT)
        self.assertIn('releaseFinishedGrant(record: ExportRecord)', PORT)

    def test_v2_upgrade_transaction_failure_retains_version(self):
        db = sqlite3.connect(':memory:')
        db.execute('CREATE TABLE prior(value TEXT)')
        db.execute("INSERT INTO prior VALUES('preserved')")
        db.execute('PRAGMA user_version=2')
        db.commit()
        try:
            with db:
                db.execute('BEGIN')
                for sql in DDL:
                    db.execute(sql)
                db.execute('PRAGMA user_version=3')
                db.execute('INSERT INTO library_export_state(singleton) VALUES(2)')
        except sqlite3.IntegrityError:
            pass
        self.assertEqual(db.execute('PRAGMA user_version').fetchone()[0], 2)
        self.assertEqual(db.execute('SELECT * FROM prior').fetchone()[0], 'preserved')
        self.assertEqual(db.execute("SELECT name FROM sqlite_master WHERE name='library_export'").fetchall(), [])
        db.close()

    def test_pending_row_visible_to_crash_gap_reconciliation(self):
        self.assertIn('putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)', PORT)
        self.assertIn('putInt(ContentResolver.QUERY_ARG_LIMIT, 2)', PORT)
        self.assertIn('cursor.getString(3) == context.packageName', PORT)
        self.assertIn('cursor.moveToNext()', PORT)
        self.assertNotIn('takePersistableUriPermission', PORT)
        self.assertIn('resolver.openFileDescriptor(uri, "wt")', PORT)

    def test_provider_is_capability_only_readonly_proxy(self):
        self.assertIn('android:exported="false" android:grantUriPermissions="true"', MANIFEST)
        self.assertNotIn('FileProvider', MANIFEST)
        self.assertNotIn('READ_EXTERNAL_STORAGE', MANIFEST)
        self.assertNotIn('WRITE_EXTERNAL_STORAGE', MANIFEST)
        self.assertIn('SharePolicy.readOnly(mode)', PROVIDER)
        self.assertIn('openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY', SHARE)
        self.assertIn('owned.also { owned = null }', SHARE)
        self.assertIn('proxyIssuances >= SharePolicy.MAX_PROXY_ISSUANCES', SHARE)
        self.assertIn('ShareProxyAdmission.open(lock', SHARE)
        self.assertIn('current.proxyReader.read(offset, size, data)', SHARE)
        self.assertIn('MaintenanceLane.handler.removeCallbacks(it)', SHARE)
        self.assertNotIn('Handler(Looper.getMainLooper())', SHARE)
        self.assertNotIn('ParcelFileDescriptor.open(file', SHARE)
        self.assertIn('ShareCopyVerifier.verify(readback', SHARE)
        self.assertIn('copy.uncertain || copy.transfers.isNotEmpty()', SHARE)
        self.assertIn('revokeOperation(operationId: String)', SHARE)

    def test_source_exact_authority_and_no_save_codec_edits_required(self):
        self.assertIn('storage.openLibraryMedia(request.source)', PORT)
        self.assertIn('LibraryMediaInput(openAuthority(authority), authority.sizeBytes, authority.sha256)', STORAGE)
        self.assertIn('oldVersion !in 1..4 || newVersion != 5', STORAGE)
        self.assertIn('if (oldVersion == 1)', STORAGE)


if __name__ == '__main__':
    unittest.main(verbosity=2)
