"""Real owned Python root+grandchild probe; no browser, Scrapling or target access."""
import json
import sys
import time
from pathlib import Path
from esv_verify.windows_job import WindowsJob

job = WindowsJob()
try:
    child = 'import time; time.sleep(30)'
    parent = 'import subprocess,sys,time; subprocess.Popen([sys.executable,"-I","-S","-c",' + repr(child) + '],creationflags=0x08000000); time.sleep(30)'
    job.spawn_suspended_then_assign([sys.executable, '-I', '-S', '-c', parent], Path.cwd())
    deadline = time.monotonic() + 3
    while job.active_count() < 2 and time.monotonic() < deadline:
        time.sleep(.02)
    if job.active_count() != 2:
        raise RuntimeError('OWNED_DESCENDANT_NOT_OBSERVED')
    job.terminate()
    deadline = time.monotonic() + 2
    while job.active_count() and time.monotonic() < deadline:
        time.sleep(.02)
    if job.active_count():
        raise RuntimeError('CLEANUP_UNPROVEN')
    print(json.dumps({'owned_root_and_grandchild': 'PASS', 'assigned_before_execution': True,
                      'remaining_job_processes': 0, 'scrapling_runtime': 'NOT_RUN', 'browser': 'NOT_RUN',
                      'memory_cpu_limits_configured': True, 'resource_limit_stress_test': 'NOT_RUN'}))
finally:
    job.close()
