"""Per-config real delay, measured inside a temporary Xray core.

Mirrors the Android RealDelayTester: one temporary core with one SOCKS inbound
per config, then a real HTTP request through each inbound.
"""

from __future__ import annotations

import re
from concurrent.futures import ThreadPoolExecutor
from typing import Optional

from .config import build_delay_probe_config
from .links import is_local_link
from .models import VpnServer
from .netprobe import free_base_port, probe_with_latency
from .runner import XrayError, XrayProcess

MAX_MS = 4000
_BAD_OUTBOUND = re.compile(r"delay_out_(\d+)")


def _bad_outbound_index(detail: str) -> Optional[int]:
    """Xray names the outbound it refused, e.g. tag delay_out_2."""
    match = _BAD_OUTBOUND.search(detail)
    return int(match.group(1)) if match else None


def measure_delays(
    servers: list[VpnServer],
    concurrency: int = 8,
    runner: Optional[XrayProcess] = None,
) -> Optional[dict[int, Optional[int]]]:
    """server id -> ms (0 = local, None = failed).

    Returns None when the temporary core could not start, so callers do not
    mistake an engine failure for "every server is down".
    """
    if not servers:
        return {}

    result: dict[int, Optional[int]] = {}
    remote: list[VpnServer] = []
    links: list[str] = []
    for server in servers:
        if is_local_link(server.link):
            result[server.id] = 0
        else:
            remote.append(server)
            links.append(server.link)
    if not links:
        return result

    base_port = free_base_port(len(links))
    if base_port is None:
        return None

    core = runner or XrayProcess()
    remaining = list(range(len(links)))  # indexes into links
    active_ports: list[Optional[int]] = []
    while True:
        active = [links[index] for index in remaining]
        try:
            config, active_ports = build_delay_probe_config(active, base_port)
        except Exception:  # noqa: BLE001 - a malformed config means no probe
            return None
        used_ports = [port for port in active_ports if port]
        if not used_ports:
            break
        try:
            core.start(config, wait_ports=used_ports, timeout=10)
            break
        except XrayError as exc:
            # One refused outbound (e.g. plain VLESS to a public IP) must not
            # cost the delay of every other server: drop it and try again.
            bad = _bad_outbound_index(f"{exc}\n{core.logs(4000)}")
            if bad is None or bad >= len(active):
                return None
            del remaining[bad]

    dropped = set(range(len(links))) - set(remaining)
    for index in dropped:
        result[remote[index].id] = None

    try:
        def probe(port: Optional[int]) -> Optional[int]:
            if port is None:
                return None
            try:
                latency = probe_with_latency(port, MAX_MS)
            except Exception:  # noqa: BLE001
                return None
            return latency if latency is not None and 1 <= latency <= MAX_MS else None

        with ThreadPoolExecutor(max_workers=max(1, concurrency)) as pool:
            measured = list(pool.map(probe, active_ports))
        for index, latency in zip(remaining, measured):
            result[remote[index].id] = latency
        return result
    finally:
        core.stop()
