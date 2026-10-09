#!/usr/bin/env python3
"""M10-B app-only channel identity. Exact previous bytes/modes outside three tiny edits."""
from pathlib import Path
import json, os, subprocess
ROOT = Path(__file__).resolve().parents[1]
BASE = '4a8f02e7d515b17e478abdce2957fd9d3401ca34'
OUT = Path(os.environ.get('ESV_EVIDENCE_DIR', ROOT/'docs/m10b/evidence'))
OUT.mkdir(parents=True, exist_ok=True)
def git(*args): return subprocess.check_output(['git', *args], cwd=ROOT)
def require(ok, message):
    if not ok: raise RuntimeError(message)
variants = [f'app/src/{v}/kotlin/com/easysavevideo/app/entitlements/BuildEntitlements.kt' for v in ('debug','release')]
edits = {'app/build.gradle.kts', *variants}
new = {
 'app/src/main/kotlin/com/easysavevideo/app/distribution/DistributionConfiguration.kt',
 'app/src/test/kotlin/com/easysavevideo/app/distribution/DistributionConfigurationTest.kt',
 'scripts/check-distribution-channel-admission.py', 'scripts/verify-distribution-channel-workspace.sh',
}
changed = set(git('diff','--name-only',BASE).decode().splitlines()) | set(git('ls-files','--others','--exclude-standard').decode().splitlines())
for p in changed: require(p in edits|new or p.startswith('docs/m10b/'), 'Out-of-scope change: '+p)
checked=0
for row in git('ls-tree','-r',BASE).decode().splitlines():
    meta, path = row.split('\t'); mode, kind, oid = meta.split(); require(kind=='blob','Unexpected tree item')
    p=ROOT/path
    require(p.is_file(), 'Removed baseline file: '+path)
    require(bool(p.stat().st_mode&0o111)==(mode=='100755'), 'Changed executable mode: '+path)
    if path in edits: continue
    require(p.read_bytes()==git('cat-file','blob',oid), 'Protected bytes differ: '+path);checked+=1
build=(ROOT/'app/build.gradle.kts').read_text()
block='''        // Explicit build identity only. A channel does not select billing or authorize release.
        val distributionChannel = providers.gradleProperty("esv.distribution.channel").orElse("unconfigured").get()
        require(distributionChannel in setOf("unconfigured", "google_play", "huawei", "xiaomi", "oppo", "vivo", "tencent_myapp")) {
            "Unsupported ESV distribution channel; use an exact documented build value"
        }
        buildConfigField("String", "DISTRIBUTION_CHANNEL", "\\\"$distributionChannel\\\"")
'''
require(build.count(block)==1 and build.replace(block,'')==git('show',BASE+':app/build.gradle.kts').decode(), 'Build delta exceeded exact channel block')
for path in variants:
    expected=git('show',BASE+':'+path).decode().replace('import com.easysavevideo.core.model.*','import com.easysavevideo.core.model.*\nimport com.easysavevideo.app.distribution.BuildDistribution')
    expected=expected.replace('    val store: StorePurchaseBoundary = UnconfiguredStoreAdapter()', '    val distribution = BuildDistribution.configuration\n    val store: StorePurchaseBoundary = distribution.store')
    require((ROOT/path).read_text()==expected, 'Entitlement composition delta exceeded alias: '+path)
config=(ROOT/'app/src/main/kotlin/com/easysavevideo/app/distribution/DistributionConfiguration.kt').read_text()
require('val store: StorePurchaseBoundary = UnconfiguredStoreAdapter()' in config, 'Store must remain fixed/unconfigured')
require('?: UNCONFIGURED' in config and 'BuildConfig.DISTRIBUTION_CHANNEL' in config, 'Fail-closed build identity missing')
require(not any(x in config for x in (' var ', 'java.net', 'android.content', 'billing', 'Log.', 'println(', 'getInstaller', 'getenv(', 'EntitlementGrant(')), 'Distribution must have no mutable selection or store side effects')
for variant in ('debug','release'):
    classes=ROOT/f'app/build/intermediates/built_in_kotlinc/{variant}/compile{variant.title()}Kotlin/classes'
    require((classes/'com/easysavevideo/app/distribution/BuildDistribution.class').is_file(), 'Distribution variant did not compile')
    body=(classes/'com/easysavevideo/app/entitlements/BuildEntitlements.class').read_bytes()
    require(b'com/easysavevideo/app/distribution/BuildDistribution' in body, 'Distribution not composed in '+variant)
    inventory=(ROOT/f'app/build/reports/{variant}RuntimeClasspath-inventory.tsv').read_text().splitlines()
    admitted={row for row in (ROOT/'third_party/admitted-runtime.tsv').read_text().splitlines() if row and not row.startswith('#')}
    internal={f'com.easysavevideo:{module}:0.3.2-commons-dev' for module in ('core-model','core-domain','ui-theme','discovery','commons-provider','save','recording','library','hls')}
    require(len(set(inventory))==83 and {row.split('\t')[0] for row in inventory if row.split('\t')[0] in internal}==internal, 'Unexpected runtime inventory shape')
    actual={'\t'.join(row.split('\t')[:3]) for row in inventory if row.split('\t')[0] not in internal}
    require(len(actual)==74 and actual==admitted, 'Exact74 inventory mismatch')
report=dict(scope='M10B_DISTRIBUTION_IDENTITY_PREPARATION_ONLY',baseline=BASE,protected_files=checked,
    exact_existing_edits=sorted(edits),channel_identities=7,android_variants=['debug','release'],
    dependencies='EXACT74_UNCHANGED',payment='SAME_UNCONFIGURED_PORT_ALL_CHANNELS',
    core_and_skinkit='BYTE_UNCHANGED',vault='DISABLED_UNCHANGED',blocked_rule_candidate='ISOLATED_NOT_REVIEWED',
    account_sdk_backend_payment_choice='NOT_CONFIGURED',apk_aab_device_publication='NOT_RUN',store_acceptance='NOT_CLAIMED')
(OUT/'distribution-channel-admission.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
