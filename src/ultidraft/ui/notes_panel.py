from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QAction
from PySide6.QtWidgets import QLabel, QListWidget, QListWidgetItem, QMenu, QVBoxLayout, QWidget

from ultidraft.domain.notes import Note

_NOTE_ID_ROLE = Qt.ItemDataRole.UserRole + 1


class NotesPanel(QWidget):
    note_selected = Signal(int)
    note_edit_requested = Signal(str)

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self._notes: list[Note] = []
        title = QLabel("NOTES")
        title.setObjectName("paneTitle")
        hint = QLabel("Double-click a note to edit it")
        hint.setObjectName("statusMuted")
        hint.setContentsMargins(10, 0, 10, 6)
        self._list = QListWidget()
        self._list.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self._list.itemClicked.connect(self._on_click)
        self._list.itemActivated.connect(self._on_activate)
        self._list.customContextMenuRequested.connect(self._on_menu)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)
        layout.addWidget(title)
        layout.addWidget(hint)
        layout.addWidget(self._list)

    def set_notes(self, notes: list[Note]) -> None:
        self._notes = list(notes)
        self._list.clear()
        for note in self._notes:
            quote = note.anchor_quote
            if len(quote) > 90:
                quote = quote[:87] + "…"
            item = QListWidgetItem(f"{note.id}  {quote}\n{note.body}")
            item.setData(Qt.ItemDataRole.UserRole, note.sentence_index)
            item.setData(_NOTE_ID_ROLE, note.id)
            self._list.addItem(item)

    def _on_click(self, item: QListWidgetItem) -> None:
        index = item.data(Qt.ItemDataRole.UserRole)
        if index is not None:
            self.note_selected.emit(int(index))

    def _on_activate(self, item: QListWidgetItem) -> None:
        note_id = item.data(_NOTE_ID_ROLE)
        if note_id:
            self.note_edit_requested.emit(str(note_id))

    def _on_menu(self, pos) -> None:
        item = self._list.itemAt(pos)
        if item is None:
            return
        note_id = item.data(_NOTE_ID_ROLE)
        if not note_id:
            return
        menu = QMenu(self)
        edit = QAction("Edit note", self)
        edit.triggered.connect(lambda: self.note_edit_requested.emit(str(note_id)))
        menu.addAction(edit)
        menu.exec(self._list.mapToGlobal(pos))
