"""System proxy control (WinINET or GSettings) with a crash-safe restore point."""

from __future__ import annotations

import ctypes
import json
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Optional

from .runner import app_data_dir

def _platform() -> str:
    return sys.platform


GSETTINGS_AVAILABLE = bool(shutil.which("gsettings"))
LINUX_IGNORE_HOSTS = (
    "['localhost', '127.0.0.0/8', '::1', '10.0.0.0/8', "
    "'172.16.0.0/12', '192.168.0.0/16']"
)

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


def _win_read() -> dict:
    if _platform() != "win32":
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


def _win_write(snapshot: dict) -> None:
    if _platform() != "win32":
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
    if _platform() != "win32":
        return
    try:
        wininet = ctypes.windll.Wininet
        wininet.InternetSetOptionW(0, INTERNET_OPTION_SETTINGS_CHANGED, 0, 0)
        wininet.InternetSetOptionW(0, INTERNET_OPTION_REFRESH, 0, 0)
    except (AttributeError, OSError):
        pass


LINUX_KEYS = {
    "mode": ("org.gnome.system.proxy", "mode"),
    "http_host": ("org.gnome.system.proxy.http", "host"),
    "http_port": ("org.gnome.system.proxy.http", "port"),
    "https_host": ("org.gnome.system.proxy.https", "host"),
    "https_port": ("org.gnome.system.proxy.https", "port"),
    "socks_host": ("org.gnome.system.proxy.socks", "host"),
    "socks_port": ("org.gnome.system.proxy.socks", "port"),
    "ignore": ("org.gnome.system.proxy", "ignore-hosts"),
}


def _gsettings(args: list[str]) -> Optional[str]:
    """Run gsettings and return stdout, or None when it cannot be used."""
    if not GSETTINGS_AVAILABLE:
        return None
    try:
        result = subprocess.run(
            ["gsettings", *args],
            capture_output=True,
            text=True,
            timeout=10,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if result.returncode != 0:
        return None
    return result.stdout.strip()


def _linux_read() -> Optional[dict]:
    """Snapshot the GNOME proxy settings; None when GSettings is unavailable."""
    values: dict[str, str] = {}
    for name, (schema, key) in LINUX_KEYS.items():
        value = _gsettings(["get", schema, key])
        if value is None:
            return None
        values[name] = value
    return values


def _linux_write(values: dict) -> bool:
    for name, value in values.items():
        schema, key = LINUX_KEYS[name]
        if _gsettings(["set", schema, key, str(value)]) is None:
            return False
    return True


def _read() -> Optional[dict]:
    return _win_read() if _platform() == "win32" else _linux_read()


def _write(snapshot: dict) -> bool:
    if _platform() == "win32":
        _win_write(snapshot)
        return True
    return _linux_write(snapshot)


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
    if _platform() != "win32" and "mode" not in snapshot:
        # A Windows-shaped snapshot left in a shared profile; nothing to do.
        path.unlink(missing_ok=True)
        return False
    if not _write(snapshot):
        return False
    path.unlink(missing_ok=True)
    return True


class SystemProxy:
    def __init__(self, port: int = 10809, data_dir: Optional[Path] = None):
        self.port = port
        self.data_dir = Path(data_dir or app_data_dir())
        self._snapshot: Optional[dict] = None

    def enable(self) -> bool:
        current = _read()
        if current is None:
            # No GSettings schema (e.g. a bare window manager): the tunnel is
            # still up on SOCKS/HTTP, the user configures their apps by hand.
            return False
        self._snapshot = current
        path = backup_path(self.data_dir)
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.is_file():
            path.write_text(json.dumps(current), encoding="utf-8")
        if _platform() == "win32":
            override = _bypass(current.get("override") or "")
            _write(
                {
                    "enable": True,
                    "server": f"127.0.0.1:{self.port}",
                    "override": override,
                }
            )
            return True
        host = "127.0.0.1"
        return _write(
            {
                "mode": "'manual'",
                "http_host": f"'{host}'",
                "http_port": str(self.port),
                "https_host": f"'{host}'",
                "https_port": str(self.port),
                "socks_host": f"'{host}'",
                "socks_port": str(max(1, self.port - 1)),
                "ignore": LINUX_IGNORE_HOSTS,
            }
        )

    def disable(self) -> None:
        path = backup_path(self.data_dir)
        if path.is_file():
            try:
                snapshot = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                snapshot = self._snapshot
            if snapshot:
                _write(snapshot)
            path.unlink(missing_ok=True)
            self._snapshot = None
            return
        if self._snapshot is not None:
            _write(self._snapshot)
            self._snapshot = None
            return
        if _platform() == "win32":
            current = _read()
            if current and current.get("enable"):
                _write({"enable": False, "server": current.get("server", ""), "override": current.get("override", "")})


def current_server() -> str:
    snapshot = _read()
    if not snapshot:
        return ""
    if _platform() == "win32":
        return snapshot.get("server", "") if snapshot.get("enable") else ""
    if snapshot.get("mode", "").strip("'") == "manual":
        return f"127.0.0.1:{snapshot.get('http_port', '').strip()}"
    return ""
