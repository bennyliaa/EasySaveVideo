#!/usr/bin/env python3
"""Historical v1→v2 host SQLite rights contracts using pinned old CREATE DDL.

The rights ALTER and bounded projections are extracted from AndroidDiscoveryStore.
Target v3 migration and history contracts are in test-discovery-history-sqlite.py.

The migration runner models SQLiteOpenHelper's version transaction on host SQLite.
It does not execute AndroidDiscoveryStore, SQLiteOpenHelper, the Kotlin rights codec,
CursorWindow, or an Android device. Payloads in these SQL tests are opaque bytes.
"""
from pathlib import Path
import json
import re
import sqlite3
import struct
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / 'app/src/main/kotlin/com/easysavevideo/app/storage/AndroidDiscoveryStore.kt').read_text()
# This suite intentionally preserves the pre-history v1→v2 rights contracts.
# The target adapter now also upgrades to v3; those steps are exercised in
# test-discovery-history-sqlite.py. Do not derive old DDL from target CREATE SQL.
TARGET_CREATE_SQL = re.findall(r'db.execSQL\("(CREATE TABLE [^\"]+)"\)', SOURCE)
HELPER = re.search(r'SQLiteOpenHelper\(context.applicationContext,\s*"([^\"]+)",\s*null,\s*(\d+)', SOURCE)
assert HELPER and HELPER.group(1) == 'discovery_v1.db', 'Upgrade the existing database in place'
assert int(HELPER.group(2)) in (2, 3), 'Unexpected target schema'
UPGRADE_SOURCE = SOURCE.split('override fun onUpgrade', 1)[1].split('override fun onDowngrade', 1)[0]
UPGRADE_SQL = [sql for sql in re.findall(r'db.execSQL\("([^\"]+)"\)', UPGRADE_SOURCE)
               if sql == 'ALTER TABLE results ADD COLUMN rights BLOB']
assert UPGRADE_SQL == ['ALTER TABLE results ADD COLUMN rights BLOB'], 'Historical rights ALTER must remain source-extracted'
assert 'DISCOVERY_MIGRATION_REQUIRED' in UPGRADE_SOURCE

