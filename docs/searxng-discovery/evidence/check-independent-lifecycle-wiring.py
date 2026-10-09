#!/usr/bin/env python3
"""Narrow source wiring assertions. These supplement host tests; they are not Android execution."""
from pathlib import Path
import hashlib
import json
import sys

root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[3]
paths = {
    'app': 'app/src/main/kotlin/com/easysavevideo/app/discovery/AndroidDiscoveryController.kt',
    'owner': 'app/src/main/kotlin/com/easysavevideo/app/discovery/AndroidSearxngConfigurationOwner.kt',
    'hub': 'app/src/main/kotlin/com/easysavevideo/app/discovery/SearxngConfigurationHub.kt',
    'controller': 'app/src/main/kotlin/com/easysavevideo/app/discovery/SearxngConfigurationController.kt',
    'store': 'app/src/main/kotlin/com/easysavevideo/app/storage/SearxngConfigurationStore.kt',
}
src = {key: (root / value).read_text() for key, value in paths.items()}
checks = []
def check(name, condition):
    if not condition:
        raise AssertionError(name)
    checks.append(name)

app, owner, hub, controller, store = (src[key] for key in ('app', 'owner', 'hub', 'controller', 'store'))
check('One process-owned hub using applicationContext', 'object AndroidSearxngConfigurationOwner' in owner and '@Volatile private var hub:' in owner and 'synchronized(this)' in owner and 'AndroidSearxngConfigurationStore(context.applicationContext)' in owner)
check('Activity uses shared owner controller', 'private val sourceOwner = AndroidSearxngConfigurationOwner.get(appContext)' in app and 'val sourceSettings = sourceOwner.controller' in app)
check('Activity does not construct its own configuration worker', 'SearxngConfigurationController(' not in app and 'AndroidSearxngConfigurationStore(' not in app)
check('Subscription is installed after UI delivery and initialized fields', app.index('sourceOwner.subscribe(::fenceSourceConfiguration, ::loadedSourceConfiguration)') > app.index('private val uiDelivery ='))
close = app[app.index('override fun close()'):]
check('Activity detaches without shutting down shared configuration owner', 'sourceSubscription?.close(); sourceSubscription = null' in close and 'sourceSettings.close()' not in close and 'sourceOwner.controller.close()' not in close)
loaded = app[app.index('private fun loadedSourceConfiguration'):app.index('private fun applySourceConfiguration')]
check('Busy/not-loaded owner snapshots cannot initialize sources', 'value.busy || !value.loaded' in loaded)
initialize = app[app.index('private fun initialize'):app.index('private fun fenceSourceConfiguration')]
check('Shared owner claim is checked before source construction', 'if (!configurationCurrent(claim))' in initialize and initialize.index('if (!configurationCurrent(claim))') < initialize.index('BuildDiscovery.registry('))
check('Advanced source intent forces explicit-search restore', 'appliedSourceIntent != expectedIntent' in initialize and 'requireExplicitSearch = recoveringUnavailable' in initialize)
check('Accepted write publishes pending latch before invoking subscriber fences', hub.index('writePending = true') < hub.index('currentFence(subscriber)?.invoke()'))
check('Replacement subscriber fences before receiving pending owner snapshot', 'if (writePending) fence()' in hub and hub.index('if (writePending) fence()') < hub.index('onState(current)'))
check('UI claim invalidation is separate from operation epoch', 'private var claimEpoch' in controller and 'fun invalidateClaims() { claimEpoch++ }' in controller and 'operation != epoch' in controller)
check('Existing cross-instance store lock retained', 'companion object { private val processLock = Any() }' in store and store.count('synchronized(processLock)') >= 2)
apply = app[app.index('private fun applySourceConfiguration'):app.index('fun candidateUrl')]
check('Callback captures source intent before queued application', apply.index('val claim = DiscoverySourceInitializationClaim(sourceIntent.get(), value?.generation)') < apply.index('submit {') and 'if (!configurationCurrent(claim)) return@submit' in apply)
check('Registry uses captured configuration rather than newer mutable state', 'BuildDiscovery.registry(demo, commonsEnabled == true, expectedConfiguration)' in initialize)
check('New coordinator reference is published before post-install source recheck', initialize.index('coordinator = VideoDiscoveryCoordinator') < initialize.index('if (!configurationCurrent(claim)) { c.suspendSourcesImmediately(); return }'))
check('Applied intent comes only from captured initialization intent', 'appliedSourceIntent = expectedIntent' in initialize and 'appliedSourceIntent = sourceIntent.get()' not in initialize)
check('Source claim is rechecked again before lifecycle dispatch', initialize.count('if (!configurationCurrent(claim)) { c.suspendSourcesImmediately(); return }') >= 2 and initialize.rindex('if (!configurationCurrent(claim))') < initialize.index('applyLifecycle(c)'))
guarded = app[app.index('private fun guarded'):app.index('override fun close()')]
check('Storage failure uses irreversible source fence', 'coordinator?.suspendSourcesImmediately()' in guarded)
print(json.dumps({'scope': 'source wiring only; no native Android/device/UI acceptance', 'checks': checks, 'source_sha256': {paths[k]: hashlib.sha256((root / paths[k]).read_bytes()).hexdigest() for k in paths}}, indent=2))
