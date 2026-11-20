"""In-app updates from GitHub Releases (check, verify, self-replace)."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from .runner import app_data_dir

RELEASES_URL = "https://api.github.com/repos/A-z-exe/SchnellVPN/releases?per_page=30"
RELEASE_PAGE = "https://github.com/A-z-exe/SchnellVPN/releases"
MAX_BYTES = 300 * 1024 * 1024
USER_AGENT = "SchnellVPN-Windows"


@dataclass
class UpdateInfo:
    version: str
    tag: str
    url: str
    sha256: Optional[str]
    notes: str = ""
    size: int = 0


def version_parts(text: str) -> tuple[int, ...]:
    match = re.match(r"^[vV]?(\d+(?:\.\d+)*)", (text or "").strip())
    if not match:
        return ()
    return tuple(int(part) for part in match.group(1).split("."))


def is_newer(remote: str, current: str) -> bool:
    remote_parts = version_parts(remote)
    current_parts = version_parts(current)
    if not remote_parts:
        return False
    for index in range(max(len(remote_parts), len(current_parts))):
        left = remote_parts[index] if index < len(remote_parts) else 0
        right = current_parts[index] if index < len(current_parts) else 0
        if left != right:
            return left > right
    return False


def legacy_build() -> bool:
    """Windows 7 builds ship PySide2 (Qt 5), so PySide6 is absent."""
    try:
        return importlib.util.find_spec("PySide6") is None
    except (ImportError, ValueError):
        return False


def _asset_kind(name: str) -> Optional[str]:
    lower = name.lower()
    if lower.endswith(".exe"):
        return "win7" if "win7" in lower else "win"
    if lower.endswith(".appimage"):
        return "linux"
    return None


def _platform() -> str:
    return sys.platform


def asset_for_build(release: dict) -> Optional[dict]:
    """Pick our own build: Linux AppImage, Win7 exe or the x64 exe."""
    if _platform().startswith("linux"):
        wanted = "linux"
    else:
        wanted = "win7" if legacy_build() else "win"
    for asset in release.get("assets") or []:
        if _asset_kind(str(asset.get("name") or "")) == wanted:
            return asset
    return None


def pick_release(releases: list[dict], current: str) -> Optional[UpdateInfo]:
    for release in releases:
        if release.get("draft"):
            continue
        asset = asset_for_build(release)
        if asset is None:
            continue
        tag = str(release.get("tag_name") or "")
        if not is_newer(tag, current):
            continue
        digest = str(asset.get("digest") or "")
        if digest.lower().startswith("sha256:"):
            digest = digest[7:]
        return UpdateInfo(
            version=".".join(str(part) for part in version_parts(tag)),
            tag=tag,
            url=str(asset.get("browser_download_url") or ""),
            sha256=digest or None,
            notes=str(release.get("body") or ""),
            size=int(asset.get("size") or 0),
        )
    return None


def _short_reason(exc: BaseException) -> str:
    text = str(getattr(exc, "reason", exc))
    if "timed out" in text or "timeout" in text:
        return "timed out"
    if "getaddrinfo" in text:
        return "DNS lookup failed"
    return text.splitlines()[0]


def _open(request: urllib.request.Request, timeout: float):
    """Open a URL through the system proxy first, then directly.

    GitHub can be slow or unreachable on a direct connection here; when the
    VPN client has set the system proxy the update should use that tunnel.
    """
    error: Optional[Exception] = None
    for opener in (
        urllib.request.build_opener(),
        urllib.request.build_opener(urllib.request.ProxyHandler({})),
    ):
        try:
            return opener.open(request, timeout=timeout)
        except (urllib.error.URLError, OSError) as exc:
            error = exc
    raise error  # type: ignore[misc]


def check(current_version: str, timeout: float = 12.0) -> Optional[UpdateInfo]:
    """Newest Windows release newer than `current_version`, else None."""
    request = urllib.request.Request(
        RELEASES_URL,
        headers={
            "User-Agent": f"{USER_AGENT}/{current_version}",
            "Accept": "application/vnd.github+json",
        },
    )
    try:
        with _open(request, timeout) as response:
            payload = response.read(4 * 1024 * 1024)
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"GitHub API: HTTP {exc.code}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"GitHub unreachable: {_short_reason(exc)}") from exc
    try:
        releases = json.loads(payload.decode("utf-8", "replace"))
    except json.JSONDecodeError as exc:
        raise RuntimeError("bad release list") from exc
    if not isinstance(releases, list):
        raise RuntimeError("bad release list")
    info = pick_release(releases, current_version)
    if info is not None and not info.url:
        return None
    return info


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(info: UpdateInfo, target_dir: Optional[Path] = None) -> Path:
    """Fetch the update to a .part file, verify it, then publish it.

    The final name only appears once the file is complete and verified, so an
    installer helper can never pick up a half-written exe.
    """
    target_dir = Path(target_dir or app_data_dir() / "updates")
    target_dir.mkdir(parents=True, exist_ok=True)
    target = target_dir / f"SchnellVPN-{info.version}.exe"
    part = target.with_name(target.name + ".part")
    request = urllib.request.Request(
        info.url, headers={"User-Agent": f"{USER_AGENT}/{info.version}"}
    )
    error: Optional[Exception] = None
    for attempt in range(3):
        if attempt:
            time.sleep(2)
        part.unlink(missing_ok=True)
        try:
            _fetch(request, part)
            _verify(info, part)
        except urllib.error.HTTPError as exc:
            part.unlink(missing_ok=True)
            raise RuntimeError(f"HTTP {exc.code}") from exc
        except RuntimeError as exc:
            part.unlink(missing_ok=True)
            if str(exc) == "update file is too large":
                raise
            error = exc
            continue
        except (urllib.error.URLError, OSError) as exc:
            part.unlink(missing_ok=True)
            error = exc
            continue
        part.replace(target)
        try:
            # A "mark of the web" makes antivirus scan the fresh exe on first start,
            # which can race PyInstaller's temp extraction ("python312.dll missing").
            os.remove(str(target) + ":Zone.Identifier")
        except OSError:
            pass
        return target
    raise RuntimeError(f"network error: {_short_reason(error)}")


def _fetch(request: urllib.request.Request, part: Path) -> None:
    with _open(request, 30) as response, part.open("wb") as out:
        done = 0
        while True:
            chunk = response.read(256 * 1024)
            if not chunk:
                break
            done += len(chunk)
            if done > MAX_BYTES:
                raise RuntimeError("update file is too large")
            out.write(chunk)


def _verify(info: UpdateInfo, part: Path) -> None:
    size = part.stat().st_size
    if info.size and size != info.size:
        raise RuntimeError(f"incomplete download ({size} of {info.size} bytes)")
    if info.sha256 and sha256_of(part).lower() != info.sha256.lower():
        raise RuntimeError("checksum mismatch")
    with part.open("rb") as handle:
        if handle.read(2) != b"MZ":
            raise RuntimeError("update is not a Windows program")


def can_self_install() -> bool:
    return sys.platform == "win32" and bool(getattr(sys, "frozen", False))


def boot_marker(version: str) -> Path:
    """File the freshly started build touches once Qt is up."""
    return Path(tempfile.gettempdir()) / f"schnellvpn-boot-{version or 'any'}.ok"


def install(
    update_path: Path,
    current_exe: Optional[Path] = None,
    version: str = "",
) -> None:
    """Swap the running exe from a helper script and restart it.

    The helper renames the running exe away first, waits for the new build's
    boot marker, and restores the previous exe if the new one never comes up.
    `ping` is the only delay it uses: unlike `timeout`, it never allocates a
    console, which is what made older helpers flash windows while retrying.
    """
    target = Path(current_exe or sys.executable).resolve()
    update_path = Path(update_path).resolve()
    if not update_path.is_file():
        raise RuntimeError("downloaded update is missing")

    backup = target.with_name(target.name + ".old")
    marker = boot_marker(version)
    lock = Path(tempfile.gettempdir()) / "schnellvpn-update.lock"
    if lock.exists() and time.time() - lock.stat().st_mtime > 600:
        lock.unlink(missing_ok=True)
    log_path = app_data_dir() / "updates" / "install.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    script = Path(tempfile.gettempdir()) / f"schnellvpn-update-{os.getpid()}.cmd"

    def q(path: Path) -> str:
        return str(path).replace('"', '""')

    script.write_text(
        "@echo off\r\n"
        "setlocal\r\n"
        'set "own="\r\n'
        f'if exist "{q(lock)}" (\r\n'
        f'  echo [%date% %time%] another installer is running >> "{q(log_path)}"\r\n'
        "  goto end\r\n"
        ")\r\n"
        f'echo running > "{q(lock)}"\r\n'
        'set "own=1"\r\n'
        f'echo [%date% %time%] installing {version} >> "{q(log_path)}"\r\n'
        f'if exist "{q(marker)}" del "{q(marker)}" >nul 2>&1\r\n'
        f'if exist "{q(backup)}" del "{q(backup)}" >nul 2>&1\r\n'
        "for /L %%i in (1,1,180) do (\r\n"
        f'  ren "{q(target)}" "{target.name}.old" >nul 2>&1 && goto swapped\r\n'
        "  ping -n 2 127.0.0.1 >nul\r\n"
        ")\r\n"
        f'echo old exe stayed locked >> "{q(log_path)}"\r\n'
        "goto end\r\n"
        ":swapped\r\n"
        f'if not exist "{q(update_path)}" goto missing\r\n'
        "for /L %%i in (1,1,10) do (\r\n"
        f'  move /Y "{q(update_path)}" "{q(target)}" >nul 2>&1 && goto boots\r\n'
        "  ping -n 2 127.0.0.1 >nul\r\n"
        ")\r\n"
        "goto restore\r\n"
        ":missing\r\n"
        f'echo downloaded update file is missing >> "{q(log_path)}"\r\n'
        "goto restore\r\n"
        ":boots\r\n"
        "ping -n 3 127.0.0.1 >nul\r\n"
        "for /L %%i in (1,1,3) do (\r\n"
        f'  start "" "{q(target)}"\r\n'
        "  for /L %%j in (1,1,20) do (\r\n"
        f'    if exist "{q(marker)}" goto ok\r\n'
        "    ping -n 2 127.0.0.1 >nul\r\n"
        "  )\r\n"
        f'  echo launch attempt %%i did not reach the app >> "{q(log_path)}"\r\n'
        ")\r\n"
        ":restore\r\n"
        f'echo new build failed to start; restoring backup >> "{q(log_path)}"\r\n'
        f'taskkill /F /IM "{target.name}" >nul 2>&1\r\n'
        "ping -n 3 127.0.0.1 >nul\r\n"
        f'if exist "{q(target)}" del "{q(target)}" >nul 2>&1\r\n'
        "for /L %%i in (1,1,10) do (\r\n"
        f'  if not exist "{q(backup)}" goto restored\r\n'
        f'  ren "{q(backup)}" "{target.name}" >nul 2>&1\r\n'
        f'  if exist "{q(target)}" goto restored\r\n'
        "  ping -n 2 127.0.0.1 >nul\r\n"
        ")\r\n"
        ":restored\r\n"
        f'if exist "{q(target)}" start "" "{q(target)}"\r\n'
        "goto end\r\n"
        ":ok\r\n"
        "for /L %%i in (1,1,5) do (\r\n"
        f'  if not exist "{q(backup)}" goto cleaned\r\n'
        f'  del "{q(backup)}" >nul 2>&1\r\n'
        "  ping -n 2 127.0.0.1 >nul\r\n"
        ")\r\n"
        ":cleaned\r\n"
        f'echo [%date% %time%] update ok >> "{q(log_path)}"\r\n'
        ":end\r\n"
        f'if defined own if exist "{q(lock)}" del "{q(lock)}" >nul 2>&1\r\n'
        'del "%~f0" >nul 2>&1\r\n',
        encoding="ascii",
    )
    flags = 0
    if sys.platform == "win32":
        flags = (
            getattr(subprocess, "DETACHED_PROCESS", 0)
            | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
            | getattr(subprocess, "CREATE_NO_WINDOW", 0)
        )
    subprocess.Popen(
        ["cmd", "/c", str(script)],
        cwd=str(target.parent),
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        creationflags=flags,
        close_fds=True,
    )
