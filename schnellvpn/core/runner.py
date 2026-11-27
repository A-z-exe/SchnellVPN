"""Lifecycle of the bundled xray.exe process."""

from __future__ import annotations

import ctypes
import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Iterable, Optional

from .netprobe import can_connect


class XrayError(RuntimeError):
    pass


def app_data_dir() -> Path:
    base = os.environ.get("SCHNELLVPN_DATA")
    if base:
        return Path(base)
    appdata = os.environ.get("APPDATA")
    if appdata:
        return Path(appdata) / "SchnellVPN"
    config = os.environ.get("XDG_CONFIG_HOME")
    if config:
        return Path(config) / "SchnellVPN"
    return Path.home() / ".config" / "SchnellVPN"


def xray_name() -> str:
    return "xray.exe" if sys.platform == "win32" else "xray"


def find_binary() -> Optional[Path]:
    env = os.environ.get("SCHNELLVPN_XRAY")
    if env and Path(env).is_file():
        return Path(env).resolve()
    name = xray_name()
    candidates: list[Path] = []
    if getattr(sys, "_MEIPASS", None):  # PyInstaller onefile
        candidates.append(Path(sys._MEIPASS) / "xray" / name)
    here = Path(__file__).resolve()
    for parent in here.parents:
        candidates.append(parent / "vendor" / "xray" / name)
        candidates.append(parent / "vendor" / "xray-linux" / name)
        candidates.append(parent / "windows" / "vendor" / "xray" / name)
        candidates.append(parent / "linux" / "vendor" / "xray-linux" / name)
    candidates.append(Path.cwd() / "vendor" / "xray" / name)
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    return None


def config_ports(config: dict) -> list[int]:
    """Every local port the core is told to listen on, api inbound included."""
    ports: list[int] = []
    for inbound in config.get("inbounds") or []:
        try:
            port = int(inbound.get("port") or 0)
        except (AttributeError, TypeError, ValueError):
            continue
        if 1 <= port <= 65535:
            ports.append(port)
    return ports


def looks_like_ours(image: str, binary: Optional[Path] = None) -> bool:
    """True for an xray binary this app started, installed or portable.

    A crashed or force-killed run (an in-app update kills the old exe) leaves
    xray.exe behind; it keeps 10808/10809 open, so the next core cannot bind.
    The app must then talk to the leftover, which still runs the old server.
    """
    if not image:
        return False
    path = Path(image)
    if path.name.lower() != xray_name().lower():
        return False
    text = str(path).replace("\\", "/").lower()
    if "schnellvpn" in text:
        return True
    if binary:
        try:
            if os.path.normcase(os.path.abspath(str(path))) == os.path.normcase(
                os.path.abspath(str(binary))
            ):
                return True
        except OSError:
            pass
    # Portable builds unpack xray to %TEMP%\_MEIxxxxxx\xray\xray.exe.
    parts = [part.lower() for part in path.parts]
    return len(parts) >= 3 and parts[-2] == "xray" and parts[-3].startswith("_mei")


def _win_kernel32():
    from ctypes import wintypes

    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    k32.OpenProcess.restype = ctypes.c_void_p
    k32.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
    k32.CloseHandle.restype = wintypes.BOOL
    k32.CloseHandle.argtypes = (ctypes.c_void_p,)
    k32.QueryFullProcessImageNameW.restype = wintypes.BOOL
    k32.QueryFullProcessImageNameW.argtypes = (
        ctypes.c_void_p,
        wintypes.DWORD,
        wintypes.LPWSTR,
        ctypes.POINTER(wintypes.DWORD),
    )
    k32.TerminateProcess.restype = wintypes.BOOL
    k32.TerminateProcess.argtypes = (ctypes.c_void_p, wintypes.UINT)
    return k32, ctypes, wintypes


