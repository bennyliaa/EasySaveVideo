"""Additive checkpoint-payload v4 host model; SQLite schema remains exactly v3.

This models bounded empty checkpoint headers and real host SQLite transactions.
It does not execute the Kotlin codec or Android SQLiteOpenHelper. Original v1-v3
cases live unchanged in the mechanically adapted inherited history wrapper.
"""
from pathlib import Path
import re
import sqlite3
import struct
import tempfile


def verify_v4_source_guards(source):
    required = (
        'require(version in 1..4)',
        'out.writeInt(if (value.sourceConfigurationKey == null && value.queryAuthorized) 3 else 4)',
        'if (value.sourceConfigurationKey != null || !value.queryAuthorized)',
        'out.optionalText(value.sourceConfigurationKey); out.writeBoolean(value.queryAuthorized)',
        'val configurationKey = if (version >= 4) input.optionalText(64) else null',
        'val queryAuthorized = if (version >= 4) input.boolean() else true',
        'require(input.available() == 0)',
        'require(value.sourceConfigurationKey == null || value.sourceConfigurationKey!!.matches(Regex("[a-f0-9]{64}")))',
        'private fun DataInputStream.optionalText(maxCharacters: Int): String? = if (boolean()) text(maxCharacters) else null',
        '0 -> false; 1 -> true; else -> throw IllegalArgumentException("CHECKPOINT_BOOLEAN")',
    )
    for fragment in required:
        assert fragment in source, ('CHECKPOINT_V4_SOURCE_GUARD', fragment)
    assert source.index('val configurationKey = if (version >= 4)') < source.index('val queryAuthorized = if (version >= 4)') < source.index('require(input.available() == 0)')


def read_configuration_tail(reader, result, error):
    if result['version'] >= 4:
        key = reader.text(64) if reader.boolean() else None
        authorized = reader.boolean()
        if key is not None and re.fullmatch(r'[a-f0-9]{64}', key) is None:
            raise error('CHECKPOINT_CONFIGURATION_KEY')
        result = {**result, 'source_configuration_key': key, 'query_authorized': authorized}
    return result


