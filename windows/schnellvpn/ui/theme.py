"""Qt stylesheets and the generated app icon."""

from __future__ import annotations

import tempfile
from pathlib import Path

from PySide6.QtCore import QPointF, QRectF, Qt
from PySide6.QtGui import (
    QColor,
    QIcon,
    QLinearGradient,
    QPainter,
    QPainterPath,
    QPen,
    QPixmap,
    QRadialGradient,
)

ACCENT = "#22D3EE"
ACCENT_DARK = "#2563EB"
SUCCESS = "#34D399"
WARN = "#FBBF24"
DANGER = "#F87171"


def download_icon(size: int = 18, color: QColor | None = None) -> QPixmap:
    """Arrow-into-tray glyph, drawn instead of a font so stylesheets cannot
    override the typeface (Qt style sheets ignore widget setFont)."""
    tint = color or QColor("#E6EDF3")
    pm = QPixmap(size, size)
    pm.fill(Qt.GlobalColor.transparent)
    painter = QPainter(pm)
    painter.setRenderHint(QPainter.RenderHint.Antialiasing)
    pen = QPen(tint)
    pen.setWidthF(max(1.6, size * 0.11))
    pen.setCapStyle(Qt.PenCapStyle.RoundCap)
    pen.setJoinStyle(Qt.PenJoinStyle.RoundJoin)
    painter.setPen(pen)

    cx = size / 2
    top = size * 0.10
    tip = size * 0.60
    wing = size * 0.22
    painter.drawLine(QPointF(cx, top), QPointF(cx, tip))
    painter.drawLine(QPointF(cx - wing, tip - wing), QPointF(cx, tip))
    painter.drawLine(QPointF(cx + wing, tip - wing), QPointF(cx, tip))

    tray_y = size * 0.80
    half = size * 0.28
    tick = size * 0.14
    painter.drawLine(QPointF(cx - half, tray_y), QPointF(cx + half, tray_y))
    painter.drawLine(QPointF(cx - half, tray_y), QPointF(cx - half, tray_y - tick))
    painter.drawLine(QPointF(cx + half, tray_y), QPointF(cx + half, tray_y - tick))
    painter.end()
    return pm

