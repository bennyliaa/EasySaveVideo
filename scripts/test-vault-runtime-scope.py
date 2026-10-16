#!/usr/bin/env python3
"""Synthetic source fixtures only; never imports or executes Android/Tink code."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('vault_scope', Path(__file__).with_name('check-vault-runtime-scope.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class ScopeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = 'app/src/main/kotlin/Test.kt'
        self.entry = 'app/src/main/kotlin/Entry.kt'
        self.put('scripts/check-tink-admission.py', '# historical fixture\n')
        self.put('settings.gradle.kts', 'include(":app")')
        self.put(self.source, 'import com.google.crypto.tink.Aead\n')
        self.put(self.entry, 'const val ENABLED: Boolean = false\nif (!ENABLED) return null\nval authentication = AndroidVaultAuthentication()\n')
        scope = {'historical_gate_sha256': hashlib.sha256(b'# historical fixture\n').hexdigest(),
                 'entry': self.entry, 'tink_references': {self.source: ['com.google.crypto.tink.Aead']}}
        self.put('docs/vault/VAULT_RUNTIME_SOURCE_SCOPE.json', json.dumps(scope))
    def tearDown(self):
        self.temp.cleanup()
    def put(self, path, text):
        p = self.root / path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
    def test_exact_scope(self):
        self.assertEqual('PASS', module.verify(self.root)['status'])
    def test_top_level_wildcard_new_file_rejected(self):
        self.put('app/src/main/kotlin/Other.kt', 'import com.google.crypto.tink.*')
        with self.assertRaises(AssertionError): module.verify(self.root)
    def test_nested_wildcard_rejected(self):
        self.put(self.source, 'import com.google.crypto.tink.aead.*')
        with self.assertRaises(AssertionError): module.verify(self.root)
    def test_fully_qualified_outside_scope_rejected(self):
        self.put('app/src/main/kotlin/Other.kt', 'val key: com.google.crypto.tink.KeysetHandle')
        with self.assertRaises(AssertionError): module.verify(self.root)
    def test_old_gate_modified_rejected(self):
        self.put('scripts/check-tink-admission.py', '# changed')
        with self.assertRaises(AssertionError): module.verify(self.root)
    def test_enabled_entry_rejected(self):
        self.put(self.entry, 'const val ENABLED: Boolean = true\nif (!ENABLED) return null\nval authentication = AndroidVaultAuthentication()')
        with self.assertRaises(ValueError): module.verify(self.root)

if __name__ == '__main__': unittest.main()
