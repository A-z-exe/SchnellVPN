"""Qt stylesheets and the generated app icon."""

from __future__ import annotations

import tempfile
from pathlib import Path
from typing import Optional

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
    pm.fill(Qt.transparent)
    painter = QPainter(pm)
    painter.setRenderHint(QPainter.Antialiasing)
    pen = QPen(tint)
    pen.setWidthF(max(1.6, size * 0.11))
    pen.setCapStyle(Qt.RoundCap)
    pen.setJoinStyle(Qt.RoundJoin)
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


def gear_icon(size: int = 18, color: QColor | None = None) -> QPixmap:
    """Cogwheel glyph drawn with a painter; no icon font required."""
    tint = color or QColor("#E6EDF3")
    pm = QPixmap(size, size)
    pm.fill(Qt.transparent)
    painter = QPainter(pm)
    painter.setRenderHint(QPainter.Antialiasing)
    painter.setPen(Qt.NoPen)
    painter.setBrush(tint)
    center = size / 2
    tooth_w = size * 0.20
    tooth_top = -size * 0.47
    tooth_h = size * 0.20
    radius = size * 0.045
    for index in range(8):
        painter.save()
        painter.translate(center, center)
        painter.rotate(index * 45)
        painter.drawRoundedRect(
            QRectF(-tooth_w / 2, tooth_top, tooth_w, tooth_h), radius, radius
        )
        painter.restore()
    ring = QPainterPath()
    ring.setFillRule(Qt.OddEvenFill)
    ring.addEllipse(QPointF(center, center), size * 0.33, size * 0.33)
    ring.addEllipse(QPointF(center, center), size * 0.135, size * 0.135)
    painter.drawPath(ring)
    painter.end()
    return pm

DARK_QSS = """
QWidget#root { background: #070B12; }
QWidget { color: #E6EDF3; font-size: 13px; }
QLabel { background: transparent; }
QLabel#title { font-size: 20px; font-weight: 700; letter-spacing: 0.3px; }
QLabel#version { color: #8496AC; font-size: 10px; font-weight: 600;
                 border: 1px solid #1B2634; border-radius: 8px; padding: 0 6px; margin-bottom: 2px; }
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
QTreeWidget { background: transparent; border: none; outline: none;
              selection-background-color: transparent; }
QTreeWidget::item { background: transparent; color: #D7E3F1;
                    border-bottom: 1px solid #131D2A; padding: 6px 4px; }
QTreeWidget::item:selected { background: #122234; color: #E6EDF3; }
QTreeWidget::branch { background: transparent; }
QTreeWidget::branch:has-children:!has-siblings:closed,
QTreeWidget::branch:closed:has-children:has-siblings { border-image: none; image: url("__ARROW_RIGHT__"); }
QTreeWidget::branch:open:has-children:!has-siblings,
QTreeWidget::branch:open:has-children:has-siblings { border-image: none; image: url("__ARROW__"); }
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
QTabWidget::pane { border: 1px solid #1A2533; border-radius: 12px; margin-top: 6px; }
QTabBar::tab { background: transparent; color: #8496AC; padding: 7px 16px;
               border: none; font-size: 12px; }
QTabBar::tab:hover { color: #C4D2E3; }
QTabBar::tab:selected { color: #E6EDF3; border-bottom: 2px solid #22D3EE; }
QToolTip { background: #0E1520; color: #E6EDF3; border: 1px solid #243246; padding: 5px; }
QMessageBox { background: #0B121C; }
QStatusBar { background: transparent; color: #7C8CA2; }
"""

LIGHT_QSS = """
QWidget#root { background: #F2F5F9; }
QWidget { color: #0F172A; font-size: 13px; }
QLabel { background: transparent; }
QLabel#title { font-size: 20px; font-weight: 700; }
QLabel#version { color: #64748B; font-size: 10px; font-weight: 600;
                 border: 1px solid #D3DCE7; border-radius: 8px; padding: 0 6px; margin-bottom: 2px; }
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
QTreeWidget { background: transparent; border: none; outline: none; }
QTreeWidget::item { background: transparent; color: #1E293B;
                    border-bottom: 1px solid #E7EDF5; padding: 6px 4px; }
QTreeWidget::item:selected { background: #E0F2FE; color: #0F172A; }
QTreeWidget::branch { background: transparent; }
QTreeWidget::branch:has-children:!has-siblings:closed,
QTreeWidget::branch:closed:has-children:has-siblings { border-image: none; image: url("__ARROW_RIGHT__"); }
QTreeWidget::branch:open:has-children:!has-siblings,
QTreeWidget::branch:open:has-children:has-siblings { border-image: none; image: url("__ARROW__"); }
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
QTabWidget::pane { border: 1px solid #DCE4EE; border-radius: 12px; margin-top: 6px; }
QTabBar::tab { background: transparent; color: #64748B; padding: 7px 16px;
               border: none; font-size: 12px; }
QTabBar::tab:hover { color: #334155; }
QTabBar::tab:selected { color: #0F172A; border-bottom: 2px solid #2563EB; }
"""