DARK_QSS = """
QWidget#root { background: #070B12; }
QWidget { color: #E6EDF3; font-size: 13px; }
QLabel { background: transparent; }
QLabel#title { font-size: 20px; font-weight: 700; letter-spacing: 0.3px; }
QLabel#subtitle { color: #8496AC; font-size: 11px; }
QLabel#section { color: #8496AC; font-size: 11px; font-weight: 600;
                 letter-spacing: 1.2px; text-transform: uppercase; }
QLabel#hint { color: #7C8CA2; font-size: 11px; }
QLabel#status { font-size: 17px; font-weight: 700; }
QLabel#chip { background: #101A27; border: 1px solid #1B2634; border-radius: 10px;
              padding: 4px 10px; color: #A9BCD2; font-size: 11px; }
QLabel#chipOk { background: rgba(52, 211, 153, 0.10); border: 1px solid rgba(52, 211, 153, 0.35);
                border-radius: 10px; padding: 4px 10px; color: #6EE7B7; font-size: 11px; }
QLabel#chipBad { background: rgba(248, 113, 113, 0.10); border: 1px solid rgba(248, 113, 113, 0.35);
                 border-radius: 10px; padding: 4px 10px; color: #FCA5A5; font-size: 11px; }
QFrame#card { background: #0E1520; border: 1px solid #1A2533; border-radius: 18px; }
QFrame#divider { background: #16202E; max-height: 1px; border: none; }
QPushButton { background: #101A27; border: 1px solid #1D2A3A; border-radius: 11px;
              padding: 8px 14px; color: #D7E3F1; }
QPushButton:hover { background: #152233; border-color: #2A3B50; }
QPushButton:pressed { background: #0C141F; }
QPushButton:disabled { color: #55637A; border-color: #16202E; background: #0B121C; }
QPushButton#primary { background: qlineargradient(x1:0, y1:0, x2:1, y2:0,
                       stop:0 #2563EB, stop:1 #22D3EE); color: #04121F;
                       border: none; border-radius: 15px; font-size: 16px; font-weight: 700;
                       padding: 14px 22px; }
QPushButton#primary:hover { background: qlineargradient(x1:0, y1:0, x2:1, y2:0,
                       stop:0 #3B82F6, stop:1 #67E8F9); }
QPushButton#primary[state="on"] { background: qlineargradient(x1:0, y1:0, x2:1, y2:0,
                       stop:0 #B91C1C, stop:1 #F87171); color: #170708; }
QPushButton#primary[state="on"]:hover { background: qlineargradient(x1:0, y1:0, x2:1, y2:0,
                       stop:0 #DC2626, stop:1 #FCA5A5); }
QToolButton { background: #0E1520; border: 1px solid #1A2533; border-radius: 10px;
              padding: 6px 9px; color: #C4D2E3; }
QToolButton:hover { background: #14202F; border-color: #2A3B50; }
QToolButton::menu-indicator { image: none; }
QComboBox { background: #0E1520; border: 1px solid #1A2533; border-radius: 10px;
            padding: 6px 10px; color: #E6EDF3; min-height: 20px; }
QComboBox:hover { border-color: #2A3B50; }
QComboBox::drop-down { border: none; width: 22px; }
QComboBox::down-arrow { image: url("__ARROW__"); width: 10px; height: 6px; }
QComboBox QAbstractItemView { background: #0E1520; border: 1px solid #1A2533;
            selection-background-color: #16324A; padding: 4px; outline: none; }
QTableWidget { background: transparent; border: none; outline: none;
               gridline-color: transparent; selection-background-color: transparent; }
QTableWidget::item { border-bottom: 1px solid #131D2A; padding: 6px 4px; }
QTableWidget::item:selected { background: #122234; color: #E6EDF3; }
QHeaderView { background: transparent; border: none; }
QHeaderView::section { background: transparent; color: #76879D; border: none;
                       padding: 4px 6px; font-size: 11px; font-weight: 600; }
QTableCornerButton::section { background: transparent; border: none; }
QScrollBar:vertical { background: transparent; width: 8px; margin: 2px; }
QScrollBar::handle:vertical { background: #243246; border-radius: 4px; min-height: 30px; }
QScrollBar::handle:vertical:hover { background: #314560; }
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height: 0; }
QPlainTextEdit, QLineEdit, QSpinBox { background: #0A111B; border: 1px solid #1A2533;
            border-radius: 10px; padding: 7px 9px; color: #E6EDF3;
            selection-background-color: #1D4ED8; }
QPlainTextEdit:focus, QLineEdit:focus, QSpinBox:focus { border-color: #2563EB; }
QCheckBox { spacing: 8px; color: #C4D2E3; }
QCheckBox::indicator { width: 16px; height: 16px; border-radius: 5px;
            border: 1px solid #2A3B50; background: #0A111B; }
QCheckBox::indicator:checked { background: #22D3EE; border-color: #22D3EE; }
QDialog { background: #0B121C; }
QMenu { background: #0E1520; border: 1px solid #1A2533; border-radius: 10px; padding: 6px; }
QMenu::item { padding: 7px 22px; border-radius: 7px; }
QMenu::item:selected { background: #16324A; }
QMenu::separator { height: 1px; background: #1A2533; margin: 5px 8px; }
QToolTip { background: #0E1520; color: #E6EDF3; border: 1px solid #243246; padding: 5px; }
QMessageBox { background: #0B121C; }
QStatusBar { background: transparent; color: #7C8CA2; }
"""

LIGHT_QSS = """
QWidget#root { background: #F2F5F9; }
QWidget { color: #0F172A; font-size: 13px; }
QLabel { background: transparent; }
QLabel#title { font-size: 20px; font-weight: 700; }
QLabel#subtitle, QLabel#section, QLabel#hint { color: #64748B; font-size: 11px; }
QLabel#status { font-size: 17px; font-weight: 700; }
QLabel#chip { background: #E7EDF5; border: 1px solid #D3DCE7; border-radius: 10px;
              padding: 4px 10px; color: #475569; font-size: 11px; }
QLabel#chipOk { background: #DCFCE7; border: 1px solid #86EFAC; border-radius: 10px;
                padding: 4px 10px; color: #166534; font-size: 11px; }
QLabel#chipBad { background: #FEE2E2; border: 1px solid #FCA5A5; border-radius: 10px;
                 padding: 4px 10px; color: #991B1B; font-size: 11px; }
QFrame#card { background: #FFFFFF; border: 1px solid #DCE4EE; border-radius: 18px; }
QPushButton { background: #FFFFFF; border: 1px solid #D3DCE7; border-radius: 11px;
              padding: 8px 14px; color: #1E293B; }
QPushButton:hover { background: #F8FAFC; border-color: #B9C7D8; }
QPushButton#primary { background: qlineargradient(x1:0, y1:0, x2:1, y2:0,
                       stop:0 #2563EB, stop:1 #22D3EE); color: #04121F; border: none;
                       border-radius: 15px; font-size: 16px; font-weight: 700; padding: 14px 22px; }
QPushButton#primary[state="on"] { background: #EF4444; color: #FFFFFF; }
QToolButton { background: #FFFFFF; border: 1px solid #D3DCE7; border-radius: 10px;
              padding: 6px 9px; color: #334155; }
QComboBox, QLineEdit, QSpinBox, QPlainTextEdit { background: #FFFFFF; border: 1px solid #D3DCE7;
              border-radius: 10px; padding: 6px 10px; color: #0F172A; }
QComboBox::drop-down { border: none; width: 22px; }
QComboBox::down-arrow { image: url("__ARROW__"); width: 10px; height: 6px; }
QTableWidget { background: transparent; border: none; gridline-color: transparent; }
QTableWidget::item { border-bottom: 1px solid #E7EDF5; padding: 6px 4px; }
QTableWidget::item:selected { background: #E0F2FE; color: #0F172A; }
QHeaderView { background: transparent; border: none; }
QHeaderView::section { background: transparent; color: #64748B; border: none;
                       padding: 4px 6px; font-size: 11px; font-weight: 600; }
QTableCornerButton::section { background: transparent; border: none; }
QScrollBar:vertical { background: transparent; width: 8px; margin: 2px; }
QScrollBar::handle:vertical { background: #CBD5E1; border-radius: 4px; min-height: 30px; }
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height: 0; }
QDialog { background: #F8FAFC; }
QMenu { background: #FFFFFF; border: 1px solid #D3DCE7; border-radius: 10px; padding: 6px; }
QMenu::item { padding: 7px 22px; border-radius: 7px; }
QMenu::item:selected { background: #E0F2FE; }
"""