def _windows_pids() -> list[int]:
    from ctypes import wintypes

    for dll in ("psapi", "kernel32"):
        try:
            enum = ctypes.WinDLL(dll, use_last_error=True).EnumProcesses
        except (OSError, AttributeError):
            continue
        enum.restype = wintypes.BOOL
        enum.argtypes = (
            ctypes.POINTER(wintypes.DWORD),
            wintypes.DWORD,
            ctypes.POINTER(wintypes.DWORD),
        )
        count = 2048
        while count <= 131072:
            buffer = (wintypes.DWORD * count)()
            needed = wintypes.DWORD()
            if not enum(buffer, ctypes.sizeof(buffer), ctypes.byref(needed)):
                break
            used = needed.value // ctypes.sizeof(wintypes.DWORD)
            if used < count:
                return [int(pid) for pid in buffer[:used] if pid]
            count *= 2
    return []


def _windows_image(pid: int) -> str:
    k32, ctypes_mod, wintypes = _win_kernel32()
    # PROCESS_QUERY_LIMITED_INFORMATION works without admin rights on Win7+.
    handle = k32.OpenProcess(0x1000, False, pid)
    if not handle:
        return ""
    try:
        size = wintypes.DWORD(32768)
        buffer = ctypes_mod.create_unicode_buffer(size.value)
        if not k32.QueryFullProcessImageNameW(
            handle, 0, buffer, ctypes_mod.byref(size)
        ):
            return ""
        return buffer.value
    finally:
        k32.CloseHandle(handle)


def _windows_terminate(pid: int) -> bool:
    k32, _ctypes, _wintypes = _win_kernel32()
    handle = k32.OpenProcess(0x0001, False, pid)  # PROCESS_TERMINATE
    if not handle:
        return False
    try:
        return bool(k32.TerminateProcess(handle, 1))
    finally:
        k32.CloseHandle(handle)


def _posix_pids() -> list[int]:
    proc = Path("/proc")
    if proc.is_dir():
        try:
            return [int(entry.name) for entry in proc.iterdir() if entry.name.isdigit()]
        except OSError:
            return []
    try:
        out = subprocess.run(
            ["ps", "-axo", "pid="], capture_output=True, text=True, timeout=5
        )
    except (OSError, subprocess.SubprocessError):
        return []
    return [int(value) for value in out.stdout.split() if value.isdigit()]


def _posix_image(pid: int) -> str:
    try:
        return os.readlink(f"/proc/{pid}/exe")
    except OSError:
        pass
    try:
        out = subprocess.run(
            ["ps", "-p", str(pid), "-o", "comm="],
            capture_output=True,
            text=True,
            timeout=5,
        )
    except (OSError, subprocess.SubprocessError):
        return ""
    return out.stdout.strip()


def _posix_terminate(pid: int) -> bool:
    try:
        os.kill(pid, signal.SIGTERM)
        return True
    except OSError:
        return False


def _all_pids() -> list[int]:
    return _windows_pids() if sys.platform == "win32" else _posix_pids()


def _image_of(pid: int) -> str:
    return _windows_image(pid) if sys.platform == "win32" else _posix_image(pid)


def _terminate(pid: int) -> bool:
    return _windows_terminate(pid) if sys.platform == "win32" else _posix_terminate(pid)


class _BasicLimit(ctypes.Structure):
    _fields_ = [
        ("PerProcessUserTimeLimit", ctypes.c_int64),
        ("PerJobUserTimeLimit", ctypes.c_int64),
        ("LimitFlags", ctypes.c_uint32),
        ("MinimumWorkingSetSize", ctypes.c_size_t),
        ("MaximumWorkingSetSize", ctypes.c_size_t),
        ("ActiveProcessLimit", ctypes.c_uint32),
        ("Affinity", ctypes.c_void_p),
        ("PriorityClass", ctypes.c_uint32),
        ("SchedulingClass", ctypes.c_uint32),
    ]


class _IoCounters(ctypes.Structure):
    _fields_ = [
        (name, ctypes.c_ulonglong)
        for name in (
            "ReadOperationCount",
            "WriteOperationCount",
            "OtherOperationCount",
            "ReadTransferCount",
            "WriteTransferCount",
            "OtherTransferCount",
        )
    ]


