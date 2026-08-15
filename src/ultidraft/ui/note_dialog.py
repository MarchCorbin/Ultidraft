from __future__ import annotations

from PySide6.QtWidgets import (
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QHBoxLayout,
    QLabel,
    QPlainTextEdit,
    QPushButton,
    QVBoxLayout,
)

from ultidraft.domain.manuscript import Sentence
from ultidraft.stt.recognizer import DictationThread


class NoteDialog(QDialog):
    def __init__(
        self,
        neighbors: tuple[Sentence, ...],
        current_index: int,
        parent=None,
    ) -> None:
        super().__init__(parent)
        self.setWindowTitle("Add note")
        self.setMinimumWidth(560)
        self._thread: DictationThread | None = None

        self._sentences = QComboBox()
        self._sentences.setMaxVisibleItems(12)
        selected = 0
        for offset, sentence in enumerate(neighbors):
            preview = sentence.text
            if len(preview) > 88:
                preview = preview[:85] + "…"
            self._sentences.addItem(preview, sentence.index)
            if sentence.index == current_index:
                selected = offset
        self._sentences.setCurrentIndex(selected)

        self._quote = QLabel()
        self._quote.setWordWrap(True)
        self._quote.setObjectName("statusMuted")
        self._sentences.currentIndexChanged.connect(self._refresh_quote)
        self._refresh_quote()

        self._body = QPlainTextEdit()
        self._body.setPlaceholderText("Type a note, or click Speak and talk.")
        self._body.setMinimumHeight(140)

        self._speak = QPushButton("Speak note")
        self._speak.setToolTip("Dictate with the microphone. Pause when you are done.")
        self._speak.clicked.connect(self._toggle_dictation)
        self._status = QLabel("Windows speech recognition stays on this PC.")
        self._status.setObjectName("statusMuted")
        speak_row = QHBoxLayout()
        speak_row.addWidget(self._speak)
        speak_row.addWidget(self._status, 1)

        buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel
        )
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)

        layout = QVBoxLayout(self)
        layout.addWidget(QLabel("Attach this note to"))
        layout.addWidget(self._sentences)
        layout.addWidget(self._quote)
        layout.addLayout(speak_row)
        layout.addWidget(self._body)
        layout.addWidget(buttons)
        self._body.setFocus()

    def selected_index(self) -> int:
        return int(self._sentences.currentData())

    def note_text(self) -> str:
        return self._body.toPlainText().strip()

    def closeEvent(self, event) -> None:
        self._stop_dictation()
        super().closeEvent(event)

    def _refresh_quote(self) -> None:
        text = self._sentences.currentText()
        self._quote.setText(f'“{text}”')

    def _toggle_dictation(self) -> None:
        if self._thread is not None and self._thread.isRunning():
            return
        self._speak.setEnabled(False)
        self._speak.setText("Listening…")
        self._status.setText("Speak now. Pause when the note is finished.")
        thread = DictationThread(self)
        thread.heard.connect(self._on_heard)
        thread.failed.connect(self._on_failed)
        thread.finished.connect(self._on_finished)
        self._thread = thread
        thread.start()

    def _on_heard(self, text: str) -> None:
        existing = self._body.toPlainText().strip()
        self._body.setPlainText(f"{existing} {text}".strip() if existing else text)
        self._status.setText("Added spoken text. Speak again to append, or Save.")

    def _on_failed(self, message: str) -> None:
        self._status.setText(message)

    def _on_finished(self) -> None:
        self._speak.setEnabled(True)
        self._speak.setText("Speak note")
        self._thread = None

    def _stop_dictation(self) -> None:
        thread = self._thread
        if thread is None:
            return
        thread.requestInterruption()
