from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import QComboBox, QHBoxLayout, QLabel, QPushButton, QSlider, QWidget

from ultidraft.tts.voices import VoiceChoice


class TransportBar(QWidget):
    play_pause = Signal()
    previous_sentence = Signal()
    next_sentence = Signal()
    previous_paragraph = Signal()
    next_paragraph = Signal()
    add_note = Signal()
    export_notes = Signal()
    edit_toggled = Signal()
    speed_changed = Signal(float)
    voice_changed = Signal(str)

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self._playing = False
        self.prev_para = QPushButton("⟸")
        self.prev_para.setToolTip("Previous paragraph")
        self.prev = QPushButton("◀")
        self.prev.setToolTip("Previous sentence")
        self.play = QPushButton("Play")
        self.play.setToolTip("Play / pause (Space)")
        self.nxt = QPushButton("▶")
        self.nxt.setToolTip("Next sentence")
        self.next_para = QPushButton("⟹")
        self.next_para.setToolTip("Next paragraph")
        self.note = QPushButton("Add note")
        self.note.setToolTip("Add a note on this sentence (N)")
        self.edit = QPushButton("Edit")
        self.edit.setToolTip("Edit the manuscript at this sentence (E)")
        self.export = QPushButton("Export notes")
        self.export.setToolTip("Export listening-notes.md (Ctrl+E)")
        self._speed = QSlider(Qt.Orientation.Horizontal)
        self._speed.setMinimum(50)
        self._speed.setMaximum(200)
        self._speed.setValue(100)
        self._speed.setFixedWidth(140)
        self._speed_label = QLabel("1.0x")
        self._speed_label.setObjectName("statusMuted")
        self._voices = QComboBox()
        self._voices.setMaxVisibleItems(16)
        self._voices.setToolTip("Neural voices need internet. This-PC voices stay offline.")

        self.prev_para.clicked.connect(self.previous_paragraph)
        self.prev.clicked.connect(self.previous_sentence)
        self.play.clicked.connect(self.play_pause)
        self.nxt.clicked.connect(self.next_sentence)
        self.next_para.clicked.connect(self.next_paragraph)
        self.note.clicked.connect(self.add_note)
        self.edit.clicked.connect(self.edit_toggled)
        self.export.clicked.connect(self.export_notes)
        self._speed.valueChanged.connect(self._on_speed)
        self._voices.currentIndexChanged.connect(self._on_voice)

        row = QHBoxLayout(self)
        row.setContentsMargins(10, 8, 10, 8)
        row.setSpacing(8)
        for widget in (
            self.prev_para,
            self.prev,
            self.play,
            self.nxt,
            self.next_para,
            self.note,
            self.edit,
            self.export,
        ):
            row.addWidget(widget)
        row.addStretch(1)
        row.addWidget(QLabel("Voice"))
        row.addWidget(self._voices)
        row.addWidget(QLabel("Speed"))
        row.addWidget(self._speed)
        row.addWidget(self._speed_label)

    def set_playing(self, playing: bool) -> None:
        self._playing = playing
        self.play.setText("Pause" if playing else "Play")

    def set_editing(self, editing: bool) -> None:
        self.edit.setText("Listen" if editing else "Edit")
        self.edit.setToolTip(
            "Return to listening (Esc)" if editing else "Edit the manuscript at this sentence (E)"
        )
        for widget in (self.prev_para, self.prev, self.play, self.nxt, self.next_para, self.note):
            widget.setEnabled(not editing)

    def set_speed(self, speed: float) -> None:
        self._speed.blockSignals(True)
        self._speed.setValue(int(round(speed * 100)))
        self._speed.blockSignals(False)
        self._speed_label.setText(f"{speed:.1f}x")

    def set_voices(self, choices: list[VoiceChoice], current_id: str) -> None:
        self._voices.blockSignals(True)
        self._voices.clear()
        selected = 0
        for index, choice in enumerate(choices):
            self._voices.addItem(choice.label, choice.id)
            if choice.id == current_id:
                selected = index
        self._voices.setCurrentIndex(selected)
        self._voices.blockSignals(False)

    def set_voice_id(self, voice_id: str) -> None:
        for index in range(self._voices.count()):
            if self._voices.itemData(index) == voice_id:
                self._voices.blockSignals(True)
                self._voices.setCurrentIndex(index)
                self._voices.blockSignals(False)
                return

    def _on_speed(self, value: int) -> None:
        speed = value / 100.0
        self._speed_label.setText(f"{speed:.1f}x")
        self.speed_changed.emit(speed)

    def _on_voice(self, index: int) -> None:
        voice_id = self._voices.itemData(index)
        if voice_id:
            self.voice_changed.emit(str(voice_id))
