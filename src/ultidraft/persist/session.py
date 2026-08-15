"""Last-opened file, sentence, and speed. Stored under %LOCALAPPDATA%\\Ultidraft."""

from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass
class Session:
    last_file: str = ""
    chapter_id: str = ""
    sentence_index: int = 0
    speed: float = 1.0
    voice_id: str = ""


def session_path() -> Path:
    root = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
    folder = Path(root) / "Ultidraft"
    folder.mkdir(parents=True, exist_ok=True)
    return folder / "session.json"


def load_session() -> Session:
    path = session_path()
    if not path.is_file():
        return Session()
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return Session()
    return Session(
        last_file=str(data.get("last_file", "")),
        chapter_id=str(data.get("chapter_id", "")),
        sentence_index=int(data.get("sentence_index", 0)),
        speed=float(data.get("speed", 1.0)),
        voice_id=str(data.get("voice_id", "")),
    )


def save_session(session: Session) -> Path:
    path = session_path()
    path.write_text(json.dumps(asdict(session), indent=2) + "\n", encoding="utf-8")
    return path
