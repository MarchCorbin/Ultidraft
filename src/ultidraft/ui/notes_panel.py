from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import QListWidget, QListWidgetItem, QVBoxLayout, QLabel, QWidget

from ultidraft.domain.notes import Note


class NotesPanel(QWidget):
    note_selected = Signal(int)

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self._notes: list[Note] = []
        title = QLabel("NOTES")
        title.setObjectName("paneTitle")
        self._list = QListWidget()
        self._list.itemClicked.connect(self._on_click)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)
        layout.addWidget(title)
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
            self._list.addItem(item)

    def _on_click(self, item: QListWidgetItem) -> None:
        index = item.data(Qt.ItemDataRole.UserRole)
        if index is not None:
            self.note_selected.emit(int(index))
