"""자막 목록 패널: 만들어진 자막을 시간순으로 보여 주고, 두 번 눌러 이동하거나 번역을 고칠 수 있다."""

from __future__ import annotations

import bisect
from typing import Iterable, List, Optional

from PySide6.QtCore import QAbstractTableModel, QModelIndex, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QFont
from PySide6.QtWidgets import (
    QAbstractItemView,
    QCheckBox,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QLineEdit,
    QTableView,
    QVBoxLayout,
    QWidget,
)

from ..subtitles import Unit, format_clock

COL_TIME, COL_SRC, COL_KO = range(3)


class SubtitleTableModel(QAbstractTableModel):
    editRequested = Signal(int, str)  # unit_id, 새 번역

    def __init__(self) -> None:
        super().__init__()
        self._units: List[Unit] = []
        self._keys: List[tuple] = []
        self._current_id: Optional[int] = None
        self._editable = True

    # -- 데이터 갱신 ---------------------------------------------------------
    def set_units(self, units: Iterable[Unit]) -> None:
        self.beginResetModel()
        self._units = sorted(units, key=lambda u: (u.start, u.id))
        self._keys = [(u.start, u.id) for u in self._units]
        self.endResetModel()

    def upsert(self, units: Iterable[Unit]) -> None:
        index = {u.id: i for i, u in enumerate(self._units)}
        for unit in units:
            row = index.get(unit.id)
            if row is not None:
                self._units[row] = unit
                self.dataChanged.emit(self.index(row, 0), self.index(row, 2))
                continue
            key = (unit.start, unit.id)
            row = bisect.bisect_left(self._keys, key)
            self.beginInsertRows(QModelIndex(), row, row)
            self._units.insert(row, unit)
            self._keys.insert(row, key)
            self.endInsertRows()
            index = {u.id: i for i, u in enumerate(self._units)}

    def set_editable(self, editable: bool) -> None:
        self._editable = editable

    def set_current(self, unit_id: Optional[int]) -> Optional[int]:
        if unit_id == self._current_id:
            return None
        old = self.row_of(self._current_id)
        self._current_id = unit_id
        new = self.row_of(unit_id)
        for row in (old, new):
            if row is not None:
                self.dataChanged.emit(self.index(row, 0), self.index(row, 2))
        return new

    def row_of(self, unit_id: Optional[int]) -> Optional[int]:
        if unit_id is None:
            return None
        for i, u in enumerate(self._units):
            if u.id == unit_id:
                return i
        return None

    def unit_at(self, row: int) -> Optional[Unit]:
        return self._units[row] if 0 <= row < len(self._units) else None

    # -- Qt 모델 -----------------------------------------------------------
    def rowCount(self, parent=QModelIndex()) -> int:
        return 0 if parent.isValid() else len(self._units)

    def columnCount(self, parent=QModelIndex()) -> int:
        return 3

    def headerData(self, section, orientation, role=Qt.DisplayRole):
        if orientation == Qt.Horizontal and role == Qt.DisplayRole:
            return ["시간", "원문", "한국어"][section]
        return None

    def data(self, index, role=Qt.DisplayRole):
        if not index.isValid():
            return None
        u = self._units[index.row()]
        col = index.column()
        if role in (Qt.DisplayRole, Qt.ToolTipRole):
            if col == COL_TIME:
                return format_clock(u.start)
            if col == COL_SRC:
                return u.text
            return u.ko if u.ko else "(번역 대기 중…)"
        if role == Qt.EditRole and col == COL_KO:
            return u.ko or ""
        if role == Qt.ForegroundRole:
            if col == COL_KO and not u.ko:
                return QColor(140, 140, 140)
            if col == COL_TIME:
                return QColor(150, 190, 230)
        if role == Qt.FontRole and col == COL_KO and u.edited:
            f = QFont()
            f.setBold(True)
            return f
        if role == Qt.BackgroundRole and u.id == self._current_id:
            return QColor(41, 98, 160, 120)
        return None

    def flags(self, index):
        base = super().flags(index)
        if index.isValid() and index.column() == COL_KO and self._editable:
            return base | Qt.ItemIsEditable
        return base

    def setData(self, index, value, role=Qt.EditRole):
        if role != Qt.EditRole or index.column() != COL_KO:
            return False
        u = self._units[index.row()]
        text = " ".join(str(value).split())
        if text == (u.ko or ""):
            return False
        self.editRequested.emit(u.id, text)
        return True


