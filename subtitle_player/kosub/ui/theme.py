"""어두운 테마, 직접 그린 아이콘, 자막 글꼴."""

from __future__ import annotations

from PySide6.QtCore import QPointF, QRectF, Qt
from PySide6.QtGui import QColor, QFont, QIcon, QPainter, QPainterPath, QPalette, QPen, QPixmap, QPolygonF
from PySide6.QtWidgets import QApplication

ACCENT = QColor("#4fc3f7")
COVERED = QColor(76, 175, 80, 190)
ACTIVE = QColor(255, 193, 7, 220)

SUBTITLE_FONTS = [
    "Malgun Gothic",
    "맑은 고딕",
    "Apple SD Gothic Neo",
    "Noto Sans CJK KR",
    "Noto Sans KR",
    "NanumGothic",
    "Noto Sans CJK JP",
    "sans-serif",
]


def apply_dark_theme(app: QApplication) -> None:
    app.setStyle("Fusion")
    pal = QPalette()
    base = QColor(30, 30, 32)
    window = QColor(38, 38, 42)
    text = QColor(230, 230, 230)
    pal.setColor(QPalette.Window, window)
    pal.setColor(QPalette.WindowText, text)
    pal.setColor(QPalette.Base, base)
    pal.setColor(QPalette.AlternateBase, QColor(44, 44, 48))
    pal.setColor(QPalette.ToolTipBase, QColor(50, 50, 55))
    pal.setColor(QPalette.ToolTipText, text)
    pal.setColor(QPalette.Text, text)
    pal.setColor(QPalette.Button, QColor(48, 48, 52))
    pal.setColor(QPalette.ButtonText, text)
    pal.setColor(QPalette.BrightText, Qt.red)
    pal.setColor(QPalette.Highlight, QColor(41, 121, 255))
    pal.setColor(QPalette.HighlightedText, Qt.white)
    pal.setColor(QPalette.Link, ACCENT)
    pal.setColor(QPalette.PlaceholderText, QColor(140, 140, 140))
    for role in (QPalette.Text, QPalette.ButtonText, QPalette.WindowText):
        pal.setColor(QPalette.Disabled, role, QColor(120, 120, 120))
    app.setPalette(pal)
    app.setStyleSheet(
        """
        QToolTip { border: 1px solid #555; padding: 4px; }
        QToolButton { border: none; padding: 4px; border-radius: 4px; }
        QToolButton:hover { background: rgba(255,255,255,0.08); }
        QToolButton:checked { background: rgba(79,195,247,0.25); }
        QStatusBar QLabel { padding: 0 6px; }
        QDockWidget::title { padding: 6px; background: #2b2b2f; }
        """
    )


def subtitle_font(pixel_size: int, bold: bool = True) -> QFont:
    font = QFont()
    font.setFamilies(SUBTITLE_FONTS)
    font.setPixelSize(max(8, int(pixel_size)))
    font.setBold(bold)
    font.setHintingPreference(QFont.PreferNoHinting)
    return font


# ---------------------------------------------------------------------------
# 아이콘 (플랫폼마다 모양이 달라지지 않게 직접 그린다)


def _icon(draw, size: int = 64) -> QIcon:
    icon = QIcon()
    for color, mode in ((QColor(235, 235, 235), QIcon.Normal), (QColor(120, 120, 120), QIcon.Disabled)):
        pm = QPixmap(size, size)
        pm.fill(Qt.transparent)
        p = QPainter(pm)
        p.setRenderHint(QPainter.Antialiasing)
        p.setPen(Qt.NoPen)
        p.setBrush(color)
        draw(p, size, color)
        p.end()
        icon.addPixmap(pm, mode)
    return icon


def icon_play() -> QIcon:
    return _icon(lambda p, s, c: p.drawPolygon(QPolygonF([QPointF(s * 0.28, s * 0.18), QPointF(s * 0.82, s * 0.5), QPointF(s * 0.28, s * 0.82)])))


def icon_pause() -> QIcon:
    def draw(p, s, c):
        p.drawRoundedRect(QRectF(s * 0.24, s * 0.18, s * 0.17, s * 0.64), 3, 3)
        p.drawRoundedRect(QRectF(s * 0.59, s * 0.18, s * 0.17, s * 0.64), 3, 3)

    return _icon(draw)


def icon_stop() -> QIcon:
    return _icon(lambda p, s, c: p.drawRoundedRect(QRectF(s * 0.24, s * 0.24, s * 0.52, s * 0.52), 4, 4))


