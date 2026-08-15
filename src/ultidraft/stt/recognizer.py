"""Windows speech recognition for spoken notes."""

from __future__ import annotations

import asyncio
import os
import threading
import time
import winreg
from datetime import timedelta

from PySide6.QtCore import QThread, Signal

LISTEN_SECONDS = 45.0
SILENCE_SECONDS = 12.0

SPEECH_SETTINGS_URI = "ms-settings:privacy-speech"
_PRIVACY_KEY = r"Software\Microsoft\Speech_OneCore\Settings\OnlineSpeechPrivacy"
_PRIVACY_VALUE = "HasAccepted"
# 0x80045509 plus the wrapper code the desktop app has been surfacing.
_PRIVACY_CODES = {0x80045509, -2147479287, 2147488009, 2147199735}

_STATUS_MESSAGES = {
    "microphone_unavailable": "No microphone was available. Check Windows privacy settings.",
    "audio_quality_failure": "The microphone audio was too unclear to transcribe.",
    "timeout_exceeded": "I didn't catch any speech. Try again.",
    "user_canceled": "Listening was canceled.",
    "network_failure": "Speech recognition needed a short network check and failed.",
}


class DictationError(Exception):
    pass


class SpeechPrivacyError(DictationError):
    """Windows has not recorded speech-privacy consent yet."""

    MESSAGE = (
        "Windows blocked the microphone because Online speech recognition is off. "
        "Open Settings → Privacy & security → Speech, turn it on, then click Speak note again."
    )

    def __init__(self, message: str = MESSAGE) -> None:
        super().__init__(message)


def speech_privacy_accepted() -> bool:
    """True when this Windows user has turned on Online speech recognition."""
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _PRIVACY_KEY) as key:
            value, _kind = winreg.QueryValueEx(key, _PRIVACY_VALUE)
    except OSError:
        return False
    return int(value) == 1


def open_speech_privacy_settings() -> None:
    """Open the Windows page where Online speech recognition can be turned on."""
    try:
        os.startfile(SPEECH_SETTINGS_URI)
    except OSError:
        os.startfile("ms-settings:privacy-speechtyping")


def is_speech_privacy_error(exc: BaseException) -> bool:
    if isinstance(exc, SpeechPrivacyError):
        return True
    text = str(exc).casefold()
    if "privacy policy" in text or "privacy statement" in text:
        return True
    for attr in ("winerror", "hresult", "errno"):
        code = getattr(exc, attr, None)
        if _is_privacy_code(code):
            return True
    for arg in getattr(exc, "args", ()):
        if _is_privacy_code(arg) or (
            isinstance(arg, str) and "privacy policy" in arg.casefold()
        ):
            return True
    cause = getattr(exc, "__cause__", None)
    if cause is not None and cause is not exc:
        return is_speech_privacy_error(cause)
    return False


def _is_privacy_code(value: object) -> bool:
    if isinstance(value, bool) or not isinstance(value, int):
        return False
    return value in _PRIVACY_CODES or (value & 0xFFFFFFFF) == 0x80045509


def join_phrases(parts: list[str]) -> str:
    return " ".join(part.strip() for part in parts if part.strip())


def finalize_dictation(parts: list[str], leftover: str = "") -> str:
    return join_phrases(parts) or leftover.strip()


def level_from_state(name: str) -> float:
    return {
        "speech_detected": 0.92,
        "sound_started": 0.7,
        "capturing": 0.22,
        "processing": 0.38,
        "sound_ended": 0.12,
        "idle": 0.06,
        "paused": 0.0,
    }.get(name.rsplit(".", 1)[-1].lower(), 0.15)


def transcribe_once() -> str:
    if not speech_privacy_accepted():
        raise SpeechPrivacyError()
    try:
        return asyncio.run(_recognize_continuous(threading.Event()))
    except SpeechPrivacyError:
        raise
    except DictationError:
        raise
    except Exception as exc:
        if is_speech_privacy_error(exc):
            raise SpeechPrivacyError() from exc
        raise DictationError(str(exc) or exc.__class__.__name__) from exc