# Pinned historical v1/v2 schema, independent of every target schema statement.
# These strings are the actual six-table schemas before M8-B1.
LEGACY_RESULTS_SQL = (
    'CREATE TABLE results (session_id TEXT NOT NULL, result_id TEXT NOT NULL, '
    'page_url TEXT NOT NULL, title TEXT, source_name TEXT NOT NULL, source_namespace TEXT, '
    'source_video_id TEXT, thumbnail TEXT, duration INTEGER, description TEXT, '
    'published_at INTEGER, provenance TEXT NOT NULL, aliases TEXT NOT NULL, '
    'identity_version INTEGER NOT NULL, discovered_at INTEGER NOT NULL, '
    'entry_order INTEGER NOT NULL, PRIMARY KEY(session_id,result_id))'
)
V2_RESULTS_SQL = (
    'CREATE TABLE results (session_id TEXT NOT NULL, result_id TEXT NOT NULL, '
    'page_url TEXT NOT NULL, title TEXT, source_name TEXT NOT NULL, source_namespace TEXT, '
    'source_video_id TEXT, thumbnail TEXT, duration INTEGER, description TEXT, '
    'published_at INTEGER, provenance TEXT NOT NULL, aliases TEXT NOT NULL, '
    'identity_version INTEGER NOT NULL, discovered_at INTEGER NOT NULL, '
    'entry_order INTEGER NOT NULL, rights BLOB, PRIMARY KEY(session_id,result_id))'
)
HISTORICAL_COMMON_SQL = (
    'CREATE TABLE checkpoints (session_id TEXT PRIMARY KEY NOT NULL, epoch INTEGER NOT NULL CHECK(epoch>0), payload BLOB NOT NULL)',
    'CREATE TABLE pages (session_id TEXT NOT NULL, page_index INTEGER NOT NULL, revision INTEGER NOT NULL, page_size INTEGER NOT NULL, result_ids TEXT NOT NULL, PRIMARY KEY(session_id,page_index))',
    'CREATE TABLE progress (profile_id TEXT NOT NULL, provider_id TEXT NOT NULL, query_key TEXT NOT NULL, provider_version TEXT NOT NULL, cursor_schema INTEGER NOT NULL, cursor TEXT, expires_at INTEGER, exhausted INTEGER NOT NULL, last_success INTEGER, PRIMARY KEY(profile_id,provider_id,query_key))',
    'CREATE TABLE seen (profile_id TEXT NOT NULL, fingerprint TEXT NOT NULL, first_discovered INTEGER NOT NULL, shown_at INTEGER, opened_at INTEGER, played_at INTEGER, saved_at INTEGER, dismissed_at INTEGER, PRIMARY KEY(profile_id,fingerprint))',
    'CREATE TABLE viewport (session_id TEXT PRIMARY KEY NOT NULL, epoch INTEGER NOT NULL, page_index INTEGER NOT NULL)',
)
V1_CREATE_SQL = (HISTORICAL_COMMON_SQL[0], LEGACY_RESULTS_SQL) + HISTORICAL_COMMON_SQL[1:]
CREATE_SQL = (HISTORICAL_COMMON_SQL[0], V2_RESULTS_SQL) + HISTORICAL_COMMON_SQL[1:]
TABLES = tuple(re.match(r'CREATE TABLE (\w+) ', sql).group(1) for sql in CREATE_SQL)
assert set(TABLES) == {'checkpoints', 'results', 'pages', 'progress', 'seen', 'viewport'}
assert V2_RESULTS_SQL in TARGET_CREATE_SQL, 'Target results schema changed; reconcile retained rights contracts explicitly'
RIGHTS_SOURCE = (ROOT / 'discovery/src/main/kotlin/com/easysavevideo/discovery/RightsAttribution.kt').read_text()
assert 'const val MAX_BYTES = 32 * 1024' in RIGHTS_SOURCE
assert 'private const val MAGIC = 0x45535231' in RIGHTS_SOURCE
assert 'private const val VERSION = 1' in RIGHTS_SOURCE
RIGHTS_MAGIC = 0x45535231
RIGHTS_LIMIT = 32 * 1024
WARNING_NAMES = {'CONFLICTING_REPORTS', 'STALE_OBSERVATION', 'PRIOR_REVIEW_WARNING'}
warning = b'PRIOR_REVIEW_WARNING'
# A warning-only envelope is a real nonempty rights report in this format. These
# bytes are independently encoded in Python, not produced by executing Kotlin.
SAMPLE_RIGHTS = struct.pack('>iiii', RIGHTS_MAGIC, 1, 1, len(warning)) + warning + struct.pack('>i', 0)
EMPTY_RIGHTS = struct.pack('>iiii', RIGHTS_MAGIC, 1, 0, 0)


def columns(db, table):
    return [row[1] for row in db.execute('PRAGMA table_info(' + table + ')')]


def result_row(session='legacy', result_id='legacy-result', rights=None, order=0):
    return dict(session_id=session, result_id=result_id, page_url='https://example.com/video',
                title='Legacy title', source_name='example', source_namespace=None,
                source_video_id=None, thumbnail=None, duration=None, description=None,
                published_at=None, provenance='provider', aliases='a' * 64,
                identity_version=1, discovered_at=1, entry_order=order, rights=rights)


def insert_result(db, data):
    names = columns(db, 'results')
    db.execute('INSERT INTO results(' + ','.join(names) + ') VALUES(' +
               ','.join('?' for _ in names) + ')', [data[name] for name in names])


def create(db, legacy=False):
    for sql in CREATE_SQL:
        db.execute(LEGACY_RESULTS_SQL if legacy and sql.startswith('CREATE TABLE results ') else sql)
    db.execute('PRAGMA user_version=' + ('1' if legacy else '2'))
    db.commit()


