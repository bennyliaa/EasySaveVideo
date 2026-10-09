"""Windows-only owned-process containment primitive; not a Scrapling worker port.

Assign a suspended worker before it can execute; no breakaway permission. Handles,
not PIDs, authorize termination. Linux process guarantees remain untouched.
"""
import ctypes
from ctypes import wintypes as w
import os
import subprocess


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


class WindowsJob:
    def __init__(self):
        if os.name != 'nt':
            raise RuntimeError('WINDOWS_REQUIRED')
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
        limits.Basic.ActiveLimit = 24
        limits.Basic.ProcessTime = 12 * 10_000_000
        limits.JobMemory = 768 * 1024 * 1024
        if not self.api.SetInformationJobObject(self.handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
            self.api.CloseHandle(self.handle)
            self.handle = None
            raise RuntimeError('JOB_LIMITS_FAILED')

    def spawn_suspended_then_assign(self, arguments, cwd):
        if self.root is not None:
            raise RuntimeError('SINGLE_ROOT_REQUIRED')
        startup = StartupInfo(); startup.cb = ctypes.sizeof(startup)
        process = ProcessInfo()
        command = ctypes.create_unicode_buffer(subprocess.list2cmdline(arguments))
        if not self.api.CreateProcessW(arguments[0], command, None, None, False,
                                      0x4 | 0x08000000, None, str(cwd), ctypes.byref(startup), ctypes.byref(process)):
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
                if self.active_count() != 0:
                    raise RuntimeError('DESCENDANT_CLEANUP_UNPROVEN')
            self.api.CloseHandle(self.handle); self.handle = None
        if self.root:
            self.api.CloseHandle(self.root); self.root = None
