#!/usr/bin/env python3
"""Bounded M9-A host SQLite lifecycle matrix. This does not run Android SQLiteOpenHelper.

Uses the existing source-guarded v3 DDL / history mutation host helpers. Four fresh
Python processes open the same temporary DB; no process carries a connection or
in-memory state into the next stage. Host payloads are independently modelled
checkpoint headers, not a substitute for the Kotlin codec JVM tests.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import sqlite3
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location('history_model', ROOT / 'docs/searxng-discovery/evidence/inherited-discovery-history-sqlite.py')
h = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = h
SPEC.loader.exec_module(h)


def digest(db):
    # Digest compares private functional rows without logging the rows themselves.
    return hashlib.sha256(repr(h.snapshot(db)).encode()).hexdigest()


def baseline(db):
    assert db.execute('PRAGMA user_version').fetchone() == (3,)
    assert h.statistics(db) == (2, 2, 2, 4, 4)
    assert all(r['provider_id'] == 'wikimedia-commons' and r['provider_version'] == '0.1.0' and r['cursor_schema'] == 1
               for r in h.rows(db, 'progress'))
    for profile in ('alpha', 'beta'):
        aliases = h.rows(db, 'seen', 'profile_id=?', (profile,))
        assert len({r['identity_id'] for r in aliases}) == 1
        assert all(r['shown_at'] == 5 and r['opened_at'] == 7 for r in aliases)
        assert all(r['played_at'] is None and r['saved_at'] is None for r in aliases)
        cp = h.rows(db, 'checkpoints', 'profile_id=?', (profile,))[0]
        assert h.decode_header(cp['payload'])['revision'] == 1
        assert h.rows(db, 'pages', 'session_id=?', ('session-' + profile,))[0]['revision'] == 1
    assert all(r['rights'] == h.old.SAMPLE_RIGHTS for r in h.rows(db, 'results'))


def worker(stage, directory):
    h.source_guards()
    identity = (ROOT / 'commons-provider/src/main/kotlin/com/easysavevideo/commons/CommonsModels.kt').read_text()
    assert 'const val PROVIDER_ID = "wikimedia-commons"' in identity
    assert 'const val VERSION = "0.1.0"' in identity
    db = sqlite3.connect(directory / 'state.db')
    try:
        if stage == 'seed':
            h.fresh(db)
            for profile in ('alpha', 'beta'):
                payload = h.encode_header('session-' + profile, profile)
                query = h.decode_header(payload)['query_key']
                a, b = h.fingerprint(1), h.fingerprint(2)
                h.add_seen(db, profile, a, shown_at=5)
                h.add_seen(db, profile, b, opened_at=7)
                h.link_aliases(db, profile, {a, b})
                # One fixed Commons schema-1 provider, two different query identities.
                for q in (query, h.fingerprint(99)):
                    h.add_progress(db, profile, 'wikimedia-commons', q)
                db.execute('UPDATE progress SET provider_version=? WHERE profile_id=?', ('0.1.0', profile))
                h.add_checkpoint(db, 'session-' + profile, profile, query, aliases=(a, b))
                db.execute('UPDATE checkpoints SET payload=? WHERE session_id=?', (payload, 'session-' + profile))
                db.execute('UPDATE results SET rights=? WHERE session_id=?', (h.old.SAMPLE_RIGHTS, 'session-' + profile))
            db.commit()
            baseline(db)
            (directory / 'baseline.sha256').write_text(digest(db))
        elif stage == 'rollback':
            baseline(db)
            before = digest(db)
            assert before == (directory / 'baseline.sha256').read_text()
            try:
                h.transaction(db, lambda: h.clear_seen(db, 'alpha', inject=True))
                raise AssertionError('EXPECTED_CANCELLATION')
            except h.Cancellation:
                pass
            assert digest(db) == before
            def interrupted_mutation():
                h.reset_progress(db, 'alpha', 'wikimedia-commons')
                h.clear_seen(db, 'alpha')
                raise RuntimeError('INJECTED_BEFORE_COMMIT')
            try:
                h.transaction(db, interrupted_mutation)
                raise AssertionError('EXPECTED_ROLLBACK')
            except RuntimeError:
                pass
            assert digest(db) == before
        elif stage == 'commit':
            baseline(db)
            assert digest(db) == (directory / 'baseline.sha256').read_text()
            unaffected = {table: h.rows(db, table, 'profile_id=?', ('beta',))
                          for table in ('seen', 'checkpoints', 'progress', 'history_profiles')}
            results = h.rows(db, 'results')
            viewport = h.rows(db, 'viewport')
            progress = h.rows(db, 'progress')
            h.transaction(db, lambda: h.clear_seen(db, 'alpha'))
            assert not h.rows(db, 'seen', 'profile_id=?', ('alpha',))
            assert h.rows(db, 'results') == results
            assert h.rows(db, 'viewport') == viewport
            assert h.rows(db, 'progress') == progress
            for table, rows in unaffected.items():
                assert h.rows(db, table, 'profile_id=?', ('beta',)) == rows
            alpha = h.rows(db, 'checkpoints', 'profile_id=?', ('alpha',))[0]
            assert h.decode_header(alpha['payload'])['revision'] == 2
            assert h.rows(db, 'pages', 'session_id=?', ('session-alpha',))[0]['revision'] == 2
            h.transaction(db, lambda: h.reset_progress(db, 'alpha', 'wikimedia-commons', h.fingerprint(99)))
            assert len(h.rows(db, 'progress', 'profile_id=?', ('alpha',))) == 1
            assert len(h.rows(db, 'progress', 'profile_id=?', ('beta',))) == 2
            (directory / 'committed.sha256').write_text(digest(db))
        elif stage == 'reopen':
            assert digest(db) == (directory / 'committed.sha256').read_text()
            assert h.statistics(db) == (2, 2, 1, 2, 3)
            assert h.decode_header(h.rows(db, 'checkpoints', 'profile_id=?', ('alpha',))[0]['payload'])['revision'] == 2
            assert h.decode_header(h.rows(db, 'checkpoints', 'profile_id=?', ('beta',))[0]['payload'])['revision'] == 1
            assert all(r['played_at'] is None and r['saved_at'] is None for r in h.rows(db, 'seen'))
            before, changes = digest(db), db.total_changes
            for _ in range(20):
                h.statistics(db); h.statistics(db, 'alpha')
            assert digest(db) == before and db.total_changes == changes
        else:
            raise AssertionError('UNKNOWN_STAGE')
    finally:
        db.close()


def main():
    if len(sys.argv) == 4 and sys.argv[1] == '--worker':
        worker(sys.argv[2], Path(sys.argv[3])); return
    h.source_guards()
    with tempfile.TemporaryDirectory(prefix='esv-m9a-reopen-') as temporary:
        for stage in ('seed', 'rollback', 'commit', 'reopen'):
            result = subprocess.run([sys.executable, str(Path(__file__).resolve()), '--worker', stage, temporary],
                                    capture_output=True, timeout=30, check=False)
            if result.returncode != 0:
                print('FAIL HOST_PROCESS_' + stage.upper())
                raise SystemExit(1)
            print('PASS HOST_PROCESS_' + stage.upper())
    print(json.dumps({
        'fresh_processes': 4,
        'source_extracted_schema': 3,
        'provider_schema': 1,
        'ordinary_rows': {'profiles': 2, 'checkpoints': 2, 'aliases_before': 4, 'progress_before': 4},
        'close_reopen_after_rollback_and_commit': 'PASS',
        'revision_barrier_alias_facts_rights_scope': 'PASS',
        'read_only_statistics_after_reopen': 'PASS',
        'android_sqlite_runtime': 'NOT_RUN',
        'android_process_death_or_power_loss': 'NOT_RUN',
        'save_webview_or_real_media_in_sql_harness': 'NOT_RUN',
        'providers_network_or_accounts': 'NOT_USED',
    }, indent=2))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        # Deliberately never print exception text, paths, payloads or private fixture values.
        print('FAIL HOST_PROCESS_REOPEN_HARNESS')
        raise SystemExit(1)
