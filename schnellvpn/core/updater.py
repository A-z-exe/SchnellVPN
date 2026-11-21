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


HELPER_LOCK_MINUTES = 10


def _ps(text: object) -> str:
    """Quote a value for PowerShell with single quotes."""
    return "'" + str(text).replace("'", "''") + "'"


def _helper_script(
    target: Path,
    update_path: Path,
    backup: Path,
    marker: Path,
    lock: Path,
    log_path: Path,
    version: str,
) -> str:
    runtime_dir = r"%LOCALAPPDATA%\SchnellVPN\runtime"
    return (
        "$ErrorActionPreference = 'SilentlyContinue'\n"
        f"$Target = {_ps(target)}\n"
        f"$Update = {_ps(update_path)}\n"
        f"$Backup = {_ps(backup)}\n"
        f"$Marker = {_ps(marker)}\n"
        f"$Lock = {_ps(lock)}\n"
        f"$Log = {_ps(log_path)}\n"
        f"$Version = {_ps(version)}\n"
        f"$Leaf = {_ps(target.name)}\n"
        f"$Runtime = {_ps(runtime_dir)}\n"
        "$Runtime = [Environment]::ExpandEnvironmentStrings($Runtime)\n"
        "\n"
        "function Log([string]$line) {\n"
        "  Add-Content -Path $Log -Value ((Get-Date).ToString('yyyy-MM-dd HH:mm:ss') + ' ' + $line)\n"
        "}\n"
        "\n"
        "function Restore-Backup {\n"
        "  if (-not (Test-Path $Backup)) { Log 'restore skipped: no backup'; return }\n"
        "  if (Test-Path $Target) {\n"
        "    Remove-Item $Target -Force\n"
        "    if (Test-Path $Target) { Log 'restore skipped: new exe is in use'; return }\n"
        "  }\n"
        "  for ($i = 0; $i -lt 20; $i++) {\n"
        "    try { Rename-Item -Path $Backup -NewName $Leaf -ErrorAction Stop; break } catch { Start-Sleep -Seconds 1 }\n"
        "  }\n"
        "  if (Test-Path $Target) { Log 'backup restored'; Start-Process -FilePath $Target }\n"
        "  else { Log 'restore failed: target missing' }\n"
        "}\n"
        "\n"
        "if (Test-Path $Lock) {\n"
        "  $age = ((Get-Date) - (Get-Item $Lock).LastWriteTime).TotalMinutes\n"
        f"  if ($age -lt {HELPER_LOCK_MINUTES}) {{ Log 'another installer is running'; exit 0 }}\n"
        "  Remove-Item $Lock -Force\n"
        "}\n"
        "New-Item -ItemType File -Path $Lock -Force | Out-Null\n"
        "Log ('installing ' + $Version)\n"
        "if (Test-Path $Marker) { Remove-Item $Marker -Force }\n"
        "if (Test-Path $Backup) { Remove-Item $Backup -Force }\n"
        "\n"
        "$swapped = $false\n"
        "for ($i = 0; $i -lt 180; $i++) {\n"
        "  try { Rename-Item -Path $Target -NewName ($Leaf + '.old') -ErrorAction Stop; $swapped = $true; break } catch { Start-Sleep -Seconds 1 }\n"
        "}\n"
        "$placed = $false\n"
        "if ($swapped -and (Test-Path $Update)) {\n"
        "  for ($i = 0; $i -lt 10; $i++) {\n"
        "    try { Move-Item -Path $Update -Destination $Target -Force -ErrorAction Stop; $placed = $true; break } catch { Start-Sleep -Seconds 2 }\n"
        "  }\n"
        "}\n"
        "if (-not $swapped) { Log 'old exe stayed locked' }\n"
        "elseif (-not $placed) { Log 'downloaded update could not be moved into place' }\n"
        "\n"
        "$ok = $false\n"
        "if ($placed) {\n"
        "  # Let the antivirus finish scanning the fresh exe before it runs.\n"
        "  Start-Sleep -Seconds 6\n"
        "  if (Test-Path $Runtime) {\n"
        "    Get-ChildItem -Path $Runtime -Filter '_MEI*' | Where-Object { $_.PSIsContainer -and $_.LastWriteTime -lt (Get-Date).AddDays(-1) } | Remove-Item -Recurse -Force\n"
        "  }\n"
        "  for ($attempt = 1; $attempt -le 2; $attempt++) {\n"
        "    if ($attempt -gt 1) { Start-Sleep -Seconds 12 }\n"
        "    if (Test-Path $Marker) { Remove-Item $Marker -Force }\n"
        "    Log ('launch attempt ' + $attempt)\n"
        "    $proc = Start-Process -FilePath $Target -PassThru\n"
        "    for ($j = 0; $j -lt 75; $j++) {\n"
        "      if (Test-Path $Marker) { $ok = $true; break }\n"
        "      if ($proc -ne $null -and $proc.HasExited) {\n"
        "        $code = 'unknown'\n"
        "        try { $code = $proc.ExitCode } catch { }\n"
        "        Log ('new build exited early with code ' + $code)\n"
        "        break\n"
        "      }\n"
        "      Start-Sleep -Seconds 1\n"
        "    }\n"
        "    if ($ok) { break }\n"
        "    Log ('launch attempt ' + $attempt + ' did not reach the app')\n"
        "    if ($proc -ne $null -and -not $proc.HasExited) { & taskkill.exe /PID $proc.Id /T /F | Out-Null }\n"
        "    Start-Sleep -Seconds 2\n"
        "  }\n"
        "}\n"
        "\n"
        "if ($ok) {\n"
        "  Log 'update ok'\n"
        "  for ($i = 0; $i -lt 5; $i++) {\n"
        "    if (-not (Test-Path $Backup)) { break }\n"
        "    Remove-Item $Backup -Force\n"
        "    Start-Sleep -Seconds 1\n"
        "  }\n"
        "} else {\n"
        "  Log 'new build failed to start; rolling back'\n"
        "  Restore-Backup\n"
        "}\n"
        "Remove-Item $Lock -Force\n"
        "Remove-Item -Path $MyInvocation.MyCommand.Path -Force\n"
    )


