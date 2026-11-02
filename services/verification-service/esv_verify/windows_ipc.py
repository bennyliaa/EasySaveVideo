"""Bounded Windows anonymous-pipe IPC. No sockets, files, credentials or broad handle inheritance."""
import ctypes
from ctypes import wintypes as w
import threading
import time
from .windows_job import WindowsJob

class SecurityAttributes(ctypes.Structure):
    _fields_ = [('Length', w.DWORD), ('Descriptor', ctypes.c_void_p), ('Inherit', w.BOOL)]

class WindowsPipeRunner:
    def __init__(self, cpu_seconds=12, process_limit=24, memory_bytes=768*1024*1024):
        self._lock = threading.Lock()
        self.poisoned = False
        self._limits = (cpu_seconds, process_limit, memory_bytes)
        self.quarantined_job = None
        self.audit = {}

    def run(self, arguments, cwd, message, deadline, cancelled=lambda: False, max_output=32768, environment=None):
        if not isinstance(message, bytes) or len(message) > 32768 or not 1 <= max_output <= 32768:
            raise ValueError('IPC_LIMIT')
        if self.poisoned or not self._lock.acquire(blocking=False):
            return 'BUSY', b''
        job = None
        handles = []
        writer = None
        output = bytearray()
        status = 'ERROR'
        writer_done = threading.Event()
        writer_ok = []
        try:
            job = WindowsJob(*self._limits)
            api = job.api
            signatures = {
                'CreatePipe': ([ctypes.POINTER(w.HANDLE), ctypes.POINTER(w.HANDLE), ctypes.POINTER(SecurityAttributes), w.DWORD], w.BOOL),
                'SetHandleInformation': ([w.HANDLE, w.DWORD, w.DWORD], w.BOOL),
                'WriteFile': ([w.HANDLE, ctypes.c_void_p, w.DWORD, ctypes.POINTER(w.DWORD), ctypes.c_void_p], w.BOOL),
                'ReadFile': ([w.HANDLE, ctypes.c_void_p, w.DWORD, ctypes.POINTER(w.DWORD), ctypes.c_void_p], w.BOOL),
                'PeekNamedPipe': ([w.HANDLE, ctypes.c_void_p, w.DWORD, ctypes.c_void_p, ctypes.POINTER(w.DWORD), ctypes.c_void_p], w.BOOL),
                'GetExitCodeProcess': ([w.HANDLE, ctypes.POINTER(w.DWORD)], w.BOOL),
            }
            for name, (args, result) in signatures.items():
                function = getattr(api, name); function.argtypes, function.restype = args, result
            security = SecurityAttributes(ctypes.sizeof(SecurityAttributes), None, True)
            def pipe():
                read, write = w.HANDLE(), w.HANDLE()
                if not api.CreatePipe(ctypes.byref(read), ctypes.byref(write), ctypes.byref(security), 65536):
                    raise RuntimeError('PIPE_CREATE_FAILED')
                handles.extend([read.value, write.value])
                return read.value, write.value
            child_input, parent_input = pipe()
            parent_output, child_output = pipe()
            parent_error, child_error = pipe()
            for handle in (parent_input, parent_output, parent_error):
                if not api.SetHandleInformation(handle, 1, 0):
                    raise RuntimeError('PIPE_PARENT_INHERIT_FAILED')
            job.spawn_suspended_then_assign(arguments, cwd, (child_input, child_output, child_error), environment)
            for handle in (child_input, child_output, child_error):
                api.CloseHandle(handle); handles.remove(handle)
            def write_message():
                try:
                    buffer = ctypes.create_string_buffer(message)
                    written = w.DWORD()
                    writer_ok.append(bool(api.WriteFile(parent_input, buffer, len(message), ctypes.byref(written), None)) and written.value == len(message))
                finally:
                    api.CloseHandle(parent_input)
                    writer_done.set()
            writer = threading.Thread(target=write_message, daemon=True)
            handles.remove(parent_input)
            writer.start()
            stderr_count = 0
            while True:
                if cancelled():
                    status = 'CANCELLED'; break
                if time.monotonic() >= deadline:
                    status = 'TIMEOUT'; break
                for handle, is_error in ((parent_output, False), (parent_error, True)):
                    available = w.DWORD()
                    if not api.PeekNamedPipe(handle, None, 0, None, ctypes.byref(available), None):
                        if ctypes.get_last_error() != 109:
                            raise RuntimeError('PIPE_PEEK_FAILED')
                        continue
                    if available.value:
                        count = min(4096, available.value)
                        buffer = ctypes.create_string_buffer(count); read = w.DWORD()
                        if not api.ReadFile(handle, buffer, count, ctypes.byref(read), None):
                            raise RuntimeError('PIPE_READ_FAILED')
                        if is_error:
                            stderr_count += read.value
                        else:
                            output.extend(buffer.raw[:read.value])
                        if len(output) > max_output or stderr_count > 4096:
                            status = 'LIMIT'; break
                if status == 'LIMIT':
                    break
                if api.WaitForSingleObject(job.root, 0) == 0:
                    # Drain bounded output after root exit; descendants are killed below.
                    available = w.DWORD()
                    if api.PeekNamedPipe(parent_output, None, 0, None, ctypes.byref(available), None) and available.value:
                        continue
                    exit_code = w.DWORD()
                    exited_cleanly = api.GetExitCodeProcess(job.root, ctypes.byref(exit_code)) and exit_code.value == 0
                    self.audit = {'exit_code':exit_code.value,'stderr_bytes':stderr_count,'output_bytes':len(output),
                                  'writer_done':writer_done.is_set(),'writer_ok':writer_ok==[True]}
                    status = 'OK' if exited_cleanly and writer_done.is_set() and writer_ok == [True] and stderr_count == 0 else 'ERROR'
                    break
                time.sleep(.005)
        except Exception as failure:
            self.audit = {'technical_exception_class':type(failure).__name__}
            status = 'ERROR'
        finally:
            try:
                if job is not None:
                    job.close()
                if writer is not None:
                    writer.join(1)
                    if writer.is_alive():
                        raise RuntimeError('IPC_WRITER_CLEANUP_UNPROVEN')
            except Exception:
                self.poisoned = True
                self.quarantined_job = job
                status = 'CLEANUP_UNPROVEN'
            finally:
                if job is not None:
                    for handle in handles:
                        job.api.CloseHandle(handle)
                self._lock.release()
        return status, bytes(output) if status == 'OK' else b''