class SubtitlePanel(QWidget):
    seekRequested = Signal(float)
    editRequested = Signal(int, str)

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self.model = SubtitleTableModel()
        self.model.editRequested.connect(self.editRequested)

        self.table = QTableView()
        self.table.setModel(self.model)
        self.table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.table.setSelectionMode(QAbstractItemView.SingleSelection)
        self.table.setEditTriggers(QAbstractItemView.DoubleClicked | QAbstractItemView.EditKeyPressed)
        self.table.setWordWrap(True)
        self.table.setTextElideMode(Qt.ElideNone)
        self.table.setAlternatingRowColors(True)
        self.table.verticalHeader().setVisible(False)
        self.table.verticalHeader().setSectionResizeMode(QHeaderView.ResizeToContents)
        hh = self.table.horizontalHeader()
        hh.setSectionResizeMode(COL_TIME, QHeaderView.ResizeToContents)
        hh.setSectionResizeMode(COL_SRC, QHeaderView.Stretch)
        hh.setSectionResizeMode(COL_KO, QHeaderView.Stretch)
        # 패널 폭이 바뀌면 줄바꿈된 행 높이를 다시 계산한다
        self._resize_timer = QTimer(self)
        self._resize_timer.setSingleShot(True)
        self._resize_timer.setInterval(150)
        self._resize_timer.timeout.connect(self.table.resizeRowsToContents)
        hh.sectionResized.connect(lambda *_: self._resize_timer.start())
        self.table.doubleClicked.connect(self._on_double_click)

        self.filter_edit = QLineEdit()
        self.filter_edit.setPlaceholderText("자막 검색…")
        self.filter_edit.setClearButtonEnabled(True)
        self.filter_edit.textChanged.connect(self._apply_filter)
        self.auto_scroll = QCheckBox("현재 자막 따라가기")
        self.auto_scroll.setChecked(True)
        self.count_label = QLabel("자막 0개")
        self.count_label.setStyleSheet("color: #aaa;")
        hint = QLabel("시간/원문을 두 번 누르면 이동, 한국어 칸을 두 번 누르면 수정")
        hint.setStyleSheet("color: #888; font-size: 11px;")
        hint.setWordWrap(True)

        top = QHBoxLayout()
        top.addWidget(self.filter_edit, 1)
        top.addWidget(self.auto_scroll)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(6, 6, 6, 6)
        layout.addLayout(top)
        layout.addWidget(self.table, 1)
        bottom = QHBoxLayout()
        bottom.addWidget(self.count_label)
        bottom.addStretch(1)
        layout.addLayout(bottom)
        layout.addWidget(hint)
        self.model.rowsInserted.connect(self._update_count)
        self.model.modelReset.connect(self._update_count)

    def _update_count(self, *args) -> None:
        self.count_label.setText(f"자막 {self.model.rowCount()}개")
        self._resize_timer.start()
        if self.filter_edit.text():
            self._apply_filter(self.filter_edit.text())

    def _apply_filter(self, text: str) -> None:
        text = text.strip().lower()
        for row in range(self.model.rowCount()):
            u = self.model.unit_at(row)
            hide = bool(text) and text not in (u.text.lower() + " " + (u.ko or "").lower())
            self.table.setRowHidden(row, hide)

    def _on_double_click(self, index) -> None:
        if index.column() == COL_KO and self.model.flags(index) & Qt.ItemIsEditable:
            return  # 편집 모드로 들어간다
        u = self.model.unit_at(index.row())
        if u:
            self.seekRequested.emit(u.start)

    def set_units(self, units: Iterable[Unit]) -> None:
        self.model.set_units(units)

    def set_columns(self, show_source: bool, show_korean: bool) -> None:
        """번역 안 함 모드에서는 '한국어' 열을, 원래 한국어 영상에서는 '원문' 열을 숨긴다."""
        self.table.setColumnHidden(COL_SRC, not show_source)
        self.table.setColumnHidden(COL_KO, not show_korean)
        self._resize_timer.start()

    def upsert(self, units: Iterable[Unit]) -> None:
        self.model.upsert(units)

    def set_current(self, unit_id: Optional[int]) -> None:
        row = self.model.set_current(unit_id)
        if row is not None and self.auto_scroll.isChecked() and self.table.state() != QAbstractItemView.EditingState:
            self.table.scrollTo(self.model.index(row, 0), QAbstractItemView.PositionAtCenter)
