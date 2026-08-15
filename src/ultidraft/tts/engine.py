"""Local Windows TTS plus optional Edge neural voices.

Playback rules that keep this reliable:

- A cache file only appears at its final path once it is completely written, so the
  player can never open a half-downloaded clip.
- Every utterance carries a generation token. End-of-media, player errors, stalls,
  and fetch timeouts all funnel into one idempotent advance, so an utterance can
  finish exactly once and never loops.
- Each clip gets a fresh QMediaPlayer. Reusing one player meant stale
  buffered/stopped events from the previous clip could strand the next one.
"""

from __future__ import annotations

import asyncio
import hashlib
import os
import tempfile
import time
import uuid
from pathlib import Path

from PySide6.QtCore import QObject, QThread, QTimer, QUrl, Signal
from PySide6.QtMultimedia import QAudioOutput, QMediaPlayer
from PySide6.QtTextToSpeech import QTextToSpeech

from ultidraft.tts.voices import (
    DEFAULT_VOICE_ID,
    VoiceChoice,
    edge_choices,
    local_choices,
    parse_voice_id,
    speed_to_edge_rate,
)

_MAX_WORKERS = 2
_FETCH_TIMEOUT = 20.0
_WAIT_CEILING = 30.0
_START_TIMEOUT = 8.0
_STALL_TIMEOUT = 2.5
_MIN_CLIP_BYTES = 400
_FAILURES_BEFORE_FALLBACK = 3


def speed_to_rate(speed: float) -> float:
    """Map 0.5x-2.0x onto QTextToSpeech rate (-1.0 to 1.0)."""
    speed = min(2.0, max(0.5, speed))
    if speed <= 1.0:
        return (speed - 1.0) * 2.0
    return speed - 1.0


class _SynthWorker(QThread):
    done = Signal(str, str)  # cache key, error message ("" when it worked)

    def __init__(self, text: str, voice: str, rate: str, dest: Path, parent=None) -> None:
        super().__init__(parent)
        self._text = text
        self._voice = voice
        self._rate = rate
        self._dest = dest

    def run(self) -> None:
        error = ""
        try:
            asyncio.run(self._synthesize())
        except Exception as exc:
            error = str(exc) or exc.__class__.__name__
        self.done.emit(str(self._dest), error)

    async def _synthesize(self) -> None:
        import edge_tts

        self._dest.parent.mkdir(parents=True, exist_ok=True)
        staging = self._dest.with_name(f"{self._dest.stem}.{uuid.uuid4().hex}.part")
        try:
            communicate = edge_tts.Communicate(self._text, self._voice, rate=self._rate)
            await asyncio.wait_for(communicate.save(str(staging)), timeout=_FETCH_TIMEOUT)
            if staging.stat().st_size < _MIN_CLIP_BYTES:
                raise RuntimeError("voice returned no audio")
            os.replace(staging, self._dest)
        finally:
            staging.unlink(missing_ok=True)