class _ExtendedLimit(ctypes.Structure):
    _fields_ = [
        ("BasicLimitInformation", _BasicLimit),
        ("IoInfo", _IoCounters),
        ("ProcessMemoryLimit", ctypes.c_size_t),
        ("JobMemoryLimit", ctypes.c_size_t),
        ("PeakProcessMemoryUsed", ctypes.c_size_t),
        ("PeakJobMemoryUsed", ctypes.c_size_t),
    ]


_JOB_HANDLE: Optional[int] = None
_JOB_LIMIT_KILL_ON_CLOSE = 0x2000
# Children this process started (main tunnel plus delay probes). They must
# never be mistaken for leftovers from an earlier run.
_OWNED_PIDS: set[int] = set()


def _job_handle() -> Optional[int]:
    """A job object that kills xray when this process dies, even on a kill -9."""
    global _JOB_HANDLE
    if sys.platform != "win32":
        return None
    if _JOB_HANDLE:
        return _JOB_HANDLE
    try:
        k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        k32.CreateJobObjectW.restype = ctypes.c_void_p
        k32.CreateJobObjectW.argtypes = (ctypes.c_void_p, ctypes.c_wchar_p)
        k32.SetInformationJobObject.restype = ctypes.c_int
        k32.SetInformationJobObject.argtypes = (
            ctypes.c_void_p,
            ctypes.c_int,
            ctypes.c_void_p,
            ctypes.c_uint32,
        )
        handle = k32.CreateJobObjectW(None, None)
        if not handle:
            return None
        info = _ExtendedLimit()
        info.BasicLimitInformation.LimitFlags = _JOB_LIMIT_KILL_ON_CLOSE
        if not k32.SetInformationJobObject(
            handle, 9, ctypes.byref(info), ctypes.sizeof(info)
        ):
            k32.CloseHandle(handle)
            return None
        _JOB_HANDLE = handle
        return handle
    except (AttributeError, OSError):
        return None


def _assign_job(process: subprocess.Popen[bytes]) -> None:
    job = _job_handle()
    if not job:
        return
    try:
        k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        k32.AssignProcessToJobObject.restype = ctypes.c_int
        k32.AssignProcessToJobObject.argtypes = (ctypes.c_void_p, ctypes.c_void_p)
        k32.AssignProcessToJobObject(job, ctypes.c_void_p(int(process._handle)))
    except (AttributeError, OSError, TypeError, ValueError):
        pass


def kill_stale_xray(
    binary: Optional[Path] = None, data_dir: Optional[Path] = None
) -> int:
    """Terminate xray processes a crashed or force-killed run left behind."""
    return XrayProcess(binary=binary, data_dir=data_dir).cleanup_stale()


