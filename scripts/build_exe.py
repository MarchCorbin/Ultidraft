"""Build the Windows app folder and drop a Desktop shortcut."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "dist" / "Ultidraft"
EXE = DIST / "Ultidraft.exe"


def run(cmd: list[str]) -> None:
    print("+", " ".join(cmd))
    subprocess.check_call(cmd, cwd=ROOT)


def ensure_icon() -> None:
    ico = ROOT / "src" / "ultidraft" / "assets" / "ultidraft.ico"
    if ico.exists():
        return
    run([sys.executable, str(ROOT / "scripts" / "make_icon.py")])


def ensure_pyinstaller() -> None:
    try:
        import PyInstaller  # noqa: F401
    except ImportError:
        run([sys.executable, "-m", "pip", "install", "pyinstaller>=6.0"])


def create_desktop_shortcut() -> Path:
    desktop = Path.home() / "Desktop"
    desktop.mkdir(parents=True, exist_ok=True)
    shortcut = desktop / "Ultidraft.lnk"
    script = f"""
$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut({str(shortcut)!r})
$link.TargetPath = {str(EXE)!r}
$link.WorkingDirectory = {str(DIST)!r}
$link.WindowStyle = 1
$link.Description = "Listen to a draft, mark notes, export for revision."
$link.IconLocation = {str(EXE)!r}
$link.Save()
"""
    subprocess.check_call(["powershell", "-NoProfile", "-Command", script])
    print(f"shortcut {shortcut}")
    return shortcut


def main() -> int:
    ensure_icon()
    ensure_pyinstaller()
    run([sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean", "Ultidraft.spec"])
    if not EXE.exists():
        raise SystemExit(f"Build finished but {EXE} is missing.")
    create_desktop_shortcut()
    print(f"launch {EXE}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
