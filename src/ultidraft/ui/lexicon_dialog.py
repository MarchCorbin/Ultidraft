from __future__ import annotations

from PySide6.QtWidgets import (
    QDialog,
    QDialogButtonBox,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QLineEdit,
    QPushButton,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
)

from ultidraft.domain.lexicon import LexiconRule


class LexiconDialog(QDialog):
    def __init__(self, rules: list[LexiconRule], parent=None) -> None:
        super().__init__(parent)
        self.setWindowTitle("Pronunciation rules")
        self.setMinimumSize(560, 360)

        self._table = QTableWidget(0, 2)
        self._table.setHorizontalHeaderLabels(["Written", "Spoken as"])
        self._table.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)
        self._table.horizontalHeader().setSectionResizeMode(1, QHeaderView.ResizeMode.Stretch)
        self._table.verticalHeader().setVisible(False)
        for rule in rules:
            self._add_row(rule.written, rule.spoken)

        self._written = QLineEdit()
        self._written.setPlaceholderText("Sk4ms")
        self._spoken = QLineEdit()
        self._spoken.setPlaceholderText("scams")
        add = QPushButton("Add rule")
        add.clicked.connect(self._add_from_fields)
        remove = QPushButton("Remove selected")
        remove.clicked.connect(self._remove_selected)

        fields = QHBoxLayout()
        fields.addWidget(self._written, 1)
        fields.addWidget(self._spoken, 1)
        fields.addWidget(add)
        fields.addWidget(remove)

        buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel
        )
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)

        layout = QVBoxLayout(self)
        layout.addWidget(QLabel("These change how the narrator speaks this book, not the text on the page."))
        layout.addWidget(self._table, 1)
        layout.addLayout(fields)
        layout.addWidget(buttons)
        self._written.setFocus()

    def rules(self) -> list[LexiconRule]:
        out: list[LexiconRule] = []
        seen: set[str] = set()
        for row in range(self._table.rowCount()):
            written_item = self._table.item(row, 0)
            spoken_item = self._table.item(row, 1)
            written = written_item.text().strip() if written_item else ""
            spoken = spoken_item.text().strip() if spoken_item else ""
            key = written.casefold()
            if not written or not spoken or key in seen:
                continue
            seen.add(key)
            out.append(LexiconRule(written=written, spoken=spoken))
        return out

    def _add_from_fields(self) -> None:
        written = self._written.text().strip()
        spoken = self._spoken.text().strip()
        if not written or not spoken:
            return
        self._add_row(written, spoken)
        self._written.clear()
        self._spoken.clear()
        self._written.setFocus()

    def _add_row(self, written: str, spoken: str) -> None:
        row = self._table.rowCount()
        self._table.insertRow(row)
        self._table.setItem(row, 0, QTableWidgetItem(written))
        self._table.setItem(row, 1, QTableWidgetItem(spoken))

    def _remove_selected(self) -> None:
        rows = sorted({index.row() for index in self._table.selectedIndexes()}, reverse=True)
        for row in rows:
            self._table.removeRow(row)
