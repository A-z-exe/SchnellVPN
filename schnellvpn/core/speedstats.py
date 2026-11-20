"""Live throughput from Xray's StatsService (bytes per second)."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Optional

from .runner import find_binary

UPLINK_SUFFIX = ">>>traffic>>>uplink"
DOWNLINK_SUFFIX = ">>>traffic>>>downlink"


def parse_totals(text: str) -> tuple[int, int]:
    """Sum inbound counters, ignoring the api inbound itself."""
    down = up = 0
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return 0, 0
    for entry in data.get("stat") or []:
        name = str(entry.get("name") or "")
        if not name.startswith("inbound>>>") or name.startswith("inbound>>>api>>>"):
            continue
        try:
            value = int(entry.get("value") or 0)
        except (TypeError, ValueError):
            continue
        if name.endswith(UPLINK_SUFFIX):
            up += value
        elif name.endswith(DOWNLINK_SUFFIX):
            down += value
    return down, up


def read_totals(
    server: str,
    binary: Optional[Path] = None,
    timeout: float = 3.0,
) -> Optional[tuple[int, int]]:
    """Cumulative (downlink, uplink) bytes of the running tunnel, else None."""
    if not server:
        return None
    binary = binary or find_binary()
    if not binary or not binary.is_file():
        return None
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) if sys.platform == "win32" else 0
    try:
        completed = subprocess.run(
            [
                str(binary),
                "api",
                "statsquery",
                f"--server={server}",
                "-pattern",
                "inbound>>>",
            ],
            capture_output=True,
            timeout=timeout,
            creationflags=flags,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if completed.returncode != 0:
        return None
    output = completed.stdout.decode("utf-8", "replace").strip()
    if not output.startswith("{"):
        return 0, 0
    return parse_totals(output)


def rates(
    previous: Optional[tuple[int, int]],
    current: Optional[tuple[int, int]],
    elapsed: float,
) -> Optional[tuple[float, float]]:
    """(download, upload) bytes per second between two samples."""
    if previous is None or current is None or elapsed <= 0:
        return None
    down = max(0, current[0] - previous[0]) / elapsed
    up = max(0, current[1] - previous[1]) / elapsed
    return down, up


def format_rate(bytes_per_second: float) -> str:
    value = max(0.0, bytes_per_second)
    if value >= 1024 * 1024:
        return f"{value / (1024 * 1024):.1f} MB/s"
    if value >= 1024:
        return f"{value / 1024:.0f} KB/s"
    return f"{value:.0f} B/s"
