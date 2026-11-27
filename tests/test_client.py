"""connect() must wire the stats port and never claim a broken setup works."""

from __future__ import annotations

from pathlib import Path

import pytest

from schnellvpn.core import client as client_module
from schnellvpn.core.client import TunnelClient, TunnelError


LINK = "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp&security=tls#test"


class FakeRunner:
    def __init__(self):
        self.running = False
        self.started: list[dict] = []
        self.stopped = 0

    def start(self, config, wait_ports=None, timeout=10.0):
        self.started.append({"config": config, "wait_ports": list(wait_ports or [])})
        self.running = True

    def stop(self):
        self.stopped += 1
        self.running = False

    def logs(self, max_bytes=0):
        return ""

    @property
    def process(self):
        return None


class FakeProxy:
    def __init__(self, enable_result: bool = True):
        self.port = 10809
        self.enable_result = enable_result
        self.enabled = 0
        self.disabled = 0

    def enable(self) -> bool:
        self.enabled += 1
        return self.enable_result

    def disable(self) -> None:
        self.disabled += 1


@pytest.fixture()
def wired(tmp_path, monkeypatch):
    runner = FakeRunner()
    proxy = FakeProxy()
    monkeypatch.setattr(client_module, "internet_available", lambda: True)
    monkeypatch.setattr(client_module, "free_port", lambda preferred, attempts=20: preferred)
    monkeypatch.setattr(client_module, "probe_with_latency", lambda *a, **k: 42)
    monkeypatch.setattr(client_module, "probe_http_proxy", lambda *a, **k: 11)
    monkeypatch.setattr(client_module, "probe_https_proxy", lambda *a, **k: 22)
    tunnel = TunnelClient(data_dir=Path(tmp_path))
    tunnel.runner = runner
    tunnel.proxy = proxy
    return tunnel, runner, proxy


def test_connect_waits_for_the_stats_port(wired):
    tunnel, runner, proxy = wired
    latency = tunnel.connect(LINK, socks_port=10808, http_port=10809)
    assert latency == 42
    assert runner.started[0]["wait_ports"] == [10808, 10809, 10810]
    assert tunnel.stats_port == 10810
    assert tunnel.stats_server == "127.0.0.1:10810"
    assert proxy.enabled == 1 and proxy.port == 10809
    assert tunnel.connected


def test_connect_fails_when_https_does_not_pass(wired, monkeypatch):
    tunnel, runner, proxy = wired
    monkeypatch.setattr(client_module, "probe_https_proxy", lambda *a, **k: None)
    with pytest.raises(TunnelError) as excinfo:
        tunnel.connect(LINK)
    assert excinfo.value.key == "https_probe_failed"
    assert runner.stopped == 1
    assert tunnel.stats_port is None
    assert not tunnel.connected


def test_connect_fails_when_system_proxy_is_not_applied(wired):
    tunnel, runner, proxy = wired
    proxy.enable_result = False
    with pytest.raises(TunnelError) as excinfo:
        tunnel.connect(LINK)
    assert excinfo.value.key == "system_proxy_failed"
    assert runner.stopped == 1
    assert proxy.disabled == 1
    assert tunnel.stats_port is None
    assert not tunnel.connected
