"""Dialogs: import, server editor, settings, logs and about."""

from __future__ import annotations

from typing import Callable, Optional
from PySide6.QtCore import Qt, QTimer, QUrl
from PySide6.QtGui import QDesktopServices, QIntValidator
from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QFormLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPlainTextEdit,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from ..core.links import LinkError, parse_outbound
from ..core.models import VpnServer
from ..core.runner import app_data_dir, find_binary
from .i18n import LANGS, t


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
    accept = box.addButton(t(lang, save_key), QDialogButtonBox.ButtonRole.AcceptRole)
    cancel = box.addButton(t(lang, "cancel"), QDialogButtonBox.ButtonRole.RejectRole)
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
        self.editor.setLayoutDirection(Qt.LayoutDirection.LeftToRight)
        self.editor.setPlaceholderText(self.tr_("import_placeholder"))
        self.editor.setMinimumHeight(150)
        layout.addWidget(self.editor)

        self.error = QLabel("")
        self.error.setStyleSheet("color: #F87171; font-size: 11px;")
        self.error.setWordWrap(True)
        layout.addWidget(self.error)

        box = QDialogButtonBox()
        ok = box.addButton(self.tr_("import_button"), QDialogButtonBox.ButtonRole.AcceptRole)
        cancel = box.addButton(self.tr_("cancel"), QDialogButtonBox.ButtonRole.RejectRole)
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
        self.link_edit.setLayoutDirection(Qt.LayoutDirection.LeftToRight)
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
    def __init__(self, lang: str, settings: dict, parent: Optional[QWidget] = None):
        super().__init__(lang, parent)
        self.settings = dict(settings)
        self.setWindowTitle(self.tr_("settings"))
        self.setMinimumWidth(460)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(20, 18, 20, 16)
        layout.setSpacing(12)

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

        form.addRow(self.tr_("language"), self.language)
        form.addRow(self.tr_("theme"), self.theme)
        form.addRow(self.tr_("socks_port"), self.socks)
        form.addRow(self.tr_("http_port"), self.http)
        form.addRow("", self.system_proxy)
        form.addRow("", self.tray)
        layout.addLayout(form)

        binary = find_binary()
        engine_caption = QLabel(f"{self.tr_('engine')}:")
        engine_path = QLabel(str(binary) if binary else self.tr_("engine_missing"))
        engine_path.setLayoutDirection(Qt.LayoutDirection.LeftToRight)
        engine_path.setAlignment(Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
        engine_path.setWordWrap(True)
        engine_path.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
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

        layout.addWidget(_buttons(self, lang))

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
        }


class LogsDialog(BaseDialog):
    def __init__(
        self,
        lang: str,
        fetch_logs: Callable[[], str],
        parent: Optional[QWidget] = None,
    ):
        super().__init__(lang, parent)
        self.setWindowTitle(self.tr_("logs"))
        self.resize(720, 460)
        self._fetch = fetch_logs

        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 14, 16, 12)
        self.view = QPlainTextEdit()
        self.view.setReadOnly(True)
        self.view.setLineWrapMode(QPlainTextEdit.LineWrapMode.NoWrap)
        self.view.setLayoutDirection(Qt.LayoutDirection.LeftToRight)
        font = self.view.font()
        font.setFamily("Cascadia Mono")
        font.setPointSize(9)
        self.view.setFont(font)
        layout.addWidget(self.view)

        row = QHBoxLayout()
        refresh = QPushButton(self.tr_("refresh"))
        refresh.clicked.connect(self.refresh)
        row.addWidget(refresh)
        row.addStretch(1)
        close = QPushButton(self.tr_("close"))
        close.clicked.connect(self.accept)
        row.addWidget(close)
        layout.addLayout(row)

        self._timer = QTimer(self)
        self._timer.timeout.connect(self.refresh)
        self._timer.start(2000)
        self.refresh()

    def refresh(self) -> None:
        text = self._fetch()
        bar = self.view.verticalScrollBar()
        at_bottom = bar.value() >= bar.maximum() - 4
        self.view.setPlainText(text or "")
        if at_bottom:
            bar.setValue(bar.maximum())


class AboutDialog(BaseDialog):
    def __init__(self, lang: str, version: str, parent: Optional[QWidget] = None):
        super().__init__(lang, parent)
        self.setWindowTitle(self.tr_("about"))
        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 20, 24, 18)
        layout.setSpacing(8)

        title = QLabel("SchnellVPN")
        title.setObjectName("title")
        layout.addWidget(title)

        subtitle = QLabel(self.tr_("version", version=version))
        subtitle.setObjectName("subtitle")
        layout.addWidget(subtitle)

        body = QLabel(
            "Xray-core client for Windows.<br>"
            "VLESS (Reality / WS / gRPC / XHTTP), VMess, Trojan, Shadowsocks.<br><br>"
            '<a href="https://github.com/A-z-exe/SchnellVPN">github.com/A-z-exe/SchnellVPN</a>'
        )
        body.setOpenExternalLinks(True)
        body.setWordWrap(True)
        layout.addWidget(body)

        layout.addStretch(1)
        box = QDialogButtonBox()
        close = box.addButton(self.tr_("close"), QDialogButtonBox.ButtonRole.AcceptRole)
        close.clicked.connect(self.accept)
        layout.addWidget(box)
