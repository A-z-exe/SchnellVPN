from schnellvpn.core import updater

import pytest


@pytest.fixture(autouse=True)
def windows_platform(monkeypatch):
    monkeypatch.setattr(updater, "_platform", lambda: "win32")


def _release(tag, assets, **extra):
    payload = {"tag_name": tag, "assets": assets, "draft": False}
    payload.update(extra)
    return payload


def _asset(name, digest=""):
    return {
        "name": name,
        "browser_download_url": f"https://example.invalid/{name}",
        "digest": digest,
    }


def test_version_parts_ignore_prefix_and_suffix():
    assert updater.version_parts("v0.2.0-windows-beta") == (0, 2, 0)
    assert updater.version_parts("1.4.3") == (1, 4, 3)
    assert updater.version_parts("nightly") == ()


def test_is_newer_compares_numerically():
    assert updater.is_newer("v0.2.0-windows-beta", "0.1.0")
    assert updater.is_newer("v0.10.0-windows-beta", "0.9.9")
    assert not updater.is_newer("v0.2.0-windows-beta", "0.2.0")
    assert not updater.is_newer("v0.1.9-windows-beta", "0.2.0")
    assert not updater.is_newer("nightly", "0.2.0")


def test_pick_release_skips_android_only_and_old_windows():
    releases = [
        _release("v1.4.3", [_asset("SchnellVPN-1.4.3.apk")]),
        _release("v0.1.0-windows-beta", [_asset("SchnellVPN.exe")]),
        _release("v0.2.0-windows-beta", [_asset("SchnellVPN.exe", "sha256:abcd")]),
    ]
    info = updater.pick_release(releases, "0.1.0")
    assert info is not None
    assert info.version == "0.2.0"
    assert info.tag == "v0.2.0-windows-beta"
    assert info.sha256 == "abcd"


def test_pick_release_returns_none_when_up_to_date():
    releases = [
        _release("v1.4.3", [_asset("SchnellVPN-1.4.3.apk")]),
        _release("v0.2.0-windows-beta", [_asset("SchnellVPN.exe")]),
    ]
    assert updater.pick_release(releases, "0.2.0") is None


def test_pick_release_skips_drafts():
    releases = [
        _release("v9.0.0", [_asset("SchnellVPN.exe")], draft=True),
        _release("v0.2.0-windows-beta", [_asset("SchnellVPN.exe")]),
    ]
    info = updater.pick_release(releases, "0.1.0")
    assert info is not None
    assert info.version == "0.2.0"


import hashlib
from pathlib import Path

from schnellvpn.core import updater as _updater

PAYLOAD = b"MZ" + b"z" * (1024 * 1024)


def _local_info(tmp_path, digest, payload=PAYLOAD, size=0):
    source = tmp_path / "payload.bin"
    source.write_bytes(payload)
    return _updater.UpdateInfo(
        version="9.9.9",
        tag="v9.9.9-windows-beta",
        url=source.as_uri(),
        sha256=digest,
        size=size,
    )


def test_download_verifies_digest(tmp_path, monkeypatch):
    monkeypatch.setattr(_updater.time, "sleep", lambda *_: None)
    digest = hashlib.sha256(PAYLOAD).hexdigest()
    info = _local_info(tmp_path, digest, size=len(PAYLOAD))
    target = updater.download(info, tmp_path / "updates")
    assert target.is_file()
    assert target.name == "SchnellVPN-9.9.9.exe"
    assert target.stat().st_size == len(PAYLOAD)
    assert not (tmp_path / "updates" / "SchnellVPN-9.9.9.exe.part").exists()


def test_download_rejects_bad_digest(tmp_path, monkeypatch):
    monkeypatch.setattr(_updater.time, "sleep", lambda *_: None)
    info = _local_info(tmp_path, "0" * 64)
    try:
        updater.download(info, tmp_path / "updates")
    except RuntimeError as exc:
        assert "checksum" in str(exc)
    else:
        raise AssertionError("bad digest was accepted")
    assert not (tmp_path / "updates" / "SchnellVPN-9.9.9.exe").exists()
    assert not (tmp_path / "updates" / "SchnellVPN-9.9.9.exe.part").exists()


