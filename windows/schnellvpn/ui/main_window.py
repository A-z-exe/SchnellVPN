"""Main window for the Windows client."""

from __future__ import annotations

import html
import threading
import time
from collections import deque
from datetime import datetime
from pathlib import Path
from typing import Callable, Optional

from PySide6.QtCore import QObject, QSize, Qt, QTimer, QUrl, Signal
from PySide6.QtGui import QColor, QDesktopServices, QFont, QGuiApplication, QIcon
from PySide6.QtWidgets import (
    QAbstractItemView,
    QApplication,
    QComboBox,
    QFrame,
    QHBoxLayout,
    QHeaderView,
    QInputDialog,
    QLabel,
    QMainWindow,
    QMenu,
    QMessageBox,
    QPushButton,
    QSystemTrayIcon,
    QTableWidget,
    QTableWidgetItem,
    QToolButton,
    QVBoxLayout,
    QWidget,
)

from .. import __version__
from ..core import speedstats, subscription, updater
from ..core.client import TunnelClient
from ..core.delay import measure_delays
from ..core.models import VpnServer
from ..core.netprobe import ping_all, probe_with_latency
from ..core.runner import XrayProcess, find_binary
from ..core.store import Store
from .dialogs import (
    AboutDialog,
    EditServerDialog,
    ImportDialog,
    LogsDialog,
    SettingsDialog,
)
from .i18n import RTL_LANGS, t
from .speedchart import SpeedChart
from .theme import ACCENT, DANGER, SUCCESS, WARN, app_icon, download_icon, stylesheet

STATE_IDLE = "idle"
STATE_CONNECTING = "connecting"
STATE_CONNECTED = "connected"
STATE_DISCONNECTING = "disconnecting"

LANG_NAMES = {"fa": "فارسی", "en": "English", "de": "Deutsch"}
CHANNEL_URL = "https://t.me/SchnellVPN"


class Task(QObject):
    """Runs a callable in a daemon thread and delivers the result to the UI."""

    ok = Signal(object)
    err = Signal(object)

    def __init__(self, fn: Callable[[], object], parent: Optional[QObject] = None):
        super().__init__(parent)
        self._fn = fn

    def start(self) -> None:
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self) -> None:
        try:
            result = self._fn()
        except Exception as exc:  # noqa: BLE001 - surfaced in the UI
            self.err.emit(exc)
            return
        self.ok.emit(result)


