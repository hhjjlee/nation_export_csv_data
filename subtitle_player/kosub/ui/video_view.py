"""동영상 화면 + 자막 오버레이 (QGraphicsView 위에 비디오와 자막을 함께 그린다)."""

from __future__ import annotations

from typing import List

from PySide6.QtCore import QPointF, QRectF, QSizeF, Qt, QTimer, Signal
from PySide6.QtGui import QBrush, QColor, QFontMetricsF, QPainter, QPainterPath, QPen
from PySide6.QtMultimediaWidgets import QGraphicsVideoItem
from PySide6.QtWidgets import (
    QApplication,
    QFrame,
    QGraphicsObject,
    QGraphicsScene,
    QGraphicsView,
)

from ..subtitles import _balanced_split
from .theme import subtitle_font


def _wrap_to_width(text: str, fm: QFontMetricsF, max_w: float) -> List[str]:
    lines: List[str] = []
    for para in text.split("\n"):
        para = para.strip()
        if not para:
            continue
        spaced = " " in para
        tokens = para.split(" ") if spaced else list(para)
        sep = " " if spaced else ""
        greedy: List[str] = []
        cur = ""
        for tok in tokens:
            cand = tok if not cur else cur + sep + tok
            if not cur or fm.horizontalAdvance(cand) <= max_w:
                cur = cand
            else:
                greedy.append(cur)
                cur = tok
        if cur:
            greedy.append(cur)
        if len(greedy) >= 2:
            balanced = _balanced_split(para, len(greedy), spaced)
            if all(fm.horizontalAdvance(b) <= max_w for b in balanced):
                greedy = balanced
        lines.extend(greedy)
    return lines


class SubtitleItem(QGraphicsObject):
    """테두리가 있는 자막 글자를 영상 아래쪽 가운데에 그린다."""

    def __init__(self) -> None:
        super().__init__()
        self._main = ""
        self._sub = ""
        self._dim_main = False
        self._video = QRectF(0, 0, 640, 360)
        self._scale = 1.0
        self._background = False
        self._paths: List[tuple] = []  # (path, fill, outline_width, box_rects)
        self._bounds = QRectF()
        self.setZValue(10)

    def set_texts(self, main: str, sub: str = "", dim_main: bool = False) -> None:
        if (main, sub, dim_main) == (self._main, self._sub, self._dim_main):
            return
        self._main, self._sub, self._dim_main = main, sub, dim_main
        self._rebuild()

    def set_video_rect(self, rect: QRectF) -> None:
        if rect != self._video:
            self._video = QRectF(rect)
            self._rebuild()

    def set_style(self, scale: float, background: bool) -> None:
        self._scale, self._background = scale, background
        self._rebuild()

    def _rebuild(self) -> None:
        self.prepareGeometryChange()
        self._paths = []
        v = self._video
        if v.width() < 10 or v.height() < 10 or not (self._main or self._sub):
            self._bounds = QRectF()
            self.update()
            return
        main_px = max(14.0, min(110.0, v.height() * 0.058 * self._scale))
        sub_px = main_px * 0.72
        max_w = v.width() * 0.92
        bottom = v.bottom() - v.height() * 0.055
        blocks = []
        if self._main:
            fill = QColor(255, 255, 255) if not self._dim_main else QColor(215, 215, 215)
            blocks.append((self._main, main_px, fill))
        if self._sub:
            blocks.append((self._sub, sub_px, QColor(255, 236, 160)))
        # 아래에서 위로 쌓는다 (원문은 한국어 아래)
        y = bottom
        bounds = QRectF()
        for text, px, fill in reversed(blocks):
            font = subtitle_font(int(px))
            fm = QFontMetricsF(font)
            lines = _wrap_to_width(text, fm, max_w)
            line_h = fm.height() * 1.08
            path = QPainterPath()
            boxes = []
            for i, line in enumerate(reversed(lines)):
                w = fm.horizontalAdvance(line)
                x = v.center().x() - w / 2
                baseline = y - fm.descent() - i * line_h
                path.addText(QPointF(x, baseline), font, line)
                boxes.append(QRectF(x - px * 0.3, baseline - fm.ascent() - px * 0.08, w + px * 0.6, fm.height() + px * 0.16))
            outline = max(2.0, px * 0.12)
            self._paths.append((path, fill, outline, boxes))
            br = path.boundingRect().adjusted(-outline, -outline, outline, outline)
            for b in boxes:
                br = br.united(b)
            bounds = bounds.united(br)
            y -= len(lines) * line_h + px * 0.25
        self._bounds = bounds
        self.update()

    def boundingRect(self) -> QRectF:
        return self._bounds

    def paint(self, painter: QPainter, option, widget=None) -> None:
        if not self._paths:
            return
        painter.setRenderHint(QPainter.Antialiasing)
        for path, fill, outline, boxes in self._paths:
            if self._background:
                painter.setPen(Qt.NoPen)
                painter.setBrush(QColor(0, 0, 0, 150))
                for b in boxes:
                    painter.drawRoundedRect(b, 6, 6)
            painter.setBrush(Qt.NoBrush)
            painter.setPen(QPen(QColor(0, 0, 0, 235), outline, Qt.SolidLine, Qt.RoundCap, Qt.RoundJoin))
            painter.drawPath(path)
            painter.setPen(Qt.NoPen)
            painter.setBrush(QBrush(fill))
            painter.drawPath(path)