def test_download_rejects_truncated_file(tmp_path, monkeypatch):
    monkeypatch.setattr(_updater.time, "sleep", lambda *_: None)
    digest = hashlib.sha256(PAYLOAD).hexdigest()
    info = _local_info(tmp_path, digest, size=len(PAYLOAD) + 42)
    try:
        updater.download(info, tmp_path / "updates")
    except RuntimeError as exc:
        assert "incomplete" in str(exc)
    else:
        raise AssertionError("truncated download was accepted")
    assert not (tmp_path / "updates" / "SchnellVPN-9.9.9.exe").exists()
    # The partial bytes are kept on purpose so a later attempt can resume.
    assert (tmp_path / "updates" / "SchnellVPN-9.9.9.exe.part").is_file()


def test_download_rejects_non_exe(tmp_path, monkeypatch):
    monkeypatch.setattr(_updater.time, "sleep", lambda *_: None)
    payload = b"PK\x03\x04" + b"z" * 1024
    digest = hashlib.sha256(payload).hexdigest()
    info = _local_info(tmp_path, digest, payload=payload)
    try:
        updater.download(info, tmp_path / "updates")
    except RuntimeError as exc:
        assert "Windows program" in str(exc)
    else:
        raise AssertionError("non-exe payload was accepted")
    assert not (tmp_path / "updates" / "SchnellVPN-9.9.9.exe").exists()


def test_install_helper_is_hidden_and_kills_only_its_own_tree(tmp_path, monkeypatch):
    temp = tmp_path / "temp"
    temp.mkdir()
    monkeypatch.setattr(_updater, "app_data_dir", lambda: tmp_path)
    monkeypatch.setattr(_updater.tempfile, "gettempdir", lambda: str(temp))
    launched = {}

    def fake_popen(args, **kwargs):
        launched["args"] = list(args)
        launched["flags"] = kwargs.get("creationflags", 0)
        launched["script"] = Path(args[-1])
        (temp / "schnellvpn-update.lock").touch()
        return object()

    monkeypatch.setattr(_updater.subprocess, "Popen", fake_popen)
    current = tmp_path / "SchnellVPN.exe"
    current.write_bytes(b"MZ old")
    update = tmp_path / "SchnellVPN-9.9.9.exe"
    update.write_bytes(b"MZ new")

    _updater.install(update, current_exe=current, version="9.9.9")

    assert launched["args"][0] == "powershell.exe"
    assert "-WindowStyle" in launched["args"]
    text = launched["script"].read_text(encoding="ascii")
    assert "schnellvpn-boot-9.9.9.ok" in text
    assert "schnellvpn-update.lock" in text
    assert "install.log" in text
    assert "taskkill.exe /PID" in text
    assert "taskkill /F /IM" not in text
    assert r"%LOCALAPPDATA%\SchnellVPN\runtime" in text
    assert "ping" not in text
    # The helper must wait for the old build (and its PyInstaller bootloader) to
    # exit before launching the new one, or the new build bails on the
    # single-instance mutex and rolls back.
    assert "$OldPid" in text
    assert "Get-Process -Id $OldPid" in text
    assert "old build exited" in text
    if _updater.sys.platform == "win32":
        assert launched["flags"] & _updater.subprocess.CREATE_NO_WINDOW


def test_install_fails_when_the_helper_never_starts(tmp_path, monkeypatch):
    temp = tmp_path / "temp"
    temp.mkdir()
    monkeypatch.setattr(_updater, "app_data_dir", lambda: tmp_path)
    monkeypatch.setattr(_updater.tempfile, "gettempdir", lambda: str(temp))
    monkeypatch.setattr(_updater.subprocess, "Popen", lambda *_, **__: object())
    clock = {"now": 0.0}
    monkeypatch.setattr(_updater.time, "monotonic", lambda: clock["now"])

    def tick(_seconds):
        clock["now"] += 1.0

    monkeypatch.setattr(_updater.time, "sleep", tick)
    current = tmp_path / "SchnellVPN.exe"
    current.write_bytes(b"MZ old")
    update = tmp_path / "SchnellVPN-9.9.9.exe"
    update.write_bytes(b"MZ new")

    try:
        _updater.install(update, current_exe=current, version="9.9.9")
    except RuntimeError as exc:
        assert "helper" in str(exc)
    else:
        raise AssertionError("a missing helper was accepted")


