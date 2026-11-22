"""Admin Worker client: heartbeat parsing, fail-open and remote commands."""

from __future__ import annotations

import json
import time
import urllib.error

from shiboken6 import delete as delete_qt

import schnellvpn.ui.main_window as mw
from schnellvpn.core import admin

HELLO = {
    "server_time": "2026-11-22T00:00:00.000Z",
    "notices": [
        {
            "id": "server-notice",
            "platforms": ["windows"],
            "level": "warning",
            "title": {"fa": "پیام سرور", "en": "Server message"},
            "message": {"fa": "متن", "en": "Text"},
        }
    ],
    "commands": [
        {"id": 7, "kind": "setting", "payload": {"key": "language", "value": "en"}},
        {"id": 8, "kind": "notice", "payload": {"title": {"en": "Hi"}, "message": {"en": "There"}}},
        {"id": 9, "kind": "unknown", "payload": {}},
        {"id": "bad", "kind": "disconnect"},
        {"kind": "disconnect"},
    ],
}


class _FakeResponse:
    def __init__(self, payload: bytes):
        self.payload = payload

    def read(self, _size: int) -> bytes:
        return self.payload

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        return False


def test_hello_parses_notices_and_commands(monkeypatch):
    seen = {}

    def fake_open(request, _timeout):
        seen["url"] = request.full_url
        seen["body"] = json.loads(request.data.decode("utf-8"))
        return _FakeResponse(json.dumps(HELLO).encode("utf-8"))

    monkeypatch.setattr(admin.updater, "_open", fake_open)
    result = admin.hello("abc12345def", "1.5.5", "fa", "windows")
    assert result is not None
    assert seen["url"] == admin.ADMIN_URL + "/v1/hello"
    assert seen["body"]["platform"] == "windows"
    assert [notice.id for notice in result.notices] == ["server-notice"]
    assert [(c.id, c.kind) for c in result.commands] == [(7, "setting"), (8, "notice")]


def test_hello_is_fail_open(monkeypatch):
    def boom(_request, _timeout):
        raise urllib.error.URLError("offline")

    monkeypatch.setattr(admin.updater, "_open", boom)
    assert admin.hello("abc12345def", "1.5.5") is None


def test_ack_posts_the_command(monkeypatch):
    seen = {}

    def fake_open(request, _timeout):
        seen["url"] = request.full_url
        seen["body"] = json.loads(request.data.decode("utf-8"))
        return _FakeResponse(b'{"ok":true}')

    monkeypatch.setattr(admin.updater, "_open", fake_open)
    assert admin.ack("abc12345def", 7, "done")
    assert seen["url"] == admin.ADMIN_URL + "/v1/ack"
    assert seen["body"] == {"install_id": "abc12345def", "command_id": 7, "status": "done"}


def test_command_notice_marks_force_update():
    command = admin.Command(id=3, kind="force_update", payload={"min_version": "9.0.0"})
    notice = admin.command_notice(command, "Title", "Message")
    assert notice.id == "cmd-3"
    assert notice.level == "critical"
    assert notice.update is True
    assert notice.title == {"en": "Title"}


def pump(app, predicate, timeout=5.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        app.processEvents()
        if predicate():
            return True
        time.sleep(0.01)
    app.processEvents()
    return predicate()


def make_window(tmp_path, monkeypatch, hello):
    from schnellvpn.core.store import Store

    monkeypatch.setattr(mw.notices, "fetch", lambda _version, _platform: [])
    monkeypatch.setattr(mw.admin, "hello", lambda *_args, **_kwargs: hello)
    acks = []
    monkeypatch.setattr(
        mw.admin, "ack", lambda install_id, command_id, status="done": acks.append(
            (command_id, status)
        )
    )
    store = Store(path=tmp_path / "profiles.json", data_dir=tmp_path)
    return mw.MainWindow(store, tmp_path), store, acks


def test_window_runs_setting_and_show_commands(tmp_path, qt_app, monkeypatch):
    hello = admin.Hello(
        notices=[],
        commands=[
            admin.Command(7, "setting", {"key": "language", "value": "en"}),
            admin.Command(8, "notice", {"title": {"en": "Hi"}, "message": {"en": "There"}}),
        ],
    )
    window, store, acks = make_window(tmp_path, monkeypatch, hello)
    try:
        window.check_notices()
        assert pump(qt_app, lambda: window._notice_box is not None)
        assert window.lang == "en"
        assert store.get_setting("language") == "en"
        assert "There" in window._notice_box.text()
        assert pump(qt_app, lambda: {tid for tid, _ in acks} == {7, 8})
        assert all(status == "done" for _, status in acks)
    finally:
        window.shutdown()
        delete_qt(window)


def test_window_acks_ignored_force_update(tmp_path, qt_app, monkeypatch):
    hello = admin.Hello(
        notices=[],
        commands=[admin.Command(11, "force_update", {"min_version": "0.0.1"})],
    )
    window, _store, acks = make_window(tmp_path, monkeypatch, hello)
    try:
        window.check_notices()
        assert pump(qt_app, lambda: bool(acks))
        assert acks == [(11, "ignored")]
        assert window._notice_box is None
    finally:
        window.shutdown()
        delete_qt(window)
