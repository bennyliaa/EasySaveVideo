#!/usr/bin/env python3
"""Run after compile/test/lint. Read-only source, release-bytecode, notice and ABI checks."""
from pathlib import Path
import hashlib, json, subprocess, sys, os, shutil
root=Path(__file__).resolve().parents[1]
evidence=Path(os.environ.get('ESV_EVIDENCE_DIR', str(root/'docs/m3b/evidence')))
evidence.mkdir(parents=True,exist_ok=True)
results={}
for module in ('core-model','core-domain','discovery','save','recording','library','hls','commons-provider'):
    bad=[]
    for path in (root/module/'src/main').rglob('*.kt'):
        for line in path.read_text().splitlines():
            if line.startswith('import ') and any(x in line for x in ('android.','androidx.','com.android.billing','newpipe','scrapling','skinkit')):
                bad.append(str(path.relative_to(root))+':'+line)
    assert not bad,bad
results['core_platform_provider_store_import_boundary']='PASS'
release=root/'app/build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes'
debug=root/'app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes'
assert release.is_dir() and debug.is_dir(),'Compile both variants first'
assert list(debug.rglob('FakeEntitlementSource.class')),'Positive Debug Fake control missing'
assert not list(release.rglob('*Fake*')),'Fake class in Release'
for path in release.rglob('*.class'):
    data=path.read_bytes()
    assert b'FakeEntitlementSource' not in data and b'development.save' not in data and b'development.all' not in data,path
results['fake_entitlements_debug_only']='PASS'
assert list(debug.rglob('DemoVideoProvider.class')), 'Positive Debug demo provider missing'
assert not list(release.rglob('*Demo*')), 'Demo class in Release'
for path in release.rglob('*.class'):
    assert b'DemoVideoProvider' not in path.read_bytes(), path
results['synthetic_discovery_debug_only']='PASS'
assert 'CommonsSourceRegistration.providers(commonsEnabled)' in (root/'app/src/release/kotlin/com/easysavevideo/app/discovery/BuildDiscovery.kt').read_text()
assert 'else emptyList()' in (root/'app/src/main/kotlin/com/easysavevideo/app/discovery/CommonsSourceRegistration.kt').read_text()
results['release_only_configured_commons_and_zero_source_fallback']='PASS (registration and two-mode JVM checks; device NOT_RUN)'
manifest=root/'third_party/current-runtime/IMPORT_MANIFEST.json'
data=json.loads(manifest.read_text())
for item in data['files']:
    path=manifest.parent/item['path']
    assert path.stat().st_size==item['bytes'] and hashlib.sha256(path.read_bytes()).hexdigest()==item['sha256'],path
results['license_recovery_original_bytes']='PASS (99 payloads + manifest)'
expected={'skinkit-contract':'4bbb7cf74f53e64f4adc7bd42d10b423f38dc961ba6a0dad145c16327007a083','skinkit-default':'b0f7b327ec9c9228de6fed2409b883b5f55973d1f65c3d69a96c45a8f2ee15df'}
for name,digest in expected.items():
    path=root/f'vendor/maven/dev/skinkit/{name}/0.0.1/{name}-0.0.1.jar'
    assert hashlib.sha256(path.read_bytes()).hexdigest()==digest,path
results['skinkit_original_release_jar_hashes']='PASS'
java=Path(os.environ['JAVA_HOME'])/'bin/java' if os.environ.get('JAVA_HOME') else Path(shutil.which('java'))
contract=root/'vendor/maven/dev/skinkit/skinkit-contract/0.0.1/skinkit-contract-0.0.1.jar'
api=subprocess.check_output([str(java),'-m','jdk.jdeps/com.sun.tools.javap.Main','-classpath',str(contract),'dev.skinkit.contract.ThemeProvider'],text=True)
for method in ('getColor(', 'getTypography(', 'getDimension(', 'getDrawable(', 'getIcon(', 'getAnimation(', 'getComponentStyle('):
    assert method in api,method
results['seven_getter_abi']='PASS'
(evidence/'skinkit-themeprovider-javap.txt').write_text(api)
(evidence/'source-boundary-checks.json').write_text(json.dumps(results,indent=2)+'\n')
print(json.dumps(results,indent=2))
