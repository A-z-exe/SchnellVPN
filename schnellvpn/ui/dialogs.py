"""Dialogs: import, server editor, settings, logs, share and about."""

from __future__ import annotations

import io
import platform
import re
from typing import Callable, Optional

import segno
from PySide6.QtCore import Qt, QTimer, QUrl, QUrlQuery
from PySide6.QtGui import QColor, QDesktopServices, QIntValidator, QPixmap
from PySide6.QtWidgets import (
    QAbstractItemView,
    QApplication,
    QCheckBox,
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QFormLayout,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QLineEdit,
    QPlainTextEdit,
    QPushButton,
    QTableWidget,
    QTableWidgetItem,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)

from ..core.links import LinkError, parse_outbound
from ..core.models import VpnServer
from ..core.runner import app_data_dir, find_binary
from .i18n import LANGS, t

REPORT_EMAIL = "amirHosseinzarei.org@gmail.com"
ACCEPT_RE = re.compile(
    r"(\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2})\S*\s+from\s+(\S+)\s+"
    r"accepted\s+(tcp|udp):(\S+)"
)
MAX_CONNECTIONS = 500


def link_style() -> str:
    """Mid-blue that keeps readable contrast on both themes."""
    return 'style="color: #3B82F6;"'


class BaseDialog(QDialog):
    def __init__(self, lang: str, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.lang = lang
        self.setModal(True)
        self.setMinimumWidth(440)

    def tr_(self, key: str, **values) -> str:
        return t(self.lang, key, **values)


def _buttons(dialog: QDialog, lang: str, save_key: str = "save") -> QDialogButtonBox:
    box = QDialogButtonBox()
    accept = box.addButton(t(lang, save_key), QDialogButtonBox.AcceptRole)
    cancel = box.addButton(t(lang, "cancel"), QDialogButtonBox.RejectRole)
    accept.setDefault(True)
    cancel.setAutoDefault(False)
    box.accepted.connect(dialog.accept)
    box.rejected.connect(dialog.reject)
    return box


class ImportDialog(BaseDialog):
    def __init__(self, lang: str, parent: Optional[QWidget] = None):
        super().__init__(lang, parent)
        self.setWindowTitle(self.tr_("import_title"))

        layout = QVBoxLayout(self)
        layout.setContentsMargins(20, 18, 20, 16)
        layout.setSpacing(10)

        hint = QLabel(self.tr_("import_hint"))
        hint.setWordWrap(True)
        layout.addWidget(hint)

        self.editor = QPlainTextEdit()
        self.editor.setLayoutDirection(Qt.LeftToRight)
        self.editor.setPlaceholderText(self.tr_("import_placeholder"))
        self.editor.setMinimumHeight(150)
        layout.addWidget(self.editor)

        self.error = QLabel("")
        self.error.setStyleSheet("color: #F87171; font-size: 11px;")
        self.error.setWordWrap(True)
        layout.addWidget(self.error)

        box = QDialogButtonBox()
        ok = box.addButton(self.tr_("import_button"), QDialogButtonBox.AcceptRole)
        cancel = box.addButton(self.tr_("cancel"), QDialogButtonBox.RejectRole)
        ok.setDefault(True)
        cancel.setAutoDefault(False)
        ok.clicked.connect(self._accept)
        cancel.clicked.connect(self.reject)
        layout.addWidget(box)

    def _accept(self) -> None:
        if not self.text().strip():
            self.error.setText(self.tr_("invalid_url"))
            return
        self.accept()

    def text(self) -> str:
        return self.editor.toPlainText()


class EditProfileDialog(BaseDialog):
    """Create or edit a profile: its display name and its subscription URL."""

    def __init__(
        self,
        lang: str,
        name: str = "",
        url: str = "",
        editing: bool = False,
        parent: Optional[QWidget] = None,
    ):
        super().__init__(lang, parent)
        self.setWindowTitle(self.tr_("edit_profile" if editing else "new_profile"))
        self.setMinimumWidth(480)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(20, 18, 20, 16)
        layout.setSpacing(10)

        form = QFormLayout()
        form.setSpacing(10)
        self.name_edit = QLineEdit(name)
        self.name_edit.setPlaceholderText(self.tr_("profile_name"))
        self.url_edit = QPlainTextEdit(url)
        self.url_edit.setLayoutDirection(Qt.LeftToRight)
        self.url_edit.setMinimumHeight(70)
        self.url_edit.setPlaceholderText(self.tr_("subscription_url_hint"))
        form.addRow(self.tr_("profile_name"), self.name_edit)
        form.addRow(self.tr_("subscription_url"), self.url_edit)
        layout.addLayout(form)

        hint = QLabel(self.tr_("profile_url_hint"))
        hint.setObjectName("hint")
        hint.setWordWrap(True)
        layout.addWidget(hint)

        self.error = QLabel("")
        self.error.setStyleSheet("color: #F87171; font-size: 11px;")
        self.error.setWordWrap(True)
        layout.addWidget(self.error)
        layout.addWidget(_buttons(self, lang))

    def accept(self) -> None:
        name = self.name_edit.text().strip()
        url = self.url_edit.toPlainText().strip()
        if not name:
            self.error.setText(self.tr_("profile_name"))
            return
        if url and not url.lower().startswith(("http://", "https://")):
            self.error.setText(self.tr_("invalid_subscription_url"))
            return
        super().accept()

    def values(self) -> tuple[str, str]:
        return self.name_edit.text().strip(), self.url_edit.toPlainText().strip()


class EditServerDialog(BaseDialog):
    def __init__(self, lang: str, server: VpnServer, parent: Optional[QWidget] = None):
        super().__init__(lang, parent)
        self.server = server
        self.setWindowTitle(self.tr_("edit_server"))

        layout = QVBoxLayout(self)
        layout.setContentsMargins(20, 18, 20, 16)
        layout.setSpacing(10)
        form = QFormLayout()
        form.setSpacing(10)

        self.name_edit = QLineEdit(server.name)
        self.link_edit = QPlainTextEdit(server.link)
        self.link_edit.setLayoutDirection(Qt.LeftToRight)
        self.link_edit.setMinimumHeight(110)
        form.addRow(self.tr_("name"), self.name_edit)
        form.addRow(self.tr_("link"), self.link_edit)
        layout.addLayout(form)

        self.error = QLabel("")
        self.error.setStyleSheet("color: #F87171; font-size: 11px;")
        self.error.setWordWrap(True)
        layout.addWidget(self.error)
        layout.addWidget(_buttons(self, lang))

    def accept(self) -> None:
        link = self.link_edit.toPlainText().strip()
        if not link:
            self.error.setText(self.tr_("invalid_url"))
            return
        try:
            parse_outbound(link)
        except LinkError as exc:
            self.error.setText(str(exc))
            return
        self.server.name = self.name_edit.text().strip() or self.server.name
        self.server.link = link
        super().accept()


class SettingsDialog(BaseDialog):
    def __init__(
        self,
        lang: str,
        settings: dict,
        parent: Optional[QWidget] = None,
        version: str = "",
        on_check_updates: Optional[Callable[[], None]] = None,
    ):
        super().__init__(lang, parent)
        self.settings = dict(settings)
        self.version = version
        self.setWindowTitle(self.tr_("settings"))
        self.setMinimumWidth(500)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(20, 18, 20, 16)
        layout.setSpacing(12)

        tabs = QTabWidget()
        self.tabs = tabs
        tabs.addTab(self._build_general(), self.tr_("general"))
        tabs.addTab(self._build_about(on_check_updates), self.tr_("about"))
        layout.addWidget(tabs)
        layout.addWidget(_buttons(self, lang))

    def _build_general(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(16, 14, 16, 14)
        layout.setSpacing(10)

        settings = self.settings
        form = QFormLayout()
        form.setSpacing(10)

        self.language = QComboBox()
        labels = {"fa": "فارسی", "en": "English", "de": "Deutsch"}
        for code in LANGS:
            self.language.addItem(labels[code], code)
        index = self.language.findData(settings.get("language", "fa"))
        self.language.setCurrentIndex(max(0, index))

        self.theme = QComboBox()
        self.theme.addItem(self.tr_("dark"), "dark")
        self.theme.addItem(self.tr_("light"), "light")
        index = self.theme.findData(settings.get("theme", "dark"))
        self.theme.setCurrentIndex(max(0, index))

        self.socks = QLineEdit(str(settings.get("socks_port", 10808)))
        self.socks.setValidator(QIntValidator(1024, 65535, self))
        self.http = QLineEdit(str(settings.get("http_port", 10809)))
        self.http.setValidator(QIntValidator(1024, 65535, self))

        self.system_proxy = QCheckBox(self.tr_("use_system_proxy"))
        self.system_proxy.setChecked(bool(settings.get("use_system_proxy", True)))
        self.tray = QCheckBox(self.tr_("minimize_to_tray"))
        self.tray.setChecked(bool(settings.get("minimize_to_tray", True)))
        self.smart_connect = QCheckBox(self.tr_("smart_connect"))
        self.smart_connect.setChecked(bool(settings.get("smart_connect", False)))
        self.always_on = QCheckBox(self.tr_("always_on"))
        self.always_on.setChecked(bool(settings.get("always_on", True)))

        form.addRow(self.tr_("language"), self.language)
        form.addRow(self.tr_("theme"), self.theme)
        form.addRow(self.tr_("socks_port"), self.socks)
        form.addRow(self.tr_("http_port"), self.http)
        form.addRow("", self.system_proxy)
        form.addRow("", self.tray)
        form.addRow("", self.smart_connect)
        form.addRow("", self.always_on)
        layout.addLayout(form)

        binary = find_binary()
        engine_caption = QLabel(f"{self.tr_('engine')}:")
        engine_path = QLabel(str(binary) if binary else self.tr_("engine_missing"))
        engine_path.setLayoutDirection(Qt.LeftToRight)
        engine_path.setAlignment(Qt.AlignLeft | Qt.AlignVCenter)
        engine_path.setWordWrap(True)
        engine_path.setTextInteractionFlags(Qt.TextSelectableByMouse)
        for label in (engine_caption, engine_path):
            label.setStyleSheet("color: #7C8CA2; font-size: 11px;")
        layout.addWidget(engine_caption)
        layout.addWidget(engine_path)

        note = QLabel(self.tr_("startup_note"))
        note.setWordWrap(True)
        note.setStyleSheet("color: #7C8CA2; font-size: 11px;")
        layout.addWidget(note)

        folder_row = QHBoxLayout()
        open_folder = QPushButton(self.tr_("open_folder"))
        open_folder.clicked.connect(
            lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(str(app_data_dir())))
        )
        folder_row.addWidget(open_folder)
        folder_row.addStretch(1)
        layout.addLayout(folder_row)
        layout.addStretch(1)
        return page

    def _build_about(
        self, on_check_updates: Optional[Callable[[], None]]
    ) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(16, 14, 16, 14)
        layout.setSpacing(8)

        update_row = QHBoxLayout()
        version_label = QLabel(self.tr_("version", version=self.version))
        version_label.setStyleSheet("color: #7C8CA2; font-size: 11.5px;")
        update_row.addWidget(version_label)
        update_row.addStretch(1)
        check_update = QPushButton(self.tr_("check_update"))
        if on_check_updates is not None:
            check_update.clicked.connect(on_check_updates)
        else:
            check_update.setEnabled(False)
        update_row.addWidget(check_update)
        layout.addLayout(update_row)

        body = QLabel(self.tr_("about_body"))
        body.setWordWrap(True)
        layout.addWidget(body)

        engine = QLabel(f"{self.tr_('engine')}: Xray-core")
        engine.setStyleSheet("color: #7C8CA2; font-size: 11.5px;")
        engine.setLayoutDirection(Qt.LeftToRight)
        layout.addWidget(engine)

        developer = QLabel(
            f'{self.tr_("developer")}: '
            f'<a {link_style()} href="https://github.com/A-z-exe">'
            "A-z-exe (Amirhosseinzarei)</a>"
        )
        developer.setOpenExternalLinks(True)
        layout.addWidget(developer)

        support = QLabel(
            f'{self.tr_("support")}: '
            f'<a {link_style()} href="https://t.me/SchnellVPN">t.me/SchnellVPN</a>'
        )
        support.setOpenExternalLinks(True)
        layout.addWidget(support)

        email = QLabel(
            f'<a {link_style()} href="mailto:{REPORT_EMAIL}">{REPORT_EMAIL}</a>'
        )
        email.setOpenExternalLinks(True)
        email.setLayoutDirection(Qt.LeftToRight)
        layout.addWidget(email)

        repository = QLabel(
            f'<a {link_style()} href="https://github.com/A-z-exe/SchnellVPN">'
            "github.com/A-z-exe/SchnellVPN</a>"
        )
        repository.setOpenExternalLinks(True)
        layout.addWidget(repository)
        layout.addStretch(1)
        return page

    def values(self) -> dict:
        def port(edit: QLineEdit, fallback: int) -> int:
            text = edit.text().strip()
            return int(text) if text.isdigit() else fallback

        return {
            "language": self.language.currentData(),
            "theme": self.theme.currentData(),
            "socks_port": port(self.socks, int(self.settings.get("socks_port", 10808))),
            "http_port": port(self.http, int(self.settings.get("http_port", 10809))),
            "use_system_proxy": self.system_proxy.isChecked(),
            "minimize_to_tray": self.tray.isChecked(),
            "smart_connect": self.smart_connect.isChecked(),
            "always_on": self.always_on.isChecked(),
        }


class LogsDialog(BaseDialog):
    def __init__(
        self,
        lang: str,
        fetch_logs: Callable[[], str],
        parent: Optional[QWidget] = None,
        version: str = "",
    ):
        super().__init__(lang, parent)
        self.setWindowTitle(self.tr_("logs"))
        self.resize(780, 520)
        self._fetch = fetch_logs
        self.version = version
        self._seen: set[tuple[str, str, str, str]] = set()

        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 14, 16, 12)
        layout.setSpacing(8)

        self.tabs = QTabWidget()
        connections = QWidget()
        conn_layout = QVBoxLayout(connections)
        conn_layout.setContentsMargins(10, 10, 10, 10)
        self.conn_view = QTableWidget(0, 4)
        self.conn_view.setHorizontalHeaderLabels(
            [
                self.tr_("time"),
                self.tr_("network"),
                self.tr_("destination"),
                self.tr_("source"),
            ]
        )
        self.conn_view.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.conn_view.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.conn_view.setSelectionMode(QAbstractItemView.SingleSelection)
        self.conn_view.verticalHeader().setVisible(False)
        self.conn_view.verticalHeader().setDefaultSectionSize(30)
        self.conn_view.setFocusPolicy(Qt.NoFocus)
        header = self.conn_view.horizontalHeader()
        header.setSectionResizeMode(0, QHeaderView.ResizeToContents)
        header.setSectionResizeMode(1, QHeaderView.ResizeToContents)
        header.setSectionResizeMode(2, QHeaderView.Stretch)
        header.setSectionResizeMode(3, QHeaderView.ResizeToContents)
        header.setHighlightSections(False)
        conn_layout.addWidget(self.conn_view)
        self.conn_empty = QLabel(self.tr_("no_connections"))
        self.conn_empty.setObjectName("hint")
        self.conn_empty.setAlignment(Qt.AlignCenter)
        conn_layout.addWidget(self.conn_empty)
        self.tabs.addTab(connections, self.tr_("connections"))

        logs_page = QWidget()
        logs_layout = QVBoxLayout(logs_page)
        logs_layout.setContentsMargins(10, 10, 10, 10)
        self.view = QPlainTextEdit()
        self.view.setReadOnly(True)
        self.view.setLineWrapMode(QPlainTextEdit.NoWrap)
        self.view.setLayoutDirection(Qt.LeftToRight)
        font = self.view.font()
        font.setFamily("Cascadia Mono")
        font.setPointSize(9)
        self.view.setFont(font)
        logs_layout.addWidget(self.view)
        self.tabs.addTab(logs_page, self.tr_("logs"))
        layout.addWidget(self.tabs)

        hint = QLabel(self.tr_("report_hint"))
        hint.setObjectName("hint")
        hint.setWordWrap(True)
        layout.addWidget(hint)

        row = QHBoxLayout()
        report = QPushButton(self.tr_("report_bug"))
        report.clicked.connect(self._report)
        row.addWidget(report)
        copy = QPushButton(self.tr_("copy_logs"))
        copy.clicked.connect(self._copy_logs)
        row.addWidget(copy)
        row.addStretch(1)
        refresh = QPushButton(self.tr_("refresh"))
        refresh.clicked.connect(self.refresh)
        row.addWidget(refresh)
        close = QPushButton(self.tr_("close"))
        close.clicked.connect(self.accept)
        row.addWidget(close)
        layout.addLayout(row)

        self._feedback = QLabel("")
        self._feedback.setStyleSheet("color: #34D399; font-size: 11px;")
        self._feedback.setWordWrap(True)
        layout.addWidget(self._feedback)

        self._timer = QTimer(self)
        self._timer.timeout.connect(self.refresh)
        self._timer.start(2000)
        self.refresh()

    def refresh(self) -> None:
        text = self._fetch() or ""
        bar = self.view.verticalScrollBar()
        at_bottom = bar.value() >= bar.maximum() - 4
        self.view.setPlainText(text)
        if at_bottom:
            bar.setValue(bar.maximum())
        self._harvest(text)

    def _harvest(self, text: str) -> None:
        fresh: list[tuple[str, str, str, str]] = []
        for match in ACCEPT_RE.finditer(text):
            key = match.groups()
            if key in self._seen:
                continue
            self._seen.add(key)
            fresh.append(key)
        if not fresh:
            return
        for stamp, source, network, destination in fresh:
            self.conn_view.insertRow(0)
            values = (stamp[11:], network, destination, source)
            for column, value in enumerate(values):
                item = QTableWidgetItem(value)
                item.setTextAlignment(
                    Qt.AlignCenter
                    if column == 1
                    else Qt.AlignLeft | Qt.AlignVCenter
                )
                if column == 3:
                    item.setForeground(QColor("#7C8CA2"))
                self.conn_view.setItem(0, column, item)
        while self.conn_view.rowCount() > MAX_CONNECTIONS:
            self.conn_view.removeRow(self.conn_view.rowCount() - 1)
        self.conn_empty.setVisible(self.conn_view.rowCount() == 0)

    def _report_body(self) -> str:
        header = (
            f"SchnellVPN {self.version}\n"
            f"{platform.platform()}\n"
        )
        logs = self.view.toPlainText()
        return f"{header}\n----- logs (tail) -----\n{logs[-1600:]}"

    def _report(self) -> None:
        logs = self.view.toPlainText()
        body = self._report_body()
        app = QApplication.instance()
        if app is not None:
            app.clipboard().setText(
                f"SchnellVPN {self.version}\n{platform.platform()}\n\n"
                f"----- logs -----\n{logs}"
            )
        url = QUrl(f"mailto:{REPORT_EMAIL}")
        query = QUrlQuery()
        query.addQueryItem("subject", f"SchnellVPN {self.version} bug report")
        query.addQueryItem("body", body)
        url.setQuery(query)
        opened = QDesktopServices.openUrl(url)
        self._feedback.setText(
            self.tr_("report_opened" if opened else "report_copied", email=REPORT_EMAIL)
        )

    def _copy_logs(self) -> None:
        app = QApplication.instance()
        if app is not None:
            app.clipboard().setText(self.view.toPlainText())
        self._feedback.setText(self.tr_("copied"))


