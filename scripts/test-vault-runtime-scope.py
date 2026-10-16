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

class StreamingScopeTest(ScopeTest):
    def setUp(self):
        super().setUp()
        self.leaf = 'app/src/main/kotlin/StreamingLeaf.kt'
        self.leaf_text = ('import com.google.crypto.tink.StreamingAead\n'
            'PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB\n'
            'handle.size() == 1; entry.status == KeyStatus.ENABLED\n'
            'entry.isPrimary; handle.primary.id == entry.id\n'
            'bytes.contentEquals(canonicalEncrypted(bytes))\n')
        self.put(self.leaf, self.leaf_text)
        path = self.root / 'docs/vault/VAULT_RUNTIME_SOURCE_SCOPE.json'
        scope = json.loads(path.read_text())
        scope['tink_references'][self.leaf] = ['com.google.crypto.tink.StreamingAead']
        scope['streaming_leaf'] = self.leaf
        scope['streaming_profile'] = 'AES256_GCM_HKDF_1MB'
        path.write_text(json.dumps(scope))
    def test_other_streaming_profile_rejected(self):
        self.put(self.leaf, self.leaf_text.replace('AES256_GCM_HKDF_1MB', 'AES128_GCM_HKDF_4KB'))
        with self.assertRaises(AssertionError): module.verify(self.root)
    def test_extra_profile_rejected(self):
        self.put(self.leaf, self.leaf_text + 'PredefinedStreamingAeadParameters.AES256_GCM_HKDF_4KB')
        with self.assertRaises(AssertionError): module.verify(self.root)
    def test_no_key_count_check_rejected(self):
        self.put(self.leaf, self.leaf_text.replace('handle.size() == 1', 'handle.size() > 0'))
        with self.assertRaises(AssertionError): module.verify(self.root)
    def test_no_canonical_envelope_check_rejected(self):
        self.put(self.leaf, self.leaf_text.replace('bytes.contentEquals(canonicalEncrypted(bytes))', 'true'))
        with self.assertRaises(AssertionError): module.verify(self.root)
    def test_secret_export_rejected(self):
        self.put(self.leaf, self.leaf_text + 'val leak = InsecureSecretKeyAccess.get()')
        with self.assertRaises(AssertionError): module.verify(self.root)

if __name__ == '__main__': unittest.main()