class MainWindow(QMainWindow):
    def __init__(self, store: Store, data_dir: Path):
        super().__init__()
        self.store = store
        self.data_dir = Path(data_dir)
        self.lang = self.store.get_setting("language", "fa")
        self.client = TunnelClient(self.data_dir)
        self.state = STATE_IDLE
        self.current_server: Optional[VpnServer] = None
        self.pings: dict[int, Optional[int]] = {}
        self.delays: dict[int, Optional[int]] = {}
        self.connected_at: Optional[float] = None
        self._busy = False
        self._monitoring = False
        self._quitting = False
        self._tray_hint_shown = False
        self._tasks: list[Task] = []
        self._events: deque[str] = deque(maxlen=400)
        self._speed_prev: Optional[tuple[int, int]] = None
        self._speed_prev_at: Optional[float] = None
        self._speed_pending = False
        self._last_rates: Optional[tuple[float, float]] = None
        self._pending_update: Optional[updater.UpdateInfo] = None

        self.setWindowTitle("SchnellVPN")
        self.setWindowIcon(app_icon())
        self.setMinimumSize(900, 640)
        self.resize(1000, 700)

        self._build_ui()
        self._build_tray()
        self._timers = {
            "uptime": QTimer(self),
            "monitor": QTimer(self),
            "speed": QTimer(self),
        }
        self._timers["uptime"].timeout.connect(self._update_uptime)
        self._timers["monitor"].timeout.connect(self._monitor_tick)
        self._timers["speed"].timeout.connect(self._speed_tick)
        self._timers["uptime"].start(1000)

        self._populate_profiles()
        self.refresh_table()
        self.apply_language(self.lang, persist=False)
        self._log(f"data dir: {self.data_dir}")
        self._update_engine_chip()

    # ------------------------------------------------------------------ layout

    def _build_ui(self) -> None:
        root = QWidget()
        root.setObjectName("root")
        self.setCentralWidget(root)

        outer = QVBoxLayout(root)
        outer.setContentsMargins(20, 18, 20, 16)
        outer.setSpacing(12)

        outer.addLayout(self._build_header())
        outer.addWidget(self._build_status_card())
        outer.addWidget(self._build_speed_card())
        outer.addLayout(self._build_toolbar())

        self.section_label = QLabel()
        self.section_label.setObjectName("section")
        outer.addWidget(self.section_label)

        self.table = QTableWidget(0, 4)
        self.table.setShowGrid(False)
        self.table.setAlternatingRowColors(False)
        self.table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.table.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.table.verticalHeader().setVisible(False)
        self.table.verticalHeader().setDefaultSectionSize(46)
        self.table.setFocusPolicy(Qt.FocusPolicy.NoFocus)
        header = self.table.horizontalHeader()
        header.setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)
        for column in (1, 2, 3):
            header.setSectionResizeMode(column, QHeaderView.ResizeMode.ResizeToContents)
        header.setHighlightSections(False)
        self.table.itemSelectionChanged.connect(self._on_selection_changed)
        self.table.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.table.customContextMenuRequested.connect(self._table_menu)
        self.table.doubleClicked.connect(self._row_activated)
        outer.addWidget(self.table, 1)

        self.empty_label = QLabel()
        self.empty_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.empty_label.setObjectName("hint")
        self.empty_label.setTextFormat(Qt.TextFormat.RichText)
        self.empty_label.setOpenExternalLinks(True)
        outer.addWidget(self.empty_label)

        outer.addLayout(self._build_footer())

    def _build_header(self) -> QHBoxLayout:
        row = QHBoxLayout()
        row.setSpacing(12)

        logo = QLabel()
        logo.setPixmap(app_icon().pixmap(46, 46))
        logo.setFixedSize(46, 46)
        row.addWidget(logo)

        titles = QVBoxLayout()
        titles.setSpacing(0)
        self.title_label = QLabel("SchnellVPN")
        self.title_label.setObjectName("title")
        self.subtitle_label = QLabel()
        self.subtitle_label.setObjectName("subtitle")
        titles.addWidget(self.title_label)
        titles.addWidget(self.subtitle_label)
        row.addLayout(titles)
        row.addStretch(1)

        self.profile_combo = QComboBox()
        self.profile_combo.setMinimumWidth(140)
        self.profile_combo.currentIndexChanged.connect(self._on_profile_changed)
        self.profile_combo.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.profile_combo.customContextMenuRequested.connect(self._profile_menu)
        row.addWidget(self.profile_combo)

        add_profile = QToolButton()
        add_profile.setText("+")
        add_profile.setToolTip(t(self.lang, "new_profile"))
        add_profile.clicked.connect(self._add_profile)
        self.add_profile_button = add_profile
        row.addWidget(add_profile)

        self.lang_button = QToolButton()
        self.lang_button.setText(LANG_NAMES.get(self.lang, "FA").upper())
        self.lang_button.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)
        lang_menu = QMenu(self.lang_button)
        for code, name in LANG_NAMES.items():
            action = lang_menu.addAction(name)
            action.triggered.connect(lambda _checked=False, c=code: self.apply_language(c))
        self.lang_button.setMenu(lang_menu)
        row.addWidget(self.lang_button)

        self.theme_button = QToolButton()
        self.theme_button.clicked.connect(self._toggle_theme)
        row.addWidget(self.theme_button)

        self.update_button = QToolButton()
        self.update_button.setIconSize(QSize(18, 18))
        self.update_button.clicked.connect(self._check_update)
        self._refresh_update_icon()
        row.addWidget(self.update_button)

        self.settings_button = QToolButton()
        self.settings_button.setText("\u2261")
        self.settings_button.clicked.connect(self._open_settings)
        row.addWidget(self.settings_button)
        return row

    def _build_status_card(self) -> QFrame:
        card = QFrame()
        card.setObjectName("card")
        layout = QVBoxLayout(card)
        layout.setContentsMargins(18, 16, 18, 16)
        layout.setSpacing(12)

        top = QHBoxLayout()
        top.setSpacing(8)
        self.status_dot = QLabel("\u25cf")
        self.status_dot.setStyleSheet(f"color: {ACCENT}; font-size: 13px;")
        self.status_label = QLabel()
        self.status_label.setObjectName("status")
        top.addWidget(self.status_dot)
        top.addWidget(self.status_label)
        top.addStretch(1)
        self.delay_chip = QLabel()
        self.delay_chip.setObjectName("chip")
        self.uptime_chip = QLabel()
        self.uptime_chip.setObjectName("chip")
        top.addWidget(self.delay_chip)
        top.addWidget(self.uptime_chip)
        layout.addLayout(top)

        self.connect_button = QPushButton()
        self.connect_button.setObjectName("primary")
        self.connect_button.setMinimumHeight(54)
        self.connect_button.clicked.connect(self._connect_clicked)
        layout.addWidget(self.connect_button)

        self.hint_label = QLabel()
        self.hint_label.setObjectName("hint")
        self.hint_label.setWordWrap(True)
        layout.addWidget(self.hint_label)
        return card

    def _build_speed_card(self) -> QFrame:
        card = QFrame()
        card.setObjectName("card")
        layout = QVBoxLayout(card)
        layout.setContentsMargins(16, 12, 16, 10)
        layout.setSpacing(4)

        top = QHBoxLayout()
        top.setSpacing(8)
        self.speed_title = QLabel()
        self.speed_title.setObjectName("subtitle")
        self.speed_down_label = QLabel("\u2014")
        self.speed_down_label.setObjectName("chip")
        self.speed_up_label = QLabel("\u2014")
        self.speed_up_label.setObjectName("chip")
        top.addWidget(self.speed_title)
        top.addStretch(1)
        top.addWidget(self.speed_down_label)
        top.addWidget(self.speed_up_label)
        layout.addLayout(top)

        self.speed_chart = SpeedChart()
        self.speed_chart.set_theme(self.store.get_setting("theme", "dark"))
        self.speed_chart.setVisible(False)
        layout.addWidget(self.speed_chart)

        self.speed_idle_label = QLabel()
        self.speed_idle_label.setObjectName("hint")
        self.speed_idle_label.setWordWrap(True)
        layout.addWidget(self.speed_idle_label)
        return card

    def _build_toolbar(self) -> QHBoxLayout:
        row = QHBoxLayout()
        row.setSpacing(8)
        self.add_sub_button = QPushButton()
        self.add_sub_button.clicked.connect(self._import_servers)
        self.refresh_button = QPushButton()
        self.refresh_button.clicked.connect(self._refresh_subscriptions)
        self.ping_button = QPushButton()
        self.ping_button.clicked.connect(self._ping_all)
        self.delay_button = QPushButton()
        self.delay_button.clicked.connect(self._test_delays)
        self.logs_button = QPushButton()
        self.logs_button.clicked.connect(self._open_logs)
        for button in (
            self.add_sub_button,
            self.refresh_button,
            self.ping_button,
            self.delay_button,
        ):
            row.addWidget(button)
        row.addStretch(1)
        row.addWidget(self.logs_button)
        return row

    def _build_footer(self) -> QHBoxLayout:
        row = QHBoxLayout()
        self.proxy_chip = QLabel()
        self.proxy_chip.setObjectName("chip")
        row.addWidget(self.proxy_chip)
        row.addStretch(1)
        self.engine_chip = QLabel()
        self.engine_chip.setObjectName("chip")
        row.addWidget(self.engine_chip)
        return row

    def _build_tray(self) -> None:
        self.tray = QSystemTrayIcon(app_icon(), self)
        menu = QMenu()
        self.tray_show_action = menu.addAction("")
        self.tray_show_action.triggered.connect(self._show_window)
        self.tray_connect_action = menu.addAction("")
        self.tray_connect_action.triggered.connect(self._connect_clicked)
        menu.addSeparator()
        self.tray_about_action = menu.addAction("")
        self.tray_about_action.triggered.connect(self._open_about)
        self.tray_quit_action = menu.addAction("")
        self.tray_quit_action.triggered.connect(self._quit)
        self.tray.setContextMenu(menu)
        self.tray.activated.connect(self._tray_activated)
        self.tray.show()

    # ------------------------------------------------------------------ i18n

    def apply_language(self, lang: str, persist: bool = True) -> None:
        self.lang = lang if lang in RTL_LANGS or lang in ("en", "de") else "en"
        app = QApplication.instance()
        if app:
            app.setLayoutDirection(
                Qt.LayoutDirection.RightToLeft
                if self.lang in RTL_LANGS
                else Qt.LayoutDirection.LeftToRight
            )
            family = "Tahoma" if self.lang in RTL_LANGS else "Segoe UI"
            font = QFont(family)
            font.setPointSizeF(9.5)
            app.setFont(font)
        if persist:
            self.store.set_setting("language", self.lang)
        self.lang_button.setText(self.lang.upper())

        self.subtitle_label.setText(t(self.lang, "tagline"))
        self.section_label.setText(t(self.lang, "servers").upper())
        self.add_sub_button.setText(f"+  {t(self.lang, 'add_subscription')}")
        self.refresh_button.setText(t(self.lang, "refresh_subscription"))
        self.refresh_button.setToolTip(t(self.lang, "refresh_subscription_hint"))
        self.ping_button.setText(t(self.lang, "test_ping"))
        self.delay_button.setText(t(self.lang, "test_delay"))
        self.logs_button.setText(t(self.lang, "logs"))
        self.settings_button.setToolTip(t(self.lang, "settings"))
        self.theme_button.setToolTip(t(self.lang, "theme"))
        self.add_profile_button.setToolTip(t(self.lang, "new_profile"))
        self.update_button.setToolTip(f"{t(self.lang, 'check_update')} \u00b7 v{__version__}")
        self.speed_title.setText(t(self.lang, "live_speed").upper())
        self.speed_down_label.setToolTip(t(self.lang, "download"))
        self.speed_up_label.setToolTip(t(self.lang, "upload"))
        self.speed_idle_label.setText(t(self.lang, "live_speed_idle"))
        if self._last_rates:
            self.speed_down_label.setText(
                f"{t(self.lang, 'download')}: {speedstats.format_rate(self._last_rates[0])}"
            )
            self.speed_up_label.setText(
                f"{t(self.lang, 'upload')}: {speedstats.format_rate(self._last_rates[1])}"
            )
        else:
            self.speed_down_label.setText(f"{t(self.lang, 'download')}: \u2014")
            self.speed_up_label.setText(f"{t(self.lang, 'upload')}: \u2014")
        self.table.setHorizontalHeaderLabels(
            [
                t(self.lang, "name"),
                t(self.lang, "type"),
                t(self.lang, "ping"),
                t(self.lang, "delay"),
            ]
        )
        self._align_headers()
        self.empty_label.setText(
            f"{html.escape(t(self.lang, 'servers_empty'))}<br>"
            f"{html.escape(t(self.lang, 'servers_empty_hint'))}<br><br>"
            f'<a href="{CHANNEL_URL}" style="color: #F5A623;">'
            f"{html.escape(t(self.lang, 'channel_hint'))}</a>"
        )
        self.tray_show_action.setText(t(self.lang, "show"))
        self.tray_about_action.setText(t(self.lang, "about"))
        self.tray_quit_action.setText(t(self.lang, "quit"))
        self._update_theme_button()
        self._render_state()
        self._update_proxy_chip()
        if hasattr(self, "engine_chip"):
            self._update_engine_chip()
        self.refresh_table()

    def _align_headers(self) -> None:
        # AlignLeft is leading; Qt mirrors it automatically in RTL layouts.
        for column in range(4):
            item = self.table.horizontalHeaderItem(column)
            if item is None:
                continue
            alignment = (
                Qt.AlignmentFlag.AlignCenter
                if column in (2, 3)
                else Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter
            )
            item.setTextAlignment(alignment)

    def _update_theme_button(self) -> None:
        theme = self.store.get_setting("theme", "dark")
        self.theme_button.setText("\u263c" if theme == "dark" else "\u263e")
        self._refresh_update_icon()

    def _refresh_update_icon(self) -> None:
        if not hasattr(self, "update_button"):
            return
        dark = self.store.get_setting("theme", "dark") == "dark"
        color = QColor("#E6EDF3") if dark else QColor("#0F172A")
        self.update_button.setIcon(QIcon(download_icon(18, color)))

    def _toggle_theme(self) -> None:
        theme = "light" if self.store.get_setting("theme", "dark") == "dark" else "dark"
        self.store.set_setting("theme", theme)
        self._apply_stylesheet()
        self._update_theme_button()

    def _apply_stylesheet(self) -> None:
        app = QApplication.instance()
        if app:
            app.setStyleSheet(stylesheet(self.store.get_setting("theme", "dark")))
        if hasattr(self, "speed_chart"):
            self.speed_chart.set_theme(self.store.get_setting("theme", "dark"))

    # ------------------------------------------------------------------ profiles

    def _populate_profiles(self) -> None:
        self.profile_combo.blockSignals(True)
        self.profile_combo.clear()
        for profile in self.store.profiles:
            self.profile_combo.addItem(profile.name, profile.id)
        index = self.profile_combo.findData(self.store.current_profile_id)
        self.profile_combo.setCurrentIndex(max(0, index))
        self.profile_combo.blockSignals(False)

    def _on_profile_changed(self, _index: int) -> None:
        profile_id = self.profile_combo.currentData()
        if profile_id is None:
            return
        if self.state == STATE_CONNECTED:
            self._disconnect_async(quiet=True)
        self.store.current_profile_id = int(profile_id)
        self.store.save()
        self.current_server = None
        self.refresh_table()

    def _add_profile(self) -> None:
        name, ok = QInputDialog.getText(
            self, t(self.lang, "new_profile"), t(self.lang, "profile_name")
        )
        if not ok or not name.strip():
            return
        self.store.add_profile(name.strip())
        self._populate_profiles()
        self.refresh_table()

    def _profile_menu(self, position) -> None:
        if len(self.store.profiles) <= 1:
            return
        menu = QMenu(self)
        delete = menu.addAction(t(self.lang, "delete_profile"))
        delete.triggered.connect(self._delete_profile)
        menu.exec(self.profile_combo.mapToGlobal(position))

    def _delete_profile(self) -> None:
        answer = QMessageBox.question(
            self,
            t(self.lang, "delete_profile"),
            t(self.lang, "delete_profile_confirm"),
        )
        if answer != QMessageBox.StandardButton.Yes:
            return
        if self.state == STATE_CONNECTED:
            self._disconnect_async(quiet=True)
        self.store.delete_profile(self.store.current_profile_id)
        self._populate_profiles()
        self.refresh_table()

    # ------------------------------------------------------------------ table

    def refresh_table(self) -> None:
        profile = self.store.profile
        selected_id = profile.selected_server_id
        self.table.blockSignals(True)
        self.table.setRowCount(len(profile.servers))
        for row, server in enumerate(profile.servers):
            name_item = QTableWidgetItem(f"{server.icon}  {server.name}")
            name_item.setData(Qt.ItemDataRole.UserRole, server.id)
            name_item.setFlags(
                Qt.ItemFlag.ItemIsEnabled | Qt.ItemFlag.ItemIsSelectable
            )
            type_item = QTableWidgetItem(server.label)
            type_item.setForeground(QColor("#8496AC"))
            ping_item = QTableWidgetItem(self._fmt_ms(self.pings.get(server.id)))
            ping_item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
            ping_item.setForeground(self._ms_color(self.pings.get(server.id)))
            delay_item = QTableWidgetItem(self._fmt_ms(self.delays.get(server.id)))
            delay_item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
            delay_item.setForeground(self._ms_color(self.delays.get(server.id)))
            for column, item in enumerate((name_item, type_item, ping_item, delay_item)):
                self.table.setItem(row, column, item)
        self.table.blockSignals(False)

        if profile.servers:
            target_row = 0
            for row, server in enumerate(profile.servers):
                if server.id == selected_id:
                    target_row = row
                    break
            self.table.selectRow(target_row)
            self.current_server = profile.servers[target_row]
        else:
            self.current_server = None

        self.empty_label.setVisible(not profile.servers)
        self.table.setVisible(bool(profile.servers))
        self._render_state()

    def _fmt_ms(self, ms: Optional[int]) -> str:
        if ms is None:
            return "\u2014"
        if ms == 0:
            return t(self.lang, "local")
        # U+200E keeps "142 ms" left-to-right inside the RTL table cells.
        return f"\u200e{ms} ms"

    @staticmethod
    def _ms_color(ms: Optional[int]) -> QColor:
        if ms is None:
            return QColor("#6B7A90")
        if ms == 0:
            return QColor(ACCENT)
        if ms < 250:
            return QColor(SUCCESS)
        if ms < 600:
            return QColor(WARN)
        return QColor(DANGER)

    def _on_selection_changed(self) -> None:
        rows = self.table.selectionModel().selectedRows() if self.table.selectionModel() else []
        if not rows:
            return
        item = self.table.item(rows[0].row(), 0)
        if item is None:
            return
        server_id = int(item.data(Qt.ItemDataRole.UserRole))
        profile = self.store.profile
        server = next((s for s in profile.servers if s.id == server_id), None)
        if server is None:
            return
        if self.state == STATE_CONNECTED and self.current_server and self.current_server.id != server.id:
            self.store.select_server(server.id)
            self.current_server = server
            self._reconnect(server)
            return
        self.store.select_server(server.id)
        self.current_server = server
        self._render_state()

    def _row_activated(self, _index) -> None:
        if self.state == STATE_IDLE and not self._busy:
            self._connect_async()

    def _table_menu(self, position) -> None:
        row = self.table.rowAt(position.y())
        if row < 0:
            return
        item = self.table.item(row, 0)
        if item is None:
            return
        server_id = int(item.data(Qt.ItemDataRole.UserRole))
        server = next(
            (s for s in self.store.profile.servers if s.id == server_id), None
        )
        if server is None:
            return
        menu = QMenu(self)
        copy = menu.addAction(t(self.lang, "copy_link"))
        edit = menu.addAction(t(self.lang, "edit"))
        menu.addSeparator()
        delete = menu.addAction(t(self.lang, "delete"))
        chosen = menu.exec(self.table.viewport().mapToGlobal(position))
        if chosen == copy:
            QGuiApplication.clipboard().setText(server.link)
            self._set_hint(t(self.lang, "copied"))
        elif chosen == edit:
            self._edit_server(server)
        elif chosen == delete:
            self._delete_server(server)

    def _edit_server(self, server: VpnServer) -> None:
        dialog = EditServerDialog(self.lang, server, self)
        if dialog.exec() != dialog.DialogCode.Accepted:
            return
        server.label = self._label_for(server.link)
        self.store.save()
        self.refresh_table()
        if self.state == STATE_CONNECTED and self.current_server and self.current_server.id == server.id:
            self._reconnect(server)

    def _delete_server(self, server: VpnServer) -> None:
        answer = QMessageBox.question(
            self, t(self.lang, "delete_server"), t(self.lang, "confirm_delete")
        )
        if answer != QMessageBox.StandardButton.Yes:
            return
        if self.state == STATE_CONNECTED and self.current_server and self.current_server.id == server.id:
            self._disconnect_async(quiet=True)
        self.store.remove_server(server.id)
        self.pings.pop(server.id, None)
        self.delays.pop(server.id, None)
        self.refresh_table()

    @staticmethod
    def _label_for(link: str) -> str:
        try:
            parsed = subscription.parse_content(link)
        except Exception:  # noqa: BLE001
            parsed = []
        return parsed[0].label if parsed else ""

    # ------------------------------------------------------------------ import

    def _import_servers(self) -> None:
        dialog = ImportDialog(self.lang, self)
        if dialog.exec() != dialog.DialogCode.Accepted:
            return
        text = dialog.text().strip()
        self._set_hint(t(self.lang, "importing"))

        def work() -> tuple[list[VpnServer], list[str]]:
            servers: list[VpnServer] = []
            urls: list[str] = []
            for line in text.splitlines():
                line = line.strip()
                if not line:
                    continue
                if line.lower().startswith(("http://", "https://")):
                    fetched = subscription.fetch(line)
                    for server in fetched:
                        server.source = line
                    servers.extend(fetched)
                    if line not in urls:
                        urls.append(line)
                else:
                    servers.extend(subscription.parse_content(line))
            if not servers:
                raise ValueError(t(self.lang, "import_fail"))
            return servers, urls

        task = self._start_task(work)
        task.ok.connect(
            lambda result: self._import_done(*result),
            Qt.ConnectionType.QueuedConnection,
        )
        task.err.connect(
            lambda exc: self._set_hint(f"{t(self.lang, 'import_fail')}: {exc}"),
            Qt.ConnectionType.QueuedConnection,
        )

    def _import_done(self, servers: list[VpnServer], urls: list[str]) -> None:
        count = self.store.add_servers(servers, urls)
        self._log(f"imported {count} servers")
        self._set_hint(t(self.lang, "import_ok", count=count))
        self.refresh_table()
        self._ping_all()

    def _refresh_subscriptions(self) -> None:
        urls = list(self.store.profile.subscription_urls)
        if not urls:
            self._set_hint(t(self.lang, "no_subscription"))
            return
        self._set_hint(t(self.lang, "refreshing"))
        self.refresh_button.setEnabled(False)

        def work() -> dict[str, Optional[list[VpnServer]]]:
            fetched: dict[str, Optional[list[VpnServer]]] = {}
            for url in urls:
                try:
                    fetched[url] = subscription.fetch(url)
                except Exception:  # noqa: BLE001 - one dead link keeps its old servers
                    fetched[url] = None
            return fetched

        task = self._start_task(work)
        task.ok.connect(self._refresh_done, Qt.ConnectionType.QueuedConnection)
        task.err.connect(self._refresh_failed, Qt.ConnectionType.QueuedConnection)

    def _refresh_failed(self, exc: Exception) -> None:
        self.refresh_button.setEnabled(True)
        self._set_hint(f"{t(self.lang, 'refresh_failed')}: {exc}")

    def _refresh_done(self, fetched: dict[str, Optional[list[VpnServer]]]) -> None:
        self.refresh_button.setEnabled(True)
        if not any(fetched.values()):
            self._set_hint(t(self.lang, "refresh_failed"))
            return
        if self.state == STATE_CONNECTED:
            self._disconnect_async(quiet=True)

        profile = self.store.profile
        old = list(profile.servers)
        selected_link = profile.selected_server.link if profile.selected_server else ""

        merged: list[VpnServer] = []
        seen: set[str] = set()
        fresh: list[VpnServer] = []
        for url, servers in fetched.items():
            if not servers:
                continue
            for server in servers:
                server.source = url
                fresh.append(server)
        kept = [
            server
            for server in old
            if not server.source
            or server.source not in fetched
            or fetched[server.source] is None
        ]
        for server in fresh + kept:
            if server.link in seen:
                continue
            seen.add(server.link)
            merged.append(server)

        if not merged:
            self._set_hint(t(self.lang, "refresh_failed"))
            return

        for index, server in enumerate(merged, 1):
            server.id = index
        profile.servers = merged
        profile.selected_server_id = next(
            (server.id for server in merged if server.link == selected_link),
            merged[0].id,
        )
        self.pings.clear()
        self.delays.clear()
        self.store.save()
        self.refresh_table()

        failed = sum(1 for servers in fetched.values() if servers is None)
        self._log(f"subscription refresh: {len(merged)} servers, {failed} links failed")
        self._set_hint(
            t(self.lang, "refresh_done", count=len(merged), failed=failed)
        )
        self._ping_all()

    # ------------------------------------------------------------------ ping / delay

    def _ping_all(self) -> None:
        servers = list(self.store.profile.servers)
        if not servers:
            self._set_hint(t(self.lang, "no_servers_to_test"))
            return
        self._set_hint(t(self.lang, "pinging"))
        task = self._start_task(lambda: ping_all([s.link for s in servers]))
        task.ok.connect(
            lambda results: self._apply_pings(
                {server.id: value for server, value in zip(servers, results)}
            ),
            Qt.ConnectionType.QueuedConnection,
        )

    def _apply_pings(self, results: dict[int, Optional[int]]) -> None:
        self.pings.update(results)
        self._set_hint(t(self.lang, "hint_ready") if self.state == STATE_IDLE else self.hint_label.text())
        self._refresh_metrics()

    def _test_delays(self) -> None:
        servers = list(self.store.profile.servers)
        if not servers:
            self._set_hint(t(self.lang, "no_servers_to_test"))
            return
        self._set_hint(t(self.lang, "testing_delay"))
        delay_runner = XrayProcess(data_dir=self.data_dir / "delay")
        task = self._start_task(lambda: measure_delays(servers, runner=delay_runner))
        task.ok.connect(self._apply_delays, Qt.ConnectionType.QueuedConnection)
        task.err.connect(
            lambda exc: self._set_hint(f"{t(self.lang, 'delay_engine_failed')}: {exc}"),
            Qt.ConnectionType.QueuedConnection,
        )

    def _apply_delays(self, results: Optional[dict[int, Optional[int]]]) -> None:
        if results is None:
            self._set_hint(t(self.lang, "delay_engine_failed"))
            return
        self.delays.update(results)
        self._set_hint(t(self.lang, "delay_done"))
        self._refresh_metrics()

    # ------------------------------------------------------------------ speed

    def _speed_tick(self) -> None:
        if (
            self.state != STATE_CONNECTED
            or not self.client.stats_server
            or self._speed_pending
        ):
            return
        self._speed_pending = True
        server = self.client.stats_server
        task = self._start_task(lambda: speedstats.read_totals(server))
        task.ok.connect(self._speed_sample, Qt.ConnectionType.QueuedConnection)
        task.err.connect(
            lambda _exc: self._speed_sample(None), Qt.ConnectionType.QueuedConnection
        )

    def _speed_sample(self, current: Optional[tuple[int, int]]) -> None:
        self._speed_pending = False
        now = time.monotonic()
        if self.state != STATE_CONNECTED or current is None:
            self._speed_prev = None
            self._speed_prev_at = None
            return
        if self._speed_prev is not None and self._speed_prev_at is not None:
            measured = speedstats.rates(
                self._speed_prev, current, now - self._speed_prev_at
            )
            if measured is not None:
                down, up = measured
                self._last_rates = (down, up)
                self.speed_chart.push(down, up)
                self.speed_down_label.setText(
                    f"{t(self.lang, 'download')}: {speedstats.format_rate(down)}"
                )
                self.speed_up_label.setText(
                    f"{t(self.lang, 'upload')}: {speedstats.format_rate(up)}"
                )
        self._speed_prev = current
        self._speed_prev_at = now

    def _speed_reset(self) -> None:
        self._timers["speed"].stop()
        self._speed_prev = None
        self._speed_prev_at = None
        self._speed_pending = False
        self._last_rates = None
        self.speed_chart.clear()
        self.speed_chart.setVisible(False)
        self.speed_idle_label.setVisible(True)
        self.speed_down_label.setText(f"{t(self.lang, 'download')}: \u2014")
        self.speed_up_label.setText(f"{t(self.lang, 'upload')}: \u2014")

    def _speed_start(self) -> None:
        self.speed_chart.clear()
        self.speed_chart.setVisible(True)
        self.speed_idle_label.setVisible(False)
        self._speed_prev = None
        self._speed_prev_at = None
        self._speed_pending = False
        self._last_rates = None
        self._timers["speed"].start(1000)

    # ------------------------------------------------------------------ updates

    def _check_update(self) -> None:
        if not self.update_button.isEnabled():
            return
        self.update_button.setEnabled(False)
        self._set_hint(t(self.lang, "checking_update"))
        task = self._start_task(lambda: updater.check(__version__))
        task.ok.connect(self._update_checked, Qt.ConnectionType.QueuedConnection)
        task.err.connect(self._update_check_failed, Qt.ConnectionType.QueuedConnection)

    def _update_checked(self, info: Optional[updater.UpdateInfo]) -> None:
        self.update_button.setEnabled(True)
        if info is None:
            self._set_hint(t(self.lang, "up_to_date", version=__version__))
            return
        self._pending_update = info
        if not updater.can_self_install():
            self._set_hint(t(self.lang, "update_source_run", version=info.version))
            QDesktopServices.openUrl(QUrl(updater.RELEASE_PAGE))
            return
        answer = QMessageBox.question(
            self,
            t(self.lang, "check_update"),
            t(self.lang, "update_available", version=info.version),
        )
        if answer == QMessageBox.StandardButton.Yes:
            self._download_update(info)

    def _update_check_failed(self, exc: Exception) -> None:
        self.update_button.setEnabled(True)
        self._set_hint(f"{t(self.lang, 'update_failed')}: {exc}")

    def _download_update(self, info: updater.UpdateInfo) -> None:
        self.update_button.setEnabled(False)
        self._set_hint(t(self.lang, "downloading_update", version=info.version))
        task = self._start_task(lambda: updater.download(info))
        task.ok.connect(self._update_downloaded, Qt.ConnectionType.QueuedConnection)
        task.err.connect(
            lambda exc: self._update_download_failed(exc),
            Qt.ConnectionType.QueuedConnection,
        )

    def _update_downloaded(self, path: Path) -> None:
        self.update_button.setEnabled(True)
        info = self._pending_update
        version = info.version if info else ""
        self._set_hint(t(self.lang, "update_ready", version=version))
        answer = QMessageBox.question(
            self,
            t(self.lang, "check_update"),
            t(self.lang, "restart_for_update"),
        )
        if answer != QMessageBox.StandardButton.Yes:
            return
        try:
            updater.install(Path(path))
        except Exception as exc:  # noqa: BLE001 - surfaced in the UI
            self._set_hint(f"{t(self.lang, 'install_failed')}: {exc}")
            return
        self._log(f"installing update {version}")
        self._quit()

    def _update_download_failed(self, exc: Exception) -> None:
        self.update_button.setEnabled(True)
        self._set_hint(f"{t(self.lang, 'update_failed')}: {exc}")

    # ------------------------------------------------------------------ connect

    def _connect_clicked(self) -> None:
        if self._busy:
            return
        if self.state == STATE_CONNECTED:
            self._disconnect_async()
        else:
            self._connect_async()

    def _connect_async(self) -> None:
        server = self.current_server or self.store.profile.selected_server
        if server is None:
            self._set_hint(t(self.lang, "select_server_hint"))
            return
        self.current_server = server
        settings = self.store.settings
        socks = int(settings.get("socks_port", 10808))
        http = int(settings.get("http_port", 10809))
        use_proxy = bool(settings.get("use_system_proxy", True))

        self._busy = True
        self._set_state(STATE_CONNECTING)
        self._speed_reset()
        self._set_hint(t(self.lang, "connecting_to", name=server.name))
        self._log(f"connecting to {server.name} ({server.label})")

        task = self._start_task(
            lambda: self.client.connect(server.link, socks, http, use_proxy)
        )
        task.ok.connect(
            lambda latency: self._connected(server, int(latency)),
            Qt.ConnectionType.QueuedConnection,
        )
        task.err.connect(self._connect_failed, Qt.ConnectionType.QueuedConnection)

    def _connected(self, server: VpnServer, latency: int) -> None:
        self._busy = False
        self.current_server = server
        self.state = STATE_CONNECTED
        self.connected_at = time.monotonic()
        self.delays[server.id] = latency
        self._log(f"connected: {server.name} via {server.label}, {latency} ms")
        self._render_state()
        self._set_hint(t(self.lang, "data_flow_ok"))
        self._refresh_metrics()
        self._start_monitor()
        self._speed_start()
        self._update_tray()

    def _connect_failed(self, exc: Exception) -> None:
        self._busy = False
        self.state = STATE_IDLE
        self.current_server = self.current_server
        key = getattr(exc, "key", None)
        if key:
            text = t(self.lang, key)
            detail = getattr(exc, "detail", "") or ""
            if detail:
                first_line = detail.strip().splitlines()[-1][:200]
                text = f"{text} \u2014 {first_line}"
        else:
            text = f"{t(self.lang, 'xray_error')}: {exc}"
        self._log(f"connect failed: {text}")
        self._speed_reset()
        self._set_hint(text)
        self._render_state()
        self._update_tray()

    def _disconnect_async(self, quiet: bool = False) -> None:
        if self._busy or self.state == STATE_IDLE:
            return
        self._busy = True
        self._stop_monitor()
        self._speed_reset()
        self._set_state(STATE_DISCONNECTING)
        self._log("disconnecting")
        task = self._start_task(self.client.disconnect)
        task.ok.connect(
            lambda _result=None: self._disconnected(quiet),
            Qt.ConnectionType.QueuedConnection,
        )
        task.err.connect(
            lambda exc: self._disconnected(quiet),
            Qt.ConnectionType.QueuedConnection,
        )

    def _disconnected(self, quiet: bool) -> None:
        self._busy = False
        self.state = STATE_IDLE
        self.connected_at = None
        self.delay_chip.setVisible(False)
        self.uptime_chip.setVisible(False)
        self._speed_reset()
        self._render_state()
        if not quiet:
            self._set_hint(t(self.lang, "disconnected_info"))
        self._log("disconnected")
        self._update_tray()

    def _reconnect(self, server: VpnServer) -> None:
        if self._busy:
            return
        self._busy = True
        self._stop_monitor()
        self._speed_reset()
        self._set_state(STATE_DISCONNECTING)
        task = self._start_task(self.client.disconnect)
        task.ok.connect(
            lambda _result=None: self._reconnect_after_stop(server),
            Qt.ConnectionType.QueuedConnection,
        )
        task.err.connect(
            lambda exc: self._reconnect_after_stop(server),
            Qt.ConnectionType.QueuedConnection,
        )

    def _reconnect_after_stop(self, server: VpnServer) -> None:
        self._busy = False
        self.state = STATE_IDLE
        self.connected_at = None
        self.current_server = server
        self._connect_async()

    def _start_monitor(self) -> None:
        interval = int(self.store.get_setting("probe_interval", 20)) * 1000
        self._timers["monitor"].start(max(5000, interval))

    def _stop_monitor(self) -> None:
        self._timers["monitor"].stop()
        self._monitoring = False

    def _monitor_tick(self) -> None:
        if self.state != STATE_CONNECTED or self._monitoring:
            return
        self._monitoring = True
        socks = int(self.store.get_setting("socks_port", 10808))

        task = self._start_task(lambda: probe_with_latency(socks, 4000))
        task.ok.connect(self._monitor_result, Qt.ConnectionType.QueuedConnection)
        task.err.connect(
            lambda exc: self._monitor_result(None), Qt.ConnectionType.QueuedConnection
        )

    def _monitor_result(self, latency: Optional[int]) -> None:
        self._monitoring = False
        if self.state != STATE_CONNECTED:
            return
        if latency is None:
            self._log("tunnel probe failed; disconnecting")
            self._set_hint(t(self.lang, "auto_disconnect"))
            if self.tray.isVisible():
                self.tray.showMessage(
                    "SchnellVPN",
                    t(self.lang, "auto_disconnect"),
                    QSystemTrayIcon.MessageIcon.Warning,
                    4000,
                )
            self._disconnect_async(quiet=True)
            return
        if self.current_server:
            self.delays[self.current_server.id] = int(latency)
        self._refresh_metrics()

    # ------------------------------------------------------------------ state / chips

    def _set_state(self, state: str) -> None:
        self.state = state
        self._render_state()
        self._update_tray()

    def _render_state(self) -> None:
        if not hasattr(self, "connect_button"):
            return
        if self.state == STATE_CONNECTED and self.current_server:
            status = t(self.lang, "connected_to", name=self.current_server.name)
            dot = SUCCESS
            button = t(self.lang, "disconnect")
            enabled = True
            self.delay_chip.setVisible(True)
            self.uptime_chip.setVisible(True)
        elif self.state == STATE_CONNECTING:
            status = t(self.lang, "connecting")
            dot = WARN
            button = t(self.lang, "connecting")
            enabled = False
            self.delay_chip.setVisible(False)
            self.uptime_chip.setVisible(False)
        elif self.state == STATE_DISCONNECTING:
            status = t(self.lang, "disconnecting")
            dot = WARN
            button = t(self.lang, "disconnecting")
            enabled = False
        else:
            status = t(self.lang, "status_idle")
            dot = "#5B6B80"
            button = t(self.lang, "connect")
            enabled = not self._busy
            self.delay_chip.setVisible(False)
            self.uptime_chip.setVisible(False)

        self.status_label.setText(status)
        self.status_dot.setStyleSheet(f"color: {dot}; font-size: 13px;")
        self.connect_button.setText(button)
        self.connect_button.setEnabled(enabled)
        self.connect_button.setProperty("state", "on" if self.state == STATE_CONNECTED else "off")
        self.connect_button.style().unpolish(self.connect_button)
        self.connect_button.style().polish(self.connect_button)
        self._update_proxy_chip()

    def _refresh_metrics(self) -> None:
        server = self.current_server
        if server:
            latency = self.delays.get(server.id)
            if latency is not None:
                self.delay_chip.setText(f"{t(self.lang, 'latency')}: {self._fmt_ms(latency)}")
                self.delay_chip.setObjectName("chipOk" if self.state == STATE_CONNECTED else "chip")
                self.delay_chip.style().unpolish(self.delay_chip)
                self.delay_chip.style().polish(self.delay_chip)
        self._update_uptime()
        self._refresh_table_metrics()

    def _refresh_table_metrics(self) -> None:
        profile = self.store.profile
        for row, server in enumerate(profile.servers):
            ping_item = self.table.item(row, 2)
            delay_item = self.table.item(row, 3)
            if ping_item is None or delay_item is None:
                continue
            ping_item.setText(self._fmt_ms(self.pings.get(server.id)))
            ping_item.setForeground(self._ms_color(self.pings.get(server.id)))
            delay_item.setText(self._fmt_ms(self.delays.get(server.id)))
            delay_item.setForeground(self._ms_color(self.delays.get(server.id)))

    def _update_uptime(self) -> None:
        if self.state != STATE_CONNECTED or self.connected_at is None:
            return
        seconds = int(time.monotonic() - self.connected_at)
        self.uptime_chip.setText(f"{t(self.lang, 'uptime')}: {self._format_duration(seconds)}")

    @staticmethod
    def _format_duration(seconds: int) -> str:
        hours, remainder = divmod(seconds, 3600)
        minutes, secs = divmod(remainder, 60)
        if hours:
            return f"{hours:02d}:{minutes:02d}:{secs:02d}"
        return f"{minutes:02d}:{secs:02d}"

    def _update_proxy_chip(self) -> None:
        if not hasattr(self, "proxy_chip"):
            return
        if self.state == STATE_CONNECTED and self.store.get_setting("use_system_proxy", True):
            port = int(self.store.get_setting("http_port", 10809))
            self.proxy_chip.setText(t(self.lang, "proxy_on", port=port))
            self.proxy_chip.setObjectName("chipOk")
        else:
            self.proxy_chip.setText(t(self.lang, "proxy_off"))
            self.proxy_chip.setObjectName("chip")
        self.proxy_chip.style().unpolish(self.proxy_chip)
        self.proxy_chip.style().polish(self.proxy_chip)

    def _update_engine_chip(self) -> None:
        binary = find_binary()
        if binary:
            self.engine_chip.setText(f"{t(self.lang, 'engine')}: {t(self.lang, 'engine_found')}")
            self.engine_chip.setObjectName("chipOk")
        else:
            self.engine_chip.setText(f"{t(self.lang, 'engine')}: {t(self.lang, 'engine_missing')}")
            self.engine_chip.setObjectName("chipBad")
        self.engine_chip.style().unpolish(self.engine_chip)
        self.engine_chip.style().polish(self.engine_chip)

    def _set_hint(self, text: str) -> None:
        self.hint_label.setText(text)

    def _log(self, message: str) -> None:
        stamp = datetime.now().strftime("%H:%M:%S")
        self._events.append(f"[{stamp}] {message}")

    # ------------------------------------------------------------------ dialogs

    def _open_settings(self) -> None:
        dialog = SettingsDialog(
            self.lang,
            self.store.settings,
            self,
            version=__version__,
            on_check_updates=self._check_update,
        )
        if dialog.exec() != dialog.DialogCode.Accepted:
            return
        values = dialog.values()
        language_changed = values["language"] != self.lang
        theme_changed = values["theme"] != self.store.get_setting("theme", "dark")
        for key, value in values.items():
            self.store.settings[key] = value
        self.store.save()
        if theme_changed:
            self._apply_stylesheet()
            self._update_theme_button()
        if language_changed:
            self.apply_language(values["language"], persist=False)
        if self.state == STATE_CONNECTED:
            self._set_hint(t(self.lang, "hint_ready"))

    def _open_logs(self) -> None:
        def fetch() -> str:
            app_lines = "\n".join(self._events)
            engine = self.client.logs(20000)
            return f"{app_lines}\n\n----- xray -----\n{engine}"

        LogsDialog(self.lang, fetch, self).exec()

    def _open_about(self) -> None:
        AboutDialog(self.lang, __version__, self).exec()

    # ------------------------------------------------------------------ tray / lifecycle

    def _tray_activated(self, reason) -> None:
        if reason in (
            QSystemTrayIcon.ActivationReason.Trigger,
            QSystemTrayIcon.ActivationReason.DoubleClick,
        ):
            self._show_window()

    def _show_window(self) -> None:
        self.showNormal()
        self.raise_()
        self.activateWindow()

    def _update_tray(self) -> None:
        if self.state == STATE_CONNECTED and self.current_server:
            self.tray.setToolTip(f"SchnellVPN \u00b7 {t(self.lang, 'connected_to', name=self.current_server.name)}")
            self.tray_connect_action.setText(t(self.lang, "disconnect"))
        else:
            self.tray.setToolTip("SchnellVPN")
            self.tray_connect_action.setText(t(self.lang, "connect"))

    def _quit(self) -> None:
        self._quitting = True
        self.shutdown()
        QApplication.quit()

    def shutdown(self) -> None:
        self._stop_monitor()
        self._timers["uptime"].stop()
        self._timers["speed"].stop()
        try:
            if self.client.runner.running or self.client.connected:
                self.client.disconnect()
        except Exception as exc:  # noqa: BLE001 - best effort on exit
            self._log(f"shutdown error: {exc}")
        self.tray.hide()

    def closeEvent(self, event) -> None:  # noqa: N802 - Qt override
        if self._quitting:
            self.shutdown()
            event.accept()
            return
        if self.store.get_setting("minimize_to_tray", True):
            event.ignore()
            self.hide()
            if not self._tray_hint_shown and self.tray.isVisible():
                self._tray_hint_shown = True
                self.tray.showMessage(
                    "SchnellVPN",
                    t(self.lang, "tray_hint"),
                    QSystemTrayIcon.MessageIcon.Information,
                    3000,
                )
            return
        self.shutdown()
        event.accept()

    # ------------------------------------------------------------------ helpers

    def _start_task(self, fn: Callable[[], object]) -> Task:
        task = Task(fn, self)
        task.ok.connect(lambda _result: None, Qt.ConnectionType.QueuedConnection)
        self._tasks.append(task)
        if len(self._tasks) > 64:
            self._tasks = [t for t in self._tasks if t is not None][-32:]
        task.start()
        return task
