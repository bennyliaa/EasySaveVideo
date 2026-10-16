#!/usr/bin/env python3
"""Static R2 source boundary only. Does not execute crypto, SQLite, devices or historical probes."""
from pathlib import Path
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[1]
SCOPE = ROOT / 'docs/vault/VAULT_RUNTIME_SOURCE_SCOPE.json'

def verify(root=ROOT):
    scope = json.loads((root / 'docs/vault/VAULT_RUNTIME_SOURCE_SCOPE.json').read_text())
    old = root / 'scripts/check-tink-admission.py'
    assert hashlib.sha256(old.read_bytes()).hexdigest() == scope['historical_gate_sha256'], 'Historical gate changed'
    imports = {}
    for source in root.glob('*/src/main/**/*'):
        if source.suffix not in ('.kt', '.java'):
            continue
        text = source.read_text()
        assert not re.search(r'import\s+com\.google\.crypto\.tink(?:\.[A-Za-z0-9_]+)*\.\*', text), f'Tink wildcard import: {source}'
        names = re.findall(r'\bcom\.google\.crypto\.tink\.[A-Za-z0-9_.]+', text)
        if names:
            relative = source.relative_to(root).as_posix()
            assert relative in scope['tink_references'], f'Unreviewed Tink source: {relative}'
            assert sorted(set(names)) == scope['tink_references'][relative], f'Tink imports changed: {relative}'
            imports[relative] = sorted(set(names))
    assert imports == scope['tink_references'], 'Missing reviewed Tink integration file'
    entry = (root / scope['entry']).read_text()
    disabled = entry.index('const val ENABLED: Boolean = false')
    reject = entry.index('if (!ENABLED) return null')
    constructor = entry.index('val authentication = AndroidVaultAuthentication()')
    assert disabled >= 0 and reject >= 0 and constructor > reject
    assert ':vault' not in (root / 'settings.gradle.kts').read_text(), 'Unexpected module'
    return {'status': 'PASS', 'scope': 'static source/import and disabled-entry boundary only',
            'historical_dependency_gate': 'preserved, not executed by this check',
            'tink_source_files': len(imports), 'production_enabled': False,
            'crypto_execution': 'NOT_RUN', 'android_sqlite': 'NOT_RUN', 'device_acceptance': 'NOT_RUN'}

if __name__ == '__main__':
    print(json.dumps(verify(), indent=2))
