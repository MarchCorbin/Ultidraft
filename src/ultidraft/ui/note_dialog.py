from __future__ import annotations

from PySide6.QtCore import QTimer
from PySide6.QtWidgets import (
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QHBoxLayout,
    QLabel,
    QMessageBox,
    QPlainTextEdit,
    QPushButton,
    QVBoxLayout,
)

from ultidraft.domain.manuscript import Sentence
from ultidraft.stt.recognizer import (
    DictationThread,
    open_speech_privacy_settings,
    speech_privacy_accepted,
)
from ultidraft.ui.voice_meter import MicLevelProbe, VoiceMeter


class NoteDialog(QDialog):
    def __init__(
        self,
        neighbors: tuple[Sentence, ...],
        current_index: int,
        parent=None,
        *,
        title: str = "Add note",
        initial_text: str = "",
    ) -> None:
        super().__init__(parent)
        self.setWindowTitle(title)
        self.setMinimumWidth(560)
        self._thread: DictationThread | None = None
        self._probe = MicLevelProbe(self)

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
        if initial_text:
            self._body.setPlainText(initial_text)

        self._speak = QPushButton("Speak note")
        self._speak.setToolTip("Dictate for up to 45 seconds. Click Stop when you are done.")
        self._speak.clicked.connect(self._toggle_dictation)
        self._meter = VoiceMeter()
        self._meter.setVisible(False)
        self._status = QLabel(
            "Uses Windows speech recognition. The first time, turn on Speech in Settings."
        )
        self._status.setObjectName("statusMuted")
        self._status.setWordWrap(True)
        speak_row = QHBoxLayout()
        speak_row.addWidget(self._speak)
        speak_row.addWidget(self._meter, 1)
        speak_row.addWidget(self._status, 2)

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
        self._probe.level.connect(self._meter.set_level)

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
            self._stop_dictation()
            self._status.setText("Stopping…")
            return
        if not speech_privacy_accepted():
            self._offer_speech_settings()
            return
        self._speak.setText("Stop listening")
        self._meter.setVisible(True)
        self._meter.set_listening(True)
        self._status.setText("Speak now — the bars jump when you talk. Click Stop when done.")
        thread = DictationThread(self)
        thread.heard.connect(self._on_heard)
        thread.partial.connect(self._on_partial)
        thread.level.connect(self._meter.set_level)
        thread.failed.connect(self._on_failed)
        thread.privacy_needed.connect(self._offer_speech_settings)
        thread.finished.connect(self._on_finished)
        self._thread = thread
        thread.start()
        QTimer.singleShot(400, self._start_probe_if_listening)

    def _start_probe_if_listening(self) -> None:
        if self._thread is not None and self._thread.isRunning():
            self._probe.start()

    def _offer_speech_settings(self) -> None:
        box = QMessageBox(self)
        box.setWindowTitle("Ultidraft")
        box.setIcon(QMessageBox.Icon.Information)
        box.setText("Windows needs Online speech recognition turned on.")
        box.setInformativeText(
            "This is a one-time Windows setting, not an Ultidraft account.\n\n"
            "1. Open Settings → Privacy & security → Speech\n"
            "2. Turn on Online speech recognition\n"
            "3. Come back and click Speak note again"
        )
        open_btn = box.addButton("Open Speech settings", QMessageBox.ButtonRole.AcceptRole)
        box.addButton("Cancel", QMessageBox.ButtonRole.RejectRole)
        box.exec()
        if box.clickedButton() is open_btn:
            open_speech_privacy_settings()
            self._status.setText(
                "Turn on Online speech recognition, then click Speak note again."
            )
        else:
            self._status.setText("Speak note needs Online speech recognition in Windows Settings.")

    def _on_heard(self, text: str) -> None:
        existing = self._body.toPlainText().strip()
        self._body.setPlainText(f"{existing} {text}".strip() if existing else text)
        self._status.setText("Hearing you. Keep talking, or click Stop.")

    def _on_partial(self, text: str) -> None:
        preview = text if len(text) <= 72 else text[:69] + "…"
        self._status.setText(f"Hearing: “{preview}”")

    def _on_failed(self, message: str) -> None:
        self._status.setText(message)

    def _on_finished(self) -> None:
        self._probe.stop()
        self._meter.set_listening(False)
        self._meter.setVisible(False)
        self._speak.setEnabled(True)
        self._speak.setText("Speak note")
        if self._status.text() in {"Stopping…", "Hearing you. Keep talking, or click Stop."}:
            self._status.setText("Added spoken text. Speak again to append, or Save.")
        self._thread = None

    def _stop_dictation(self) -> None:
        thread = self._thread
        if thread is None:
            return
        thread.stop_listening()
        thread.requestInterruption()
        self._probe.stop()
