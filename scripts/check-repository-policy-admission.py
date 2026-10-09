#!/usr/bin/env python3
"""M7-B source/admission guards. Behavioral and device evidence are separate."""
from pathlib import Path
import json, os, subprocess, zipfile
ROOT = Path(__file__).resolve().parents[1]
OUT = Path(os.environ.get('ESV_EVIDENCE_DIR', ROOT/'docs/m7b/evidence'))
OUT.mkdir(parents=True, exist_ok=True)
BASE = 'bccfc3ba644b4e6b86a52bf4474f240dd1971cee'
protected = ['third_party','vendor','gradle','app','settings.gradle.kts','core-model','core-domain',
             'library','save','recording','hls','discovery','ui-theme']
assert not subprocess.check_output(['git','diff','--name-only',BASE,'--',*protected],cwd=ROOT,text=True)
folder = ROOT/'commons-provider/src/main/kotlin/com/easysavevideo/commons/maintenance'
policy = (folder/'RepositoryPolicy.kt').read_text()
repository = (folder/'ProviderRepository.kt').read_text()
runtime = (folder/'ProviderUpdateRuntime.kt').read_text()
for required in ['0x45535653','SHA256withRSA','MAX_ENVELOPE_BYTES = 8_192','MAX_PAYLOAD_BYTES = 4_096',
                 'MAX_REVOKED_PACKAGE_VERSIONS = 64','MAX_REVOKED_PACKAGE_KEY_IDS = 32',
                 'Collections.unmodifiableSet','Map<String, PublicKey> = emptyMap()']:
    assert required in policy, required
for required in ['REPLAYED_POLICY','SECURITY_FLOOR_REGRESSION','REVOCATION_REGRESSION',
                 'SIGNED_FALLBACK_REQUIRED','DISABLE_BARRIER','ROLLBACK_NOT_AUTHORIZED','CLOCK_ROLLBACK',
                 'private const val FORMAT = 2','RepositoryPolicyVerifier()','targetPackageDigest']:
    assert required in repository, required
assert repository.count('clock.nowMillis()') == 1
assert 'ProviderUpdateKind.REPOSITORY_POLICY -> repository::installPolicyGuarded' in runtime
combined = '\n'.join(p.read_text() for p in folder.glob('*.kt'))
for banned in ['KeyPairGenerator','PrivateKey','initSign(','https://','http://','BEGIN PRIVATE',
               'DexClassLoader','URLClassLoader','Class.forName','evaluateJavascript(',
               'Runtime.getRuntime(','ProcessBuilder(','java.net.','android.','androidx.',
               'Log.d(','Log.e(','println(','printStackTrace(','exception.message','failure.message']:
    assert banned not in combined, banned
jars=list((ROOT/'commons-provider/build/libs').glob('*.jar'))
assert len(jars)==1,jars
classes=0
with zipfile.ZipFile(jars[0]) as jar:
    for name in jar.namelist():
        if not name.endswith('.class'): continue
        classes+=1; data=jar.read(name)
        for token in [b'Fixtures',b'KeyPairGenerator',b'java/security/PrivateKey',b'initSign']:
            assert token not in data,(name,token)
report=dict(kind='STATIC_SOURCE_AND_MAIN_JAR_GUARDS_ONLY',baseline=BASE,
    scope='M7-B SINGLE COMMONS OFFLINE SIGNED REPOSITORY POLICY',runtime_dependencies='EXACT74_UNCHANGED_INPUTS',
    production_package_and_policy_roots='EMPTY',source_endpoint='ABSENT',main_jar_classes=classes,
    test_signing_material_in_main_jar=False,package_policy_version_anchors='SEPARATE',
    full_directory_rollback_resistance='NOT_PROVIDED',trusted_external_clock='NOT_PROVIDED',
    policy_root_rotation='NOT_IMPLEMENTED',device_power_loss='NOT_RUN',vault='DISABLED_UNCHANGED')
(OUT/'repository-policy-admission.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