def qr_pixmap(text: str, size: int = 220) -> QPixmap:
    qr = segno.make(text, error="m")
    buffer = io.BytesIO()
    qr.save(buffer, kind="png", scale=8, border=2, dark="#0B1220", light="#FFFFFF")
    pixmap = QPixmap()
    pixmap.loadFromData(buffer.getvalue())
    return pixmap.scaled(
        size,
        size,
        Qt.KeepAspectRatio,
        Qt.SmoothTransformation,
    )


class ShareDialog(BaseDialog):
    def __init__(
        self,
        lang: str,
        text: str,
        copied_key: str = "copied",
        parent: Optional[QWidget] = None,
    ):
        super().__init__(lang, parent)
        self.setWindowTitle(self.tr_("share"))
        self.setMinimumWidth(360)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(20, 18, 20, 16)
        layout.setSpacing(12)

        qr = QLabel()
        qr.setAlignment(Qt.AlignCenter)
        qr.setPixmap(qr_pixmap(text))
        layout.addWidget(qr)

        link = QLabel(text)
        link.setWordWrap(True)
        link.setTextInteractionFlags(Qt.TextSelectableByMouse)
        link.setLayoutDirection(Qt.LeftToRight)
        link.setAlignment(Qt.AlignCenter)
        link.setStyleSheet("color: #7C8CA2; font-size: 11px;")
        layout.addWidget(link)

        row = QHBoxLayout()
        copy = QPushButton(self.tr_("copy_link"))
        copy.clicked.connect(self._copy)
        row.addWidget(copy)
        row.addStretch(1)
        close = QPushButton(self.tr_("close"))
        close.clicked.connect(self.accept)
        row.addWidget(close)
        layout.addLayout(row)
        self._text = text
        self._copied_key = copied_key
        self._feedback = QLabel("")
        self._feedback.setStyleSheet("color: #34D399; font-size: 11px;")
        self._feedback.setAlignment(Qt.AlignCenter)
        layout.addWidget(self._feedback)

    def _copy(self) -> None:
        app = QApplication.instance()
        if app is not None:
            app.clipboard().setText(self._text)
        self._feedback.setText(self.tr_(self._copied_key))


