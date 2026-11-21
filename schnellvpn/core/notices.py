"""Update notices published as a static JSON file on GitHub Pages.

The file is optional infrastructure: any network or format error simply means
"no notices" (fail-open), so a broken page can never keep the app from
starting or from working offline.
"""

from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Optional

from . import updater

NOTICES_URL = "https://a-z-exe.github.io/SchnellVPN/notices.json"
USER_AGENT = "SchnellVPN-Notice"
MAX_BYTES = 256 * 1024


@dataclass(frozen=True)
class Notice:
    id: str
    level: str
    title: dict[str, str]
    message: dict[str, str]
    update: bool


def platform_name() -> str:
    if sys.platform == "win32":
        return "windows"
    if sys.platform.startswith("linux"):
        return "linux"
    return sys.platform


def _text(value: Any) -> dict[str, str]:
    """Accept {"fa": "...", ...} or a single string (treated as English)."""
    if isinstance(value, str):
        return {"en": value} if value.strip() else {}
    if not isinstance(value, dict):
        return {}
    return {
        str(key): str(text)
        for key, text in value.items()
        if isinstance(text, str) and text.strip()
    }


def _at_most(current: str, limit: str) -> bool:
    current_parts = updater.version_parts(current)
    limit_parts = updater.version_parts(limit)
    if not current_parts or not limit_parts:
        return False
    for index in range(max(len(current_parts), len(limit_parts))):
        left = current_parts[index] if index < len(current_parts) else 0
        right = limit_parts[index] if index < len(limit_parts) else 0
        if left != right:
            return left < right
    return True


def _matches_platform(value: Any, platform: str) -> bool:
    if value is None:
        return True
    if isinstance(value, str):
        value = [value]
    if not isinstance(value, list):
        return False
    return platform in {str(item) for item in value}


def parse(payload: Any, version: str, platform: str) -> list[Notice]:
    if not isinstance(payload, dict):
        return []
    items = payload.get("notices")
    if not isinstance(items, list):
        return []
    result: list[Notice] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        notice_id = str(item.get("id") or "").strip()
        title = _text(item.get("title"))
        message = _text(item.get("message"))
        if not notice_id or not title or not message:
            continue
        if not _matches_platform(item.get("platforms"), platform):
            continue
        max_version = item.get("max_version")
        if isinstance(max_version, str) and max_version.strip():
            if not _at_most(version, max_version):
                continue
        min_version = item.get("min_version")
        if isinstance(min_version, str) and min_version.strip():
            if updater.is_newer(min_version, version):
                continue
        level = str(item.get("level") or "info").lower()
        if level not in ("info", "warning", "critical"):
            level = "info"
        result.append(
            Notice(
                id=notice_id,
                level=level,
                title=title,
                message=message,
                update=bool(item.get("update")),
            )
        )
    return result


def localized(text: dict[str, str], lang: str) -> str:
    for key in (lang, "en", "fa"):
        if text.get(key):
            return text[key]
    return next(iter(text.values()), "")


def fetch(
    version: str,
    platform: Optional[str] = None,
    timeout: float = 8.0,
) -> list[Notice]:
    """Notices for this build; an unreachable or bad page means none."""
    request = urllib.request.Request(
        NOTICES_URL,
        headers={"User-Agent": f"{USER_AGENT}/{version}", "Accept": "application/json"},
    )
    try:
        with updater._open(request, timeout) as response:
            payload = response.read(MAX_BYTES + 1)
    except (urllib.error.URLError, OSError, ValueError):
        return []
    if len(payload) > MAX_BYTES:
        return []
    try:
        data = json.loads(payload.decode("utf-8", "replace"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return []
    return parse(data, version, platform or platform_name())