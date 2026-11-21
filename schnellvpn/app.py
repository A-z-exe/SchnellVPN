from __future__ import annotations

import ctypes
import sys
import tempfile
import time
from pathlib import Path

from PySide6.QtCore import QLockFile, Qt, QTimer
from PySide6.QtNetwork import QLocalServer, QLocalSocket
from PySide6.QtWidgets import QApplication

from . import __version__
from .core.client import TunnelClient
from .core.runner import app_data_dir
from .core.store import Store
from .ui.main_window import MainWindow
from .ui.theme import app_icon, stylesheet

INSTANCE_KEY = "SchnellVPN-single-instance"
MUTEX_NAME = "Local\\SchnellVPN-single-instance"
ERROR_ALREADY_EXISTS = 183
_mutex_handle: int | None = None
_lock_file: QLockFile | None = None


def _platform() -> str:
    return sys.platform


def _acquire_mutex() -> bool:
    """Hold a session-wide named mutex so only one copy can start."""
    global _mutex_handle
    if sys.platform != "win32":
        return True
    kernel32 = ctypes.windll.kernel32
    kernel32.CreateMutexW.restype = ctypes.c_void_p
    kernel32.CreateMutexW.argtypes = (ctypes.c_void_p, ctypes.c_int, ctypes.c_wchar_p)
    kernel32.CloseHandle.argtypes = (ctypes.c_void_p,)
    kernel32.SetLastError(0)
    handle = kernel32.CreateMutexW(None, 0, MUTEX_NAME)
    if not handle:
        return True
    if kernel32.GetLastError() == ERROR_ALREADY_EXISTS:
        kernel32.CloseHandle(handle)
        return False
    _mutex_handle = handle
    return True


def _acquire_instance(data_dir: Path) -> bool:
    """Windows uses the named mutex; elsewhere a lock file in the data dir."""
    if _platform() == "win32":
        return _acquire_mutex()
    global _lock_file
    lock = QLockFile(str(data_dir / "instance.lock"))
    lock.setStaleLockTime(10_000)
    if not lock.tryLock(50):
        return False
    _lock_file = lock
    return True


def _notify_running(timeout_ms: int = 5000) -> bool:
    """Ask the first instance to show its window."""
    deadline = time.monotonic() + timeout_ms / 1000
    while True:
        sock = QLocalSocket()
        sock.connectToServer(INSTANCE_KEY)
        if sock.waitForConnected(250):
            sock.write(b"show")
            sock.flush()
            sock.waitForBytesWritten(300)
            sock.disconnectFromServer()
            return True
        sock.abort()
        if time.monotonic() >= deadline:
            return False
        time.sleep(0.15)


def _listen_for_relaunch(window: MainWindow) -> QLocalServer:
    """Serve 'show' requests from a second launch (or a shortcut re-run)."""
    server = QLocalServer()
    if not server.listen(INSTANCE_KEY):
        QLocalServer.removeServer(INSTANCE_KEY)
        server.listen(INSTANCE_KEY)

    def show_existing() -> None:
        while server.hasPendingConnections():
            conn = server.nextPendingConnection()
            conn.readAll()
            conn.disconnectFromServer()
        window.setWindowState(
            (window.windowState() & ~Qt.WindowMinimized)
            | Qt.WindowActive
        )
        window.show()
        window.raise_()
        window.activateWindow()

    server.newConnection.connect(show_existing)
    return server


def _mark_boot_ok() -> None:
    """Signal the update helper that this build really came up."""
    try:
        marker = Path(tempfile.gettempdir()) / f"schnellvpn-boot-{__version__}.ok"
        marker.write_text(str(sys.executable), encoding="utf-8")
    except OSError:
        pass


def main() -> int:
    QApplication.setApplicationName("SchnellVPN")
    QApplication.setOrganizationName("A-z-exe")
    QApplication.setApplicationDisplayName("SchnellVPN")
    QApplication.setQuitOnLastWindowClosed(False)

    app = QApplication(sys.argv)
    app.setWindowIcon(app_icon())
    if sys.platform != "win32":
        QApplication.setDesktopFileName("schnellvpn")

    data_dir = app_data_dir()
    data_dir.mkdir(parents=True, exist_ok=True)

    if not _acquire_instance(data_dir):
        _notify_running()
        return 0

    TunnelClient.restore_stale_proxy()

    store = Store(data_dir=data_dir)
    app.setStyleSheet(stylesheet(store.get_setting("theme", "dark")))

    window = MainWindow(store, data_dir)
    server = _listen_for_relaunch(window)
    window.show()
    QTimer.singleShot(2500, window.check_notices)
    _mark_boot_ok()
    try:
        return app.exec()
    finally:
        server.close()
        window.shutdown()
        if _lock_file is not None:
            _lock_file.unlock()