def _arrow_png(color: str) -> str:
    """QSS can only reference images by path, so draw the combo arrow once."""
    target = Path(tempfile.gettempdir()) / f"schnellvpn-arrow-v1-{color.lstrip('#')}.png"
    if not target.is_file():
        pixmap = QPixmap(20, 12)
        pixmap.fill(Qt.GlobalColor.transparent)
        painter = QPainter(pixmap)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(QColor(color))
        head = QPainterPath()
        head.moveTo(QPointF(5, 3))
        head.lineTo(QPointF(15, 3))
        head.lineTo(QPointF(10, 9))
        head.closeSubpath()
        painter.drawPath(head)
        painter.end()
        pixmap.save(str(target))
    return target.as_posix()


def stylesheet(theme: str) -> str:
    qss = LIGHT_QSS if theme == "light" else DARK_QSS
    arrow_color = "#64748B" if theme == "light" else "#8496AC"
    return qss.replace("__ARROW__", _arrow_png(arrow_color))


def make_logo(size: int = 128) -> QPixmap:
    """The Android launcher icon: navy tile, white shield, orange bolt."""
    pixmap = QPixmap(size, size)
    pixmap.fill(Qt.GlobalColor.transparent)
    painter = QPainter(pixmap)
    painter.setRenderHint(QPainter.RenderHint.Antialiasing)
    unit = size / 100.0

    def point(x: float, y: float) -> QPointF:
        return QPointF(x * unit, y * unit)

    tile = QPainterPath()
    tile.addRoundedRect(
        QRectF(0, 0, size, size), 22 * unit, 22 * unit
    )
    gradient = QLinearGradient(0, 0, 0, size)
    gradient.setColorAt(0.0, QColor("#0B192E"))
    gradient.setColorAt(1.0, QColor("#0F263E"))
    painter.setPen(Qt.PenStyle.NoPen)
    painter.setBrush(gradient)
    painter.drawPath(tile)

    glow = QRadialGradient(
        QPointF(74 * unit, 36 * unit), 58 * unit
    )
    glow.setColorAt(0.0, QColor(28, 71, 99, 110))
    glow.setColorAt(1.0, QColor(28, 71, 99, 0))
    painter.setBrush(glow)
    painter.drawPath(tile)

    shield = QPainterPath()
    shield.moveTo(point(50, 18.8))
    shield.lineTo(point(72.5, 30.8))
    shield.lineTo(point(72.5, 57.0))
    shield.lineTo(point(50, 82.0))
    shield.lineTo(point(27.5, 57.0))
    shield.lineTo(point(27.5, 30.8))
    shield.closeSubpath()
    painter.setBrush(QColor("#FFFFFF"))
    painter.drawPath(shield)

    bolt = QPainterPath()
    bolt.moveTo(point(55.7, 31.2))
    bolt.lineTo(point(40.6, 55.0))
    bolt.lineTo(point(49.5, 55.0))
    bolt.lineTo(point(44.3, 70.6))
    bolt.lineTo(point(60.4, 46.4))
    bolt.lineTo(point(50.4, 46.4))
    bolt.closeSubpath()
    painter.setBrush(QColor("#F5A623"))
    painter.drawPath(bolt)
    painter.end()
    return pixmap


def app_icon() -> QIcon:
    icon = QIcon()
    for size in (16, 24, 32, 48, 64, 128, 256):
        icon.addPixmap(make_logo(size))
    return icon
