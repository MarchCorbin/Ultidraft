"""The narrator must always move on: exactly once per utterance, never twice."""

from __future__ import annotations

import os

import pytest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

QtWidgets = pytest.importorskip("PySide6.QtWidgets")

from ultidraft.tts.engine import SpeechEngine  # noqa: E402


@pytest.fixture(scope="module")
def app():
    existing = QtWidgets.QApplication.instance()
    yield existing or QtWidgets.QApplication([])


@pytest.fixture()
def engine(app):
    eng = SpeechEngine()
    yield eng
    eng.stop()


def _count_finishes(engine: SpeechEngine) -> list[int]:
    hits: list[int] = []
    engine.finished_utterance.connect(lambda: hits.append(1))
    return hits


def test_blank_text_still_advances(engine):
    hits = _count_finishes(engine)
    engine.speak("   ")
    assert len(hits) == 1
    assert not engine.is_waiting()


def test_advance_fires_once_even_if_several_recovery_paths_trigger(engine):
    hits = _count_finishes(engine)
    engine._begin_utterance()
    token = engine._token
    engine._advance(token)
    engine._advance(token)
    engine._advance(token, "stalled")
    assert len(hits) == 1


def test_events_from_a_previous_utterance_cannot_advance_the_new_one(engine):
    hits = _count_finishes(engine)
    engine._begin_utterance()
    stale = engine._token
    engine._begin_utterance()
    engine._advance(stale)
    assert hits == []
    assert engine.is_waiting()
    engine._advance(engine._token)
    assert len(hits) == 1


def test_stop_clears_the_in_flight_utterance(engine):
    hits = _count_finishes(engine)
    engine._begin_utterance()
    token = engine._token
    engine.stop()
    engine._advance(token)
    assert hits == []
    assert not engine.is_waiting()


def test_resuming_after_a_pause_between_clips_keeps_reading(engine):
    """Pausing in the gap between two clips used to strand the narrator."""
    hits = _count_finishes(engine)
    engine._begin_utterance()
    engine._advance(engine._token)  # clip ended, nothing in flight
    assert len(hits) == 1

    engine.pause()
    engine.resume()
    assert len(hits) == 2, "resume should ask for the next span"


def test_resuming_mid_clip_does_not_skip_ahead(engine):
    hits = _count_finishes(engine)
    engine._begin_utterance()
    engine.pause()
    engine.resume()
    assert hits == [], "an in-flight utterance must not be dropped on resume"


def test_one_failed_fetch_skips_the_line_but_keeps_the_voice(engine):
    hits = _count_finishes(engine)
    dropped: list[str] = []
    engine.failed.connect(dropped.append)

    engine._begin_utterance()
    engine._awaiting_key = "clip-a"
    engine._retried_key = "clip-a"  # retry already spent
    engine._on_synth_done("clip-a", "network hiccup")

    assert len(hits) == 1, "playback should move to the next line"
    assert dropped == [], "a single hiccup should not swap the voice"


def test_repeated_failures_fall_back_to_another_voice(engine):
    dropped: list[str] = []
    engine.failed.connect(dropped.append)

    for name in ("clip-a", "clip-b", "clip-c"):
        engine._begin_utterance()
        engine._awaiting_key = name
        engine._retried_key = name
        engine._on_synth_done(name, "network is down")

    assert dropped, "a dead network should trigger the local-voice fallback"


def test_a_success_resets_the_failure_streak(engine, tmp_path):
    dropped: list[str] = []
    engine.failed.connect(dropped.append)

    for name in ("clip-a", "clip-b"):
        engine._begin_utterance()
        engine._awaiting_key = name
        engine._retried_key = name
        engine._on_synth_done(name, "hiccup")
    assert engine._consecutive_failures == 2

    engine._on_synth_done("unrelated-clip", "")
    assert engine._consecutive_failures == 0

    engine._begin_utterance()
    engine._awaiting_key = "clip-c"
    engine._retried_key = "clip-c"
    engine._on_synth_done("clip-c", "hiccup")
    assert dropped == []


def test_half_written_cache_files_are_not_playable(engine, tmp_path):
    partial = tmp_path / "partial.mp3"
    partial.write_bytes(b"\x00" * 64)
    assert not engine._is_playable(partial)
    assert not engine._is_playable(tmp_path / "missing.mp3")
    whole = tmp_path / "whole.mp3"
    whole.write_bytes(b"\x00" * 4096)
    assert engine._is_playable(whole)


def test_synth_writes_through_a_staging_file(tmp_path, monkeypatch):
    """A cache path must never exist until the download is complete."""
    import asyncio

    from ultidraft.tts import engine as engine_mod

    dest = tmp_path / "clip.mp3"
    seen_during_write: list[bool] = []

    class FakeCommunicate:
        def __init__(self, text, voice, rate=None):
            pass

        async def save(self, path):
            seen_during_write.append(dest.exists())
            with open(path, "wb") as handle:
                handle.write(b"\x00" * 4096)

    monkeypatch.setitem(
        __import__("sys").modules, "edge_tts", type("m", (), {"Communicate": FakeCommunicate})
    )
    worker = engine_mod._SynthWorker("hello", "voice", "+0%", dest)
    asyncio.run(worker._synthesize())

    assert seen_during_write == [False]
    assert dest.stat().st_size == 4096
    assert list(tmp_path.glob("*.part")) == []
