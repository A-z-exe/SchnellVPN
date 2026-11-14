"""Small live-throughput chart for the status card."""

from __future__ import annotations

from collections import deque
from typing import Deque

from PySide6.QtCore import QPointF, QRectF, Qt
from PySide6.QtGui import (
    QColor,
    QFont,
    QLinearGradient,
    QPainter,
    QPainterPath,
    QPen,
)
from PySide6.QtWidgets import QWidget

from ..core.speedstats import format_rate

POINTS = 60

THEMES = {
    "dark": {
        "grid": QColor("#16202E"),
        "down": QColor("#34D399"),
        "up": QColor("#22D3EE"),
        "text": QColor("#6B7A90"),
    },
    "light": {
        "grid": QColor("#E2E8F0"),
        "down": QColor("#059669"),
        "up": QColor("#0891B2"),
        "text": QColor("#64748B"),
    },
}


class SpeedChart(QWidget):
    """Plots the last minute of download/upload speed in KB/s."""

    def __init__(self, parent: QWidget | None = None):
        super().__init__(parent)
        self.download: Deque[float] = deque([0.0] * POINTS, maxlen=POINTS)
        self.upload: Deque[float] = deque([0.0] * POINTS, maxlen=POINTS)
        self._theme = "dark"
        self._idle = True
        self.setMinimumHeight(96)
        self.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents, True)

    def set_theme(self, theme: str) -> None:
        self._theme = theme if theme in THEMES else "dark"
        self.update()

    def clear(self) -> None:
        self.download = deque([0.0] * POINTS, maxlen=POINTS)
        self.upload = deque([0.0] * POINTS, maxlen=POINTS)
        self._idle = True
        self.update()

    def push(self, down_bps: float, up_bps: float) -> None:
        self._idle = False
        self.download.append(max(0.0, down_bps) / 1024.0)
        self.upload.append(max(0.0, up_bps) / 1024.0)
        self.update()

    def paintEvent(self, _event) -> None:  # noqa: N802 - Qt override
        colors = THEMES[self._theme]
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        area = QRectF(self.rect()).adjusted(4, 6, -4, -6)
        if area.width() <= 2 or area.height() <= 2:
            painter.end()
            return

        grid_pen = QPen(colors["grid"])
        grid_pen.setWidthF(1.0)
        painter.setPen(grid_pen)
        for step in range(4):
            y = area.top() + area.height() * step / 3.0
            painter.drawLine(QPointF(area.left(), y), QPointF(area.right(), y))

        peak = max(max(self.download), max(self.upload))
        scale = max(32.0, peak * 1.15)
        if not self._idle:
            painter.setPen(colors["text"])
            font = QFont(self.font())
            font.setPointSizeF(7.5)
            painter.setFont(font)
            painter.drawText(
                QRectF(area.left(), area.top(), area.width(), 14),
                Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignTop,
                format_rate(scale * 1024),
            )

        for series, color, fill in (
            (self.download, colors["down"], True),
            (self.upload, colors["up"], False),
        ):
            line = self._series_path(series, area, scale)
            if fill:
                filled = QPainterPath(line)
                filled.lineTo(area.right(), area.bottom())
                filled.lineTo(area.left(), area.bottom())
                filled.closeSubpath()
                painter.setPen(Qt.PenStyle.NoPen)
                gradient = QLinearGradient(0, area.top(), 0, area.bottom())
                top = QColor(color)
                top.setAlpha(70)
                bottom = QColor(color)
                bottom.setAlpha(0)
                gradient.setColorAt(0.0, top)
                gradient.setColorAt(1.0, bottom)
                painter.setBrush(gradient)
                painter.drawPath(filled)
            pen = QPen(color)
            pen.setWidthF(1.8)
            pen.setCapStyle(Qt.PenCapStyle.RoundCap)
            pen.setJoinStyle(Qt.PenJoinStyle.RoundJoin)
            painter.setPen(pen)
            painter.setBrush(Qt.BrushStyle.NoBrush)
            painter.drawPath(line)
        painter.end()

    @staticmethod
    def _series_path(values: Deque[float], area: QRectF, scale: float) -> QPainterPath:
        count = len(values)
        step = area.width() / max(1, count - 1)
        path = QPainterPath()
        for index, value in enumerate(values):
            x = area.left() + index * step
            y = area.bottom() - min(value / scale, 1.0) * area.height()
            if index == 0:
                path.moveTo(x, y)
            else:
                path.lineTo(x, y)
        return path
