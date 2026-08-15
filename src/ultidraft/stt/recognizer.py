"""On-device Windows speech recognition. No cloud, no Cursor tokens."""

from __future__ import annotations

import asyncio
from datetime import timedelta

from PySide6.QtCore import QThread, Signal


class DictationError(Exception):
    pass


_STATUS_MESSAGES = {
    "microphone_unavailable": "No microphone was available. Check Windows privacy settings.",
    "audio_quality_failure": "The microphone audio was too unclear to transcribe.",
    "timeout_exceeded": "I didn't catch any speech. Try again.",
    "user_canceled": "Listening was canceled.",
    "network_failure": "Speech recognition needed a short network check and failed.",
}


def transcribe_once() -> str:
    return asyncio.run(_recognize())


async def _recognize() -> str:
    from winrt.windows.globalization import Language
    from winrt.windows.media.speechrecognition import (
        SpeechRecognitionResultStatus,
        SpeechRecognizer,
    )

    recognizer = SpeechRecognizer(Language("en-US"))
    await recognizer.compile_constraints_async()
    timeouts = recognizer.timeouts
    timeouts.initial_silence_timeout = timedelta(seconds=6)
    timeouts.end_silence_timeout = timedelta(milliseconds=1400)
    timeouts.babble_timeout = timedelta(seconds=8)
    result = await recognizer.recognize_async()
    status = result.status
    if status == SpeechRecognitionResultStatus.SUCCESS:
        text = (result.text or "").strip()
        if not text:
            raise DictationError("I heard you, but could not turn it into text.")
        return text
    name = str(status).rsplit(".", 1)[-1].lower()
    raise DictationError(_STATUS_MESSAGES.get(name, f"Could not transcribe ({name})."))


class DictationThread(QThread):
    heard = Signal(str)
    failed = Signal(str)

    def run(self) -> None:
        try:
            self.heard.emit(transcribe_once())
        except Exception as exc:
            self.failed.emit(str(exc))
