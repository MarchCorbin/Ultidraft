from __future__ import annotations

import sys
from pathlib import Path

from PySide6.QtCore import Qt
from PySide6.QtGui import QFont, QIcon
from PySide6.QtWidgets import QApplication

from ultidraft.ui.main_window import MainWindow

APP_USER_MODEL_ID = "Ultidraft.App.1"


def _icon_path() -> Path | None:
    here = Path(__file__).resolve().parent / "assets" / "ultidraft.ico"
    if here.exists():
        return here
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        bundled = Path(meipass) / "ultidraft" / "assets" / "ultidraft.ico"
        if bundled.exists():
            return bundled
    return None


def _apply_windows_app_id() -> None:
    if sys.platform != "win32":
        return
    try:
        import ctypes

        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(APP_USER_MODEL_ID)
    except Exception:
        pass


def main(argv: list[str] | None = None) -> int:
    args = argv if argv is not None else sys.argv
    _apply_windows_app_id()
    QApplication.setHighDpiScaleFactorRoundingPolicy(
        Qt.HighDpiScaleFactorRoundingPolicy.PassThrough
    )
    app = QApplication(args)
    app.setApplicationName("Ultidraft")
    app.setOrganizationName("Ultidraft")
    app.setApplicationDisplayName("Ultidraft")
    app.setFont(QFont("Segoe UI", 10))
    icon = _icon_path()
    if icon is not None:
        app.setWindowIcon(QIcon(str(icon)))
    window = MainWindow()
    window.show()
    window.restore_session()
    return app.exec()
