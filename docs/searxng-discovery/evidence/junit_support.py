"""Exact module/result accounting, including the documented core-model NO-SOURCE task."""
import xml.etree.ElementTree as ET

MODULES = ('core-model', 'core-domain', 'ui-theme', 'discovery', 'commons-provider',
           'searxng-provider', 'save', 'recording', 'library', 'hls', 'app')
FIELDS = ('tests', 'failures', 'errors', 'skipped')


def summarize_junit(root, aggregate_log, expected):
    counts = {}
    total = dict.fromkeys(FIELDS, 0)
    lines = aggregate_log.read_text().splitlines()
    for module in MODULES:
        count = dict.fromkeys(FIELDS, 0)
        folder = root / module / 'build/test-results' / ('testDebugUnitTest' if module == 'app' else 'test')
        results = sorted(folder.glob('TEST-*.xml'))
        if module == 'core-model':
            # The immutable baseline has main models only, no test sources;
            # both archived baseline evidence and the current task must say zero.
            assert not any(p.is_file() for p in (root / module / 'src/test').rglob('*')), 'CORE_MODEL_UNEXPECTED_TEST_SOURCE'
            assert not results, 'CORE_MODEL_UNEXPECTED_JUNIT_RESULTS'
            assert '> Task :core-model:test NO-SOURCE' in lines, 'CORE_MODEL_NO_SOURCE_TASK_REQUIRED'
        else:
            assert results, ('MISSING_JUNIT_MODULE_RESULTS', module)
        for path in results:
            node = ET.parse(path).getroot()
            assert node.tag == 'testsuite', ('UNEXPECTED_JUNIT_ROOT', str(path))
            for key in count:
                value = int(node.attrib.get(key, 0))
                assert value >= 0, ('NEGATIVE_JUNIT_COUNT', module, key)
                count[key] += value
        if module != 'core-model':
            assert count['tests'] > 0, ('EMPTY_JUNIT_MODULE_RESULTS', module)
        counts[module] = count
        for key in count:
            total[key] += count[key]
    assert total['tests'] == expected and all(total[key] == 0 for key in ('failures', 'errors', 'skipped')), {'expected': expected, 'actual': total}
    counts['total'] = total
    return counts