def seed_legacy(db):
    for session in ('legacy', 'other'):
        db.execute('INSERT INTO checkpoints VALUES (?,?,?)', (session, 1, b'bounded-header-' + session.encode()))
        insert_result(db, result_row(session=session, result_id=session + '-result'))
        db.execute('INSERT INTO pages VALUES (?,?,?,?,?)', (session, 0, 1, 20, session + '-result'))
        db.execute('INSERT INTO viewport VALUES (?,?,?)', (session, 1, 0))
    db.execute('INSERT INTO progress VALUES (?,?,?,?,?,?,?,?,?)', ('p', 'provider', 'q', '1', 1, 'old', None, 0, 1))
    db.execute('INSERT INTO seen VALUES (?,?,?,?,?,?,?,?)', ('p', 'fingerprint', 1, None, None, None, None, None))
    db.commit()


def snapshot(db):
    return (
        db.execute('PRAGMA user_version').fetchone(),
        db.execute('SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name').fetchall(),
        {table: db.execute('SELECT * FROM ' + table + ' ORDER BY rowid').fetchall() for table in TABLES},
    )


def schema_by_name(db):
    # Column order differs legitimately if a fresh schema groups its columns;
    # compare every name/type/nullability/default/PK fact, excluding its ordinal.
    return {table: sorted(row[1:] for row in db.execute('PRAGMA table_info(' + table + ')')) for table in TABLES}


class InjectedFailure(RuntimeError):
    pass


def migrate(db, fail_at=None):
    """Host-only model of the helper's atomic onUpgrade + user_version update."""
    version = db.execute('PRAGMA user_version').fetchone()[0]
    if version == 2:
        return False
    if version != 1:
        raise ValueError('DISCOVERY_MIGRATION_REQUIRED')
    # Python's `with db` alone does not begin a DDL transaction. BEGIN explicitly
    # to model Android SQLiteOpenHelper's transaction around onUpgrade/setVersion.
    db.execute('BEGIN IMMEDIATE')
    try:
        for sql in UPGRADE_SQL:
            db.execute(sql)
        if fail_at == 'after_ddl':
            raise InjectedFailure(fail_at)
        if fail_at == 'after_rights_insert':
            insert_result(db, result_row(result_id='injected-rights', rights=SAMPLE_RIGHTS))
            raise InjectedFailure(fail_at)
        db.execute('PRAGMA user_version=2')
        if fail_at == 'after_version':
            raise InjectedFailure(fail_at)
        db.commit()
    except Exception:
        db.rollback()
        raise
    return True


def expect_failure(action, error):
    try:
        action()
    except error:
        return
    raise AssertionError('Expected ' + error.__name__)


def upsert(db, table, data, keys):
    # The SQL below mirrors the adapter's generic Kotlin interpolation. Guard the
    # relevant source fragments so this host test cannot silently diverge from it.
    assert 'val columns = values.keySet().sorted()' in SOURCE
    assert 'val changed = columns.filterNot { it in keys }' in SOURCE
    assert '"$it=excluded.$it"' in SOURCE
    assert '"$it IS NOT excluded.$it"' in SOURCE
    names = sorted(data)
    changed = [name for name in names if name not in keys]
    sql = ('INSERT INTO ' + table + ' (' + ','.join(names) + ') VALUES (' +
           ','.join('?' for _ in names) + ') ON CONFLICT(' + ','.join(keys) +
           ') DO UPDATE SET ' + ','.join(name + '=excluded.' + name for name in changed) +
           ' WHERE ' + ' OR '.join(name + ' IS NOT excluded.' + name for name in changed))
    db.execute(sql, [data[name] for name in names])


