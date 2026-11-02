#!/usr/bin/env python3
"""M8-B1 host SQLite history contracts; no Android/runtime/device acceptance.

Old CREATE DDL is pinned independently in test-discovery-sqlite.py. Target CREATE
and ALTER DDL are extracted verbatim from AndroidDiscoveryStore. The migration,
codec-header parser, alias merge, mutation/retention planner and controls below are
explicit Python models guarded against relevant Kotlin source fragments. SQLite
DDL, row writes/deletions, transactions, rollback and disk reopen execute for real.
The exact Kotlin planner and checkpoint codec have separate JVM tests.
"""
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
import hashlib
import importlib.util
import json
import random
import re
import sqlite3
import struct
import tempfile
import unicodedata

ROOT = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location('historical_sqlite', ROOT / 'scripts/test-discovery-sqlite.py')
old = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(old)
SOURCE = old.SOURCE
PLANNER_SOURCE = (ROOT / 'modules/discovery/src/main/kotlin/com/easysavevideo/discovery/DiscoveryRetention.kt').read_text()
CODEC_SOURCE = (ROOT / 'app/src/main/kotlin/com/easysavevideo/app/storage/DiscoveryCheckpointCodec.kt').read_text()
MODELS_SOURCE = (ROOT / 'modules/discovery/src/main/kotlin/com/easysavevideo/discovery/DiscoveryModels.kt').read_text()


class CapacityFailure(RuntimeError):
    pass


class Cancellation(BaseException):
    """Host rollback injection, not evidence of Android coroutine cancellation."""


class HeaderError(ValueError):
    pass


class Reader:
    def __init__(self, data):
        if not isinstance(data, bytes) or len(data) > 64 * 1024 * 1024:
            raise HeaderError('CHECKPOINT_SIZE_OR_TYPE')
        self.data, self.offset = data, 0

    def take(self, size):
        if size < 0 or self.offset + size > len(self.data):
            raise HeaderError('CHECKPOINT_TRUNCATED')
        value = self.data[self.offset:self.offset + size]
        self.offset += size
        return value

    def integer(self):
        return struct.unpack('>i', self.take(4))[0]

    def long(self):
        return struct.unpack('>q', self.take(8))[0]

    def boolean(self):
        value = self.take(1)[0]
        if value not in (0, 1):
            raise HeaderError('CHECKPOINT_BOOLEAN')
        return bool(value)

    def text(self, maximum):
        size = self.integer()
        if not 0 <= size <= min(32768, maximum * 4):
            raise HeaderError('CHECKPOINT_TEXT_SIZE')
        try:
            value = self.take(size).decode('utf-8', errors='strict')
        except UnicodeDecodeError as error:
            raise HeaderError('CHECKPOINT_ENCODING') from error
        if len(value.encode('utf-16-le')) // 2 > maximum:
            raise HeaderError('CHECKPOINT_TEXT_LENGTH')
        return value

    def count(self, maximum):
        value = self.integer()
        if not 0 <= value <= maximum:
            raise HeaderError('CHECKPOINT_COUNT')
        return value

    def optional_long(self):
        return self.long() if self.boolean() else None


