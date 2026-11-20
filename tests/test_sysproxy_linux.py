"""GSettings backend of the system proxy (runs on any host with a fake gsettings)."""

import json

import pytest

import schnellvpn.core.sysproxy as sp


@pytest.fixture(autouse=True)
def linux(monkeypatch):
    monkeypatch.setattr(sp, "_platform", lambda: "linux")


def fake_gsettings(store):
    def run(args):
        if args[0] == "get":
            return store[(args[1], args[2])]
        store[(args[1], args[2])] = args[3]
        return ""

    return run


DEFAULTS = {
    (schema, key): "'none'" if key == "mode" else ("''" if "host" in key else ("0" if "port" in key else "[]"))
    for schema, key in sp.LINUX_KEYS.values()
}


def test_enable_sets_manual_proxy_and_disable_restores(tmp_path, monkeypatch):
    store = dict(DEFAULTS)
    monkeypatch.setattr(sp, "GSETTINGS_AVAILABLE", True)
    monkeypatch.setattr(sp, "_gsettings", fake_gsettings(store))

    proxy = sp.SystemProxy(port=10809, data_dir=tmp_path)
    assert proxy.enable() is True
    assert store[("org.gnome.system.proxy", "mode")] == "'manual'"
    assert store[("org.gnome.system.proxy.http", "host")] == "'127.0.0.1'"
    assert store[("org.gnome.system.proxy.http", "port")] == "10809"
    assert store[("org.gnome.system.proxy.socks", "port")] == "10808"
    assert "127.0.0.0/8" in store[("org.gnome.system.proxy", "ignore-hosts")]

    proxy.disable()
    assert store[("org.gnome.system.proxy", "mode")] == "'none'"
    assert store[("org.gnome.system.proxy.http", "port")] == "0"
    assert not sp.backup_path(tmp_path).exists()


def test_without_gsettings_enable_is_a_noop(tmp_path, monkeypatch):
    monkeypatch.setattr(sp, "GSETTINGS_AVAILABLE", False)
    proxy = sp.SystemProxy(port=10809, data_dir=tmp_path)
    assert proxy.enable() is False
    assert not sp.backup_path(tmp_path).exists()


def test_restore_stale_restores_a_linux_snapshot(tmp_path, monkeypatch):
    store = dict(DEFAULTS)
    monkeypatch.setattr(sp, "GSETTINGS_AVAILABLE", True)
    monkeypatch.setattr(sp, "_gsettings", fake_gsettings(store))

    backup = sp.backup_path(tmp_path)
    backup.parent.mkdir(parents=True)
    backup.write_text(json.dumps({name: "'manual'" for name in sp.LINUX_KEYS}), encoding="utf-8")

    assert sp.restore_stale(tmp_path) is True
    assert store[("org.gnome.system.proxy", "mode")] == "'manual'"
    assert not backup.exists()