def test_migration(directory):
    fresh = sqlite3.connect(':memory:')
    create(fresh)
    rights_column = [row for row in fresh.execute('PRAGMA table_info(results)') if row[1] == 'rights']
    assert len(rights_column) == 1 and rights_column[0][2:5] == ('BLOB', 0, None)
    expected_schema = schema_by_name(fresh)
    fresh.close()
    path = directory / HELPER.group(1)
    db = sqlite3.connect(path)
    create(db, legacy=True)
    seed_legacy(db)
    original = snapshot(db)
    for fail_at in ('after_ddl', 'after_rights_insert', 'after_version'):
        expect_failure(lambda: migrate(db, fail_at), InjectedFailure)
        assert snapshot(db) == original, 'Migration rollback changed legacy schema, version, or rows'
        db.close()
        db = sqlite3.connect(path)
        assert snapshot(db) == original, 'Failed migration did not survive disk reopen unchanged'
        assert 'rights' not in columns(db, 'results')
    assert migrate(db) is True
    assert schema_by_name(db) == expected_schema
    assert db.execute('PRAGMA user_version').fetchone() == (2,)
    assert db.execute('SELECT rights FROM results').fetchall() == [(None,), (None,)]
    # Every original value survives the real ALTER SQL; only nullable rights is new.
    for table, before in original[2].items():
        names = [name for name in columns(db, table) if table != 'results' or name != 'rights']
        assert db.execute('SELECT ' + ','.join(names) + ' FROM ' + table + ' ORDER BY rowid').fetchall() == before
    insert_result(db, result_row(result_id='rights-result', rights=SAMPLE_RIGHTS, order=1))
    db.commit()
    upgraded = snapshot(db)
    for _ in range(3):
        db.close()
        db = sqlite3.connect(path)
        changes = db.total_changes
        assert migrate(db) is False
        assert db.total_changes == changes and snapshot(db) == upgraded
        assert columns(db, 'results').count('rights') == 1
    db.close()
    # Unsupported future versions are preserved, never reset as a recovery path.
    db = sqlite3.connect(':memory:')
    create(db)
    insert_result(db, result_row(rights=SAMPLE_RIGHTS))
    db.execute('PRAGMA user_version=3')
    db.commit()
    future = snapshot(db)
    expect_failure(lambda: migrate(db), ValueError)
    assert snapshot(db) == future
    db.close()
    return path


def test_transactions_and_upserts(db):
    before = snapshot(db)
    try:
        with db:
            db.execute('UPDATE checkpoints SET epoch=2 WHERE session_id=?', ('legacy',))
            db.execute('UPDATE results SET rights=? WHERE session_id=?', (b'changed-rights', 'legacy'))
            db.execute('UPDATE pages SET revision=2 WHERE session_id=?', ('legacy',))
            db.execute('UPDATE progress SET cursor=?', ('new',))
            db.execute('UPDATE seen SET shown_at=2')
            raise InjectedFailure('checkpoint transaction')
    except InjectedFailure:
        pass
    assert snapshot(db) == before
    changes = db.total_changes
    upsert(db, 'checkpoints', dict(session_id='legacy', epoch=1, payload=b'bounded-header-legacy'), ['session_id'])
    assert db.total_changes == changes
    row = result_row(rights=None)
    rowid = db.execute('SELECT rowid FROM results WHERE session_id=? AND result_id=?', ('legacy', 'legacy-result')).fetchone()
    # NULL -> NULL, NULL -> BLOB, identical BLOB bytes, changed BLOB, BLOB -> NULL.
    for rights, expected_writes in ((None, 0), (SAMPLE_RIGHTS, 1),
                                    (bytes(bytearray(SAMPLE_RIGHTS)), 0),
                                    (SAMPLE_RIGHTS + b'changed', 1), (None, 1), (None, 0)):
        row['rights'] = rights
        changes = db.total_changes
        upsert(db, 'results', row, ['session_id', 'result_id'])
        assert db.total_changes - changes == expected_writes
        assert db.execute('SELECT rowid FROM results WHERE session_id=? AND result_id=?', ('legacy', 'legacy-result')).fetchone() == rowid
        names = columns(db, 'results')
        stored = db.execute('SELECT * FROM results WHERE session_id=? AND result_id=?', ('legacy', 'legacy-result')).fetchone()
        assert dict(zip(names, stored)) == row
    # SQL preserves unrecognized/corrupt bytes instead of dropping the row/payload.
    for index, payload in enumerate((b'', b'\xff\x00corrupt', b'unknown-version\x7f', b'x' * (32 * 1024 + 1))):
        row = result_row(session='invalid-payloads', result_id=str(index), rights=payload)
        insert_result(db, row)
        changes = db.total_changes
        upsert(db, 'results', row, ['session_id', 'result_id'])
        assert db.total_changes == changes
        assert db.execute('SELECT typeof(rights),rights FROM results WHERE session_id=? AND result_id=?', ('invalid-payloads', str(index))).fetchone() == ('blob', payload)
    db.commit()



