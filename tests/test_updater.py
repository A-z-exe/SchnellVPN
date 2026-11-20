from schnellvpn.core import updater


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


def test_install_helper_uses_no_console_apps(tmp_path, monkeypatch):
    # `timeout` needs a console and made the helper flash cmd windows in a loop.
    monkeypatch.setattr(_updater, "app_data_dir", lambda: tmp_path)
    launched = {}

    def fake_popen(args, **kwargs):
        launched["args"] = args
        return object()

    monkeypatch.setattr(_updater.subprocess, "Popen", fake_popen)
    current = tmp_path / "SchnellVPN.exe"
    current.write_bytes(b"MZ old")
    update = tmp_path / "SchnellVPN-9.9.9.exe"
    update.write_bytes(b"MZ new")

    _updater.install(update, current_exe=current, version="9.9.9")

    script = Path(launched["args"][2])
    try:
        text = script.read_text(encoding="ascii")
    finally:
        script.unlink(missing_ok=True)
    assert "timeout" not in text
    assert "schnellvpn-boot-9.9.9.ok" in text
    assert "schnellvpn-update.lock" in text
    assert "install.log" in text


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
