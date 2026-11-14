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

from schnellvpn.core import updater as _updater


def _local_info(tmp_path, digest):
    source = tmp_path / "payload.bin"
    source.write_bytes(b"z" * (1024 * 1024))
    return _updater.UpdateInfo(
        version="9.9.9",
        tag="v9.9.9-windows-beta",
        url=source.as_uri(),
        sha256=digest,
    )


def test_download_verifies_digest(tmp_path):
    digest = hashlib.sha256(b"z" * (1024 * 1024)).hexdigest()
    info = _local_info(tmp_path, digest)
    target = updater.download(info, tmp_path / "updates")
    assert target.is_file()
    assert target.name == "SchnellVPN-9.9.9.exe"
    assert target.stat().st_size == 1024 * 1024


def test_download_rejects_bad_digest(tmp_path):
    info = _local_info(tmp_path, "0" * 64)
    try:
        updater.download(info, tmp_path / "updates")
    except RuntimeError as exc:
        assert "checksum" in str(exc)
    else:
        raise AssertionError("bad digest was accepted")
    assert not (tmp_path / "updates" / "SchnellVPN-9.9.9.exe").exists()
