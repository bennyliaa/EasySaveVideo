#!/usr/bin/env python3
"""Scope gate only; independent behavioral, build and runtime admission remain required."""
import json
import sys
sys.dont_write_bytecode = True
from scope_support import checked_output, verify_scope

report = {'scope': 'FIRST_LAYER_SEARXNG_HOST_SOURCE_DEVELOPMENT_ONLY', **verify_scope(),
    'runtime_artifacts': 'EXACT74_SEPARATELY_GATED_BY_STRICT_GRADLE',
    'device_acceptance': 'NOT_RUN', 'live_endpoint_acceptance': 'NOT_RUN',
    'apk_aab_assembly': 'NOT_RUN', 'M0_acceptance': False, 'two_layer_discovery_acceptance': False,
    'blocked_candidates': 'NOT_EXECUTED_REVIEWED_RETRIED_OR_SUBSTITUTED'}
(checked_output() / 'searxng-admission.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
