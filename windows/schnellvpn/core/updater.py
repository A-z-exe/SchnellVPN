"""In-app updates from GitHub Releases (check, verify, self-replace)."""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
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


def exe_asset(release: dict) -> Optional[dict]:
    for asset in release.get("assets") or []:
        name = str(asset.get("name") or "")
        if name.lower().endswith(".exe"):
            return asset
    return None


def pick_release(releases: list[dict], current: str) -> Optional[UpdateInfo]:
    for release in releases:
        if release.get("draft"):
            continue
        asset = exe_asset(release)
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
        )
    return None


def check(current_version: str, timeout: float = 12.0) -> Optional[UpdateInfo]:
    """Newest Windows release newer than `current_version`, else None."""
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    request = urllib.request.Request(
        RELEASES_URL,
        headers={
            "User-Agent": f"{USER_AGENT}/{current_version}",
            "Accept": "application/vnd.github+json",
        },
    )
    try:
        with opener.open(request, timeout=timeout) as response:
            payload = response.read(4 * 1024 * 1024)
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"GitHub API: HTTP {exc.code}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"network error: {exc.reason}") from exc
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
    target_dir = Path(target_dir or app_data_dir() / "updates")
    target_dir.mkdir(parents=True, exist_ok=True)
    target = target_dir / f"SchnellVPN-{info.version}.exe"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    request = urllib.request.Request(
        info.url, headers={"User-Agent": f"{USER_AGENT}/{info.version}"}
    )
    try:
        with opener.open(request, timeout=30) as response, target.open("wb") as out:
            done = 0
            while True:
                chunk = response.read(256 * 1024)
                if not chunk:
                    break
                done += len(chunk)
                if done > MAX_BYTES:
                    raise RuntimeError("update file is too large")
                out.write(chunk)
    except urllib.error.HTTPError as exc:
        target.unlink(missing_ok=True)
        raise RuntimeError(f"HTTP {exc.code}") from exc
    except urllib.error.URLError as exc:
        target.unlink(missing_ok=True)
        raise RuntimeError(f"network error: {exc.reason}") from exc

    if info.sha256 and sha256_of(target).lower() != info.sha256.lower():
        target.unlink(missing_ok=True)
        raise RuntimeError("checksum mismatch")
    return target


def can_self_install() -> bool:
    return sys.platform == "win32" and bool(getattr(sys, "frozen", False))


def install(update_path: Path, current_exe: Optional[Path] = None) -> None:
    """Spawn a helper that swaps the running exe once this process exits."""
    target = Path(current_exe or sys.executable).resolve()
    update_path = Path(update_path).resolve()
    if not update_path.is_file():
        raise RuntimeError("downloaded update is missing")

    script = Path(tempfile.gettempdir()) / f"schnellvpn-update-{os.getpid()}.cmd"
    script.write_text(
        "@echo off\r\n"
        "setlocal\r\n"
        "for /L %%i in (1,1,120) do (\r\n"
        f'  move /Y "{update_path}" "{target}" >nul 2>&1 && goto run\r\n'
        "  timeout /t 1 /nobreak >nul\r\n"
        ")\r\n"
        "goto end\r\n"
        ":run\r\n"
        f'start "" "{target}"\r\n'
        ":end\r\n"
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
