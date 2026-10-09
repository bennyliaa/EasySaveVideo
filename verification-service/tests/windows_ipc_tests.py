"""Only owned isolated stdlib workers; no browser, runtime or external targets."""
import json, pathlib, sys, threading, time, unittest
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
from esv_verify.windows_ipc import WindowsPipeRunner

class WindowsIpcTests(unittest.TestCase):
    def run_worker(self, code, message=b'fixture', budget=1, cancelled=lambda:False, limit=32768):
        runner=WindowsPipeRunner()
        result=runner.run([sys.executable,'-I','-S','-c',code],pathlib.Path.cwd(),message,time.monotonic()+budget,cancelled,limit)
        self.assertFalse(runner.poisoned)
        return result
    def test_echo(self):
        self.assertEqual(('OK',b'fixture'),self.run_worker('import sys; sys.stdout.buffer.write(sys.stdin.buffer.read())'))
    def test_overflow_abstains(self):
        self.assertEqual(('LIMIT',b''),self.run_worker('import sys; sys.stdout.buffer.write(b"x"*20000)',limit=128))
    def test_stderr_abstains(self):
        self.assertEqual(('ERROR',b''),self.run_worker('import sys; sys.stderr.write("owned diagnostic")'))
    def test_timeout_descendants(self):
        code='import subprocess,sys,time; subprocess.Popen([sys.executable,"-I","-S","-c","import time;time.sleep(30)"]);time.sleep(30)'
        self.assertEqual(('TIMEOUT',b''),self.run_worker(code,budget=.2))
    def test_cancel(self):
        self.assertEqual(('CANCELLED',b''),self.run_worker('import time;time.sleep(30)',cancelled=lambda:True))
    def test_large_unread_input_timeout(self):
        self.assertEqual(('TIMEOUT',b''),self.run_worker('import time;time.sleep(30)',message=b'x'*32768,budget=.15))
    def test_nonzero_exit_abstains(self):
        self.assertEqual(('ERROR',b''),self.run_worker('import sys;sys.stdout.write("fixture");sys.exit(3)'))
    def test_explicit_environment_excludes_ambient(self):
        import os
        runner=WindowsPipeRunner()
        result=runner.run([sys.executable,'-I','-S','-c','import os,sys;sys.stdout.write(os.environ.get("ESV_OWNED_SENTINEL","absent"))'],pathlib.Path.cwd(),b'',time.monotonic()+1,environment={'SystemRoot':os.environ['SystemRoot']})
        self.assertEqual(('OK',b'absent'),result);self.assertFalse(runner.poisoned)
    def test_invalid_limits_no_process(self):
        with self.assertRaises(ValueError):
            WindowsPipeRunner().run([],'.',b'x'*32769,time.monotonic()+1)

if __name__=='__main__':unittest.main()