def decode_warning_only_envelope(payload):
    """Independent, deliberately partial Python decoder, never a Kotlin test.

    Only empty or warning-only envelopes are modeled. Snapshot field decoding
    and the actual RightsCodec implementation are tested separately on the JVM.
    """
    if payload is None:
        return set()
    if not isinstance(payload, bytes) or len(payload) > RIGHTS_LIMIT:
        raise ValueError('RIGHTS_BOUND')
    offset = 0

    def integer():
        nonlocal offset
        if offset + 4 > len(payload):
            raise ValueError('RIGHTS_TRUNCATED')
        value = struct.unpack_from('>i', payload, offset)[0]
        offset += 4
        return value

    if integer() != RIGHTS_MAGIC or integer() != 1:
        raise ValueError('RIGHTS_UNKNOWN_FORMAT')
    count = integer()
    if not 0 <= count <= len(WARNING_NAMES):
        raise ValueError('RIGHTS_WARNING_COUNT')
    warnings = set()
    for _ in range(count):
        length = integer()
        if not 0 <= length <= 64 * 4 or offset + length > len(payload):
            raise ValueError('RIGHTS_TEXT_BOUND')
        try:
            name = payload[offset:offset + length].decode('utf-8', errors='strict')
        except UnicodeDecodeError as error:
            raise ValueError('RIGHTS_TEXT_ENCODING') from error
        offset += length
        if len(name) > 64 or name not in WARNING_NAMES or name in warnings:
            raise ValueError('RIGHTS_WARNING')
        warnings.add(name)
    if integer() != 0:
        raise ValueError('HOST_TEST_ONLY_SUPPORTS_ZERO_SNAPSHOTS')
    if offset != len(payload):
        raise ValueError('RIGHTS_TRAILING_BYTES')
    return warnings


def test_host_decode_failure(db):
    assert decode_warning_only_envelope(None) == set()
    assert decode_warning_only_envelope(EMPTY_RIGHTS) == set()
    assert decode_warning_only_envelope(SAMPLE_RIGHTS) == {'PRIOR_REVIEW_WARNING'}
    projection = re.findall(r'"(length\(CAST\(rights AS BLOB\)\) AS rights_bytes|CASE WHEN typeof\(rights\)=\'blob\' AND length\(rights\)<=32768 THEN rights ELSE NULL END AS rights)"', SOURCE)
    assert len(projection) == 2, 'Use both actual bounded source projection expressions'
    # Source-backed preflight ordering is checked, but this Python helper is not
    # execution evidence for AndroidDiscoveryStore's checkpoint implementation.
    put_source = SOURCE.split('override fun putCheckpoint', 1)[1].split('override fun removeCheckpoint', 1)[0]
    assert put_source.index('checkpoint(session)') < put_source.index('upsert("checkpoints"')
    malformed = (
        b'', EMPTY_RIGHTS[:-1], b'BAD!' + EMPTY_RIGHTS[4:],
        struct.pack('>iiii', RIGHTS_MAGIC, 2, 0, 0), EMPTY_RIGHTS + b'\x00',
        struct.pack('>iii', RIGHTS_MAGIC, 1, -1),
        struct.pack('>iii', RIGHTS_MAGIC, 1, 4),
        struct.pack('>iiii', RIGHTS_MAGIC, 1, 1, -1),
        struct.pack('>iiii', RIGHTS_MAGIC, 1, 1, 257),
        struct.pack('>iiii', RIGHTS_MAGIC, 1, 1, 1) + b'\xff' + struct.pack('>i', 0),
        struct.pack('>iiii', RIGHTS_MAGIC, 1, 1, 7) + b'UNKNOWN' + struct.pack('>i', 0),
        SAMPLE_RIGHTS + b'x' * RIGHTS_LIMIT,
        'not-a-blob', 'before-null\x00' + '界' * 40000, 12345,
    )
    for index, payload in enumerate((None, EMPTY_RIGHTS, SAMPLE_RIGHTS) + malformed):
        insert_result(db, result_row(session='decode-fixtures', result_id=str(index), rights=payload))
    db.commit()

    def read_warning_only(result_id):
        length, payload = db.execute('SELECT ' + ','.join(projection) +
                                    ' FROM results WHERE session_id=? AND result_id=?',
                                    ('decode-fixtures', str(result_id))).fetchone()
        if length is None:
            return decode_warning_only_envelope(None)
        if length > RIGHTS_LIMIT:
            assert payload is None, 'Oversize data must not be returned in the SQL BLOB projection'
            raise ValueError('RIGHTS_BOUND')
        if payload is None:
            raise ValueError('RIGHTS_STORAGE_TYPE')
        assert len(payload) == length
        return decode_warning_only_envelope(payload)

    assert read_warning_only(0) == set()
    assert read_warning_only(1) == set()
    assert read_warning_only(2) == {'PRIOR_REVIEW_WARNING'}
    for index, payload in enumerate(malformed, start=3):
        before = snapshot(db)
        try:
            with db:
                db.execute('UPDATE progress SET cursor=?', ('must-rollback',))
                db.execute('UPDATE seen SET shown_at=99')
                read_warning_only(index)
        except ValueError:
            pass
        else:
            raise AssertionError('Malformed/future envelope was accepted by host decoder')
        assert snapshot(db) == before
        assert db.execute('SELECT rights FROM results WHERE session_id=? AND result_id=?',
                          ('decode-fixtures', str(index))).fetchone() == (payload,)


