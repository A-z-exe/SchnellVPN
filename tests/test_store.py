"""Profile storage writes credentials; on Linux the file must stay private."""

import os
import stat

import pytest

from schnellvpn.core.models import VpnServer
from schnellvpn.core.store import Store


def _server(name, source=""):
    return VpnServer(0, name, "VLESS",
                     f"vless://id@example.com:443?type=tcp#{name}", source=source)


def test_each_subscription_gets_its_own_profile(tmp_path):
    store = Store(path=tmp_path / "profiles.json")
    first = store.add_subscription_profile("https://a.example/sub",
                                           [_server("normal"), _server("info")])
    second = store.add_subscription_profile("https://b.example/sub", [_server("other")])

    assert [p.id for p in store.profiles] == [1, 2]
    assert first.subscription_urls == ["https://a.example/sub"]
    assert second.subscription_urls == ["https://b.example/sub"]
    assert {s.source for s in first.servers} == {"https://a.example/sub"}
    assert store.current_profile_id == 2
    # Profile 1 keeps its own servers after the second subscription is added.
    assert {s.name for s in store.profiles[0].servers} == {"normal", "info"}


def test_update_profile_changes_name_and_subscription_url(tmp_path):
    store = Store(path=tmp_path / "profiles.json")
    profile = store.add_subscription_profile("https://old.example/sub", [_server("a")])

    store.update_profile(profile.id, "My link", ["https://new.example/sub"])

    assert store.profiles[0].name == "My link"
    assert store.profiles[0].subscription_urls == ["https://new.example/sub"]
    assert store.profiles[0].servers[0].source == "https://new.example/sub"


def test_delete_profile_renumbers_the_rest(tmp_path):
    store = Store(path=tmp_path / "profiles.json")
    store.add_subscription_profile("https://a.example/sub", [_server("a")])
    store.add_subscription_profile("https://b.example/sub", [_server("b")])
    third = store.add_subscription_profile("https://c.example/sub", [_server("c")])
    assert store.current_profile_id == 3

    store.delete_profile(2)

    assert [p.id for p in store.profiles] == [1, 2]
    assert [p.subscription_urls[0] for p in store.profiles] == [
        "https://a.example/sub", "https://c.example/sub"
    ]
    # Deleting the active profile selects the next one (old 3 became 2).
    assert store.current_profile_id == 2


@pytest.mark.skipif(os.name != "posix", reason="POSIX permissions only")
def test_profile_file_and_dir_are_private(tmp_path):
    path = tmp_path / "cfg" / "profiles.json"
    Store(path=path).save()

    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert stat.S_IMODE(path.parent.stat().st_mode) == 0o700
