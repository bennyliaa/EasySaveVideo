#!/usr/bin/env python3
"""M7-C static scope checks; not independent behavioral or physical-device evidence."""
from pathlib import Path
import hashlib, json, os, subprocess, zipfile
ROOT = Path(__file__).resolve().parents[1]
OUT = Path(os.environ.get('ESV_EVIDENCE_DIR', ROOT/'docs/m7c/evidence'))
OUT.mkdir(parents=True, exist_ok=True)
BASE = '13ab2b09e1ba33b1e8312b52ba42f78939071c3d'
protected = ['third_party','vendor','gradle','settings.gradle.kts','build.gradle.kts',
             'app/build.gradle.kts','app/src/main/AndroidManifest.xml','core-model','core-domain',
             'library','save','recording','hls','discovery','ui-theme',
             'app/src/main/kotlin/com/easysavevideo/app/library',
             'app/src/main/kotlin/com/easysavevideo/app/recording',
             'app/src/main/kotlin/com/easysavevideo/app/save','app/src/debug','app/src/release']
assert not subprocess.check_output(['git','diff','--name-only',BASE,'--',*protected],cwd=ROOT,text=True)
folder = ROOT/'commons-provider/src/main/kotlin/com/easysavevideo/commons/maintenance'
for name in ('ProviderPackage.kt','RepositoryPolicy.kt','ProviderUpdateRuntime.kt'):
    path = folder/name
    original = subprocess.check_output(['git','show',f'{BASE}:{path.relative_to(ROOT)}'],cwd=ROOT)
    assert original == path.read_bytes(), name
repository = (folder/'ProviderRepository.kt').read_text()
for required in ['REPLAYED_POLICY','SECURITY_FLOOR_REGRESSION','REVOCATION_REGRESSION',
                 'SIGNED_FALLBACK_REQUIRED','DISABLE_BARRIER','ROLLBACK_NOT_AUTHORIZED','CLOCK_ROLLBACK',
                 'private const val FORMAT = 3','RepositoryPolicyVerifier()','targetPackageDigest',
                 'ProviderFixedSelfTest::run','ProviderHealthLimits.MAX_QUARANTINED_VERSIONS',
                 'beginObservation','completeObservation','commitValidation','lease.packageDigest',
                 'lease.policyDigest','Collections.unmodifiableList']:
    assert required in repository, required
assert repository.count('clock.nowMillis()') == 1
assert 'fun confirmHealthy(' not in repository
combined = '\n'.join(p.read_text() for p in folder.glob('*.kt'))
for banned in ['KeyPairGenerator','PrivateKey','initSign(','https://','http://','BEGIN PRIVATE',
               'DexClassLoader','URLClassLoader','Class.forName','evaluateJavascript(',
               'Runtime.getRuntime(','ProcessBuilder(','java.net.','android.','androidx.',
               'Log.d(','Log.e(','println(','printStackTrace(','exception.message','failure.message']:
    assert banned not in combined,banned
registration = (ROOT/'app/src/main/kotlin/com/easysavevideo/app/discovery/CommonsSourceRegistration.kt').read_text()
assert 'ProviderPackageVerifier()' in registration and 'RepositoryCommonsMaintenance' in registration
assert not any(token in registration for token in ['https://','http://','PublicKey','ProviderUpdateRuntime'])
metadata = (folder.parent/'CommonsMetadataProvider.kt').read_text()
assert 'if (!result.fromCache) lease?.succeeded(::checkCompletion)' in metadata
assert 'freshExchangeStarted && failure.code in OBSERVABLE_FAILURES' in metadata
ui = (ROOT/'app/src/main/kotlin/com/easysavevideo/app/ui/ProviderManagementScreen.kt').read_text()
assert 'EsvButton' in ui and 'EsvText' in ui and 'EsvTheme' in ui
assert 'MaterialTheme' not in ui
jars = list((ROOT/'commons-provider/build/libs').glob('*.jar'))
assert len(jars)==1,jars
classes=0
with zipfile.ZipFile(jars[0]) as jar:
    for name in jar.namelist():
        if not name.endswith('.class'): continue
        classes+=1; data=jar.read(name)
        for token in (b'Fixtures',b'KeyPairGenerator',b'java/security/PrivateKey',b'initSign',b'confirmHealthy'):
            assert token not in data,(name,token)
report = dict(kind='STATIC_SOURCE_AND_MAIN_JAR_GUARDS_ONLY',baseline=BASE,
    scope='M7-C SINGLE COMMONS LOCAL HEALTH AND MANAGEMENT',runtime_dependencies='EXACT74_UNCHANGED_INPUTS',
    production_package_and_policy_roots='EMPTY',source_endpoint='ABSENT',main_jar_classes=classes,
    test_signing_or_version_only_promotion_in_main_jar=False,format=3,legacy_state='FAIL_CLOSED_NO_RESET',
    fixed_self_test='SYNTHETIC_OFFLINE_NOT_SITE_COMPATIBILITY',health='LOCAL_BOUNDED_PROBATION_ONLY',
    full_directory_rollback_resistance='NOT_PROVIDED',trusted_external_clock='NOT_PROVIDED',
    policy_root_rotation='NOT_IMPLEMENTED',device_power_loss='NOT_RUN',vault='DISABLED_UNCHANGED')
(OUT/'provider-health-admission.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
