"""탐색 막대: 재생 위치와 함께 '자막이 만들어진 구간'(초록)과 '처리 중인 구간'(노랑)을 보여 준다."""

from __future__ import annotations

from typing import List, Optional, Sequence, Tuple

from PySide6.QtCore import QPointF, QRectF, QSize, Qt, Signal
from PySide6.QtGui import QColor, QPainter
from PySide6.QtWidgets import QSizePolicy, QToolTip, QWidget

from ..subtitles import format_clock
from .theme import ACCENT, ACTIVE, COVERED


class SeekBar(QWidget):
    seekRequested = Signal(int)  # 밀리초

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self._duration_ms = 0
        self._position_ms = 0
        self._coverage: List[Tuple[float, float]] = []
        self._active: Optional[Tuple[float, float]] = None
        self._dragging = False
        self._hover = False
        self.setMouseTracking(True)
        self.setFocusPolicy(Qt.NoFocus)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        self.setMinimumWidth(120)
        self.setCursor(Qt.PointingHandCursor)

    def sizeHint(self) -> QSize:
        return QSize(400, 24)

    # -- 상태 -------------------------------------------------------------
    def set_duration(self, ms: int) -> None:
        self._duration_ms = max(0, int(ms))
        self.update()

    def set_position(self, ms: int) -> None:
        if not self._dragging:
            self._position_ms = max(0, int(ms))
            self.update()

    def set_coverage(self, intervals: Sequence[Sequence[float]], active: Optional[Sequence[float]] = None) -> None:
        self._coverage = [(float(a), float(b)) for a, b in intervals]
        self._active = (float(active[0]), float(active[1])) if active else None
        self.update()

    def is_dragging(self) -> bool:
        return self._dragging

    # -- 그리기 -----------------------------------------------------------
    def _groove(self) -> QRectF:
        h = 8.0 if (self._hover or self._dragging) else 6.0
        return QRectF(8, (self.height() - h) / 2, max(1.0, self.width() - 16), h)

    def _x_for(self, seconds: float, groove: QRectF) -> float:
        if self._duration_ms <= 0:
            return groove.left()
        frac = min(1.0, max(0.0, seconds * 1000.0 / self._duration_ms))
        return groove.left() + frac * groove.width()

    def paintEvent(self, event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        g = self._groove()
        p.setPen(Qt.NoPen)
        p.setBrush(QColor(255, 255, 255, 45))
        p.drawRoundedRect(g, g.height() / 2, g.height() / 2)
        if self._duration_ms > 0:
            p.setBrush(COVERED)
            for a, b in self._coverage:
                x1, x2 = self._x_for(a, g), self._x_for(b, g)
                if x2 - x1 >= 0.5:
                    p.drawRect(QRectF(x1, g.top(), x2 - x1, g.height()))
            if self._active:
                a, b = self._active
                x1, x2 = self._x_for(a, g), self._x_for(max(b, a), g)
                p.setBrush(ACTIVE)
                p.drawRect(QRectF(x1, g.top() - 1, max(2.0, x2 - x1), g.height() + 2))
            pos_x = self._x_for(self._position_ms / 1000.0, g)
            played = QColor(ACCENT)
            played.setAlpha(150)
            p.setBrush(played)
            p.drawRoundedRect(QRectF(g.left(), g.top() + g.height() * 0.3, pos_x - g.left(), g.height() * 0.4), 2, 2)
            r = 7.0 if (self._hover or self._dragging) else 6.0
            p.setBrush(QColor(255, 255, 255))
            p.drawEllipse(QPointF(pos_x, g.center().y()), r, r)
        p.end()

    # -- 마우스 -----------------------------------------------------------
    def _ms_at(self, x: float) -> int:
        g = self._groove()
        frac = min(1.0, max(0.0, (x - g.left()) / g.width()))
        return int(frac * self._duration_ms)

    def mousePressEvent(self, event) -> None:
        if event.button() == Qt.LeftButton and self._duration_ms > 0:
            self._dragging = True
            self._position_ms = self._ms_at(event.position().x())
            self.seekRequested.emit(self._position_ms)
            self.update()

    def mouseMoveEvent(self, event) -> None:
        if self._duration_ms <= 0:
            return
        ms = self._ms_at(event.position().x())
        if self._dragging:
            self._position_ms = ms
            self.seekRequested.emit(ms)
            self.update()
        sec = ms / 1000.0
        done = any(a <= sec < b for a, b in self._coverage)
        tip = f"{format_clock(sec)}  ·  {'자막 있음' if done else '자막 준비 전'}"
        QToolTip.showText(event.globalPosition().toPoint(), tip, self)

    def mouseReleaseEvent(self, event) -> None:
        if self._dragging and event.button() == Qt.LeftButton:
            self._dragging = False
            self.seekRequested.emit(self._ms_at(event.position().x()))
            self.update()

    def enterEvent(self, event) -> None:
        self._hover = True
        self.update()

    def leaveEvent(self, event) -> None:
        self._hover = False
        self.update()
