"""Sidecar notes stored beside a manuscript. No Qt — Android v2 writes this shape."""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from ultidraft.domain.lexicon import LexiconRule

SIDECAR_VERSION = 1


@dataclass
class Note:
    id: str
    created: str
    chapter_title: str
    anchor_quote: str
    context_before: str
    context_after: str
    sentence_index: int
    body: str

    def to_dict(self) -> dict:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict) -> Note:
        return cls(
            id=str(data["id"]),
            created=str(data["created"]),
            chapter_title=str(data.get("chapter_title", "")),
            anchor_quote=str(data.get("anchor_quote", "")),
            context_before=str(data.get("context_before", "")),
            context_after=str(data.get("context_after", "")),
            sentence_index=int(data.get("sentence_index", 0)),
            body=str(data.get("body", "")),
        )


@dataclass
class Sidecar:
    version: int
    manuscript_path: str
    manuscript_hash: str
    position: dict
    notes: list[Note] = field(default_factory=list)
    lexicon: list[LexiconRule] = field(default_factory=list)

    def next_note_id(self) -> str:
        highest = 0
        for note in self.notes:
            match = note.id[1:] if note.id.startswith("N") else note.id
            try:
                highest = max(highest, int(match))
            except ValueError:
                continue
        return f"N{highest + 1:03d}"

    def add_note(self, note: Note) -> None:
        self.notes.append(note)

    def to_dict(self) -> dict:
        return {
            "version": self.version,
            "manuscript_path": self.manuscript_path,
            "manuscript_hash": self.manuscript_hash,
            "position": self.position,
            "notes": [note.to_dict() for note in self.notes],
            "lexicon": [rule.to_dict() for rule in self.lexicon],
        }

    @classmethod
    def new(cls, manuscript_path: str, manuscript_hash: str) -> Sidecar:
        return cls(
            version=SIDECAR_VERSION,
            manuscript_path=manuscript_path,
            manuscript_hash=manuscript_hash,
            position={"chapter_id": "", "sentence_index": 0},
            notes=[],
            lexicon=[],
        )

    @classmethod
    def from_dict(cls, data: dict) -> Sidecar:
        notes = [Note.from_dict(item) for item in data.get("notes", [])]
        lexicon = [LexiconRule.from_dict(item) for item in data.get("lexicon", [])]
        position = data.get("position") or {"chapter_id": "", "sentence_index": 0}
        return cls(
            version=int(data.get("version", SIDECAR_VERSION)),
            manuscript_path=str(data.get("manuscript_path", "")),
            manuscript_hash=str(data.get("manuscript_hash", "")),
            position={
                "chapter_id": str(position.get("chapter_id", "")),
                "sentence_index": int(position.get("sentence_index", 0)),
            },
            notes=notes,
            lexicon=lexicon,
        )


def sidecar_path(manuscript_path: Path) -> Path:
    return manuscript_path.with_name(f"{manuscript_path.stem}.ultidraft.json")


def load_sidecar(manuscript_path: Path, manuscript_hash: str) -> Sidecar:
    path = sidecar_path(manuscript_path)
    if not path.is_file():
        return Sidecar.new(manuscript_path.name, manuscript_hash)
    data = json.loads(path.read_text(encoding="utf-8"))
    sidecar = Sidecar.from_dict(data)
    sidecar.manuscript_path = manuscript_path.name
    return sidecar


def save_sidecar(manuscript_path: Path, sidecar: Sidecar) -> Path:
    path = sidecar_path(manuscript_path)
    path.write_text(
        json.dumps(sidecar.to_dict(), indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    return path


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
