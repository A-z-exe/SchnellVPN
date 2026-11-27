"""A start() must never succeed on a port owned by a leftover core."""

from __future__ import annotations

import socket
from pathlib import Path

import pytest

from schnellvpn.core import runner
from schnellvpn.core.runner import (
    XrayError,
    XrayProcess,
    config_ports,
    looks_like_ours,
)


def _listener() -> socket.socket:
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    # A real server accepts connections; with a tiny backlog the second
    # can_connect() probe would be refused and the test would prove nothing.
    sock.listen(32)
    return sock


def test_config_ports_covers_every_inbound():
    config = {
        "inbounds": [
            {"port": 10808},
            {"port": 10809},
            {"port": 10811},
            {"port": "bad"},
            {"protocol": "dokodemo-door"},
        ]
    }
    assert config_ports(config) == [10808, 10809, 10811]


def _ours(path: str) -> str:
    """An xray path that `looks_like_ours` accepts on this platform."""
    return str(Path(path) / runner.xray_name())


def test_looks_like_ours_matches_installed_and_portable_paths():
    name = runner.xray_name()
    assert looks_like_ours(_ours("C:/Users/a/AppData/Local/SchnellVPN/runtime/_MEI1/xray"))
    assert looks_like_ours(_ours("C:/Temp/_MEI99/xray"))
    assert looks_like_ours(_ours("C:/Program Files/SchnellVPN"))
    assert not looks_like_ours(_ours("C:/tools/xray"))
    assert not looks_like_ours(str(Path("C:/SchnellVPN") / (name + ".bak")))
    assert not looks_like_ours("")


def test_start_refuses_a_port_owned_by_someone_else(tmp_path, monkeypatch):
    sock = _listener()
    port = sock.getsockname()[1]
    binary = tmp_path / "xray.exe"
    binary.write_bytes(b"stub")
    monkeypatch.setattr(XrayProcess, "stale_pids", lambda self: [])
    try:
        process = XrayProcess(binary=binary, data_dir=tmp_path)
        with pytest.raises(XrayError) as excinfo:
            process.start({"inbounds": [{"port": port}]}, timeout=1)
        assert str(port) in str(excinfo.value)
        assert process.process is None
    finally:
        sock.close()


def test_start_cleans_a_leftover_owner_before_binding(tmp_path, monkeypatch):
    sock = _listener()
    port = sock.getsockname()[1]
    binary = tmp_path / "xray.exe"
    binary.write_bytes(b"stub")
    killed: list[int] = []

    def fake_cleanup(self, ports=()):
        killed.extend(ports)
        sock.close()
        return 1

    monkeypatch.setattr(XrayProcess, "cleanup_stale", fake_cleanup)
    # The binary is a stub, so the spawn fails; what matters is that the port
    # was cleared first instead of the start reporting a bogus success.
    process = XrayProcess(binary=binary, data_dir=tmp_path)
    with pytest.raises(XrayError):
        process.start({"inbounds": [{"port": port}]}, timeout=1)
    assert killed == [port]


def test_cleanup_stale_skips_our_own_children(tmp_path, monkeypatch):
    binary = tmp_path / runner.xray_name()
    binary.write_bytes(b"stub")
    process = XrayProcess(binary=binary, data_dir=tmp_path)
    process.process = type("P", (), {"pid": 4242})()
    monkeypatch.setattr(runner, "_all_pids", lambda: [4242, 4243])
    monkeypatch.setattr(runner, "_image_of", lambda pid: _ours("C:/SchnellVPN"))
    assert process.stale_pids() == [4243]


def test_kill_stale_xray_terminates_matching_processes(tmp_path, monkeypatch):
    binary = tmp_path / runner.xray_name()
    binary.write_bytes(b"stub")
    terminated: list[int] = []
    monkeypatch.setattr(runner, "_all_pids", lambda: [11, 12, 13])
    monkeypatch.setattr(
        runner,
        "_image_of",
        lambda pid: _ours("C:/SchnellVPN") if pid != 13 else "C:/other/notepad.exe",
    )
    monkeypatch.setattr(runner, "_terminate", lambda pid: terminated.append(pid) or True)
    count = runner.kill_stale_xray(binary=binary, data_dir=tmp_path)
    assert count == 2
    assert terminated == [11, 12]
