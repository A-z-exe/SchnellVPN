"""Lifecycle of the bundled xray.exe process."""

from __future__ import annotations

import json
import os
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
    return Path.home() / ".schnellvpn"


def find_binary() -> Optional[Path]:
    env = os.environ.get("SCHNELLVPN_XRAY")
    if env and Path(env).is_file():
        return Path(env).resolve()
    candidates: list[Path] = []
    if getattr(sys, "_MEIPASS", None):  # PyInstaller onefile
        candidates.append(Path(sys._MEIPASS) / "xray" / "xray.exe")
    here = Path(__file__).resolve()
    for parent in here.parents:
        candidates.append(parent / "vendor" / "xray" / "xray.exe")
        candidates.append(parent / "windows" / "vendor" / "xray" / "xray.exe")
    candidates.append(Path.cwd() / "vendor" / "xray" / "xray.exe")
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    return None


class XrayProcess:
    def __init__(self, binary: Optional[Path] = None, data_dir: Optional[Path] = None):
        # xray runs with the binary's directory as cwd, so every path handed to
        # it must be absolute or it will not find the generated config.
        self.binary = Path(binary).resolve() if binary else find_binary()
        self.data_dir = (Path(data_dir) if data_dir else app_data_dir()).resolve()
        self.process: Optional[subprocess.Popen[bytes]] = None
        self.config_path = self.data_dir / "run" / "config.json"
        self.log_path = self.data_dir / "logs" / "xray.log"
        self._log_handle = None

    @property
    def running(self) -> bool:
        return self.process is not None and self.process.poll() is None

    def start(
        self,
        config: dict,
        wait_ports: Iterable[int] = (),
        timeout: float = 10.0,
    ) -> None:
        if self.running:
            raise XrayError("xray is already running")
        if not self.binary or not self.binary.is_file():
            raise XrayError(
                "xray.exe not found. Put it in vendor/xray/ or set SCHNELLVPN_XRAY."
            )
        self.config_path.parent.mkdir(parents=True, exist_ok=True)
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        self.config_path.write_text(
            json.dumps(config, indent=2, ensure_ascii=False), encoding="utf-8"
        )

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

        ports = [port for port in wait_ports if port]
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                detail = self.logs(4000).strip().splitlines()
                reason = detail[-1] if detail else "no output"
                self._cleanup_after_failure()
                raise XrayError(f"xray exited during startup: {reason}")
            if all(can_connect(port) for port in ports):
                return
            time.sleep(0.1)
        self.stop()
        raise XrayError("xray did not open its local ports in time")

    def stop(self) -> None:
        process, self.process = self.process, None
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
