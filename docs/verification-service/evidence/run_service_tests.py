#!/usr/bin/env python3
"""Run isolated service checks with an explicit result record; skip is not acceptance."""
from pathlib import Path
import importlib.metadata
import json
import os
import sys
import unittest
ROOT = Path(__file__).resolve().parents[3]
OUT = Path(os.environ['ESV_EVIDENCE_DIR']).resolve()
sys.path.insert(0, str(ROOT/'verification-service'))
# Clean subprocess/CLI tests need the same explicit local module root. Worker isolation
# still creates its own minimal environment and does not inherit this value.
os.environ['PYTHONPATH'] = str(ROOT/'verification-service')
os.environ['ESV_KOTLIN_FIXTURE_CLASSPATH'] = (ROOT/'verification-client/build/fixture-classpath.txt').read_text().strip()
assert os.environ['ESV_KOTLIN_FIXTURE_CLASSPATH'], 'MISSING_COMPILED_KOTLIN_FIXTURE'
suite = unittest.defaultTestLoader.discover(str(ROOT/'verification-service/tests'))
result = unittest.TextTestRunner(verbosity=2).run(suite)
report = dict(scope='OWNED_ISOLATED_FIXTURES_ONLY', tests=result.testsRun, failures=len(result.failures),
              errors=len(result.errors), skipped=len(result.skipped),
              scrapling_version=importlib.metadata.version('scrapling'),
              public_pages='NOT_RUN', production_deployment='NOT_RUN', runtime_distribution='NOT_APPROVED')
(OUT/'service-summary.json').write_text(json.dumps(report,indent=2)+'\n')
expected=json.loads((ROOT/'docs/verification-service/evidence/frozen-inputs.json').read_text())['expected_service_tests']
assert result.wasSuccessful() and not result.skipped and result.testsRun==expected and report['scrapling_version']=='0.4.15', report