def test_independence_and_cleanup(db):
    rights_before = db.execute('SELECT session_id,result_id,rights FROM results ORDER BY session_id,result_id').fetchall()
    seen_before = db.execute('SELECT * FROM seen').fetchall()
    with db:
        db.execute('DELETE FROM progress WHERE profile_id=?', ('p',))
    assert db.execute('SELECT * FROM seen').fetchall() == seen_before
    assert db.execute('SELECT session_id,result_id,rights FROM results ORDER BY session_id,result_id').fetchall() == rights_before
    with db:
        db.execute('INSERT INTO progress VALUES (?,?,?,?,?,?,?,?,?)', ('p', 'provider', 'q', '1', 1, 'new', None, 0, 2))
        db.execute('DELETE FROM seen WHERE profile_id=?', ('p',))
    assert db.execute('SELECT cursor FROM progress').fetchone() == ('new',)
    assert db.execute('SELECT session_id,result_id,rights FROM results ORDER BY session_id,result_id').fetchall() == rights_before
    with db:
        db.execute('INSERT INTO seen VALUES (?,?,?,?,?,?,?,?)', ('p', 'fingerprint', 1, None, None, None, None, None))
        db.execute('UPDATE results SET rights=? WHERE session_id=?', (SAMPLE_RIGHTS, 'other'))
    # Mirror checkpoint pool eviction, leaving the other session untouched.
    assert ' AND result_id NOT IN (' in SOURCE
    with db:
        db.execute('DELETE FROM results WHERE session_id=? AND result_id NOT IN (?)', ('legacy', 'legacy-result'))
    assert db.execute('SELECT count(*) FROM results WHERE result_id=?', ('rights-result',)).fetchone() == (0,)
    assert db.execute('SELECT rights FROM results WHERE session_id=?', ('other',)).fetchone() == (SAMPLE_RIGHTS,)
    progress_before = db.execute('SELECT * FROM progress').fetchall()
    seen_before = db.execute('SELECT * FROM seen').fetchall()
    other_before = {table: db.execute('SELECT * FROM ' + table + ' WHERE session_id=?', ('other',)).fetchall()
                    for table in ('checkpoints', 'results', 'pages', 'viewport')}
    remove = re.search(r'override fun removeCheckpoint\(sessionId: String\).*?listOf\(([^)]+)\)', SOURCE, re.S)
    assert remove, 'Extract checkpoint-owned tables from adapter removeCheckpoint'
    remove_tables = re.findall(r'"(\w+)"', remove.group(1))
    assert set(remove_tables) == {'checkpoints', 'results', 'pages', 'viewport'}
    with db:
        for table in remove_tables:
            db.execute('DELETE FROM ' + table + ' WHERE session_id=?', ('legacy',))
    for table in remove_tables:
        assert db.execute('SELECT count(*) FROM ' + table + ' WHERE session_id=?', ('legacy',)).fetchone() == (0,)
        assert db.execute('SELECT * FROM ' + table + ' WHERE session_id=?', ('other',)).fetchall() == other_before[table]
    assert db.execute('SELECT * FROM progress').fetchall() == progress_before
    assert db.execute('SELECT * FROM seen').fetchall() == seen_before
    # Rights are inline in results; there is no independently orphanable rights table.
    assert db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall() == [(name,) for name in sorted(TABLES)]


