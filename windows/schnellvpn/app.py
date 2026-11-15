from __future__ import annotations

import sys
import tempfile

from PySide6.QtWidgets import QApplication

from . import __version__
from .core.client import TunnelClient
from .core.runner import app_data_dir
from .core.store import Store
from .ui.main_window import MainWindow
from .ui.theme import app_icon, stylesheet


def _mark_boot_ok() -> None:
    """Signal the update helper that this build really came up."""
    try:
        marker = tempfile.gettempdir() / f"schnellvpn-boot-{__version__}.ok"
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

    data_dir = app_data_dir()
    data_dir.mkdir(parents=True, exist_ok=True)
    TunnelClient.restore_stale_proxy()

    store = Store(data_dir=data_dir)
    app.setStyleSheet(stylesheet(store.get_setting("theme", "dark")))

    window = MainWindow(store, data_dir)
    window.show()
    _mark_boot_ok()
    try:
        return app.exec()
    finally:
        window.shutdown()