def install(
    update_path: Path,
    current_exe: Optional[Path] = None,
    version: str = "",
) -> None:
    """Swap the running exe from a hidden PowerShell helper and restart it.

    The helper renames the running exe away, moves the download in, launches it
    once (twice only if the first try never reaches the boot marker), and rolls
    back to the previous exe otherwise. It runs windowless, tracks the PID it
    started and kills only that process tree, so a failed launch leaves no
    stray dialogs, and it waits a few seconds before starting the new build so
    antivirus scanning of the fresh exe finishes first.
    """
    target = Path(current_exe or sys.executable).resolve()
    update_path = Path(update_path).resolve()
    if not update_path.is_file():
        raise RuntimeError("downloaded update is missing")

    backup = target.with_name(target.name + ".old")
    marker = boot_marker(version)
    lock = Path(tempfile.gettempdir()) / "schnellvpn-update.lock"
    if lock.exists() and time.time() - lock.stat().st_mtime > HELPER_LOCK_MINUTES * 60:
        lock.unlink(missing_ok=True)
    log_path = app_data_dir() / "updates" / "install.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    script = Path(tempfile.gettempdir()) / f"schnellvpn-update-{os.getpid()}.ps1"
    script.write_text(
        _helper_script(target, update_path, backup, marker, lock, log_path, version),
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
        [
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-WindowStyle",
            "Hidden",
            "-File",
            str(script),
        ],
        cwd=str(target.parent),
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        creationflags=flags,
        close_fds=True,
    )
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        if lock.exists():
            return
        time.sleep(0.25)
    raise RuntimeError("the update helper did not start")
