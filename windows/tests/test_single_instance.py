"""Only one copy may run; a second launch raises the first window."""

from __future__ import annotations

import os
import sys

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

import pytest
from PySide6.QtWidgets import QApplication, QMainWindow

from schnellvpn import app as app_module


@pytest.fixture(scope="module")
def qt_app():
    return QApplication.instance() or QApplication([])


def test_second_launch_raises_the_first_window(qt_app, monkeypatch):
    monkeypatch.setattr(app_module, "INSTANCE_KEY", f"SchnellVPN-test-{os.getpid()}")
    assert app_module._notify_running(300) is False

    window = QMainWindow()
    window.hide()
    server = app_module._listen_for_relaunch(window)
    try:
        assert server.isListening()
        assert app_module._notify_running(1000) is True
        for _ in range(50):
            qt_app.processEvents()
        assert window.isVisible()
    finally:
        server.close()
        window.close()


@pytest.mark.skipif(sys.platform != "win32", reason="named mutex is Windows-only")
def test_named_mutex_rejects_a_second_copy(monkeypatch):
    monkeypatch.setattr(app_module, "MUTEX_NAME", f"Local\\SchnellVPN-test-{os.getpid()}")
    assert app_module._acquire_mutex() is True
    assert app_module._acquire_mutex() is False
