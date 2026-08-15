"""Play spans back to back and report whether playback kept advancing.

Also injects a corrupt clip to prove the narrator recovers instead of sticking.

Run: python scripts/smoke_playback.py
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from PySide6.QtCore import QTimer  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

from ultidraft.tts.engine import SpeechEngine  # noqa: E402
from ultidraft.tts.voices import edge_choices, parse_voice_id  # noqa: E402

LINES = [
    "The city had a way of swallowing men whole, and it never bothered to spit out the bones.",
    "He counted the exits twice before he sat down, the way his father had taught him.",
    "Rain came sideways off the river, hard enough to sting.",
    "This line is deliberately backed by a corrupt clip.",
    "Nobody in the room believed him, and that was exactly what he wanted.",
    "She set the envelope on the table and did not let go of it.",
    "The hallway smelled of bleach and old smoke.",
    "He waited until the door clicked shut before he spoke again.",
]
CORRUPT_INDEX = 3


def main() -> int:
    app = QApplication([])
    engine = SpeechEngine()
    engine.set_voice(edge_choices()[0].id)
    voice = parse_voice_id(engine.current_voice_id())[2]

    # Poison one clip with plausible-looking but unplayable bytes.
    bad = engine._cache_path(LINES[CORRUPT_INDEX], voice)
    bad.parent.mkdir(parents=True, exist_ok=True)
    bad.write_bytes(b"\x00" * 5000)
    print(f"injected corrupt clip for line {CORRUPT_INDEX + 1}")

    state = {"i": 0, "audio_ms": 0, "started": 0.0, "notes": 0}

    def speak_next() -> None:
        i = state["i"]
        if i >= len(LINES):
            wall = time.monotonic() - state["started"]
            audio = state["audio_ms"] / 1000.0
            overhead = wall - audio
            print(f"\nspans played: {len(LINES)}")
            print(f"audio length: {audio:.1f}s")
            print(f"wall clock:   {wall:.1f}s")
            print(f"dead air:     {overhead:.1f}s total, {overhead / len(LINES):.2f}s per span")
            print(f"recoveries:   {state['notes']}")
            healthy = overhead < 1.0 * len(LINES) and state["i"] == len(LINES)
            print("RESULT: PASS" if healthy else "RESULT: FAIL")
            app.quit()
            return
        print(f"[{i + 1}/{len(LINES)}] {LINES[i][:48]}...")
        engine.speak(LINES[i])
        engine.prefetch_many(LINES[i + 1 : i + 4])

    def on_finished() -> None:
        player = engine._player
        if player is not None:
            state["audio_ms"] += max(0, player.duration())
        state["i"] += 1
        speak_next()

    engine.finished_utterance.connect(on_finished)
    engine.preparing.connect(lambda msg: print(f"    ! {msg}"))
    engine.failed.connect(lambda msg: print(f"    FAILED: {msg}"))

    def note(msg: str) -> None:
        if "Skipped" in msg or "Recovered" in msg:
            state["notes"] += 1

    engine.preparing.connect(note)

    def start() -> None:
        state["started"] = time.monotonic()
        speak_next()

    QTimer.singleShot(0, start)
    QTimer.singleShot(240_000, app.quit)
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