def icon_skip(forward: bool) -> QIcon:
    def draw(p, s, c):
        def tri(x0):
            if forward:
                pts = [QPointF(x0, s * 0.25), QPointF(x0 + s * 0.3, s * 0.5), QPointF(x0, s * 0.75)]
            else:
                pts = [QPointF(x0 + s * 0.3, s * 0.25), QPointF(x0, s * 0.5), QPointF(x0 + s * 0.3, s * 0.75)]
            p.drawPolygon(QPolygonF(pts))

        tri(s * 0.18)
        tri(s * 0.5)

    return _icon(draw)


def icon_volume(muted: bool) -> QIcon:
    def draw(p, s, c):
        body = QPolygonF([
            QPointF(s * 0.14, s * 0.38), QPointF(s * 0.3, s * 0.38), QPointF(s * 0.5, s * 0.2),
            QPointF(s * 0.5, s * 0.8), QPointF(s * 0.3, s * 0.62), QPointF(s * 0.14, s * 0.62),
        ])
        p.drawPolygon(body)
        pen = QPen(c, s * 0.07, Qt.SolidLine, Qt.RoundCap)
        p.setPen(pen)
        p.setBrush(Qt.NoBrush)
        if muted:
            p.drawLine(QPointF(s * 0.6, s * 0.36), QPointF(s * 0.86, s * 0.64))
            p.drawLine(QPointF(s * 0.86, s * 0.36), QPointF(s * 0.6, s * 0.64))
        else:
            p.drawArc(QRectF(s * 0.38, s * 0.3, s * 0.3, s * 0.4), -60 * 16, 120 * 16)
            p.drawArc(QRectF(s * 0.34, s * 0.18, s * 0.5, s * 0.64), -60 * 16, 120 * 16)

    return _icon(draw)


def icon_fullscreen() -> QIcon:
    def draw(p, s, c):
        pen = QPen(c, s * 0.08, Qt.SolidLine, Qt.RoundCap, Qt.RoundJoin)
        p.setPen(pen)
        p.setBrush(Qt.NoBrush)
        m, l = s * 0.2, s * 0.2
        for x, y, dx, dy in ((m, m, 1, 1), (s - m, m, -1, 1), (m, s - m, 1, -1), (s - m, s - m, -1, -1)):
            path = QPainterPath(QPointF(x + dx * l, y))
            path.lineTo(x, y)
            path.lineTo(x, y + dy * l)
            p.drawPath(path)

    return _icon(draw)


def icon_cc() -> QIcon:
    def draw(p, s, c):
        pen = QPen(c, s * 0.06)
        p.setPen(pen)
        p.setBrush(Qt.NoBrush)
        p.drawRoundedRect(QRectF(s * 0.1, s * 0.22, s * 0.8, s * 0.56), s * 0.1, s * 0.1)
        font = QFont()
        font.setPixelSize(int(s * 0.3))
        font.setBold(True)
        p.setFont(font)
        p.drawText(QRectF(0, 0, s, s), Qt.AlignCenter, "자막")

    return _icon(draw)


def icon_list() -> QIcon:
    def draw(p, s, c):
        for i in range(3):
            y = s * (0.27 + i * 0.23)
            p.drawEllipse(QPointF(s * 0.2, y), s * 0.05, s * 0.05)
            p.drawRoundedRect(QRectF(s * 0.32, y - s * 0.04, s * 0.5, s * 0.08), 2, 2)

    return _icon(draw)


def app_icon() -> QIcon:
    icon = QIcon()
    for size in (16, 24, 32, 48, 64, 128, 256):
        pm = QPixmap(size, size)
        pm.fill(Qt.transparent)
        p = QPainter(pm)
        p.setRenderHint(QPainter.Antialiasing)
        p.setPen(Qt.NoPen)
        p.setBrush(QColor(25, 118, 210))
        p.drawRoundedRect(QRectF(0, 0, size, size), size * 0.2, size * 0.2)
        p.setBrush(QColor(255, 255, 255))
        p.drawPolygon(QPolygonF([QPointF(size * 0.36, size * 0.2), QPointF(size * 0.72, size * 0.42), QPointF(size * 0.36, size * 0.64)]))
        p.setBrush(QColor(255, 213, 79))
        p.drawRoundedRect(QRectF(size * 0.16, size * 0.72, size * 0.68, size * 0.12), size * 0.05, size * 0.05)
        p.end()
        icon.addPixmap(pm)
    return icon