def test_v4_headers_and_sqlite(h):
    cases = []
    def checked(name, condition):
        assert condition, name
        cases.append(name)
    def text(value):
        raw = value.encode('utf-8')
        return struct.pack('>i', len(raw)) + raw
    def tail(key, authorized):
        return (b'\x00' if key is None else b'\x01' + text(key)) + bytes([int(authorized)])
    def payload(key, authorized, session='configured', profile='configured-profile'):
        legacy = h.encode_header(session, profile)
        return legacy[:4] + struct.pack('>i', 4) + legacy[8:] + tail(key, authorized)
    def rejected(name, data):
        h.failure(lambda: h.decode_header(data), h.HeaderError)
        cases.append(name)

    for key in (None, 'a' * 64):
        for authorized in (False, True):
            value = h.decode_header(payload(key, authorized))
            checked('valid_configuration_' + ('absent' if key is None else 'present') + '_' + str(authorized),
                value['version'] == 4 and value['source_configuration_key'] == key and value['query_authorized'] == authorized)
    good = payload('a' * 64, False)
    suffix = tail('a' * 64, False)
    base = good[:-len(suffix)]
    rejected('missing_entire_configuration_tail', base)
    rejected('missing_authorization_with_absent_key', base + b'\x00')
    rejected('missing_authorization_with_present_key', good[:-1])
    rejected('invalid_key_presence_boolean', base + b'\x02\x00')
    rejected('invalid_authorization_boolean', good[:-1] + b'\x02')
    rejected('trailing_bytes_after_v4_tail', good + b'x')
    rejected('negative_key_length', base + b'\x01' + struct.pack('>i', -1) + b'\x00')
    rejected('truncated_key_data', base + b'\x01' + struct.pack('>i', 64) + b'a' * 63)
    rejected('invalid_utf8_key', base + b'\x01' + struct.pack('>i', 1) + b'\xff\x00')
    for key in ('', 'a' * 63, 'a' * 65, 'A' * 64, 'g' * 64):
        rejected('invalid_key_' + str(len(key)) + '_' + (key[:1] or 'empty'), payload(key, False))
    for version in (0, 5, 99):
        rejected('unsupported_payload_version_' + str(version), good[:4] + struct.pack('>i', version) + good[8:])
    legacy = h.encode_header('legacy', 'legacy-profile')
    rejected('legacy_v3_cannot_smuggle_v4_tail', legacy + b'\x00\x00')
    for version in (1, 2):
        decoded = h.decode_header(h.old_header(version))
        checked('genuine_legacy_v' + str(version) + '_header_shape_unchanged',
            decoded['version'] == version and 'source_configuration_key' not in decoded and 'query_authorized' not in decoded)
    checked('legacy_v3_header_shape_unchanged',
        h.decode_header(legacy)['version'] == 3 and 'source_configuration_key' not in h.decode_header(legacy))

    with tempfile.TemporaryDirectory(prefix='searxng-checkpoint-v4-sqlite-') as temporary:
        path = Path(temporary) / 'schema3-payload4.db'
        db = sqlite3.connect(path)
        h.fresh(db)
        states = [('configured', 'configured-profile', 'a' * 64, False),
                  ('unconfigured', 'unconfigured-profile', None, False),
                  ('authorized', 'authorized-profile', 'b' * 64, True)]
        original_payloads = {}
        for session, profile, key, authorized in states:
            raw = payload(key, authorized, session, profile)
            query = h.decode_header(raw)['query_key']
            h.add_seen(db, profile, h.fingerprint(1), shown_at=5)
            h.add_progress(db, profile, 'synthetic-provider', query)
            h.add_checkpoint(db, session, profile, query)
            db.execute('UPDATE checkpoints SET payload=? WHERE session_id=?', (raw, session))
            original_payloads[session] = raw
        db.commit()
        checked('payload_v4_retains_sqlite_schema3', db.execute('PRAGMA user_version').fetchone() == (3,))
        before = h.snapshot(db)
        h.failure(lambda: h.transaction(db, lambda: h.clear_seen(db, 'configured-profile', inject=True)), h.Cancellation)
        checked('v4_seen_clear_cancellation_rolls_back', h.snapshot(db) == before)
        db.close(); db = sqlite3.connect(path)
        checked('v4_rollback_survives_disk_reopen', h.snapshot(db) == before)
        progress = h.rows(db, 'progress')
        h.transaction(db, lambda: h.clear_seen(db))
        expected_payloads = {}
        for session, profile, key, authorized in states:
            raw = h.rows(db, 'checkpoints', 'session_id=?', (session,))[0]['payload']
            decoded = h.decode_header(raw)
            checked('seen_clear_preserves_v4_fence_' + session,
                decoded['source_configuration_key'] == key and decoded['query_authorized'] == authorized and decoded['revision'] == 2)
            checked('seen_clear_preserves_exact_v4_tail_' + session,
                raw[-len(tail(key, authorized)):] == tail(key, authorized))
            prefix_reader, _ = h.header_prefix(original_payloads[session])
            offset = prefix_reader.offset - 16
            checked('seen_clear_changes_only_revision_bytes_' + session,
                raw == original_payloads[session][:offset] + struct.pack('>q', 2) + original_payloads[session][offset + 8:])
            expected_payloads[session] = raw
        checked('v4_seen_clear_does_not_reset_progress', h.rows(db, 'progress') == progress)
        committed = h.snapshot(db)
        db.close(); db = sqlite3.connect(path)
        checked('v4_committed_fences_survive_disk_reopen', h.snapshot(db) == committed)
        db.execute('UPDATE checkpoints SET payload=? WHERE session_id=?', (good[:-1], 'configured'))
        db.commit(); corrupt = h.snapshot(db)
        h.failure(lambda: h.transaction(db, lambda: h.clear_seen(db, 'configured-profile')), h.HeaderError)
        checked('malformed_v4_fence_rejects_clear_atomically', h.snapshot(db) == corrupt)
        db.close(); db = sqlite3.connect(path)
        checked('malformed_v4_rollback_survives_disk_reopen', h.snapshot(db) == corrupt)
        checked('sqlite_schema_remains3_after_all_v4_cases', db.execute('PRAGMA user_version').fetchone() == (3,))
        db.close()
    return {'status': 'PASS', 'cases': len(cases), 'case_names': cases,
        'checkpoint_payload_versions': [1, 2, 3, 4], 'sqlite_schema': 3,
        'model': 'INDEPENDENT_PYTHON_EMPTY_HEADER_AND_HOST_SQLITE', 'kotlin_codec': 'SEPARATE_JVM_TESTS',
        'android_sqlite_runtime': 'NOT_RUN', 'device_or_live_endpoint': 'NOT_RUN'}