class SpeechEngine(QObject):
    finished_utterance = Signal()
    state_changed = Signal(str)
    preparing = Signal(str)
    failed = Signal(str)

    def __init__(self, parent: QObject | None = None) -> None:
        super().__init__(parent)
        self._speed = 1.0
        self._voice_id = DEFAULT_VOICE_ID
        self._paused = False

        # One in-flight utterance, identified by a token so late events are ignorable.
        self._token = 0
        self._active = False
        self._awaiting_key: str | None = None
        self._retried_key: str | None = None
        # A blip should only cost one line; a dead network should switch voices.
        self._consecutive_failures = 0

        self._queue: list[tuple[str, Path]] = []
        self._workers: dict[str, _SynthWorker] = {}
        self._texts: dict[str, str] = {}

        self._local_engine = "winrt"
        self._tts = self._make_local("winrt")
        self._last_local = self._tts.state()
        self._tts.stateChanged.connect(self._on_local_state)

        self._audio = QAudioOutput(self)
        self._player: QMediaPlayer | None = None
        self._clip_path: Path | None = None

        self._started_at = 0.0
        self._heard_audio = False
        self._last_position = -1
        self._last_moved_at = 0.0

        self._watch = QTimer(self)
        self._watch.setInterval(250)
        self._watch.timeout.connect(self._on_watchdog)

    # ------------------------------------------------------------------ voices

    def available_voices(self) -> list[VoiceChoice]:
        found: list[tuple[str, str]] = []
        for engine in QTextToSpeech.availableEngines():
            if engine == "mock":
                continue
            probe = QTextToSpeech(engine)
            for voice in probe.availableVoices():
                found.append((engine, voice.name()))
        return edge_choices() + local_choices(found)

    def current_voice_id(self) -> str:
        return self._voice_id

    def set_voice(self, voice_id: str) -> None:
        self.stop()
        self._voice_id = voice_id or DEFAULT_VOICE_ID
        backend, engine, name = parse_voice_id(self._voice_id)
        if backend != "local":
            return
        if engine and engine != self._local_engine:
            self._tts.stateChanged.disconnect(self._on_local_state)
            self._local_engine = engine
            self._tts = self._make_local(engine)
            self._last_local = self._tts.state()
            self._tts.stateChanged.connect(self._on_local_state)
        self._apply_local_voice(name)
        self._tts.setRate(speed_to_rate(self._speed))

    def set_speed(self, speed: float) -> None:
        self._speed = speed
        self._tts.setRate(speed_to_rate(speed))

    # ---------------------------------------------------------------- playback

    def speak(self, text: str) -> None:
        cleaned = text.strip()
        self._begin_utterance()
        if not cleaned:
            self._advance(self._token)
            return
        backend, _engine, voice = parse_voice_id(self._voice_id)
        if backend != "edge":
            self._tts.setRate(speed_to_rate(self._speed))
            self._tts.say(cleaned)
            return
        dest = self._cache_path(cleaned, voice)
        if self._is_playable(dest):
            self._start_clip(dest, self._token)
            return
        self.preparing.emit("Fetching neural voice...")
        self._awaiting_key = str(dest)
        self._enqueue(cleaned, dest)
        self._watch.start()

    def prefetch(self, text: str) -> None:
        self.prefetch_many([text])

    def prefetch_many(self, texts: list[str]) -> None:
        backend, _engine, voice = parse_voice_id(self._voice_id)
        if backend != "edge":
            return
        for text in texts:
            cleaned = text.strip()
            if not cleaned:
                continue
            dest = self._cache_path(cleaned, voice)
            if self._is_playable(dest):
                continue
            self._enqueue(cleaned, dest)

    def pause(self) -> None:
        self._paused = True
        self._watch.stop()
        if parse_voice_id(self._voice_id)[0] == "edge":
            if self._player is not None:
                self._player.pause()
            self.state_changed.emit("paused")
            return
        self._tts.pause()

    def resume(self) -> None:
        if not self._paused:
            return
        self._paused = False
        if not self._active:
            # Paused in the gap between clips, so there is nothing to un-pause.
            # Ask the reader for the next span instead of sitting here silently.
            self.finished_utterance.emit()
            return
        if parse_voice_id(self._voice_id)[0] != "edge":
            self._tts.resume()
            return
        if self._player is None:
            # Audio is still on its way; the watchdog covers it from here.
            self._arm_watchdog()
            return
        if self._at_clip_end():
            self._advance(self._token)
            return
        self._player.play()
        self._arm_watchdog()
        self.state_changed.emit("speaking")

    def stop(self) -> None:
        self._token += 1
        self._active = False
        self._paused = False
        self._awaiting_key = None
        self._retried_key = None
        self._consecutive_failures = 0
        self._queue.clear()
        self._texts.clear()
        self._watch.stop()
        self._cancel_workers()
        self._teardown_player()
        self._tts.stop()

    def is_speaking(self) -> bool:
        if parse_voice_id(self._voice_id)[0] != "edge":
            return self._tts.state() == QTextToSpeech.State.Speaking
        return (
            self._player is not None
            and self._player.playbackState() == QMediaPlayer.PlaybackState.PlayingState
        )

    def is_paused(self) -> bool:
        if parse_voice_id(self._voice_id)[0] != "edge":
            return self._tts.state() == QTextToSpeech.State.Paused
        return self._paused

    def is_waiting(self) -> bool:
        """True while an utterance is in flight, including fetching audio."""
        return self._active

    def playback_ratio(self) -> float | None:
        if parse_voice_id(self._voice_id)[0] != "edge" or self._player is None:
            return None
        duration = self._player.duration()
        if duration <= 0:
            return None
        return min(1.0, max(0.0, self._player.position() / duration))

    # ------------------------------------------------------------- utterances

    def _begin_utterance(self) -> None:
        self._token += 1
        self._active = True
        self._paused = False
        self._awaiting_key = None
        self._retried_key = None
        self._started_at = time.monotonic()
        self._watch.stop()
        self._teardown_player()
        self._tts.stop()

    def _advance(self, token: int, note: str = "") -> None:
        """The single exit from an utterance. Late or duplicate calls are ignored."""
        if token != self._token or not self._active:
            return
        self._active = False
        self._awaiting_key = None
        self._watch.stop()
        if note:
            self.preparing.emit(note)
        self.finished_utterance.emit()

    def _start_clip(self, path: Path, token: int) -> None:
        if token != self._token:
            return
        self._teardown_player()
        player = QMediaPlayer(self)
        player.setAudioOutput(self._audio)
        player.mediaStatusChanged.connect(
            lambda status, tok=token: self._on_media(status, tok)
        )
        player.errorOccurred.connect(lambda *_args, tok=token: self._on_error(tok))
        self._player = player
        self._clip_path = path
        self._heard_audio = False
        self._last_position = -1
        self._started_at = time.monotonic()
        self._last_moved_at = self._started_at
        player.setSource(QUrl.fromLocalFile(str(path)))
        player.play()
        self._arm_watchdog()
        self.state_changed.emit("speaking")

    def _teardown_player(self) -> None:
        player = self._player
        self._player = None
        self._clip_path = None
        if player is None:
            return
        player.stop()
        player.setSource(QUrl())
        player.deleteLater()

    def _on_media(self, status: QMediaPlayer.MediaStatus, token: int) -> None:
        if token != self._token or not self._active:
            return
        if status == QMediaPlayer.MediaStatus.InvalidMedia:
            self._on_error(token)
            return
        if status == QMediaPlayer.MediaStatus.EndOfMedia:
            self._advance(token)

    def _on_error(self, token: int) -> None:
        if token != self._token or not self._active:
            return
        bad = self._clip_path
        if bad is not None:
            bad.unlink(missing_ok=True)
        self._advance(token, "Skipped a clip that would not play.")

    # ------------------------------------------------------------------ synth

    def _enqueue(self, text: str, dest: Path) -> None:
        key = str(dest)
        self._texts[key] = text
        if key in self._workers or any(str(item[1]) == key for item in self._queue):
            return
        self._queue.append((text, dest))
        self._pump()

    def _pump(self) -> None:
        while len(self._workers) < _MAX_WORKERS and self._queue:
            text, dest = self._queue.pop(0)
            key = str(dest)
            if self._is_playable(dest):
                self._on_synth_done(key, "")
                continue
            worker = _SynthWorker(
                text,
                parse_voice_id(self._voice_id)[2],
                speed_to_edge_rate(self._speed),
                dest,
                self,
            )
            worker.done.connect(self._on_synth_done)
            worker.finished.connect(worker.deleteLater)
            self._workers[key] = worker
            worker.start()

    def _on_synth_done(self, key: str, error: str) -> None:
        self._workers.pop(key, None)
        waiting_on_this = self._active and self._awaiting_key == key
        if error:
            text = self._texts.get(key, "")
            if waiting_on_this and text and self._retried_key != key:
                # One clean retry, then move on rather than stalling the book.
                self._retried_key = key
                self._enqueue(text, Path(key))
            elif waiting_on_this:
                self._awaiting_key = None
                self._texts.pop(key, None)
                self._consecutive_failures += 1
                if self._consecutive_failures >= _FAILURES_BEFORE_FALLBACK:
                    self.failed.emit(error)
                    return
                self._advance(self._token, "Skipped a line the narrator could not fetch.")
            else:
                self._texts.pop(key, None)
            self._pump()
            return
        self._consecutive_failures = 0
        self._texts.pop(key, None)
        if waiting_on_this:
            self._awaiting_key = None
            self._start_clip(Path(key), self._token)
        self._pump()

    # -------------------------------------------------------------- watchdog

    def _arm_watchdog(self) -> None:
        self._started_at = time.monotonic()
        self._last_moved_at = self._started_at
        self._last_position = -1
        self._watch.start()

    def _at_clip_end(self) -> bool:
        if self._player is None:
            return False
        duration = self._player.duration()
        if duration <= 0:
            return self._player.mediaStatus() == QMediaPlayer.MediaStatus.EndOfMedia
        return self._player.position() >= duration - 150

    def _on_watchdog(self) -> None:
        if not self._active or self._paused:
            return
        token = self._token
        now = time.monotonic()

        if self._player is None:
            # Waiting on synthesis. Workers have their own timeout; this is the backstop.
            if now - self._started_at > _WAIT_CEILING:
                self._advance(token, "Skipped a line that never produced audio.")
            return

        position = self._player.position()
        if position > 0:
            self._heard_audio = True
        if position != self._last_position:
            self._last_position = position
            self._last_moved_at = now

        if self._at_clip_end():
            self._advance(token)
            return
        if self._heard_audio and now - self._last_moved_at > _STALL_TIMEOUT:
            self._advance(token, "Recovered from a stalled clip.")
            return
        if not self._heard_audio and now - self._started_at > _START_TIMEOUT:
            self._advance(token, "Skipped a clip that would not start.")

    # ----------------------------------------------------------------- local

    def _on_local_state(self, state: QTextToSpeech.State) -> None:
        previous = self._last_local
        self._last_local = state
        label = {
            QTextToSpeech.State.Ready: "ready",
            QTextToSpeech.State.Speaking: "speaking",
            QTextToSpeech.State.Paused: "paused",
            QTextToSpeech.State.Error: "error",
        }.get(state, "ready")
        self.state_changed.emit(label)
        if parse_voice_id(self._voice_id)[0] == "edge":
            return
        if state == QTextToSpeech.State.Error:
            self._advance(self._token, "Skipped a line the local voice refused.")
            return
        if previous == QTextToSpeech.State.Speaking and state == QTextToSpeech.State.Ready:
            self._advance(self._token)

    def _make_local(self, engine: str) -> QTextToSpeech:
        if engine in QTextToSpeech.availableEngines():
            return QTextToSpeech(engine, self)
        return QTextToSpeech(self)

    def _apply_local_voice(self, name: str) -> None:
        for voice in self._tts.availableVoices():
            if voice.name() == name:
                self._tts.setVoice(voice)
                return

    # ----------------------------------------------------------------- cache

    def _cache_path(self, text: str, voice: str) -> Path:
        rate = speed_to_edge_rate(self._speed)
        digest = hashlib.sha256(f"{voice}|{rate}|{text}".encode("utf-8")).hexdigest()[:20]
        return Path(tempfile.gettempdir()) / "Ultidraft" / "tts" / f"{digest}.mp3"

    def _is_playable(self, path: Path) -> bool:
        try:
            return path.stat().st_size >= _MIN_CLIP_BYTES
        except OSError:
            return False

    def _cancel_workers(self) -> None:
        for worker in list(self._workers.values()):
            try:
                worker.done.disconnect(self._on_synth_done)
            except (RuntimeError, TypeError):
                pass
        self._workers.clear()