def _arrow_png(color: str, direction: str = "down") -> str:
    """QSS can only reference images by path, so draw the arrow once."""
    target = Path(tempfile.gettempdir()) / (
        f"schnellvpn-arrow-v2-{direction}-{color.lstrip('#')}.png"
    )
    if not target.is_file():
        pixmap = QPixmap(20, 12)
        pixmap.fill(Qt.transparent)
        painter = QPainter(pixmap)
        painter.setRenderHint(QPainter.Antialiasing)
        painter.setPen(Qt.NoPen)
        painter.setBrush(QColor(color))
        head = QPainterPath()
        if direction == "right":
            head.moveTo(QPointF(5, 2))
            head.lineTo(QPointF(5, 10))
            head.lineTo(QPointF(12, 6))
        else:
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
    return qss.replace("__ARROW__", _arrow_png(arrow_color)).replace(
        "__ARROW_RIGHT__", _arrow_png(arrow_color, "right")
    )


def make_logo(size: int = 128, green: bool = False) -> QPixmap:
    """The launcher/app icon: white shield, orange bolt, cyan ring.

    Uses the real PNG when it is committed in the repo (so CI builds get the
    same icon); falls back to the vector-drawn version for bare source trees.
    `green=True` draws the connected-state shield in green.
    """
    import sys

    if green:
        name = "schnellvpn_connected.png"
        roots = [Path(__file__).resolve().parents[2]]
        bundle = getattr(sys, "_MEIPASS", None)
        if bundle:
            roots.insert(0, Path(bundle))
        for root in roots:
            for candidate in (
                root / "resources" / name,
                root / "windows" / "resources" / name,
            ):
                if candidate.is_file():
                    loaded = QPixmap(str(candidate))
                    if not loaded.isNull():
                        return loaded.scaled(
                            size, size, Qt.KeepAspectRatio, Qt.SmoothTransformation
                        )
        # Fall through to the vector drawing with a green tint.

    roots = [Path(__file__).resolve().parents[2]]
    bundle = getattr(sys, "_MEIPASS", None)
    if bundle:
        roots.insert(0, Path(bundle))
    candidates = [root / "docs" / "icon.png" for root in roots]
    candidates += [root / "resources" / "schnellvpn.png" for root in roots]
    for path in candidates:
        if path.is_file():
            loaded = QPixmap(str(path))
            if not loaded.isNull():
                return loaded.scaled(
                    size, size, Qt.KeepAspectRatio, Qt.SmoothTransformation
                )

    pixmap = QPixmap(size, size)
    pixmap.fill(Qt.transparent)
    painter = QPainter(pixmap)
    painter.setRenderHint(QPainter.Antialiasing)
    unit = size / 100.0

    def point(x: float, y: float) -> QPointF:
        return QPointF(x * unit, y * unit)

    tile = QPainterPath()
    tile.addRoundedRect(
        QRectF(0, 0, size, size), 22 * unit, 22 * unit
    )
    gradient = QLinearGradient(0, 0, 0, size)
    if green:
        gradient.setColorAt(0.0, QColor("#0E5A2A"))
        gradient.setColorAt(1.0, QColor("#0A3D1E"))
    else:
        gradient.setColorAt(0.0, QColor("#0B192E"))
        gradient.setColorAt(1.0, QColor("#0F263E"))
    painter.setPen(Qt.NoPen)
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
    painter.setBrush(QColor("#E8F9EE" if green else "#FFFFFF"))
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


def _icon_from(name: str) -> Optional[QIcon]:
    import sys

    roots = [Path(__file__).resolve().parents[2]]
    bundle = getattr(sys, "_MEIPASS", None)
    if bundle:
        roots.insert(0, Path(bundle))
    candidates = [root / "resources" / name for root in roots]
    candidates += [root / "windows" / "resources" / name for root in roots]
    if name == "schnellvpn.png":
        candidates += [root / "docs" / "icon.png" for root in roots]
    for path in candidates:
        if path.is_file():
            pixmap = QPixmap(str(path))
            if not pixmap.isNull():
                icon = QIcon()
                for size in (16, 24, 32, 48, 64, 128, 256):
                    icon.addPixmap(
                        pixmap.scaled(
                            size, size, Qt.KeepAspectRatio, Qt.SmoothTransformation
                        )
                    )
                return icon
    return None


def app_icon() -> QIcon:
    """Default (disconnected) icon: blue shield."""
    return _icon_from("schnellvpn.png") or _drawn_icon()


def connected_icon() -> QIcon:
    """Connected-state icon: green shield (tray + header + notifications).

    Always returns a *green* icon, even in an older bundle that does not ship
    the green PNG: the fallback recolours the drawn shield at runtime so the
    tray never stays blue while connected.
    """
    return _icon_from("schnellvpn_connected.png") or _drawn_icon(green=True)


def _drawn_icon(green: bool = False) -> QIcon:
    icon = QIcon()
    for size in (16, 24, 32, 48, 64, 128, 256):
        pixmap = make_logo(size, green=green)
        icon.addPixmap(pixmap)
    return icon
