import pathlib,sys,ctypes,time,unittest
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
from esv_verify.windows_job import WindowsJob,Accounting
from esv_verify.windows_ipc import WindowsPipeRunner
BASE_PYTHON=getattr(sys,"_base_executable",sys.executable)

class WindowsLimitsTests(unittest.TestCase):
    def test_cpu_limit_kills_owned_root(self):
        job=WindowsJob(cpu_seconds=1)
        try:
            job.spawn_suspended_then_assign([BASE_PYTHON,'-I','-S','-c','while True: pass'],pathlib.Path.cwd())
            self.assertEqual(0,job.api.WaitForSingleObject(job.root,15000))
            data=Accounting();self.assertTrue(job.api.QueryInformationJobObject(job.handle,1,ctypes.byref(data),ctypes.sizeof(data),None))
            self.assertGreater(data.UserTime+data.KernelTime,5_000_000)
            # Physical root termination is proven by its handle. Accounting may retain this open handle.
            self.assertLessEqual(data.ActiveProcesses,1)
        finally:job.close()
    def test_aggregate_commit_limit_blocks_large_owned_allocation(self):
        runner=WindowsPipeRunner()
        code='try:\n b=bytearray(900*1024*1024)\n print("UNBOUNDED")\nexcept MemoryError:\n print("LIMITED")'
        status,output=runner.run([BASE_PYTHON,'-I','-S','-c',code],pathlib.Path.cwd(),b'',time.monotonic()+3)
        self.assertEqual(('OK',b'LIMITED\r\n'),(status,output));self.assertFalse(runner.poisoned)
    def test_active_process_limit_prevents_additional_execution(self):
        runner=WindowsPipeRunner(process_limit=3)
        code='import subprocess,sys\nchildren=[]\nfor i in range(10):\n try: children.append(subprocess.Popen([sys.executable,"-I","-S","-c","import time;time.sleep(20)"],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL))\n except OSError: pass\nprint(len(children))'
        status,output=runner.run([BASE_PYTHON,'-I','-S','-c',code],pathlib.Path.cwd(),b'',time.monotonic()+3)
        self.assertEqual(('OK',b'2\r\n'),(status,output),runner.audit);self.assertFalse(runner.poisoned)
        # Windows includes failed associations in ActiveProcesses until their handles close.
        # This proves only root + two executable children, not an instantaneous accounting ceiling.
    def test_limits_cannot_be_relaxed(self):
        for kwargs in ({'cpu_seconds':13},{'process_limit':25},{'memory_bytes':769*1024*1024}):
            with self.assertRaises(ValueError):WindowsJob(**kwargs)

if __name__=='__main__':unittest.main()
