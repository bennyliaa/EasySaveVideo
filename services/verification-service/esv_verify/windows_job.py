"""Windows-only owned-process containment primitive; not a Scrapling worker port.

Assign a suspended worker before it can execute; no breakaway permission. Handles,
not PIDs, authorize termination. Linux process guarantees remain untouched.
"""
import ctypes
from ctypes import wintypes as w
import os
import subprocess
import time


class BasicLimits(ctypes.Structure):
    _fields_ = [('ProcessTime', ctypes.c_int64), ('JobTime', ctypes.c_int64),
                ('Flags', w.DWORD), ('MinimumWorkingSet', ctypes.c_size_t),
                ('MaximumWorkingSet', ctypes.c_size_t), ('ActiveLimit', w.DWORD),
                ('Affinity', ctypes.c_size_t), ('Priority', w.DWORD), ('Scheduling', w.DWORD)]


class IoCounters(ctypes.Structure):
    _fields_ = [(name, ctypes.c_uint64) for name in
                ['ReadOperations', 'WriteOperations', 'OtherOperations', 'ReadBytes', 'WriteBytes', 'OtherBytes']]


class ExtendedLimits(ctypes.Structure):
    _fields_ = [('Basic', BasicLimits), ('Io', IoCounters),
                ('ProcessMemory', ctypes.c_size_t), ('JobMemory', ctypes.c_size_t),
                ('PeakProcessMemory', ctypes.c_size_t), ('PeakJobMemory', ctypes.c_size_t)]


class Accounting(ctypes.Structure):
    _fields_ = [(name, ctypes.c_int64) for name in ['UserTime', 'KernelTime', 'PeriodUser', 'PeriodKernel']] + [
        ('PageFaults', w.DWORD), ('TotalProcesses', w.DWORD), ('ActiveProcesses', w.DWORD), ('Terminated', w.DWORD)]


class StartupInfo(ctypes.Structure):
    _fields_ = [('cb', w.DWORD), ('Reserved', w.LPWSTR), ('Desktop', w.LPWSTR), ('Title', w.LPWSTR)] + [
        (name, w.DWORD) for name in ['X', 'Y', 'XSize', 'YSize', 'XChars', 'YChars', 'Fill', 'Flags']] + [
        ('ShowWindow', w.WORD), ('ReservedSize', w.WORD), ('ReservedBytes', ctypes.c_void_p),
        ('Stdin', w.HANDLE), ('Stdout', w.HANDLE), ('Stderr', w.HANDLE)]


class ProcessInfo(ctypes.Structure):
    _fields_ = [('Process', w.HANDLE), ('Thread', w.HANDLE), ('Pid', w.DWORD), ('Tid', w.DWORD)]

class StartupInfoEx(ctypes.Structure):
    _fields_ = [('Startup', StartupInfo), ('Attributes', ctypes.c_void_p)]