async def _recognize_continuous(
    stop: threading.Event,
    on_final=None,
    on_partial=None,
    on_level=None,
) -> str:
    from winrt.windows.globalization import Language
    from winrt.windows.media.speechrecognition import (
        SpeechRecognitionConfidence,
        SpeechRecognitionResultStatus,
        SpeechRecognitionScenario,
        SpeechRecognitionTopicConstraint,
        SpeechRecognizer,
    )

    recognizer = SpeechRecognizer(Language("en-US"))
    recognizer.constraints.append(
        SpeechRecognitionTopicConstraint(SpeechRecognitionScenario.DICTATION, "dictation")
    )
    compiled = await recognizer.compile_constraints_async()
    if compiled.status != SpeechRecognitionResultStatus.SUCCESS:
        name = str(compiled.status).rsplit(".", 1)[-1].lower()
        raise DictationError(_STATUS_MESSAGES.get(name, f"Could not start listening ({name})."))

    timeouts = recognizer.timeouts
    timeouts.initial_silence_timeout = timedelta(seconds=SILENCE_SECONDS)
    timeouts.end_silence_timeout = timedelta(seconds=8)
    timeouts.babble_timeout = timedelta(seconds=20)

    session = recognizer.continuous_recognition_session
    session.auto_stop_silence_timeout = timedelta(seconds=SILENCE_SECONDS)

    parts: list[str] = []
    last_partial = ""
    lock = threading.Lock()
    handlers: list[object] = []

    def on_result(_sender, args) -> None:
        result = args.result
        if result.confidence == SpeechRecognitionConfidence.REJECTED:
            return
        text = (result.text or "").strip()
        if not text:
            return
        with lock:
            parts.append(text)
        if on_final is not None:
            on_final(text)
        if on_level is not None:
            on_level(0.88)

    def on_hypothesis(_sender, args) -> None:
        nonlocal last_partial
        text = (args.hypothesis.text or "").strip()
        if not text:
            return
        with lock:
            last_partial = text
        if on_partial is not None:
            on_partial(text)
        if on_level is not None:
            on_level(min(1.0, 0.45 + min(len(text), 80) / 120))

    def on_state(_sender, args) -> None:
        if on_level is not None:
            on_level(level_from_state(str(args.state)))

    def on_completed(_sender, _args) -> None:
        stop.set()

    callbacks = (on_result, on_hypothesis, on_state, on_completed)
    handlers.append(callbacks)
    handlers.append(session.add_result_generated(on_result))
    handlers.append(recognizer.add_hypothesis_generated(on_hypothesis))
    handlers.append(recognizer.add_state_changed(on_state))
    handlers.append(session.add_completed(on_completed))

    try:
        await session.start_async()
        deadline = time.monotonic() + LISTEN_SECONDS
        while not stop.is_set() and time.monotonic() < deadline:
            await asyncio.sleep(0.08)
        try:
            await session.stop_async()
        except Exception:
            pass
    finally:
        try:
            recognizer.close()
        except Exception:
            pass
        del handlers

    with lock:
        text = finalize_dictation(parts, last_partial)
        leftover = last_partial
    if text and leftover and leftover == text and on_final is not None and not parts:
        on_final(text)
    if not text:
        raise DictationError("I didn't catch any speech. Try again.")
    return text


class DictationThread(QThread):
    heard = Signal(str)
    partial = Signal(str)
    level = Signal(float)
    failed = Signal(str)
    privacy_needed = Signal()

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self._stop = threading.Event()

    def stop_listening(self) -> None:
        self._stop.set()

    def run(self) -> None:
        if not speech_privacy_accepted():
            self.privacy_needed.emit()
            return
        try:
            asyncio.run(
                _recognize_continuous(
                    self._stop,
                    on_final=self.heard.emit,
                    on_partial=self.partial.emit,
                    on_level=self.level.emit,
                )
            )
        except SpeechPrivacyError:
            self.privacy_needed.emit()
        except DictationError as exc:
            if is_speech_privacy_error(exc):
                self.privacy_needed.emit()
            else:
                self.failed.emit(str(exc))
        except Exception as exc:
            if is_speech_privacy_error(exc):
                self.privacy_needed.emit()
            else:
                self.failed.emit(str(exc))
