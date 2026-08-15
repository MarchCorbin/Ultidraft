"""Drive the real window through the manuscript and confirm the position advances.

Run: python scripts/smoke_window.py [path-to-manuscript] [seconds]
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from PySide6.QtCore import QTimer  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

from ultidraft.ui.main_window import MainWindow  # noqa: E402

DEFAULT_BOOK = Path(r"C:\Users\march\Desktop\SODOM\SODOM.md")


def main() -> int:
    book = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_BOOK
    seconds = float(sys.argv[2]) if len(sys.argv) > 2 else 45.0
    if not book.is_file():
        print(f"no manuscript at {book}")
        return 2

    app = QApplication([])
    window = MainWindow()
    window._open_path(book)
    window.show()

    marks: list[tuple[float, int]] = []
    start = time.monotonic()

    def sample() -> None:
        # Only sample while playing, so deliberate pauses don't read as a stall.
        if window._playing:
            marks.append((time.monotonic() - start, window._index))

    ticker = QTimer()
    ticker.setInterval(500)
    ticker.timeout.connect(sample)

    def begin() -> None:
        window._toggle_play()
        ticker.start()

    def report() -> None:
        ticker.stop()
        window._engine.stop()
        if not marks:
            print("RESULT: FAIL (no samples)")
            app.quit()
            return
        indices = [i for _t, i in marks]
        stuck = 0
        worst = 0
        run = 0
        for prev, cur in zip(indices, indices[1:]):
            run = run + 1 if cur == prev else 0
            worst = max(worst, run)
        stuck = worst * 0.5
        print(f"start index: {indices[0]}   end index: {indices[-1]}")
        print(f"sentences advanced: {indices[-1] - indices[0]} over {seconds:.0f}s")
        print(f"longest stretch on one sentence: {stuck:.1f}s")
        healthy = indices[-1] > indices[0] and stuck < 12.0
        print("RESULT: PASS" if healthy else "RESULT: FAIL")
        app.quit()

    def pause_now() -> None:
        print("-- pause --")
        window._toggle_play()

    def resume_now() -> None:
        print("-- resume --")
        window._toggle_play()

    QTimer.singleShot(500, begin)
    # Pause/resume mid-book: the risky moment is landing in the gap between clips.
    for offset in (18_000, 34_000):
        QTimer.singleShot(offset, pause_now)
        QTimer.singleShot(offset + 2_500, resume_now)
    QTimer.singleShot(int(seconds * 1000) + 500, report)
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