class WindowsJob:
    def __init__(self, cpu_seconds=12, process_limit=24, memory_bytes=768*1024*1024):
        if os.name != 'nt':
            raise RuntimeError('WINDOWS_REQUIRED')
        if (type(cpu_seconds) is not int or not 1<=cpu_seconds<=12 or
                type(process_limit) is not int or not 1<=process_limit<=24 or
                type(memory_bytes) is not int or not 16*1024*1024<=memory_bytes<=768*1024*1024):
            raise ValueError('JOB_LIMITS_CANNOT_BE_WEAKENED')
        self.api = ctypes.WinDLL('kernel32', use_last_error=True)
        signatures = {
            'CreateJobObjectW': ([ctypes.c_void_p, w.LPCWSTR], w.HANDLE),
            'SetInformationJobObject': ([w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD], w.BOOL),
            'QueryInformationJobObject': ([w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD, ctypes.c_void_p], w.BOOL),
            'AssignProcessToJobObject': ([w.HANDLE, w.HANDLE], w.BOOL),
            'TerminateJobObject': ([w.HANDLE, w.UINT], w.BOOL),
            'ResumeThread': ([w.HANDLE], w.DWORD),
            'TerminateProcess': ([w.HANDLE, w.UINT], w.BOOL),
            'WaitForSingleObject': ([w.HANDLE, w.DWORD], w.DWORD),
            'CloseHandle': ([w.HANDLE], w.BOOL),
            'CreateProcessW': ([w.LPCWSTR, w.LPWSTR, ctypes.c_void_p, ctypes.c_void_p, w.BOOL,
                                w.DWORD, ctypes.c_void_p, w.LPCWSTR, ctypes.POINTER(StartupInfo), ctypes.POINTER(ProcessInfo)], w.BOOL)
        }
        for name, (arguments, result) in signatures.items():
            function = getattr(self.api, name)
            function.argtypes, function.restype = arguments, result
        self.handle = self.api.CreateJobObjectW(None, None)
        self.root = None
        self.assigned = False
        if not self.handle:
            raise RuntimeError('JOB_CREATE_FAILED')
        limits = ExtendedLimits()
        limits.Basic.Flags = 0x2000 | 0x8 | 0x2 | 0x200  # kill-on-close, active, CPU, job memory
        limits.Basic.ActiveLimit = process_limit
        limits.Basic.ProcessTime = cpu_seconds * 10_000_000
        limits.JobMemory = memory_bytes
        if not self.api.SetInformationJobObject(self.handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
            self.api.CloseHandle(self.handle)
            self.handle = None
            raise RuntimeError('JOB_LIMITS_FAILED')

    def spawn_suspended_then_assign(self, arguments, cwd, std_handles=None, environment=None):
        if self.root is not None:
            raise RuntimeError('SINGLE_ROOT_REQUIRED')
        startup = StartupInfo(); startup.cb = ctypes.sizeof(startup)
        attributes = None
        extended = None
        flags = 0x4 | 0x08000000
        inherit = False
        startup_pointer = ctypes.byref(startup)
        if std_handles is not None:
            if len(std_handles) != 3:
                raise ValueError('THREE_STD_HANDLES_REQUIRED')
            for name, args in {
                'InitializeProcThreadAttributeList': [ctypes.c_void_p, w.DWORD, w.DWORD, ctypes.POINTER(ctypes.c_size_t)],
                'UpdateProcThreadAttribute': [ctypes.c_void_p, w.DWORD, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_void_p],
                'DeleteProcThreadAttributeList': [ctypes.c_void_p],
            }.items():
                function = getattr(self.api, name)
                function.argtypes = args
                function.restype = None if name.startswith('Delete') else w.BOOL
            size = ctypes.c_size_t()
            self.api.InitializeProcThreadAttributeList(None, 1, 0, ctypes.byref(size))
            attributes = ctypes.create_string_buffer(size.value)
            if not self.api.InitializeProcThreadAttributeList(attributes, 1, 0, ctypes.byref(size)):
                raise RuntimeError('ATTRIBUTE_INIT_FAILED')
            handles = (w.HANDLE * 3)(*std_handles)
            if not self.api.UpdateProcThreadAttribute(attributes, 0, 0x20002, handles,
                                                      ctypes.sizeof(handles), None, None):
                self.api.DeleteProcThreadAttributeList(attributes)
                raise RuntimeError('HANDLE_ALLOWLIST_FAILED')
            extended = StartupInfoEx()
            extended.Startup.cb = ctypes.sizeof(extended)
            extended.Startup.Flags = 0x100
            extended.Startup.Stdin, extended.Startup.Stdout, extended.Startup.Stderr = std_handles
            extended.Attributes = ctypes.cast(attributes, ctypes.c_void_p)
            startup_pointer = ctypes.cast(ctypes.byref(extended), ctypes.POINTER(StartupInfo))
            flags |= 0x80000
            inherit = True
        process = ProcessInfo()
        command = ctypes.create_unicode_buffer(subprocess.list2cmdline(arguments))
        environment_block = None
        if environment is not None:
            if any(not isinstance(k,str) or not isinstance(v,str) or '\x00' in k+v or '=' in k for k,v in environment.items()):
                raise ValueError('ENVIRONMENT_INVALID')
            environment_block = ctypes.create_unicode_buffer('\x00'.join(k+'='+v for k,v in sorted(environment.items(),key=lambda item:item[0].casefold()))+'\x00\x00')
            flags |= 0x400
        try:
            created = self.api.CreateProcessW(arguments[0], command, None, None, inherit,
                                              flags, environment_block, str(cwd), startup_pointer, ctypes.byref(process))
        finally:
            if attributes is not None:
                self.api.DeleteProcThreadAttributeList(attributes)
        if not created:
            raise RuntimeError('PROCESS_CREATE_FAILED')
        self.root = process.Process
        try:
            if not self.api.AssignProcessToJobObject(self.handle, process.Process):
                # Only this newly created, still-suspended process handle is terminated.
                self.api.TerminateProcess(process.Process, 70)
                self.api.WaitForSingleObject(process.Process, 1000)
                raise RuntimeError('JOB_ASSIGN_FAILED_NO_WORKER_EXECUTION')
            self.assigned = True
            if self.api.ResumeThread(process.Thread) == 0xFFFFFFFF:
                raise RuntimeError('PROCESS_RESUME_FAILED')
        finally:
            self.api.CloseHandle(process.Thread)

    def active_count(self):
        data = Accounting()
        if not self.api.QueryInformationJobObject(self.handle, 1, ctypes.byref(data), ctypes.sizeof(data), None):
            raise RuntimeError('JOB_QUERY_FAILED')
        return data.ActiveProcesses

    def terminate(self):
        if not self.api.TerminateJobObject(self.handle, 70):
            raise RuntimeError('JOB_TERMINATE_FAILED')

    def close(self):
        if self.root and not self.assigned:
            if self.api.WaitForSingleObject(self.root, 0) != 0:
                if not self.api.TerminateProcess(self.root, 70):
                    raise RuntimeError('SUSPENDED_ROOT_TERMINATION_UNPROVEN')
                if self.api.WaitForSingleObject(self.root, 1000) != 0:
                    raise RuntimeError('SUSPENDED_ROOT_CLEANUP_UNPROVEN')
        if self.handle:
            if self.assigned:
                self.terminate()
                if self.root and self.api.WaitForSingleObject(self.root, 1000) != 0:
                    raise RuntimeError('ROOT_CLEANUP_UNPROVEN')
                # Accounting can retain a terminated root while our process handle stays open.
                if self.root:
                    self.api.CloseHandle(self.root); self.root = None
                deadline = time.monotonic() + 1
                while self.active_count() != 0 and time.monotonic() < deadline:
                    time.sleep(.005)
                if self.active_count() != 0:
                    raise RuntimeError('DESCENDANT_CLEANUP_UNPROVEN')
            self.api.CloseHandle(self.handle); self.handle = None
        if self.root:
            self.api.CloseHandle(self.root); self.root = None