class OsdItem(QGraphicsObject):
    """잠깐 나타났다 사라지는 안내 문구 (볼륨, 이동 등)."""

    def __init__(self) -> None:
        super().__init__()
        self._text = ""
        self._rect = QRectF()
        self._timer = QTimer()
        self._timer.setSingleShot(True)
        self._timer.timeout.connect(self._hide)
        self.setZValue(20)

    def show_text(self, text: str, msec: int = 1500) -> None:
        self.prepareGeometryChange()
        self._text = text
        font = subtitle_font(18, bold=True)
        fm = QFontMetricsF(font)
        self._rect = QRectF(0, 0, fm.horizontalAdvance(text) + 24, fm.height() + 12)
        self.setVisible(True)
        self.update()
        self._timer.start(msec)

    def _hide(self) -> None:
        self.setVisible(False)

    def boundingRect(self) -> QRectF:
        return self._rect

    def paint(self, painter: QPainter, option, widget=None) -> None:
        if not self._text:
            return
        painter.setRenderHint(QPainter.Antialiasing)
        painter.setPen(Qt.NoPen)
        painter.setBrush(QColor(0, 0, 0, 170))
        painter.drawRoundedRect(self._rect, 8, 8)
        painter.setPen(QColor(255, 255, 255))
        painter.setFont(subtitle_font(18, bold=True))
        painter.drawText(self._rect, Qt.AlignCenter, self._text)


class PlaceholderItem(QGraphicsObject):
    def __init__(self) -> None:
        super().__init__()
        self._rect = QRectF(0, 0, 10, 10)
        self.setZValue(5)

    def set_area(self, rect: QRectF) -> None:
        self.prepareGeometryChange()
        self._rect = QRectF(rect)
        self.update()

    def boundingRect(self) -> QRectF:
        return self._rect

    def paint(self, painter: QPainter, option, widget=None) -> None:
        painter.setRenderHint(QPainter.Antialiasing)
        painter.setPen(QColor(170, 170, 170))
        painter.setFont(subtitle_font(22, bold=True))
        r = QRectF(self._rect)
        painter.drawText(r.adjusted(0, -40, 0, -40), Qt.AlignCenter, "동영상 파일을 여기로 끌어다 놓으세요")
        painter.setFont(subtitle_font(15, bold=False))
        painter.setPen(QColor(130, 130, 130))
        painter.drawText(
            r.adjusted(0, 30, 0, 30),
            Qt.AlignCenter,
            "또는 Ctrl+O 로 열기 — 재생하면 한국어 자막을 자동으로 만들고 저장합니다",
        )