class AboutDialog(BaseDialog):
    def __init__(self, lang: str, version: str, parent: Optional[QWidget] = None):
        super().__init__(lang, parent)
        self.setWindowTitle(self.tr_("about"))
        self.setMinimumWidth(480)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 20, 24, 18)
        layout.setSpacing(8)

        title = QLabel("SchnellVPN")
        title.setObjectName("title")
        layout.addWidget(title)

        subtitle = QLabel(self.tr_("version", version=version))
        subtitle.setObjectName("subtitle")
        layout.addWidget(subtitle)

        body = QLabel(self.tr_("about_body"))
        body.setWordWrap(True)
        layout.addWidget(body)

        layout.addSpacing(6)
        engine = QLabel(f"{self.tr_('engine')}: Xray-core")
        engine.setStyleSheet("color: #7C8CA2; font-size: 11.5px;")
        engine.setLayoutDirection(Qt.LeftToRight)
        layout.addWidget(engine)

        developer = QLabel(
            f'{self.tr_("developer")}: '
            f'<a {link_style()} href="https://github.com/A-z-exe">'
            "A-z-exe (Amirhosseinzarei)</a>"
        )
        developer.setOpenExternalLinks(True)
        layout.addWidget(developer)

        support = QLabel(
            f'{self.tr_("support")}: '
            f'<a {link_style()} href="https://t.me/SchnellVPN">t.me/SchnellVPN</a>'
        )
        support.setOpenExternalLinks(True)
        layout.addWidget(support)

        repository = QLabel(
            f'<a {link_style()} href="https://github.com/A-z-exe/SchnellVPN">'
            "github.com/A-z-exe/SchnellVPN</a>"
        )
        repository.setOpenExternalLinks(True)
        layout.addWidget(repository)

        layout.addStretch(1)
        box = QDialogButtonBox()
        close = box.addButton(self.tr_("close"), QDialogButtonBox.AcceptRole)
        close.clicked.connect(self.accept)
        layout.addWidget(box)