class XrayProcess:
    def __init__(self, binary: Optional[Path] = None, data_dir: Optional[Path] = None):
        # xray runs with the binary's directory as cwd, so every path handed to
        # it must be absolute or it will not find the generated config.
        self.binary = Path(binary).resolve() if binary else find_binary()
        self.data_dir = (Path(data_dir) if data_dir else app_data_dir()).resolve()
        self.process: Optional[subprocess.Popen[bytes]] = None
        self.config_path = self.data_dir / "run" / "config.json"
        self.pid_path = self.data_dir / "run" / "xray.pid"
        self.log_path = self.data_dir / "logs" / "xray.log"
        self._log_handle = None

    @property
    def running(self) -> bool:
        return self.process is not None and self.process.poll() is None

    def stale_pids(self) -> list[int]:
        """PIDs of xray processes left over from an earlier SchnellVPN run."""
        mine = os.getpid()
        own = self.process.pid if self.process is not None else None
        found: list[int] = []
        for pid in _all_pids():
            if pid <= 4 or pid == mine or pid == own or pid in _OWNED_PIDS:
                continue
            if looks_like_ours(_image_of(pid), self.binary):
                found.append(pid)
        return found

    def cleanup_stale(self, ports: Iterable[int] = ()) -> int:
        """Kill leftover xray processes and wait for their ports to close."""
        killed = 0
        for pid in self.stale_pids():
            if _terminate(pid):
                killed += 1
        waiting = [int(port) for port in ports if port]
        if killed and waiting:
            deadline = time.monotonic() + 3.0
            while time.monotonic() < deadline:
                if not any(can_connect(port) for port in waiting):
                    break
                time.sleep(0.1)
        return killed

    def start(
        self,
        config: dict,
        wait_ports: Optional[Iterable[int]] = None,
        timeout: float = 10.0,
    ) -> None:
        if self.running:
            raise XrayError("xray is already running")
        if not self.binary or not self.binary.is_file():
            raise XrayError(
                f"{xray_name()} not found. Put it in vendor/xray/ or set SCHNELLVPN_XRAY."
            )
        ports = [
            int(port)
            for port in (wait_ports if wait_ports is not None else config_ports(config))
            if port
        ]
        busy = [port for port in ports if can_connect(port)]
        if busy:
            # A leftover core owns these ports. Checking "can I connect to the
            # port" after spawning is not proof of a working tunnel: the old
            # process answers, the new one dies with "bind: address in use", and
            # the app would report a healthy connection to the stale server.
            self.cleanup_stale(busy)
            busy = [port for port in ports if can_connect(port)]
        if busy:
            raise XrayError(
                f"local port {busy[0]} is already in use by another program; "
                "close it and connect again"
            )
        if sys.platform != "win32" and not os.access(str(self.binary), os.X_OK):
            try:
                self.binary.chmod(0o755)
            except OSError:
                pass
        self.config_path.parent.mkdir(parents=True, exist_ok=True)
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        self.config_path.write_text(
            json.dumps(config, indent=2, ensure_ascii=False), encoding="utf-8"
        )
        if sys.platform != "win32":
            # The generated config carries server UUIDs/passwords.
            for path, mode in ((self.config_path, 0o600), (self.config_path.parent, 0o700)):
                try:
                    path.chmod(mode)
                except OSError:
                    pass

        self._log_handle = open(self.log_path, "wb")
        creationflags = 0
        if sys.platform == "win32":
            creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        try:
            self.process = subprocess.Popen(
                [str(self.binary), "run", "-c", str(self.config_path)],
                cwd=str(self.binary.parent),
                stdin=subprocess.DEVNULL,
                stdout=self._log_handle,
                stderr=subprocess.STDOUT,
                creationflags=creationflags,
            )
        except OSError as exc:
            self._close_log()
            raise XrayError(f"could not start xray: {exc}") from exc

        # Tie the child's lifetime to this process. Without this, an in-app
        # update (which only kills the app) or a crash leaves xray.exe running,
        # holding 10808/10809 so the next start silently talks to the old core.
        _OWNED_PIDS.add(self.process.pid)
        _assign_job(self.process)

        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                detail = self.logs(4000).strip().splitlines()
                reason = detail[-1] if detail else "no output"
                self._cleanup_after_failure()
                raise XrayError(f"xray exited during startup: {reason}")
            if all(can_connect(port) for port in ports):
                # A listening port can belong to a core that died right after
                # binding; confirm ours is still the process behind it.
                time.sleep(0.05)
                if self.process.poll() is None:
                    return
                continue
            time.sleep(0.1)
        self.stop()
        raise XrayError("xray did not open its local ports in time")

    def stop(self) -> None:
        process, self.process = self.process, None
        if process is not None:
            _OWNED_PIDS.discard(process.pid)
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    pass
        self._close_log()

    def logs(self, max_bytes: int = 65536) -> str:
        try:
            data = self.log_path.read_bytes()
        except OSError:
            return ""
        return data[-max_bytes:].decode("utf-8", "replace")

    def _cleanup_after_failure(self) -> None:
        if self.process is not None:
            _OWNED_PIDS.discard(self.process.pid)
            try:
                self.process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                pass
        self.process = None
        self._close_log()

    def _close_log(self) -> None:
        if self._log_handle is not None:
            try:
                self._log_handle.close()
            except OSError:
                pass
            self._log_handle = None
