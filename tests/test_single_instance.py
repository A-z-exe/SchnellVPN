"""Only one copy may run; a second launch raises the first window."""

from __future__ import annotations

import os
import sys

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

import pytest

import schnellvpn  # noqa: F401  (installs the PySide2 shim on Win7 builds)
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


def test_lock_file_rejects_a_second_copy(tmp_path, monkeypatch):
    monkeypatch.setattr(app_module, "_platform", lambda: "linux")
    assert app_module._acquire_instance(tmp_path) is True
    assert app_module._acquire_instance(tmp_path) is False
    if app_module._lock_file is not None:
        app_module._lock_file.unlock()
        app_module._lock_file = None


@pytest.mark.skipif(sys.platform != "win32", reason="named mutex is Windows-only")
def test_stale_last_error_does_not_block_the_first_copy(monkeypatch):
    # The exe once read a leftover ERROR_ALREADY_EXISTS (183) from an earlier
    # API call and refused to start at all.
    import ctypes

    monkeypatch.setattr(app_module, "MUTEX_NAME", f"Local\\SchnellVPN-stale-{os.getpid()}")
    ctypes.windll.kernel32.SetLastError(183)
    assert app_module._acquire_mutex() is True