def framed(*fields):
    return ''.join(str(len(field.encode('utf-16-le')) // 2) + ':' + field for field in fields)


def header_prefix(payload):
    """Independent reader for codec prefix, shared by real v1/v2 fixture layouts."""
    reader = Reader(payload)
    if reader.integer() != 0x45534432:
        raise HeaderError('CHECKPOINT_MAGIC')
    version = reader.integer()
    if version not in (1, 2, 3):
        raise HeaderError('CHECKPOINT_VERSION')
    session = reader.text(128)
    epoch = reader.long()
    profile = reader.text(128)
    query, locale = reader.text(512), reader.text(64)
    namespaces = [reader.text(128) for _ in range(reader.count(100))]
    if len(namespaces) != len(set(namespaces)):
        raise HeaderError('CHECKPOINT_DUPLICATE_NAMESPACE')
    minimum, maximum = reader.optional_long(), reader.optional_long()
    unknown, safe = reader.boolean(), reader.boolean()
    history = reader.text(32)
    sort = reader.text(32) if version >= 3 else 'RELEVANCE'
    revision, order = reader.long(), reader.long()
    if (not re.fullmatch(r'[A-Za-z0-9_.-]{1,128}', session) or epoch <= 0 or
            not re.fullmatch(r'[A-Za-z0-9_.-]{1,128}', profile) or not locale or
            any(not re.fullmatch(r'[A-Za-z0-9_.-]{1,128}', item) for item in namespaces) or
            minimum is not None and minimum < 0 or maximum is not None and maximum < 0 or
            minimum is not None and maximum is not None and minimum > maximum or
            history not in ('PREFER_NEW', 'HIDE_WATCHED', 'HIDE_SHOWN', 'SHOW_ALL') or
            sort not in ('RELEVANCE', 'NEWEST', 'DURATION_SHORTEST', 'DURATION_LONGEST') or revision < 0 or order < 0):
        raise HeaderError('CHECKPOINT_FIELD')
    # These fixtures have ordinary BMP queries. This independently models Kotlin's
    # normalized, framed profile key; it is not an implementation of every Unicode
    # behavior of Java's Normalizer/Locale across all possible user input.
    norm = re.sub(r'\s+', ' ', unicodedata.normalize('NFKC', query).strip(), flags=re.ASCII).lower()
    filter_key = framed(','.join(sorted(item.lower() for item in namespaces)),
                        'null' if minimum is None else str(minimum),
                        'null' if maximum is None else str(maximum), str(unknown).lower(), str(safe).lower())
    query_key = hashlib.sha256(framed(norm, locale.lower(), filter_key).encode()).hexdigest()
    return reader, dict(version=version, session=session, epoch=epoch, profile=profile, query_key=query_key, revision=revision, next_order=order)


def decode_header(payload):
    reader, result = header_prefix(payload)
    if reader.integer() != 0 or reader.integer() != 0 or reader.offset != len(payload):
        raise HeaderError('CHECKPOINT_HEADER_NOT_EMPTY')
    return result


def old_header(version):
    """Retain the exact genuine old prefix, write empty result/page count fields.

    SQL checkpoints contain codec headers with no inline rows. This is a host-
    derived fixture from frozen old-codec bytes, not a version-patched new codec.
    """
    path = ROOT / f'app/src/test/resources/discovery-checkpoint-v{version}.bin'
    payload = path.read_bytes()
    expected = {1: 'a58309da3e846d6af207f3726d6745ab8a2b7e82a40943a120b7471b60616bd5',
                2: 'a0d25e3c6d1b3c1e2f4165f2ff88b75e1b985442500061e089d98b037b65d0e0'}[version]
    assert hashlib.sha256(payload).hexdigest() == expected, 'Frozen old fixture changed'
    reader, header = header_prefix(payload)
    assert header['version'] == version
    assert reader.count(500) > 0, 'Fixture must be genuine full old checkpoint'
    prefix_size = reader.offset - 4
    result = payload[:prefix_size] + struct.pack('>ii', 0, 0)
    assert decode_header(result) == header
    return result


def table_names(db):
    return [row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]


def snapshot(db):
    return (db.execute('PRAGMA user_version').fetchone(),
            db.execute('SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name').fetchall(),
            {table: db.execute('SELECT * FROM ' + table + ' ORDER BY rowid').fetchall() for table in table_names(db)})


def rows(db, table, where='', values=()):
    cursor = db.execute('SELECT * FROM ' + table + (' WHERE ' + where if where else '') + ' ORDER BY rowid', values)
    return [dict(zip([column[0] for column in cursor.description], row)) for row in cursor]


def insert(db, table, values):
    db.execute('INSERT INTO ' + table + '(' + ','.join(values) + ') VALUES(' +
               ','.join('?' for _ in values) + ')', tuple(values.values()))


def schema_by_name(db):
    return {table: sorted(row[1:] for row in db.execute('PRAGMA table_info(' + table + ')')) for table in table_names(db)}


def transaction(db, action):
    db.execute('BEGIN IMMEDIATE')
    try:
        result = action()
        db.commit()
        return result
    except BaseException:
        db.rollback()
        raise


def failure(action, expected):
    try:
        action()
    except expected:
        return
    raise AssertionError('Expected ' + repr(expected))


@dataclass(frozen=True)
class Policy:
    profiles: int = 32
    aliases: int = 65536
    progress: int = 512
    checkpoints: int = 8


def retention_plan(db, policy, written=()):
    """Python mirror of shared Kotlin planner; source guards accompany execution."""
    checkpoint_rows = rows(db, 'checkpoints')
    seen_rows, progress_rows = rows(db, 'seen'), rows(db, 'progress')
    protected = []
    protected_aliases = set()
    for checkpoint in checkpoint_rows:
        entries = rows(db, 'results', 'session_id=?', (checkpoint['session_id'],))
        pages = rows(db, 'pages', 'session_id=?', (checkpoint['session_id'],))
        if pages or checkpoint['session_id'] in written:
            protected.append(checkpoint)
            pinned = {result for page in pages for result in page['result_ids'].split('\n')}
            for entry in entries:
                if entry['result_id'] not in pinned:
                    continue
                protected_aliases.update((checkpoint['profile_id'], alias) for alias in entry['aliases'].split('\n'))
    profile_ids = {row['profile_id'] for row in checkpoint_rows + seen_rows + progress_rows}
    protected_profiles = {row['profile_id'] for row in protected}
    profile_sequences = {row['profile_id']: row['mutation_seq'] for row in rows(db, 'history_profiles')}
    removed_profiles = set()
    for profile in sorted(profile_ids, key=lambda key: (profile_sequences.get(key, 0), key)):
        if len(profile_ids) - len(removed_profiles) <= policy.profiles:
            break
        if profile not in protected_profiles:
            removed_profiles.add(profile)
    if len(profile_ids) - len(removed_profiles) > policy.profiles:
        raise CapacityFailure('CAPACITY_REACHED')
    removed_checkpoints = {row['session_id'] for row in checkpoint_rows if row['profile_id'] in removed_profiles}
    active_checkpoints = [row for row in checkpoint_rows if row['profile_id'] not in removed_profiles]
    protected_sessions = {row['session_id'] for row in protected}
    checkpoint_count = len(active_checkpoints)
    for row in sorted(active_checkpoints, key=lambda item: (item['mutation_seq'], item['session_id'])):
        if checkpoint_count <= policy.checkpoints:
            break
        if row['session_id'] not in protected_sessions:
            removed_checkpoints.add(row['session_id'])
            checkpoint_count -= 1
    if checkpoint_count > policy.checkpoints:
        raise CapacityFailure('CAPACITY_REACHED')
    groups = defaultdict(list)
    removed_seen = {(row['profile_id'], row['fingerprint']) for row in seen_rows if row['profile_id'] in removed_profiles}
    for row in seen_rows:
        if row['profile_id'] not in removed_profiles:
            groups[row['profile_id'], row['identity_id']].append(row)
    alias_count = sum(map(len, groups.values()))
    for _, aliases in sorted(groups.items(), key=lambda item: (max(row['mutation_seq'] for row in item[1]), item[0])):
        if alias_count <= policy.aliases:
            break
        keys = {(row['profile_id'], row['fingerprint']) for row in aliases}
        if keys.isdisjoint(protected_aliases):
            removed_seen.update(keys)
            alias_count -= len(keys)
    if alias_count > policy.aliases:
        raise CapacityFailure('CAPACITY_REACHED')
    key_of = lambda row: (row['profile_id'], row['provider_id'], row['query_key'])
    removed_progress = {key_of(row) for row in progress_rows if row['profile_id'] in removed_profiles}
    active_progress = [row for row in progress_rows if row['profile_id'] not in removed_profiles]
    protected_queries = {(row['profile_id'], row['query_key']) for row in protected}
    progress_count = len(active_progress)
    for row in sorted(active_progress, key=lambda item: (item['mutation_seq'], key_of(item))):
        if progress_count <= policy.progress:
            break
        if (row['profile_id'], row['query_key']) not in protected_queries:
            removed_progress.add(key_of(row))
            progress_count -= 1
    if progress_count > policy.progress:
        raise CapacityFailure('CAPACITY_REACHED')
    return removed_profiles, removed_seen, removed_progress, removed_checkpoints


def apply_plan(db, plan, fail_after=None, sequence=None):
    removed_profiles, removed_seen, removed_progress, removed_checkpoints = plan
    # Source automatic pruning deletes directly: it does not touch mutation
    # sequence or refresh surviving profile recency through removeCheckpoint.
    for session in sorted(removed_checkpoints):
        for table in ('checkpoints', 'results', 'pages', 'viewport'):
            db.execute('DELETE FROM ' + table + ' WHERE session_id=?', (session,))
    if fail_after == 'checkpoint':
        raise old.InjectedFailure('after checkpoint pruning')
    for profile, fingerprint in sorted(removed_seen):
        db.execute('DELETE FROM seen WHERE profile_id=? AND fingerprint=?', (profile, fingerprint))
    if fail_after == 'seen':
        raise old.InjectedFailure('after Seen pruning')
    for key in sorted(removed_progress):
        db.execute('DELETE FROM progress WHERE profile_id=? AND provider_id=? AND query_key=?', key)
    if fail_after == 'progress':
        raise old.InjectedFailure('after progress pruning')
    db.execute(CLEAN_PROFILES_SQL)


def fingerprint(index):
    return format(index, '064x')


def add_profile(db, profile, sequence):
    db.execute('INSERT INTO history_profiles(profile_id,mutation_seq) VALUES(?,?) '
               'ON CONFLICT(profile_id) DO UPDATE SET mutation_seq=excluded.mutation_seq', (profile, sequence))


def add_seen(db, profile, alias, identity=None, sequence=1, **events):
    record = dict(profile_id=profile, fingerprint=alias, first_discovered=events.get('first_discovered', 1),
                  shown_at=events.get('shown_at'), opened_at=events.get('opened_at'),
                  played_at=events.get('played_at'), saved_at=events.get('saved_at'),
                  dismissed_at=events.get('dismissed_at'), identity_id=identity or alias, mutation_seq=sequence)
    insert(db, 'seen', record)
    add_profile(db, profile, sequence)


def add_progress(db, profile, provider, query, sequence=1):
    insert(db, 'progress', dict(profile_id=profile, provider_id=provider, query_key=query,
                               provider_version='1', cursor_schema=1, cursor='opaque-host-cursor',
                               expires_at=None, exhausted=0, last_success=1, mutation_seq=sequence))
    add_profile(db, profile, sequence)


def add_checkpoint(db, session, profile, query, sequence=1, aliases=()):
    # Opaque payload here: these retention/control tests are SQL/planner tests.
    # Genuine codec headers are used in the separate migration tests below.
    insert(db, 'checkpoints', dict(session_id=session, epoch=1, payload=b'host-retention-only',
                                  profile_id=profile, query_key=query, mutation_seq=sequence))
    if aliases:
        result = old.result_row(session=session, result_id=aliases[0])
        result['aliases'] = '\n'.join(aliases)
        old.insert_result(db, result)
        insert(db, 'pages', dict(session_id=session, page_index=0, revision=1, page_size=20, result_ids=aliases[0]))
        insert(db, 'viewport', dict(session_id=session, epoch=1, page_index=0))
    add_profile(db, profile, sequence)


ALL_SQL = re.findall(r'db.execSQL\("([^"\n]+)"', SOURCE)
CREATE_SQL = [sql for sql in ALL_SQL if sql.startswith('CREATE TABLE ')]
INDEX_SQL = [sql for sql in ALL_SQL if sql.startswith('CREATE INDEX ')]
assert INDEX_SQL == ['CREATE INDEX seen_identity ON seen(profile_id,identity_id)']
TARGET_TABLES = {re.match(r'CREATE TABLE (\w+) ', sql).group(1) for sql in CREATE_SQL}
assert TARGET_TABLES == set(old.TABLES) | {'history_profiles', 'history_meta'} and len(CREATE_SQL) == 8
assert old.HELPER.group(2) == '3', 'This suite tests target schema 3'
assert 'oldVersion !in 1..2 || newVersion != 3' in old.UPGRADE_SOURCE
UPGRADE_SQL = re.findall(r'db.execSQL\("([^"\n]+)"', old.UPGRADE_SOURCE)
assert len([sql for sql in UPGRADE_SQL if sql.startswith('ALTER TABLE ')]) == 7


def source_sql(prefix):
    matches = [sql for sql in ALL_SQL if sql.startswith(prefix)]
    assert len(set(matches)) == 1, 'Missing/ambiguous source SQL: ' + prefix
    return matches[0]


META_SEED_SQL = source_sql('INSERT INTO history_meta(')
SEED_PROFILES_SQL = source_sql('INSERT INTO history_profiles(profile_id,mutation_seq) SELECT')
SET_CHECKPOINT_METADATA_SQL = source_sql('UPDATE checkpoints SET profile_id=')
ADD_CHECKPOINT_PROFILE_SQL = source_sql('INSERT OR IGNORE INTO history_profiles(')
CLEAN_PROFILES_SQL = source_sql('DELETE FROM history_profiles WHERE')
NEXT_SEQUENCE_SQL = source_sql('UPDATE history_meta SET mutation_seq=')
TOUCH_PROFILE_SQL = source_sql('INSERT INTO history_profiles(profile_id,mutation_seq) VALUES')
EVENT_COLUMNS = ('first_discovered', 'shown_at', 'opened_at', 'played_at', 'saved_at', 'dismissed_at')


def source_guards():
    assert 'DiscoveryCheckpointCodec.decode(c.getBlob(1))' in SOURCE
    assert 'it.stamp.sessionId == sessionId && it.stamp.epoch == c.getLong(0)' in SOURCE
    assert 'it.pool.entries.isEmpty() && it.pool.pages.isEmpty()' in SOURCE
    assert 'checkpoint.pool.entries.forEach { linkSeenAliases(checkpoint.profile.profileId' in SOURCE
    assert 'if (groups.size < 2) return' in SOURCE
    assert 'MIN(first_discovered),MAX(shown_at),MAX(opened_at),MAX(played_at),MAX(saved_at),MAX(dismissed_at)' in SOURCE
    assert 'put("identity_id", identityId); put("mutation_seq", seq)' in SOURCE
    assert 'DiscoveryRetentionPlanner.plan(DiscoveryRetentionSnapshot(profiles, aliases, progress, checkpoints), retention)' in SOURCE
    pruning = SOURCE.split('plan.removeCheckpoints.forEach', 1)[1].split('override fun historyStatistics', 1)[0]
    assert 'touchProfile(' not in pruning and 'mutationSequence(' not in pruning
    assert '::removeCheckpoint' not in pruning
    assert 'listOf("checkpoints", "results", "pages", "viewport")' in pruning
    assert 'checkpoint.pool.pages.isNotEmpty() || metadata.sessionId in writtenCheckpoints' in SOURCE
    assert 'checkpoint.pool.entries.filter { it.result.resultId in pinned }' in SOURCE
    assert SOURCE.index('transaction.finish()') < SOURCE.index('transaction.checkBeforeCommit()') < SOURCE.index('db.setTransactionSuccessful()')
    assert 'fun checkBeforeCommit() { commitGuards.forEach { it() } }' in SOURCE
    assert 'if (!dirty) return' in SOURCE
    assert 'old in 0 until Long.MAX_VALUE' in SOURCE
    assert 'groups = seenAliases.groupBy { it.profileId to it.identityId }' in PLANNER_SOURCE
    assert 'it.value.maxOf { alias -> alias.lastMutationSequence }' in PLANNER_SOURCE
    assert 'aliases.none { RetentionSeenKey(it.profileId, it.fingerprint) in protectedAliases }' in PLANNER_SOURCE
    assert 'protectedCheckpoints.map { it.profileId to it.queryKey }' in PLANNER_SOURCE
    assert 'if (!value) throw DiscoveryFailure(DiscoveryError.CAPACITY_REACHED)' in PLANNER_SOURCE
    assert 'Normalizer.normalize(query, Normalizer.Form.NFKC)' in MODELS_SOURCE
    assert 'private const val MAGIC = 0x45534432' in CODEC_SOURCE
    assert 'require(version in 1..3)' in CODEC_SOURCE
    clear = SOURCE.split('override fun clearSeen', 1)[1].split('private fun Cursor.optionalLong', 1)[0]
    assert 'db.delete("seen", where, args)' in clear
    assert 'val revision = checkpoint.pool.viewRevision + 1' in clear
    assert 'pages = checkpoint.pool.pages.map { it.copy(revision = revision) }' in clear
    reset = SOURCE.split('override fun resetProgress', 1)[1].split('override fun seen', 1)[0]
    assert 'where.append(" AND provider_id=?")' in reset and 'where.append(" AND query_key=?")' in reset
    assert 'db.delete("progress", where.toString(), args.toTypedArray())' in reset
    assert 'delete("seen"' not in reset
    assert not any(word in SOURCE for word in ('File(', 'deleteFile(', 'Vault', 'MediaStore', 'ProviderRegistry'))


def fresh(db):
    for sql in CREATE_SQL:
        db.execute(sql)
    db.execute(META_SEED_SQL)
    for sql in INDEX_SQL:
        db.execute(sql)
    db.execute('PRAGMA user_version=3')
    db.commit()


def next_sequence(db):
    current = db.execute('SELECT mutation_seq FROM history_meta WHERE singleton=1').fetchone()[0]
    if not 0 <= current < 2 ** 63 - 1:
        raise ValueError('SEQUENCE_EXHAUSTED')
    db.execute(NEXT_SEQUENCE_SQL, (current + 1,))
    return current + 1


def link_aliases(db, profile, aliases, sequence=None):
    # Mirrors source group query + aggregate/update. Crucially, missing alias
    # rows are not created, and there is no cross-profile relation inference.
    placeholders = ','.join('?' for _ in aliases)
    groups = [row[0] for row in db.execute('SELECT DISTINCT identity_id FROM seen WHERE profile_id=? AND fingerprint IN (' +
                                         placeholders + ')', (profile, *sorted(aliases)))]
    if len(groups) < 2:
        return sequence
    group_placeholders = ','.join('?' for _ in groups)
    condition = 'profile_id=? AND identity_id IN (' + group_placeholders + ')'
    values = (profile, *sorted(groups))
    aggregate = db.execute('SELECT MIN(first_discovered),MAX(shown_at),MAX(opened_at),MAX(played_at),MAX(saved_at),MAX(dismissed_at) '
                           'FROM seen WHERE ' + condition, values).fetchone()
    sequence = next_sequence(db) if sequence is None else sequence
    db.execute(TOUCH_PROFILE_SQL, (profile, sequence))
    db.execute('UPDATE seen SET ' + ','.join(column + '=?' for column in EVENT_COLUMNS) +
               ',identity_id=?,mutation_seq=? WHERE ' + condition, (*aggregate, min(groups), sequence, *values))
    return sequence


def migrate(db, fail_at=None, policy=Policy()):
    """Source SQL with independently implemented host migration data loop."""
    version = db.execute('PRAGMA user_version').fetchone()[0]
    if version == 3:
        return False
    if version not in (1, 2):
        raise ValueError('DISCOVERY_MIGRATION_REQUIRED')

    def upgrade():
        for index, sql in enumerate(UPGRADE_SQL):
            if sql == old.UPGRADE_SQL[0] and version == 2:
                continue
            db.execute(sql)
            if fail_at == f'ddl_{index}':
                raise old.InjectedFailure(fail_at)
        for sql in CREATE_SQL:
            if sql.startswith('CREATE TABLE history_'):
                db.execute(sql)
        db.execute(META_SEED_SQL)
        for sql in INDEX_SQL:
            db.execute(sql)
        if fail_at == 'after_ddl':
            raise old.InjectedFailure(fail_at)
        db.execute(SEED_PROFILES_SQL)
        sequence = None
        for session, epoch, payload in db.execute('SELECT session_id,epoch,payload FROM checkpoints ORDER BY session_id').fetchall():
            header = decode_header(payload)
            if (header['session'], header['epoch']) != (session, epoch):
                raise HeaderError('CHECKPOINT_STAMP')
            # Partial host envelope decoder rejects corrupt/unknown bytes on the
            # same data-loading path. Full attribution decoding is JVM coverage.
            entries = rows(db, 'results', 'session_id=?', (session,))
            for entry in entries:
                old.decode_warning_only_envelope(entry['rights'])
                aliases = entry['aliases'].split('\n')
                if (not re.fullmatch('[a-f0-9]{64}', entry['result_id']) or entry['result_id'] not in aliases or
                        len(aliases) > 128 or len(set(aliases)) != len(aliases) or
                        any(not re.fullmatch('[a-f0-9]{64}', alias) for alias in aliases)):
                    raise HeaderError('CHECKPOINT_IDENTITY')
            db.execute(SET_CHECKPOINT_METADATA_SQL, (header['profile'], header['query_key'], session))
            db.execute(ADD_CHECKPOINT_PROFILE_SQL, (header['profile'],))
            for entry in entries:
                sequence = link_aliases(db, header['profile'], set(entry['aliases'].split('\n')), sequence)
            if fail_at == 'after_metadata':
                raise old.InjectedFailure(fail_at)
        db.execute(CLEAN_PROFILES_SQL)
        apply_plan(db, retention_plan(db, policy), sequence=sequence)
        db.execute(CLEAN_PROFILES_SQL)
        if fail_at == 'after_prune':
            raise old.InjectedFailure(fail_at)
        db.execute('PRAGMA user_version=3')
        if fail_at == 'after_version':
            raise old.InjectedFailure(fail_at)
    transaction(db, upgrade)
    return True


def seed_old(db, version):
    old.create(db, legacy=version == 1)
    header_bytes = old_header(version)
    header = decode_header(header_bytes)
    insert(db, 'checkpoints', dict(session_id=header['session'], epoch=header['epoch'], payload=header_bytes))
    result = old.result_row(session=header['session'], result_id=fingerprint(10), rights=old.SAMPLE_RIGHTS if version == 2 else None)
    result['aliases'] = '\n'.join((fingerprint(10), fingerprint(11), fingerprint(12)))
    old.insert_result(db, result)
    insert(db, 'pages', dict(session_id=header['session'], page_index=0, revision=header['revision'], page_size=20, result_ids=fingerprint(10)))
    insert(db, 'viewport', dict(session_id=header['session'], epoch=header['epoch'], page_index=0))
    for profile, alias, first, shown, opened in ((header['profile'], 10, 9, 10, None),
                                                (header['profile'], 11, 3, None, 12),
                                                (header['profile'], 13, 4, None, None),
                                                ('other-profile', 10, 8, None, None)):
        insert(db, 'seen', dict(profile_id=profile, fingerprint=fingerprint(alias), first_discovered=first,
                               shown_at=shown, opened_at=opened, played_at=None, saved_at=None, dismissed_at=None))
    for profile in (header['profile'], 'other-profile'):
        insert(db, 'progress', dict(profile_id=profile, provider_id='commons', query_key=header['query_key'],
                                   provider_version='1', cursor_schema=1, cursor='old-cursor', expires_at=None,
                                   exhausted=0, last_success=17))
    db.commit()
    return header


def test_migration(directory):
    db = sqlite3.connect(':memory:')
    fresh(db)
    target_schema = schema_by_name(db)
    db.close()
    reports = {}
    for version in (1, 2):
        path = directory / f'historical-v{version}.db'
        db = sqlite3.connect(path)
        header = seed_old(db, version)
        original = snapshot(db)
        # DDL must run under BEGIN: Python's implicit transaction does not cover
        # standalone ALTER statements by itself.
        failures = [f'ddl_{index}' for index, sql in enumerate(UPGRADE_SQL)
                    if not (version == 2 and sql == old.UPGRADE_SQL[0])]
        failures += ['after_ddl', 'after_metadata', 'after_prune', 'after_version']
        for point in failures:
            failure(lambda: migrate(db, point), old.InjectedFailure)
            assert snapshot(db) == original, 'Failed upgrade altered old schema/data/version'
            db.close()
            db = sqlite3.connect(path)
            assert snapshot(db) == original, 'Upgrade rollback did not survive reopen'
        assert migrate(db)
        assert schema_by_name(db) == target_schema
        assert [row[2] for row in db.execute('PRAGMA index_info(seen_identity)')] == ['profile_id', 'identity_id']
        assert db.execute('PRAGMA user_version').fetchone() == (3,)
        checkpoint = rows(db, 'checkpoints')[0]
        assert (checkpoint['profile_id'], checkpoint['query_key']) == (header['profile'], header['query_key'])
        assert checkpoint['payload'] == original[2]['checkpoints'][0][2]
        # Existing facts form a union only where stored aliases prove identity.
        linked = rows(db, 'seen', 'profile_id=? AND identity_id=?', (header['profile'], fingerprint(10)))
        assert len(linked) == 2
        assert all(tuple(row[key] for key in EVENT_COLUMNS) == (3, 10, 12, None, None, None) for row in linked)
        assert not rows(db, 'seen', 'fingerprint=?', (fingerprint(12),)), 'Migration invented an absent Seen alias'
        unknown = rows(db, 'seen', 'profile_id=? AND fingerprint=?', (header['profile'], fingerprint(13)))[0]
        assert unknown['identity_id'] == fingerprint(13), 'Unknown old relation must remain singleton'
        other = rows(db, 'seen', 'profile_id=?', ('other-profile',))[0]
        assert other['identity_id'] == fingerprint(10) and other['shown_at'] is None and other['opened_at'] is None
        assert db.execute('SELECT played_at,saved_at FROM seen').fetchall() == [(None, None)] * 4
        for table in ('results', 'pages', 'viewport', 'progress'):
            names = [column for column in old.columns(db, table) if column not in ('rights', 'mutation_seq') or (column == 'rights' and version == 2)]
            assert db.execute('SELECT ' + ','.join(names) + ' FROM ' + table + ' ORDER BY rowid').fetchall() == original[2][table]
        expected_rights = None if version == 1 else old.SAMPLE_RIGHTS
        assert db.execute('SELECT rights FROM results').fetchall() == [(expected_rights,)]
        after = snapshot(db)
        for _ in range(3):
            db.close()
            db = sqlite3.connect(path)
            changes = db.total_changes
            assert migrate(db) is False and db.total_changes == changes and snapshot(db) == after
        db.close()
        reports[f'v{version}_failure_points'] = len(failures)
    return reports


def test_migration_rejection(directory):
    cases = []
    for version in (1, 2):
        for mutation in ('future_header', 'bad_magic', 'truncated', 'trailing', 'wrong_stamp', 'full_pool', 'corrupt_rights', 'bad_alias', 'protected_over_capacity'):
            path = directory / f'rejected-{version}-{mutation}.db'
            db = sqlite3.connect(path)
            seed_old(db, version)
            if mutation == 'future_header':
                payload = old_header(version)
                db.execute('UPDATE checkpoints SET payload=?', (payload[:4] + struct.pack('>i', 99) + payload[8:],))
            elif mutation == 'bad_magic':
                payload = old_header(version)
                db.execute('UPDATE checkpoints SET payload=?', (b'BAD!' + payload[4:],))
            elif mutation == 'truncated':
                db.execute('UPDATE checkpoints SET payload=?', (old_header(version)[:-1],))
            elif mutation == 'trailing':
                db.execute('UPDATE checkpoints SET payload=?', (old_header(version) + b'x',))
            elif mutation == 'wrong_stamp':
                db.execute('UPDATE checkpoints SET epoch=epoch+1')
            elif mutation == 'full_pool':
                db.execute('UPDATE checkpoints SET payload=?', ((ROOT / f'app/src/test/resources/discovery-checkpoint-v{version}.bin').read_bytes(),))
            elif mutation == 'corrupt_rights':
                if version == 1:
                    db.close()
                    continue
                db.execute('UPDATE results SET rights=?', (b'broken',))
            elif mutation == 'bad_alias':
                db.execute('UPDATE results SET aliases=?', ('unknown-invalid-alias',))
            db.commit()
            before = snapshot(db)
            limit = Policy(aliases=1) if mutation == 'protected_over_capacity' else Policy()
            failure(lambda: migrate(db, policy=limit), (HeaderError, ValueError, CapacityFailure))
            assert snapshot(db) == before
            db.close()
            db = sqlite3.connect(path)
            assert snapshot(db) == before
            db.close()
            cases.append((version, mutation))
    for version in (0, 4, 99):
        db = sqlite3.connect(':memory:')
        old.create(db)
        old.seed_legacy(db)
        db.execute(f'PRAGMA user_version={version}')
        db.commit()
        before = snapshot(db)
        failure(lambda: migrate(db), ValueError)
        assert snapshot(db) == before
        db.close()
    return len(cases)


def encode_header(session, profile, query='host-query', revision=1):
    """Synthetic v3 bytes for host control SQL tests, not Kotlin encoder output."""
    def text(value):
        value = value.encode()
        return struct.pack('>i', len(value)) + value
    return (struct.pack('>ii', 0x45534432, 3) + text(session) + struct.pack('>q', 1) + text(profile) +
            text(query) + text('und') + struct.pack('>i', 0) + b'\x00\x00\x01\x01' +
            text('PREFER_NEW') + text('RELEVANCE') + struct.pack('>qqii', revision, 1, 0, 0))


def clear_seen(db, profile=None, inject=False):
    where, values = (' WHERE profile_id=?', (profile,)) if profile is not None else ('', ())
    affected = [row[0] for row in db.execute('SELECT DISTINCT profile_id FROM seen' + where, values)]
    sessions = rows(db, 'checkpoints', 'profile_id=?' if profile is not None else '', values)
    sequence = next_sequence(db) if affected or sessions else None
    for affected_profile in affected:
        db.execute(TOUCH_PROFILE_SQL, (affected_profile, sequence))
    db.execute('DELETE FROM seen' + where, values)
    if inject:
        raise Cancellation('after Seen deletion and before revision barrier')
    for checkpoint in sessions:
        reader, header = header_prefix(checkpoint['payload'])
        decode_header(checkpoint['payload'])
        assert header['profile'] == checkpoint['profile_id'] and header['query_key'] == checkpoint['query_key']
        if header['revision'] >= 2 ** 63 - 1:
            raise ValueError('REVISION_EXHAUSTED')
        offset = reader.offset - 16
        payload = checkpoint['payload'][:offset] + struct.pack('>q', header['revision'] + 1) + checkpoint['payload'][offset + 8:]
        db.execute('UPDATE checkpoints SET payload=?,mutation_seq=? WHERE session_id=?',
                   (payload, sequence, checkpoint['session_id']))
        db.execute('UPDATE pages SET revision=? WHERE session_id=?', (header['revision'] + 1, checkpoint['session_id']))
        db.execute(TOUCH_PROFILE_SQL, (checkpoint['profile_id'], sequence))
    db.execute(CLEAN_PROFILES_SQL)


def reset_progress(db, profile, provider=None, query=None):
    conditions, values = ['profile_id=?'], [profile]
    if provider is not None:
        conditions.append('provider_id=?')
        values.append(provider)
    if query is not None:
        conditions.append('query_key=?')
        values.append(query)
    if db.execute('DELETE FROM progress WHERE ' + ' AND '.join(conditions), values).rowcount:
        db.execute(TOUCH_PROFILE_SQL, (profile, next_sequence(db)))
    db.execute(CLEAN_PROFILES_SQL)


def statistics(db, profile=None):
    # Same five local SELECTs as source. No provider/interface call and no writes.
    where, args = (' WHERE profile_id=?', (profile,)) if profile is not None else ('', ())
    return tuple(db.execute(sql, args).fetchone()[0] for sql in (
        'SELECT COUNT(*) FROM (SELECT profile_id FROM checkpoints UNION SELECT profile_id FROM seen UNION SELECT profile_id FROM progress)' + where,
        'SELECT COUNT(*) FROM checkpoints' + where,
        'SELECT COUNT(*) FROM (SELECT profile_id,identity_id FROM seen' + where + ' GROUP BY profile_id,identity_id)',
        'SELECT COUNT(*) FROM seen' + where,
        'SELECT COUNT(*) FROM progress' + where))


def test_controls_and_statistics(directory):
    path = directory / 'controls.db'
    db = sqlite3.connect(path)
    fresh(db)
    for index, profile in enumerate(('alpha', 'beta')):
        payload = encode_header('session-' + profile, profile)
        query = decode_header(payload)['query_key']
        for alias in (1, 2):
            add_seen(db, profile, fingerprint(alias), fingerprint(1), shown_at=5, opened_at=7)
        for provider in ('commons', 'other-provider'):
            for query_key in (query, fingerprint(99)):
                add_progress(db, profile, provider, query_key)
        add_checkpoint(db, 'session-' + profile, profile, query, aliases=(fingerprint(1), fingerprint(2)))
        db.execute('UPDATE checkpoints SET payload=? WHERE session_id=?', (payload, 'session-' + profile))
    db.commit()
    media = directory / 'independent-media-sentinel.mp4'
    media.write_bytes(b'host synthetic media sentinel; no real media content')
    media_hash = hashlib.sha256(media.read_bytes()).digest()
    initial = snapshot(db)
    changes = db.total_changes
    for _ in range(20):
        assert statistics(db) == (2, 2, 2, 4, 8)
        assert statistics(db, 'alpha') == (1, 1, 1, 2, 4)
        assert statistics(db, 'missing') == (0, 0, 0, 0, 0)
    assert db.total_changes == changes and snapshot(db) == initial, 'Statistics mutated rows/recency'
    # Cancellation between Seen removal and checkpoint barrier must roll both back.
    failure(lambda: transaction(db, lambda: clear_seen(db, 'alpha', inject=True)), Cancellation)
    assert snapshot(db) == initial
    db.close()
    db = sqlite3.connect(path)
    assert snapshot(db) == initial
    beta_before = {table: rows(db, table, 'profile_id=?', ('beta',)) for table in ('seen', 'progress', 'checkpoints', 'history_profiles')}
    result_before, progress_before, viewport_before = rows(db, 'results'), rows(db, 'progress'), rows(db, 'viewport')
    transaction(db, lambda: clear_seen(db, 'alpha'))
    assert rows(db, 'seen', 'profile_id=?', ('alpha',)) == []
    for table, original in beta_before.items():
        assert rows(db, table, 'profile_id=?', ('beta',)) == original
    assert rows(db, 'results') == result_before and rows(db, 'progress') == progress_before and rows(db, 'viewport') == viewport_before
    alpha_cp = rows(db, 'checkpoints', 'profile_id=?', ('alpha',))[0]
    assert decode_header(alpha_cp['payload'])['revision'] == 2
    assert db.execute('SELECT revision FROM pages WHERE session_id=?', ('session-alpha',)).fetchone() == (2,)
    assert db.execute('SELECT epoch FROM checkpoints WHERE profile_id=?', ('alpha',)).fetchone() == (1,)
    # A repeated clear still fences old callbacks even when no Seen row remains.
    transaction(db, lambda: clear_seen(db, 'alpha'))
    assert decode_header(rows(db, 'checkpoints', 'profile_id=?', ('alpha',))[0]['payload'])['revision'] == 3
    assert rows(db, 'seen', 'profile_id=?', ('alpha',)) == []
    history_before = rows(db, 'seen')
    checkpoint_before, page_before = rows(db, 'checkpoints'), rows(db, 'pages')
    transaction(db, lambda: reset_progress(db, 'alpha', 'commons'))
    assert not rows(db, 'progress', 'profile_id=? AND provider_id=?', ('alpha', 'commons'))
    assert len(rows(db, 'progress', 'profile_id=? AND provider_id=?', ('alpha', 'other-provider'))) == 2
    assert rows(db, 'progress', 'profile_id=?', ('beta',)) == beta_before['progress']
    assert rows(db, 'seen') == history_before and rows(db, 'results') == result_before
    assert rows(db, 'checkpoints') == checkpoint_before and rows(db, 'pages') == page_before
    # Optional query scope removes exactly one provider-query row.
    transaction(db, lambda: reset_progress(db, 'beta', 'commons', fingerprint(99)))
    assert len(rows(db, 'progress', 'profile_id=?', ('beta',))) == 3
    progress_before = rows(db, 'progress')
    transaction(db, lambda: clear_seen(db))
    assert rows(db, 'seen') == [] and rows(db, 'progress') == progress_before and rows(db, 'results') == result_before
    assert decode_header(rows(db, 'checkpoints', 'profile_id=?', ('alpha',))[0]['payload'])['revision'] == 4
    assert decode_header(rows(db, 'checkpoints', 'profile_id=?', ('beta',))[0]['payload'])['revision'] == 2
    assert rows(db, 'viewport') == viewport_before and hashlib.sha256(media.read_bytes()).digest() == media_hash
    # Failure after destructive operations also preserves rights and both profiles.
    before = snapshot(db)
    def fault():
        reset_progress(db, 'beta')
        clear_seen(db)
        raise old.InjectedFailure('after reset and revision barriers')
    failure(lambda: transaction(db, fault), old.InjectedFailure)
    assert snapshot(db) == before
    db.close()
    db = sqlite3.connect(path)
    assert snapshot(db) == before
    db.close()


def test_alias_groups_and_boundaries(directory):
    db = sqlite3.connect(directory / 'alias-boundary.db')
    fresh(db)
    a, b, c, d = [fingerprint(index) for index in range(1, 5)]
    add_seen(db, 'p', a, sequence=1, first_discovered=7, shown_at=9)
    add_seen(db, 'p', b, sequence=1, first_discovered=3, opened_at=12)
    add_seen(db, 'p', c, sequence=1, first_discovered=5, played_at=14)
    add_seen(db, 'other', a, sequence=1, first_discovered=2, saved_at=18)
    db.commit()
    transaction(db, lambda: link_aliases(db, 'p', {a, b}))
    transaction(db, lambda: link_aliases(db, 'p', {b, c, d}))
    linked = rows(db, 'seen', 'profile_id=?', ('p',))
    assert len(linked) == 3 and {row['identity_id'] for row in linked} == {a}
    assert all(tuple(row[key] for key in EVENT_COLUMNS) == (3, 9, 12, 14, None, None) for row in linked)
    assert rows(db, 'seen', 'profile_id=?', ('other',))[0]['saved_at'] == 18
    assert all(row['saved_at'] is None for row in linked), 'Another profile leaked an event'
    assert not rows(db, 'seen', 'fingerprint=?', (d,)), 'Linking manufactured an unobserved alias'
    # Groups are indivisible: target two aliases drops the whole three-alias old
    # group and retains the newer singleton, never half an identity.
    db.execute('UPDATE seen SET mutation_seq=10 WHERE profile_id=?', ('other',))
    db.commit()
    plan = retention_plan(db, Policy(aliases=2))
    assert plan[1] == {('p', alias) for alias in (a, b, c)}
    transaction(db, lambda: apply_plan(db, plan))
    assert len(rows(db, 'seen')) == 1
    # Protection is the complete transitive group even if only one alias is in
    # a materialized page. Saved/played are independent and never inferred.
    for alias in (a, b, c):
        add_seen(db, 'p', alias, a, sequence=1, shown_at=4)
    add_checkpoint(db, 'pinned', 'p', fingerprint(80), sequence=1, aliases=(a,))
    add_progress(db, 'p', 'provider-a', fingerprint(80), 1)
    add_progress(db, 'p', 'provider-b', fingerprint(80), 1)
    db.commit()
    plan = retention_plan(db, Policy(aliases=3, progress=2))
    assert plan[1] == {('other', a)} and not plan[2] and not plan[3]
    transaction(db, lambda: apply_plan(db, plan))
    protected = snapshot(db)
    failure(lambda: transaction(db, lambda: apply_plan(db, retention_plan(db, Policy(aliases=2)))), CapacityFailure)
    assert snapshot(db) == protected
    failure(lambda: transaction(db, lambda: apply_plan(db, retention_plan(db, Policy(progress=1)))), CapacityFailure)
    assert snapshot(db) == protected
    # Failure includes caller writes made before the capacity check.
    def capacity_after_write():
        db.execute('UPDATE progress SET cursor=?', ('must-not-commit',))
        add_seen(db, 'p', d, a, 99)
        apply_plan(db, retention_plan(db, Policy(aliases=3)))
    failure(lambda: transaction(db, capacity_after_write), CapacityFailure)
    assert snapshot(db) == protected
    db.close()
    db = sqlite3.connect(directory / 'alias-boundary.db')
    assert snapshot(db) == protected
    db.close()
    # Separate profile and checkpoint caps must also refuse protected eviction.
    db = sqlite3.connect(':memory:')
    fresh(db)
    for index in range(2):
        profile = 'profile-' + str(index)
        alias = fingerprint(index + 20)
        add_seen(db, profile, alias)
        add_checkpoint(db, 'materialized-' + str(index), profile, fingerprint(80), aliases=(alias,))
    db.commit()
    before = snapshot(db)
    for policy in (Policy(profiles=1), Policy(checkpoints=1)):
        failure(lambda: transaction(db, lambda: apply_plan(db, retention_plan(db, policy))), CapacityFailure)
        assert snapshot(db) == before
    db.close()
    # A new empty checkpoint is protected in its creating transaction; after
    # commit it is evictable unless a page has been materialized.
    db = sqlite3.connect(':memory:')
    fresh(db)
    add_checkpoint(db, 'a', 'p', fingerprint(80), 1)
    add_checkpoint(db, 'b', 'p', fingerprint(80), 1)
    db.commit()
    assert retention_plan(db, Policy(checkpoints=1), written={'a'})[3] == {'b'}
    failure(lambda: retention_plan(db, Policy(checkpoints=1), written={'a', 'b'}), CapacityFailure)
    assert retention_plan(db, Policy(checkpoints=1))[3] == {'a'}, 'Lexical tie-break must be deterministic'
    db.close()


def test_post_prune_rollback(directory):
    path = directory / 'post-prune.db'
    db = sqlite3.connect(path)
    fresh(db)
    for index in range(3):
        profile = 'p' + str(index)
        for alias in range(3):
            add_seen(db, profile, fingerprint(index * 10 + alias), fingerprint(index * 10), index + 1)
        add_progress(db, profile, 'provider', fingerprint(70), index + 1)
        add_checkpoint(db, 'session-' + profile, profile, fingerprint(70), index + 1)
    db.commit()
    before = snapshot(db)
    for point in ('checkpoint', 'seen', 'progress', 'before_commit_fault', 'before_commit_cancel'):
        def mutation():
            sequence = next_sequence(db)
            add_seen(db, 'new', fingerprint(99), sequence=sequence, shown_at=10)
            add_progress(db, 'new', 'provider', fingerprint(71), sequence)
            db.execute('UPDATE results SET rights=?', (old.SAMPLE_RIGHTS,))
            plan = retention_plan(db, Policy(profiles=2, aliases=4, progress=2, checkpoints=1))
            assert all(plan), 'Every retention category must be part of rollback fixture'
            apply_plan(db, plan, fail_after=point, sequence=sequence)
            if point == 'before_commit_fault':
                raise old.InjectedFailure('commit guard after all pruning')
            if point == 'before_commit_cancel':
                raise Cancellation('commit guard after all pruning')
        expected = Cancellation if point == 'before_commit_cancel' else old.InjectedFailure
        failure(lambda: transaction(db, mutation), expected)
        assert snapshot(db) == before, 'Pruning/caller mutation/sequence escaped rollback'
        db.close()
        db = sqlite3.connect(path)
        assert snapshot(db) == before, 'Rollback before transaction success did not survive reopen'
    # A subsequent valid transaction proves the database remains usable.
    transaction(db, lambda: apply_plan(db, retention_plan(db, Policy(profiles=2, aliases=4, progress=2, checkpoints=1))))
    assert statistics(db)[0] <= 2 and statistics(db)[3] <= 4
    committed = snapshot(db)
    db.close()
    db = sqlite3.connect(path)
    assert snapshot(db) == committed
    db.close()


def test_mutation_sequence_overflow(directory):
    path = directory / 'sequence-overflow.db'
    db = sqlite3.connect(path)
    fresh(db)
    add_seen(db, 'p', fingerprint(1))
    db.execute('UPDATE history_meta SET mutation_seq=?', (2 ** 63 - 1,))
    db.commit()
    before = snapshot(db)
    def mutation():
        db.execute('DELETE FROM seen')
        next_sequence(db)
    failure(lambda: transaction(db, mutation), ValueError)
    assert snapshot(db) == before
    db.close()
    db = sqlite3.connect(path)
    assert snapshot(db) == before
    db.close()


def test_bounded_properties(directory):
    path = directory / 'bounded-properties.db'
    db = sqlite3.connect(path)
    fresh(db)
    policy = Policy(profiles=5, aliases=24, progress=8, checkpoints=3)
    pinned = tuple(fingerprint(index) for index in range(1, 5))
    for alias in pinned:
        add_seen(db, 'active', alias, pinned[0], shown_at=7)
    add_progress(db, 'active', 'commons', fingerprint(900), 1)
    add_progress(db, 'active', 'other-provider', fingerprint(900), 1)
    add_checkpoint(db, 'active-session', 'active', fingerprint(900), 1, aliases=(pinned[0],))
    db.commit()
    rng = random.Random(881)
    successful = rejected = 0
    for index in range(1000):
        before = snapshot(db)
        profile = 'p' + str(rng.randrange(12))
        size = rng.randint(1, 5)
        aliases = tuple(fingerprint(10000 + index * 10 + offset) for offset in range(size))
        session = 'new-session-' + str(index)
        def mutation():
            sequence = next_sequence(db)
            for alias in aliases:
                add_seen(db, profile, alias, aliases[0], sequence, shown_at=index + 10)
            query = fingerprint(1000 + index)
            add_progress(db, profile, 'provider-' + str(index % 3), query, sequence)
            if index % 3 == 0:
                add_checkpoint(db, session, profile, query, sequence)
            # Periodic impossible protected groups exercise failure across the
            # same long-lived database; rollback must not reset any older state.
            if index % 101 == 0:
                for extra in range(25):
                    add_seen(db, 'active', fingerprint(1000000 + index * 25 + extra), pinned[0], sequence, shown_at=7)
            candidate = rows(db, 'seen')
            expected_groups = defaultdict(set)
            facts = {}
            for row in candidate:
                expected_groups[row['profile_id'], row['identity_id']].add(row['fingerprint'])
                facts[row['profile_id'], row['fingerprint']] = tuple(row[column] for column in EVENT_COLUMNS)
            plan = retention_plan(db, policy, written={session} if index % 3 == 0 else set())
            apply_plan(db, plan, sequence=sequence)
            remaining = defaultdict(set)
            for row in rows(db, 'seen'):
                key = (row['profile_id'], row['identity_id'])
                remaining[key].add(row['fingerprint'])
                assert tuple(row[column] for column in EVENT_COLUMNS) == facts[row['profile_id'], row['fingerprint']]
                assert row['played_at'] is None and row['saved_at'] is None
            assert all(aliases == expected_groups[key] for key, aliases in remaining.items()), 'Eviction split an identity group'
            counts = statistics(db)
            assert counts[0] <= policy.profiles and counts[1] <= policy.checkpoints
            assert counts[3] <= policy.aliases and counts[4] <= policy.progress
            assert {row['fingerprint'] for row in rows(db, 'seen', 'profile_id=?', ('active',))} == set(pinned)
            assert len(rows(db, 'progress', 'profile_id=?', ('active',))) == 2
            assert rows(db, 'checkpoints', 'session_id=?', ('active-session',))
            active_profiles = {row[0] for row in db.execute('SELECT profile_id FROM checkpoints UNION SELECT profile_id FROM seen UNION SELECT profile_id FROM progress')}
            assert {row['profile_id'] for row in rows(db, 'history_profiles')} == active_profiles
        try:
            transaction(db, mutation)
            successful += 1
        except CapacityFailure:
            rejected += 1
            assert snapshot(db) == before
        if index % 100 == 0:
            stable = snapshot(db)
            db.close()
            db = sqlite3.connect(path)
            assert snapshot(db) == stable
    assert successful == 990 and rejected == 10
    final = snapshot(db)
    db.close()
    db = sqlite3.connect(path)
    assert snapshot(db) == final
    db.close()
    return dict(transactions=1000, committed=successful, protected_rejections=rejected)



def test_migration_pruning(directory):
    for version in (1, 2):
        path = directory / f'migration-prune-v{version}.db'
        db = sqlite3.connect(path)
        header = seed_old(db, version)
        original = snapshot(db)
        policy = Policy(profiles=1)
        failure(lambda: migrate(db, 'after_prune', policy), old.InjectedFailure)
        assert snapshot(db) == original
        db.close()
        db = sqlite3.connect(path)
        assert snapshot(db) == original
        assert migrate(db, policy=policy)
        assert statistics(db)[0] == 1
        assert {row['profile_id'] for row in rows(db, 'seen')} == {header['profile']}
        assert {row['profile_id'] for row in rows(db, 'progress')} == {header['profile']}
        assert len(rows(db, 'results')) == len(rows(db, 'pages')) == len(rows(db, 'viewport')) == 1
        committed = snapshot(db)
        db.close()
        db = sqlite3.connect(path)
        assert snapshot(db) == committed
        db.close()


def test_independent_event_monotonicity():
    db = sqlite3.connect(':memory:')
    fresh(db)
    a, b = fingerprint(1), fingerprint(2)
    for alias in (a, b):
        add_seen(db, 'p', alias, a, first_discovered=5, shown_at=12, opened_at=16, played_at=18)
    add_seen(db, 'other', a, first_discovered=9, saved_at=80)
    db.commit()
    for incoming in (dict(first_discovered=9, shown_at=8), dict(first_discovered=2, saved_at=23),
                     dict(first_discovered=7, played_at=17), dict(first_discovered=20, opened_at=30)):
        def put_seen():
            # Independently model source mergeFacts plus updateGroup. A missing
            # event contributes no value; each event column remains independent.
            prior = rows(db, 'seen', 'profile_id=? AND identity_id=?', ('p', a))
            merged = {}
            for column in EVENT_COLUMNS:
                values = [row[column] for row in prior if row[column] is not None]
                if incoming.get(column) is not None:
                    values.append(incoming[column])
                merged[column] = (min(values) if column == 'first_discovered' else max(values)) if values else None
            sequence = next_sequence(db)
            db.execute(TOUCH_PROFILE_SQL, ('p', sequence))
            db.execute('UPDATE seen SET ' + ','.join(column + '=?' for column in EVENT_COLUMNS) +
                       ',mutation_seq=? WHERE profile_id=? AND identity_id=?',
                       (*[merged[column] for column in EVENT_COLUMNS], sequence, 'p', a))
        transaction(db, put_seen)
        records = rows(db, 'seen', 'profile_id=?', ('p',))
        assert len({tuple(row[column] for column in EVENT_COLUMNS) for row in records}) == 1
        assert all(row['shown_at'] == 12 and row['played_at'] == 18 for row in records)
        assert rows(db, 'seen', 'profile_id=?', ('other',))[0]['saved_at'] == 80
    assert all(tuple(row[column] for column in EVENT_COLUMNS) == (2, 12, 30, 18, 23, None)
               for row in rows(db, 'seen', 'profile_id=?', ('p',)))
    db.close()


def test_deterministic_insertion_order():
    original, reversed_order = sqlite3.connect(':memory:'), sqlite3.connect(':memory:')
    fresh(original)
    fresh(reversed_order)
    for index in range(5):
        profile = 'p' + str(index)
        for offset in range(2):
            add_seen(original, profile, fingerprint(index * 10 + offset), fingerprint(index * 10), sequence=1)
        add_progress(original, profile, 'provider', fingerprint(1), sequence=1)
        add_checkpoint(original, 'session-' + profile, profile, fingerprint(1), sequence=1)
    original.commit()
    for table in table_names(reversed_order):
        reversed_order.execute('DELETE FROM ' + table)
        for row in reversed(rows(original, table)):
            insert(reversed_order, table, row)
    reversed_order.commit()
    policy = Policy(profiles=3, aliases=4, progress=2, checkpoints=2)
    forward = retention_plan(original, policy)
    reverse = retention_plan(reversed_order, policy)
    assert forward == reverse
    assert forward[0] == {'p0', 'p1'}
    assert forward[3] == {'session-p0', 'session-p1', 'session-p2'}
    assert ('p2', fingerprint(20)) in forward[1] and ('p2', fingerprint(21)) in forward[1]
    assert ('p2', 'provider', fingerprint(1)) in forward[2]
    old_meta = rows(original, 'history_meta')
    old_profiles = {row['profile_id']: row['mutation_seq'] for row in rows(original, 'history_profiles')}
    transaction(original, lambda: apply_plan(original, forward))
    assert rows(original, 'history_meta') == old_meta
    assert all(row['mutation_seq'] == old_profiles[row['profile_id']] for row in rows(original, 'history_profiles'))
    original.close()
    reversed_order.close()


def main():
    source_guards()
    with tempfile.TemporaryDirectory(prefix='discovery-history-sqlite-') as temporary:
        directory = Path(temporary)
        migration_report = test_migration(directory)
        rejection_count = test_migration_rejection(directory)
        test_migration_pruning(directory)
        test_independent_event_monotonicity()
        test_deterministic_insertion_order()
        test_controls_and_statistics(directory)
        test_alias_groups_and_boundaries(directory)
        test_post_prune_rollback(directory)
        test_mutation_sequence_overflow(directory)
        properties = test_bounded_properties(directory)
    report = {
        'host_sqlite_version': sqlite3.sqlite_version,
        'target_schema': 3,
        'database_filename_preserved': old.HELPER.group(1),
        'source_extracted_create_statements': len(CREATE_SQL),
        'source_extracted_alter_statements': 7,
        'source_extracted_group_lookup_indexes': len(INDEX_SQL),
        'old_schemas': 'independently pinned complete v1/v2 DDL',
        'old_headers': 'untouched genuine frozen v1/v2 prefixes plus empty pool counts; host-derived',
        'genuine_v1_and_v2_to_v3_host_sql_migration': 'PASS',
        'fresh_v3_matches_both_migrated_schemas': 'PASS',
        **migration_report,
        'malformed_corrupt_and_protected_upgrade_rejections': rejection_count,
        'unsupported_schema_0_4_99_preserved': 'PASS',
        'migration_idempotence_close_reopen': 'PASS',
        'migration_pruning_is_atomic_with_version_and_metadata': 'PASS',
        'independent_monotonic_event_columns': 'PASS',
        'deterministic_plan_across_opposite_insertion_orders': 'PASS',
        'automatic_pruning_does_not_refresh_recency': 'PASS',
        'existing_known_aliases_merge_only_observed_facts': 'PASS',
        'unknown_legacy_aliases_stay_singletons': 'PASS',
        'missing_alias_rows_and_events_not_invented': 'PASS',
        'transitive_alias_groups_evict_whole': 'PASS',
        'protected_profile_checkpoint_group_and_query_boundaries': 'PASS',
        'current_and_all_seen_clear_scope_with_revision_barriers': 'PASS',
        'single_provider_and_optional_query_reset_scope': 'PASS',
        'rights_progress_other_profiles_and_synthetic_media_unchanged_by_seen_clear': 'PASS',
        'statistics_no_writes_or_recency_change': 'PASS',
        'post_pruning_cancellation_and_fault_rollback_reopen': 'PASS',
        'mutation_sequence_overflow_rollback_reopen': 'PASS',
        'bounded_randomized_host_model': properties,
        'adapter_source_sha256': hashlib.sha256(SOURCE.encode()).hexdigest(),
        'planner_source_sha256': hashlib.sha256(PLANNER_SOURCE.encode()).hexdigest(),
        'kotlin_planner_and_codec_execution': 'NOT_RUN_BY_THIS_SCRIPT',
        'android_sqlite_runtime': 'NOT_RUN',
        'android_process_or_power_loss': 'NOT_RUN',
        'device_media_files': 'NOT_TOUCHED',
        'providers_network_or_requests': 'NOT_USED',
    }
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