class VideoView(QGraphicsView):
    clicked = Signal()
    doubleClicked = Signal()
    wheelSteps = Signal(int)
    mouseMoved = Signal()
    filesDropped = Signal(list)
    contextMenuAt = Signal(object)

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self._scene = QGraphicsScene(self)
        self.setScene(self._scene)
        self.setFrameShape(QFrame.NoFrame)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self.setVerticalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self.setBackgroundBrush(QColor(0, 0, 0))
        self.setAlignment(Qt.AlignLeft | Qt.AlignTop)
        self.setViewportUpdateMode(QGraphicsView.FullViewportUpdate)
        self.setRenderHints(QPainter.Antialiasing | QPainter.SmoothPixmapTransform | QPainter.TextAntialiasing)
        self.setFocusPolicy(Qt.NoFocus)
        self.setMouseTracking(True)
        self.viewport().setMouseTracking(True)
        self.setAcceptDrops(True)
        self.viewport().setAcceptDrops(True)
        self.setMinimumSize(320, 180)

        self.video_item = QGraphicsVideoItem()
        self.video_item.setAspectRatioMode(Qt.KeepAspectRatio)
        self._scene.addItem(self.video_item)
        self.video_item.nativeSizeChanged.connect(lambda _s: self.relayout())

        self.placeholder = PlaceholderItem()
        self._scene.addItem(self.placeholder)
        self.subtitle = SubtitleItem()
        self._scene.addItem(self.subtitle)
        self.osd = OsdItem()
        self.osd.setVisible(False)
        self._scene.addItem(self.osd)

        self._click_timer = QTimer(self)
        self._click_timer.setSingleShot(True)
        self._click_timer.timeout.connect(self.clicked.emit)

    def set_placeholder_visible(self, visible: bool) -> None:
        self.placeholder.setVisible(visible)

    def video_rect(self) -> QRectF:
        br = self.video_item.boundingRect()
        if br.isEmpty() or self.video_item.nativeSize().isEmpty():
            return QRectF(self.viewport().rect())
        return br.translated(self.video_item.pos())

    def relayout(self) -> None:
        r = QRectF(self.viewport().rect())
        self._scene.setSceneRect(r)
        self.video_item.setPos(0, 0)
        self.video_item.setSize(QSizeF(r.width(), r.height()))
        self.subtitle.set_video_rect(self.video_rect())
        self.placeholder.set_area(r)
        self.osd.setPos(r.left() + 16, r.top() + 16)

    def show_osd(self, text: str, msec: int = 1500) -> None:
        self.osd.show_text(text, msec)

    # -- 이벤트 -----------------------------------------------------------
    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        self.relayout()

    def showEvent(self, event) -> None:
        super().showEvent(event)
        self.relayout()

    def mousePressEvent(self, event) -> None:
        if event.button() == Qt.LeftButton:
            self._click_timer.start(QApplication.doubleClickInterval())
        event.accept()

    def mouseDoubleClickEvent(self, event) -> None:
        if event.button() == Qt.LeftButton:
            self._click_timer.stop()
            self.doubleClicked.emit()
        event.accept()

    def mouseMoveEvent(self, event) -> None:
        self.mouseMoved.emit()
        super().mouseMoveEvent(event)

    def wheelEvent(self, event) -> None:
        delta = event.angleDelta().y()
        if delta:
            self.wheelSteps.emit(1 if delta > 0 else -1)
        event.accept()

    def contextMenuEvent(self, event) -> None:
        self.contextMenuAt.emit(event.globalPos())
        event.accept()

    def dragEnterEvent(self, event) -> None:
        if event.mimeData().hasUrls():
            event.acceptProposedAction()
        else:
            event.ignore()

    def dragMoveEvent(self, event) -> None:
        if event.mimeData().hasUrls():
            event.acceptProposedAction()

    def dropEvent(self, event) -> None:
        paths = [u.toLocalFile() for u in event.mimeData().urls() if u.isLocalFile()]
        if paths:
            self.filesDropped.emit(paths)
            event.acceptProposedAction()
