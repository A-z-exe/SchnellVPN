"""Offscreen checks for smart connect, failover and always-on recovery."""

from __future__ import annotations

import os
import time

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

import pytest

import schnellvpn.ui.main_window as mw
from PySide6.QtWidgets import QApplication

from schnellvpn.core.models import VpnServer
from schnellvpn.core.store import Store


class FakeClient:
    """Accepts every link except the ones listed in `bad_links`."""

    def __init__(self, bad_links=()):
        self.bad_links = set(bad_links)
        self.running = False
        self.link = ""
        self.stats_server = ""

    def connect(self, link, socks_port, http_port, use_system_proxy):
        if link in self.bad_links:
            raise RuntimeError("boom")
        self.link = link
        self.running = True
        return 40

    def disconnect(self):
        self.running = False

    def logs(self, max_bytes=20000):
        return ""


@pytest.fixture(scope="module")
def qt_app():
    return QApplication.instance() or QApplication([])


def pump(app, predicate, timeout=6.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        app.processEvents()
        if predicate():
            return True
        time.sleep(0.01)
    app.processEvents()
    return predicate()


@pytest.fixture(autouse=True)
def fast_reconnects(monkeypatch):
    monkeypatch.setattr(mw, "RECONNECT_DELAYS", (0, 0, 0, 0, 0))


def make_window(tmp_path, qt_app, servers, bad_links=()):
    store = Store(path=tmp_path / "profiles.json", data_dir=tmp_path)
    store.profile.servers = servers
    store.profile.selected_server_id = servers[0].id
    store.set_setting("always_on", True)
    store.set_setting("smart_connect", True)
    window = mw.MainWindow(store, tmp_path)
    window.client = FakeClient(bad_links)
    return window


def server(server_id, name):
    return VpnServer(id=server_id, name=name, label="VMess", link=f"vmess://{name}")


def test_smart_connect_picks_the_fastest(tmp_path, qt_app, monkeypatch):
    window = make_window(
        tmp_path, qt_app, [server(1, "slow"), server(2, "fast"), server(3, "dead")]
    )
    monkeypatch.setattr(mw, "ping_all", lambda links: [500, 10, None])
    window._connect_clicked()
    try:
        assert pump(qt_app, lambda: window.state == mw.STATE_CONNECTED)
        assert window.current_server.id == 2
        assert window.store.profile.selected_server_id == 2
        assert window.client.link == "vmess://fast"
    finally:
        window.shutdown()
        window.close()


def test_failover_switches_to_the_next_server(tmp_path, qt_app, monkeypatch):
    servers = [server(1, "primary"), server(2, "backup")]
    window = make_window(tmp_path, qt_app, servers, bad_links={"vmess://primary"})
    window.client.running = True
    window.current_server = servers[0]
    window.state = mw.STATE_CONNECTED
    try:
        window._start_recovery()
        assert pump(
            qt_app,
            lambda: window.state == mw.STATE_CONNECTED and window.current_server.id == 2,
        )
        assert window.client.link == "vmess://backup"
        assert window._recovering is False
        assert window.store.profile.selected_server_id == 2
    finally:
        window.shutdown()
        window.close()


def test_disconnect_cancels_recovery(tmp_path, qt_app):
    window = make_window(tmp_path, qt_app, [server(1, "only")])
    window.client.running = True
    window.current_server = window.store.profile.servers[0]
    window.state = mw.STATE_CONNECTED
    try:
        window._start_recovery()
        window._disconnect_async()
        assert pump(qt_app, lambda: window.state == mw.STATE_IDLE and not window._busy)
        assert window._recovering is False
        assert pump(qt_app, lambda: window.state == mw.STATE_IDLE)
        assert window.state == mw.STATE_IDLE
    finally:
        window.shutdown()
        window.close()


def test_bounded_mode_gives_up(tmp_path, qt_app):
    window = make_window(tmp_path, qt_app, [server(1, "dead")], bad_links={"vmess://dead"})
    window.store.set_setting("always_on", False)
    window.client.running = True
    window.current_server = window.store.profile.servers[0]
    window.state = mw.STATE_CONNECTED
    try:
        window._start_recovery()
        assert pump(qt_app, lambda: window.state == mw.STATE_IDLE, timeout=8)
        assert window._recovering is False
        assert window._reconnect_attempt >= 3
    finally:
        window.shutdown()
        window.close()
