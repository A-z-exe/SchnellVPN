from __future__ import annotations

import sys

from PySide6.QtWidgets import QApplication

from .core.client import TunnelClient
from .core.runner import app_data_dir
from .core.store import Store
from .ui.main_window import MainWindow
from .ui.theme import app_icon, stylesheet


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
    try:
        return app.exec()
    finally:
        window.shutdown()