def test_wide_rows():
    db = sqlite3.connect(':memory:')
    create(db)
    for i in range(500):
        row = result_row(session='wide', result_id=str(i), rights=b'r' * (32 * 1024), order=i)
        row.update(page_url='https://example.com/' + 'x' * 8000, title='界' * 512,
                   thumbnail='https://example.com/' + 'y' * 8000, description='界' * 4096)
        insert_result(db, row)
    rows = db.execute('SELECT * FROM results').fetchall()
    assert len(rows) == 500
    # Host payload-byte estimate only: no SQLite record overhead/CursorWindow claim.
    sizes = [sum(len(value.encode()) if isinstance(value, str) else len(value) if isinstance(value, bytes)
                 else 0 if value is None else 8 for value in row) for row in rows]
    assert sum(sizes) > 2 * 1024 * 1024 and max(sizes) < 128 * 1024
    db.close()
    return sizes


def main():
    with tempfile.TemporaryDirectory(prefix='discovery-sqlite-') as temporary:
        path = test_migration(Path(temporary))
        db = sqlite3.connect(path)
        test_transactions_and_upserts(db)
        test_host_decode_failure(db)
        test_independence_and_cleanup(db)
        stored = snapshot(db)
        db.close()
        db = sqlite3.connect(path)
        assert snapshot(db) == stored, 'Rights fixtures and independent rows survive disk reopen'
        db.close()
    sizes = test_wide_rows()
    report = {
        'host_sqlite_version': sqlite3.sqlite_version,
        'ddl_tables': len(CREATE_SQL),
        'ddl_scope': 'pinned historical v1/v2, independent of current target schema',
        'adapter_target_schema': int(HELPER.group(2)),
        'database_filename_preserved': HELPER.group(1),
        'migration_v1_to_v2_legacy_null_rights': 'PASS',
        'fresh_v2_matches_migrated_schema': 'PASS',
        'migration_disk_reopen_idempotence': 'PASS',
        'migration_rollback_after_ddl': 'PASS',
        'migration_rollback_after_rights_insert': 'PASS',
        'migration_rollback_after_version_update': 'PASS',
        'unsupported_version_preserved_host_model': 'PASS',
        'atomic_rollback_including_rights': 'PASS',
        'seen_progress_rights_independent': 'PASS',
        'unchanged_upsert_no_write': 'PASS',
        'changed_blob_and_null_upsert': 'PASS',
        'corrupt_unknown_oversize_blob_storage_preserved': 'PASS',
        'rights_storage_survives_disk_reopen': 'PASS',
        'bounded_rights_sql_projection': 'PASS',
        'host_warning_envelope_decode_rejection_preserves_rows': 'PASS',
        'host_decode_failure_rolls_back_progress_and_seen': 'PASS',
        'eviction_removal_no_orphan_rights': 'PASS',
        'wide_rows': len(sizes),
        'rights_blob_bytes_per_wide_row': 32 * 1024,
        'max_result_row_bytes_estimate': max(sizes),
        'total_result_bytes_estimate': sum(sizes),
        'kotlin_rights_codec_execution': 'NOT_RUN',
        'android_sqlite_runtime': 'NOT_RUN',
        'cursor_window_device_check': 'NOT_RUN',
    }
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
