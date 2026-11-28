"""Profiles are separated, grouped and collapsible in the desktop UI."""

from __future__ import annotations

import os

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from shiboken6 import delete as delete_qt

import schnellvpn.ui.main_window as mw
from PySide6.QtCore import Qt

from schnellvpn.core.models import Profile, VpnServer
from schnellvpn.core.store import Store


def _server(name, link=None):
    return VpnServer(
        1, name, "VLESS", link or f"vless://id@example.com:443?type=tcp#{name}"
    )


def _window(tmp_path, qt_app, profiles=None):
    store = Store(path=tmp_path / "profiles.json", data_dir=tmp_path)
    if profiles:
        store.profiles = profiles
        store.current_profile_id = profiles[0].id
    window = mw.MainWindow(store, tmp_path)
    return window


def test_server_tree_groups_by_profile(tmp_path, qt_app):
    first = Profile(1, "Profile 1", [_server("a")], 1, ["https://a.example/sub"])
    second = Profile(2, "Profile 2", [_server("b")], 1, ["https://b.example/sub"])
    window = _window(tmp_path, qt_app, [first, second])
    try:
        assert window.tree.topLevelItemCount() == 2
        group = window.tree.topLevelItem(0)
        assert group.data(0, Qt.UserRole + 1) == "profile"
        assert group.childCount() == 1
        child = group.child(0)
        assert child.data(0, Qt.UserRole + 1) == "server"
        assert child.data(0, Qt.UserRole) == (1, 1)
    finally:
        window.shutdown()
        delete_qt(window)


def test_profile_groups_collapse_and_rebuild(tmp_path, qt_app):
    first = Profile(1, "Profile 1", [_server("a")], 1, [])
    second = Profile(2, "Profile 2", [_server("b")], 1, [])
    window = _window(tmp_path, qt_app, [first, second])
    try:
        window.tree.topLevelItem(1).setExpanded(False)
        assert 2 in window._collapsed_profiles
        window.refresh_table()
        assert not window.tree.topLevelItem(1).isExpanded()
        assert window.tree.topLevelItem(0).isExpanded()
    finally:
        window.shutdown()
        delete_qt(window)


def test_profile_actions_live_inside_the_combo(tmp_path, qt_app):
    window = _window(tmp_path, qt_app)
    try:
        items = [
            window.profile_combo.itemData(i)
            for i in range(window.profile_combo.count())
        ]
        assert 1 in items  # the profile itself
        for key in ("add_from_clipboard", "add_manual", "edit_profile", "delete_profile"):
            assert key in items
    finally:
        window.shutdown()
        delete_qt(window)


def test_double_click_profile_toggles_expansion(tmp_path, qt_app):
    first = Profile(1, "Profile 1", [_server("a")], 1, [])
    window = _window(tmp_path, qt_app, [first])
    try:
        group = window.tree.topLevelItem(0)
        assert group.isExpanded()
        window._row_activated(group, 0)
        assert not group.isExpanded()
        window._row_activated(group, 0)
        assert group.isExpanded()
    finally:
        window.shutdown()
        delete_qt(window)


def test_dark_theme_styles_the_server_tree():
    from schnellvpn.ui.theme import stylesheet

    qss = stylesheet("dark")
    assert "QTreeWidget" in qss
    assert "QTreeWidget::item:selected" in qss
    assert "__ARROW_RIGHT__" not in qss


def test_import_creates_one_profile_per_subscription(tmp_path, qt_app, monkeypatch):
    window = _window(tmp_path, qt_app)
    monkeypatch.setattr(window, "_ping_all", lambda: None)
    monkeypatch.setattr(
        mw.subscription,
        "fetch",
        lambda url: [_server("from-" + url.split("//")[1].split(".")[0],
                             f"vless://id@h:443#{url}")],
    )
    try:
        parsed = window._parse_import(
            "https://a.example/sub\nhttps://b.example/sub\nvless://id@c:443#direct"
        )
        window._import_done(parsed)
        assert [p.name for p in window.store.profiles] == ["Profile 1", "Profile 2"]
        assert [p.subscription_urls for p in window.store.profiles] == [
            ["https://a.example/sub"], ["https://b.example/sub"]
        ]
        assert [s.name for s in window.store.profiles[0].servers] == ["from-a", "direct"]
        assert window.tree.topLevelItemCount() == 2
    finally:
        window.shutdown()
        delete_qt(window)
