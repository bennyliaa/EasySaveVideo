#!/usr/bin/env python3
"""Current bookmark scope admission, independent of behavioral acceptance."""
import json
from scope_support import checked_output, verify_scope

scope = verify_scope()
report = {'scope': 'M1_B1_ORDINARY_BROWSER_BOOKMARKS_ONLY', **scope,
          'historical_scripts_tests_fixtures_evidence': 'BYTE_AND_GIT_MODE_UNCHANGED',
          'manifest_dependencies_notices_core_modules': 'BYTE_AND_GIT_MODE_UNCHANGED',
          'runtime_artifacts': 'EXACT74_SEPARATELY_GATED_BY_STRICT_GRADLE',
          'vault': 'DISABLED_UNCHANGED', 'blocked_rule_candidate': 'NOT_RETRIED_OR_SUBSTITUTED',
          'device_acceptance': 'NOT_RUN', 'apk_aab_assembly': 'NOT_RUN',
          'overall_browser_completion': False}
(checked_output() / 'bookmark-admission.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
