"""WinINET system proxy control with a crash-safe restore point."""

from __future__ import annotations

import ctypes
import json
import sys
from pathlib import Path
from typing import Optional

from .runner import app_data_dir

REG_PATH = r"Software\Microsoft\Windows\CurrentVersion\Internet Settings"
INTERNET_OPTION_REFRESH = 37
INTERNET_OPTION_SETTINGS_CHANGED = 39
# Windows' own default list; an empty ProxyOverride makes every app (browsers
# included) send even localhost and LAN traffic into the tunnel.
DEFAULT_BYPASS = (
    "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;"
    "172.21.*;172.22.*;172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;"
    "172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;<local>"
)


def _bypass(value: str) -> str:
    items = [item.strip() for item in (value or "").split(";") if item.strip()]
    if not items:
        return DEFAULT_BYPASS
    if "<local>" not in items:
        items.append("<local>")
    if "localhost" not in items and "127.*" not in items:
        items.append("localhost")
    return ";".join(items)


def _winreg():  # imported lazily so non-Windows tests can import this module
    import winreg

    return winreg


def _read() -> dict:
    if sys.platform != "win32":
        return {"enable": False, "server": "", "override": ""}
    winreg = _winreg()
    with winreg.OpenKey(
        winreg.HKEY_CURRENT_USER, REG_PATH, 0, winreg.KEY_READ
    ) as key:
        def value(name: str):
            try:
                return winreg.QueryValueEx(key, name)[0]
            except FileNotFoundError:
                return None

        return {
            "enable": bool(value("ProxyEnable") or 0),
            "server": value("ProxyServer") or "",
            "override": value("ProxyOverride") or "",
        }


def _write(snapshot: dict) -> None:
    if sys.platform != "win32":
        return
    winreg = _winreg()
    with winreg.OpenKey(
        winreg.HKEY_CURRENT_USER, REG_PATH, 0, winreg.KEY_SET_VALUE
    ) as key:
        winreg.SetValueEx(key, "ProxyEnable", 0, winreg.REG_DWORD, 1 if snapshot["enable"] else 0)
        winreg.SetValueEx(key, "ProxyServer", 0, winreg.REG_SZ, snapshot.get("server", ""))
        winreg.SetValueEx(key, "ProxyOverride", 0, winreg.REG_SZ, snapshot.get("override", ""))
    _notify()


def _notify() -> None:
    if sys.platform != "win32":
        return
    try:
        wininet = ctypes.windll.Wininet
        wininet.InternetSetOptionW(0, INTERNET_OPTION_SETTINGS_CHANGED, 0, 0)
        wininet.InternetSetOptionW(0, INTERNET_OPTION_REFRESH, 0, 0)
    except (AttributeError, OSError):
        pass


def backup_path(data_dir: Optional[Path] = None) -> Path:
    return Path(data_dir or app_data_dir()) / "run" / "proxy_backup.json"


def restore_stale(data_dir: Optional[Path] = None) -> bool:
    """Undo a proxy left behind by a crash. Returns True if something was restored."""
    path = backup_path(data_dir)
    if not path.is_file():
        return False
    try:
        snapshot = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        path.unlink(missing_ok=True)
        return False
    _write(snapshot)
    path.unlink(missing_ok=True)
    return True


class SystemProxy:
    def __init__(self, port: int = 10809, data_dir: Optional[Path] = None):
        self.port = port
        self.data_dir = Path(data_dir or app_data_dir())
        self._snapshot: Optional[dict] = None

    def enable(self) -> None:
        current = _read()
        self._snapshot = current
        path = backup_path(self.data_dir)
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.is_file():
            path.write_text(json.dumps(current), encoding="utf-8")
        override = _bypass(current.get("override") or "")
        _write(
            {
                "enable": True,
                "server": f"127.0.0.1:{self.port}",
                "override": override,
            }
        )

    def disable(self) -> None:
        path = backup_path(self.data_dir)
        if path.is_file():
            try:
                snapshot = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                snapshot = self._snapshot or {"enable": False, "server": "", "override": ""}
            _write(snapshot)
            path.unlink(missing_ok=True)
            self._snapshot = None
            return
        if self._snapshot is not None:
            _write(self._snapshot)
            self._snapshot = None
            return
        current = _read()
        if current.get("enable"):
            _write({"enable": False, "server": current.get("server", ""), "override": current.get("override", "")})


def current_server() -> str:
    snapshot = _read()
    return snapshot.get("server", "") if snapshot.get("enable") else ""