def test_asset_selection_follows_the_build(monkeypatch):
    release = {
        "assets": [
            {"name": "SchnellVPN.exe", "browser_download_url": "x64"},
            {"name": "SchnellVPN-Win7.exe", "browser_download_url": "win7"},
        ]
    }
    monkeypatch.setattr(_updater, "legacy_build", lambda: False)
    assert _updater.asset_for_build(release)["browser_download_url"] == "x64"
    monkeypatch.setattr(_updater, "legacy_build", lambda: True)
    assert _updater.asset_for_build(release)["browser_download_url"] == "win7"


def test_linux_build_picks_the_appimage(monkeypatch):
    monkeypatch.setattr(_updater, "_platform", lambda: "linux")
    release = {
        "assets": [
            {"name": "SchnellVPN.exe", "browser_download_url": "exe"},
            {"name": "SchnellVPN-1.5.0-x86_64.AppImage", "browser_download_url": "app"},
        ]
    }
    assert _updater.asset_for_build(release)["browser_download_url"] == "app"


class _FakeResponse:
    def __init__(self, body: bytes, status: int):
        self.body = body
        self.status = status
        self.pos = 0

    def read(self, size: int = -1) -> bytes:
        chunk = self.body[self.pos:self.pos + size]
        self.pos += len(chunk)
        return chunk

    def __enter__(self):
        return self

    def __exit__(self, *exc_info):
        return False


def _fake_remote(monkeypatch, seen, honor_range=True):
    def fake_open(request, timeout):
        header = request.get_header("Range")
        seen.append(header)
        offset = int(header.split("=")[1].rstrip("-")) if header else 0
        if honor_range and offset:
            return _FakeResponse(PAYLOAD[offset:], 206)
        return _FakeResponse(PAYLOAD, 200)

    monkeypatch.setattr(_updater, "_open", fake_open)


def test_download_resumes_from_partial_file(tmp_path, monkeypatch):
    monkeypatch.setattr(_updater.time, "sleep", lambda *_: None)
    digest = hashlib.sha256(PAYLOAD).hexdigest()
    info = _local_info(tmp_path, digest, size=len(PAYLOAD))
    target_dir = tmp_path / "updates"
    target_dir.mkdir()
    (target_dir / "SchnellVPN-9.9.9.exe.part").write_bytes(PAYLOAD[:300_000])
    seen: list = []
    _fake_remote(monkeypatch, seen)
    result = _updater.download(info, target_dir)
    assert seen[0] == "bytes=300000-"
    assert result.read_bytes() == PAYLOAD
    assert not (target_dir / "SchnellVPN-9.9.9.exe.part").exists()


def test_download_restarts_when_range_is_ignored(tmp_path, monkeypatch):
    monkeypatch.setattr(_updater.time, "sleep", lambda *_: None)
    digest = hashlib.sha256(PAYLOAD).hexdigest()
    info = _local_info(tmp_path, digest, size=len(PAYLOAD))
    target_dir = tmp_path / "updates"
    target_dir.mkdir()
    (target_dir / "SchnellVPN-9.9.9.exe.part").write_bytes(PAYLOAD[:300_000])
    seen: list = []
    _fake_remote(monkeypatch, seen, honor_range=False)
    result = _updater.download(info, target_dir)
    assert seen[0] == "bytes=300000-"
    assert result.read_bytes() == PAYLOAD


def test_download_publishes_a_complete_part_without_network(tmp_path, monkeypatch):
    digest = hashlib.sha256(PAYLOAD).hexdigest()
    info = _local_info(tmp_path, digest, size=len(PAYLOAD))
    target_dir = tmp_path / "updates"
    target_dir.mkdir()
    (target_dir / "SchnellVPN-9.9.9.exe.part").write_bytes(PAYLOAD)

    def no_network(*_args, **_kwargs):
        raise AssertionError("the network was used for a complete file")

    monkeypatch.setattr(_updater, "_open", no_network)
    result = _updater.download(info, target_dir)
    assert result.read_bytes() == PAYLOAD
