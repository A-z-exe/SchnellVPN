"""Static update notices: parsing, version gates and fail-open fetching."""

from __future__ import annotations

import os
import time
import urllib.error

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

import pytest
from shiboken6 import delete as delete_qt

import schnellvpn.ui.main_window as mw
from schnellvpn.core import notices as notices_module
from schnellvpn.core.notices import Notice, fetch, localized, parse
from schnellvpn.core.store import Store


PAYLOAD = {
    "schema": 1,
    "notices": [
        {
            "id": "old-clients",
            "platforms": ["windows", "android"],
            "max_version": "1.5.1",
            "level": "warning",
            "update": True,
            "title": {"fa": "به‌روزرسانی", "en": "Update required"},
            "message": {"fa": "نسخه را به‌روز کن", "en": "Please update"},
        },
        {
            "id": "linux-only",
            "platforms": ["linux"],
            "title": "Linux note",
            "message": "Only for Linux",
        },
        {
            "id": "new-clients",
            "min_version": "2.0.0",
            "title": "Future",
            "message": "For 2.x",
        },
        {"id": "", "title": "Broken", "message": "no id"},
        {"id": "no-message", "title": "Broken"},
    ],
}


def test_parse_filters_platform_and_version():
    old = {notice.id for notice in parse(PAYLOAD, "1.5.1", "windows")}
    assert old == {"old-clients"}

    current = {notice.id for notice in parse(PAYLOAD, "1.5.2", "windows")}
    assert current == set()

    linux = {notice.id for notice in parse(PAYLOAD, "1.5.1", "linux")}
    assert linux == {"linux-only"}


def test_parse_reads_single_string_text_and_update_flag():
    notice = parse(PAYLOAD, "1.0.0", "linux")[0]
    assert notice.title == {"en": "Linux note"}
    assert notice.update is False
    first = parse(PAYLOAD, "1.0.0", "android")[0]
    assert first.update is True
    assert first.level == "warning"


def test_localized_falls_back_to_english():
    text = {"en": "Hello", "de": "Hallo"}
    assert localized(text, "fa") == "Hello"
    assert localized(text, "de") == "Hallo"
    assert localized({}, "fa") == ""


class _FakeResponse:
    def __init__(self, payload: bytes):
        self.payload = payload

    def read(self, _size: int) -> bytes:
        return self.payload

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        return False


def test_fetch_is_fail_open(monkeypatch):
    def boom(_request, _timeout):
        raise urllib.error.URLError("offline")

    monkeypatch.setattr(notices_module.updater, "_open", boom)
    assert fetch("1.0.0", "windows") == []


def test_fetch_parses_without_network(monkeypatch):
    import json

    payload = json.dumps(PAYLOAD).encode("utf-8")
    monkeypatch.setattr(
        notices_module.updater, "_open", lambda _request, _timeout: _FakeResponse(payload)
    )
    items = fetch("1.0.0", "windows")
    assert [notice.id for notice in items] == ["old-clients"]


def pump(app, predicate, timeout=5.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        app.processEvents()
        if predicate():
            return True
        time.sleep(0.01)
    app.processEvents()
    return predicate()


def test_window_shows_each_notice_once(tmp_path, qt_app, monkeypatch):
    from PySide6.QtWidgets import QMessageBox

    item = Notice(
        id="shown-once",
        level="warning",
        title={"en": "Update required"},
        message={"en": "Please update"},
        update=False,
    )
    monkeypatch.setattr(mw.notices, "fetch", lambda _version, _platform: [item])
    monkeypatch.setattr(mw.admin, "hello", lambda *_args, **_kwargs: None)

    store = Store(path=tmp_path / "profiles.json", data_dir=tmp_path)
    window = mw.MainWindow(store, tmp_path)
    try:
        window.check_notices()
        assert pump(qt_app, lambda: window._notice_box is not None)
        box = window._notice_box
        assert "Please update" in box.text()
        close = [
            button
            for button in box.buttons()
            if box.buttonRole(button) == QMessageBox.RejectRole
        ][0]
        close.click()
        assert pump(qt_app, lambda: window._notice_box is None)
        assert "shown-once" in store.get_setting("seen_notices")

        second = mw.MainWindow(store, tmp_path)
        try:
            second.check_notices()
            pump(qt_app, lambda: False, timeout=0.5)
            assert second._notice_box is None
        finally:
            second.shutdown()
            delete_qt(second)
    finally:
        window.shutdown()
        delete_qt(window)