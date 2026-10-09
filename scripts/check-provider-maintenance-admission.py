#!/usr/bin/env python3
"""M7-A static scope guards; not signature/rollback/device behavioral acceptance."""
from pathlib import Path
import json, os, subprocess
ROOT = Path(__file__).resolve().parents[1]
OUT = Path(os.environ.get('ESV_EVIDENCE_DIR', ROOT/'docs/m7a/evidence'))
OUT.mkdir(parents=True, exist_ok=True)
BASE = '0d1bae2dbbb4212c5fc43b5ed536df447974a221'
protected = ['third_party', 'vendor', 'gradle', 'app/build.gradle.kts', 'settings.gradle.kts',
             'app/src/main/AndroidManifest.xml', 'core-model', 'core-domain', 'library', 'save', 'recording', 'hls',
             'app/src/main/kotlin/com/easysavevideo/app/library', 'app/src/main/kotlin/com/easysavevideo/app/recording',
             'app/src/main/kotlin/com/easysavevideo/app/save',
             'app/src/debug/kotlin/com/easysavevideo/app/entitlements',
             'app/src/release/kotlin/com/easysavevideo/app/entitlements']
assert not subprocess.check_output(['git','diff','--name-only',BASE,'--',*protected],cwd=ROOT,text=True)
source = ROOT/'commons-provider/src/main/kotlin/com/easysavevideo/commons'
maintenance = source/'maintenance'
text = '\n'.join(p.read_text() for p in maintenance.glob('*.kt'))
for banned in ['DexClassLoader', 'URLClassLoader', 'Class.forName', 'evaluateJavascript(', 'Runtime.getRuntime(',
               'ProcessBuilder(', 'java.net.', 'android.', 'androidx.', 'com.google.crypto.tink',
               'Log.d(', 'Log.e(', 'println(', 'printStackTrace(', 'exception.message', 'failure.message']:
    assert banned not in text, banned
package = (maintenance/'ProviderPackage.kt').read_text()
for required in ['SHA256withRSA', 'MAX_ENVELOPE_BYTES = 8_192', 'MAX_PAYLOAD_BYTES = 4_096',
                 'MAX_LIFETIME_MILLIS', 'TARGET_MISMATCH', 'UNTRUSTED_KEY', 'INVALID_SIGNATURE']:
    assert required in package, required
registration = (ROOT/'app/src/main/kotlin/com/easysavevideo/app/discovery/CommonsSourceRegistration.kt').read_text()
assert 'ProviderPackageVerifier()' in registration and 'RepositoryCommonsMaintenance' in registration
assert 'enabled && configured && maintenanceAvailable' in registration and 'else emptyList()' in registration
assert 'File(context.applicationContext.filesDir, "provider-maintenance")' in registration
assert not any(x in registration for x in ['https://', 'http://', 'PublicKey', 'BEGIN', 'ProviderUpdateRuntime'])
metadata = (source/'CommonsMetadataProvider.kt').read_text()
assert 'lease?.check()' in metadata and 'lease?.succeeded(::checkCompletion)' in metadata and 'shrinkLimits(limits, it)' in metadata
assert 'CommonsIdentity.API + "?" + query' in metadata and 'val namespace = lease?.cacheNamespace' in metadata
assert 'if (count == 0) invalidResponse()' in metadata
report = dict(kind='STATIC_SOURCE_GUARDS_ONLY',baseline=BASE,scope='M7-A BUILTIN COMMONS PARAMETERS ONLY',
    runtime_dependencies='EXACT74_UNCHANGED_INPUTS',free_library_and_entitlements='UNCHANGED',
    package='DECLARATIVE_FIXED_TARGET_BOUNDED',production_trust_roots='EMPTY',remote_endpoint='ABSENT',
    extraction_rules_and_other_providers='NOT_IMPLEMENTED',android_storage_device='NOT_RUN',vault='DISABLED_UNCHANGED')
(OUT/'provider-maintenance-admission.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
