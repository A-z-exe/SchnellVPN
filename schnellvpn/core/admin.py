"""Anonymous heartbeat and remote commands from the admin Worker.

The client generates a random install id once and sends only platform,
version and locale. There is no account or personal data. Every failure
means "nothing to do": an unreachable or broken Worker never blocks the app.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Optional

from . import notices, updater

ADMIN_URL = "https://schnellvpn-admin.a-z-exe.workers.dev"
MAX_BYTES = 512 * 1024
COMMAND_KINDS = ("notice", "force_update", "disconnect", "setting")


@dataclass(frozen=True)
class Command:
    id: int
    kind: str
    payload: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class Hello:
    notices: list[notices.Notice] = field(default_factory=list)
    commands: list[Command] = field(default_factory=list)


def _post(path: str, payload: dict[str, Any], timeout: float, version: str = ""):
    request = urllib.request.Request(
        ADMIN_URL + path,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        method="POST",
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "Accept": "application/json",
            "User-Agent": f"{notices.USER_AGENT}/{version}" if version else notices.USER_AGENT,
        },
    )
    return updater._open(request, timeout)


def hello(
    install_id: str,
    version: str,
    locale: str = "en",
    platform: Optional[str] = None,
    timeout: float = 8.0,
) -> Optional[Hello]:
    platform = platform or notices.platform_name()
    payload = {
        "install_id": install_id,
        "platform": platform,
        "version": version,
        "locale": locale,
    }
    try:
        with _post("/v1/hello", payload, timeout, version) as response:
            body = response.read(MAX_BYTES + 1)
    except (urllib.error.URLError, OSError, ValueError):
        return None
    if len(body) > MAX_BYTES:
        return None
    try:
        data = json.loads(body.decode("utf-8", "replace"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not isinstance(data, dict):
        return None

    commands: list[Command] = []
    for item in data.get("commands") or []:
        if not isinstance(item, dict):
            continue
        try:
            command_id = int(item.get("id"))
        except (TypeError, ValueError):
            continue
        kind = str(item.get("kind") or "")
        if kind not in COMMAND_KINDS:
            continue
        command_payload = item.get("payload")
        commands.append(
            Command(
                id=command_id,
                kind=kind,
                payload=command_payload if isinstance(command_payload, dict) else {},
            )
        )
    return Hello(notices=notices.parse(data, version, platform), commands=commands)


def ack(
    install_id: str,
    command_id: int,
    status: str = "done",
    timeout: float = 6.0,
) -> bool:
    try:
        with _post(
            "/v1/ack",
            {"install_id": install_id, "command_id": command_id, "status": status},
            timeout,
        ):
            return True
    except (urllib.error.URLError, OSError, ValueError):
        return False


def command_notice(command: Command, title: str, message: str) -> notices.Notice:
    """Turn a `notice`/`force_update` command into something the dialog shows."""
    payload = command.payload
    level = str(payload.get("level") or "info").lower()
    if level not in ("info", "warning", "critical"):
        level = "info"
    return notices.Notice(
        id=f"cmd-{command.id}",
        level="critical" if command.kind == "force_update" else level,
        title=notices.text(payload.get("title")) or {"en": title},
        message=notices.text(payload.get("message")) or {"en": message},
        update=bool(payload.get("update")) or command.kind == "force_update",
    )
