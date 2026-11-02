"""Windows constructor-only fixture backend; no production source admission."""
import os,sys,tempfile,threading,time
from pathlib import Path
from .protocol import InvalidRequest, strict_json, encode_result
from .windows_ipc import WindowsPipeRunner

class WindowsVerificationService:
    def __init__(self,capability,max_workers=2):
        if type(max_workers) is not int or not 1<=max_workers<=4:raise ValueError('WORKER_LIMIT')
        self.capability=capability
        self.runners=[WindowsPipeRunner() for _ in range(max_workers)]
        self.lock=threading.Lock();self.active=set();self.closed=threading.Event()
        self.refused=False
    def close(self):self.closed.set()
    def verify(self,request,cancelled=lambda:False,accepted_at=None):
        from .service import encode_envelope
        deadline=(time.monotonic() if accepted_at is None else accepted_at)+request.budgetMs/1000
        def aborted():return cancelled() or self.closed.is_set()
        if aborted():return request.result('CANCELLED')
        if time.monotonic()>=deadline:return request.result('TIMEOUT')
        if self.capability.rule_for(request.candidateUrl) is None:return request.result('UNSUPPORTED')
        identity=(request.requestId,request.generation)
        with self.lock:
            if any(r.poisoned for r in self.runners):return request.result('BUSY')
            if self.refused:return request.result('REJECTED')
            runner=next((r for r in self.runners if not r.poisoned and r not in self.active),None)
            if identity in self.active or runner is None:return request.result('BUSY')
            self.active.update((runner,identity))
        try:
            envelope=encode_envelope(request,self.capability,deadline)
            # Scratch has no secrets/profile/state. No inherited proxy/netrc/credentials or user HOME.
            with tempfile.TemporaryDirectory(prefix='esv-windows-fixture-') as scratch:
                system=os.environ.get('SystemRoot',r'C:\Windows')
                env={'SystemRoot':system,'WINDIR':system,'PATH':str(Path(system)/'System32'),
                     'HOME':scratch,'USERPROFILE':scratch,'TEMP':scratch,'TMP':scratch,
                     'NO_PROXY':'127.0.0.1','PYTHONDONTWRITEBYTECODE':'1'}
                code='import sys;sys.path.insert(0,'+repr(str(Path(__file__).resolve().parent.parent))+');from esv_verify.windows_worker import main;main()'
                status,output=runner.run([sys.executable,'-I','-B','-c',code],scratch,envelope,deadline,aborted,environment=env)
                if aborted():return request.result('CANCELLED')
                if time.monotonic()>=deadline:return request.result('TIMEOUT')
                if status!='OK':return request.result({'BUSY':'BUSY','TIMEOUT':'TIMEOUT','CANCELLED':'CANCELLED'}.get(status,'UNAVAILABLE'))
                result=strict_json(output);encode_result(request,result)
                if result['outcome']=='REJECTED':
                    with self.lock:self.refused=True
                return result
        except Exception:return request.result('UNAVAILABLE')
        finally:
            with self.lock:self.active.difference_update((runner,identity))
