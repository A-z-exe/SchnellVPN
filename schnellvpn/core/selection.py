"""Pick the best server for smart connect and failover.

The ranking mirrors the table sort: a measured data-flow delay beats a bare
TCP/TLS ping, servers with no measurement go last, and ties keep the list
order stable by name.
"""

from __future__ import annotations

from typing import Iterable, Mapping, Optional

from .models import VpnServer


def server_score(
    server: VpnServer,
    pings: Mapping[int, Optional[int]],
    delays: Mapping[int, Optional[int]],
) -> Optional[int]:
    delay = delays.get(server.id)
    if delay is not None:
        return delay
    return pings.get(server.id)


def rank_servers(
    servers: Iterable[VpnServer],
    pings: Mapping[int, Optional[int]],
    delays: Mapping[int, Optional[int]],
    exclude: Optional[set] = None,
) -> list[VpnServer]:
    """Best first; servers in `exclude` (already failed) are left out."""
    skipped = exclude or set()
    candidates = [s for s in servers if s.link and s.id not in skipped]

    def key(server: VpnServer):
        score = server_score(server, pings, delays)
        # `info.` servers are pinned first, then by measured score.
        return (
            not server.pinned,
            score is None,
            score if score is not None else 0,
            server.name.lower(),
            server.id,
        )

    return sorted(candidates, key=key)
