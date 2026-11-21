from schnellvpn.core.models import VpnServer
from schnellvpn.core.selection import rank_servers, server_score


def _server(server_id: int, name: str = "") -> VpnServer:
    return VpnServer(
        id=server_id,
        name=name or f"s{server_id}",
        label="VMess",
        link=f"vmess://{server_id}",
    )


def test_delay_beats_ping():
    a, b = _server(1), _server(2)
    ranked = rank_servers([a, b], pings={1: 20, 2: 40}, delays={1: 90, 2: 60})
    assert [s.id for s in ranked] == [2, 1]


def test_unknown_goes_last_and_local_first():
    fast, slow, unknown, local = _server(1), _server(2), _server(3), _server(4)
    ranked = rank_servers(
        [unknown, slow, fast, local],
        pings={1: 120, 2: 10, 4: 0},
        delays={},
    )
    assert [s.id for s in ranked] == [4, 2, 1, 3]


def test_exclude_skips_failed_servers():
    a, b, c = _server(1), _server(2), _server(3)
    ranked = rank_servers([a, b, c], {1: 10, 2: 20, 3: 30}, {}, exclude={1})
    assert [s.id for s in ranked] == [2, 3]


def test_links_are_required():
    empty = VpnServer(id=9, name="empty", label="", link="")
    ranked = rank_servers([empty], {}, {})
    assert ranked == []


def test_score_prefers_delay_then_ping():
    server = _server(1)
    assert server_score(server, {1: 50}, {1: 70}) == 70
    assert server_score(server, {1: 50}, {}) == 50
    assert server_score(server, {}, {}) is None
