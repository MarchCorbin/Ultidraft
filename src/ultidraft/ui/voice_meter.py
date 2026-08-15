"""Live listening meter for Speak note."""

from __future__ import annotations

import struct
import time

from PySide6.QtCore import QObject, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QPainter, QPaintEvent
from PySide6.QtWidgets import QWidget

_BARS = 16


class VoiceMeter(QWidget):
    """A row of bars that rise when Windows hears your voice."""

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self._level = 0.0
        self._shown = 0.0
        self._listening = False
        self.setFixedHeight(22)
        self.setMinimumWidth(160)
        self.setToolTip("Jumps when the microphone hears you")
        self._decay = QTimer(self)
        self._decay.setInterval(40)
        self._decay.timeout.connect(self._tick)

    def set_listening(self, listening: bool) -> None:
        self._listening = listening
        if listening:
            self._decay.start()
            return
        self._decay.stop()
        self._level = 0.0
        self._shown = 0.0
        self.update()

    def set_level(self, value: float) -> None:
        self._level = max(self._level * 0.35, min(1.0, max(0.0, value)))
        self.update()

    def _tick(self) -> None:
        target = self._level
        if self._listening and self._level < 0.16:
            pulse = 0.07 + 0.05 * (1.0 if (time.monotonic() % 1.1) < 0.55 else 0.0)
            target = max(target, pulse)
        self._shown += (target - self._shown) * 0.45
        self._level *= 0.82
        self.update()

    def paintEvent(self, event: QPaintEvent) -> None:
        del event
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        width = self.width()
        height = self.height()
        gap = 2
        bar_w = max(3, (width - gap * (_BARS - 1)) / _BARS)
        for index in range(_BARS):
            threshold = (index + 1) / _BARS
            x = index * (bar_w + gap)
            on = self._shown >= threshold - 0.02
            if on:
                t = index / max(1, _BARS - 1)
                color = QColor(0, 120, 212) if t < 0.7 else QColor(196, 165, 116)
            else:
                color = QColor(51, 51, 51)
            bar_h = 6 + (height - 8) * ((index + 1) / _BARS)
            y = height - bar_h
            painter.setPen(Qt.PenStyle.NoPen)
            painter.setBrush(color)
            painter.drawRoundedRect(int(x), int(y), int(bar_w), int(bar_h), 2, 2)


class MicLevelProbe(QObject):
    """Shared-mode capture so the bars move with your actual voice."""

    level = Signal(float)

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self._source = None
        self._io = None

    def start(self) -> None:
        self.stop()
        try:
            from PySide6.QtMultimedia import QAudioFormat, QAudioSource, QMediaDevices
        except Exception:
            return
        device = QMediaDevices.defaultAudioInput()
        if device.isNull():
            return
        fmt = QAudioFormat()
        fmt.setSampleRate(16000)
        fmt.setChannelCount(1)
        fmt.setSampleFormat(QAudioFormat.SampleFormat.Int16)
        if not device.isFormatSupported(fmt):
            fmt = device.preferredFormat()
        try:
            source = QAudioSource(device, fmt, self)
            source.setBufferSize(4096)
            io = source.start()
        except Exception:
            return
        if io is None:
            return
        self._source = source
        self._io = io
        io.readyRead.connect(self._on_audio)

    def stop(self) -> None:
        io = self._io
        source = self._source
        self._io = None
        self._source = None
        if io is not None:
            try:
                io.readyRead.disconnect(self._on_audio)
            except (RuntimeError, TypeError):
                pass
        if source is not None:
            source.stop()
            source.deleteLater()

    def _on_audio(self) -> None:
        if self._io is None:
            return
        raw = bytes(self._io.readAll())
        if len(raw) < 4:
            return
        count = len(raw) // 2
        samples = struct.unpack("<" + "h" * count, raw[: count * 2])
        rms = (sum(sample * sample for sample in samples) / count) ** 0.5
        self.level.emit(min(1.0, rms / 3500.0))
