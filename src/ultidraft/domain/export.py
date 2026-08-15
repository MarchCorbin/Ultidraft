"""Write Cursor-ready listening notes. Quote anchors, not page numbers."""

from __future__ import annotations

from datetime import date
from pathlib import Path

from ultidraft.domain.notes import Sidecar


def export_notes_markdown(sidecar: Sidecar, manuscript_name: str) -> str:
    today = date.today().isoformat()
    lines = [
        f"# Ultidraft notes — {today}",
        f"Manuscript: {manuscript_name}",
        f"Hash: {sidecar.manuscript_hash}",
        "",
    ]
    if not sidecar.notes:
        lines.append("_No notes yet._")
        lines.append("")
        return "\n".join(lines)

    for note in sidecar.notes:
        context = _context_line(note.context_before, note.context_after)
        lines.extend(
            [
                f"## {note.id}",
                f"- Chapter: {note.chapter_title}",
                f'- Anchor quote: "{note.anchor_quote}"',
                f"- Context: {context}",
                f"- Note: {note.body}",
                "",
            ]
        )
    return "\n".join(lines)


def write_notes_markdown(path: Path, sidecar: Sidecar, manuscript_name: str) -> Path:
    path.write_text(export_notes_markdown(sidecar, manuscript_name), encoding="utf-8")
    return path


def default_export_path(manuscript_path: Path) -> Path:
    return manuscript_path.with_name("listening-notes.md")


def _context_line(before: str, after: str) -> str:
    left = before.strip() if before else ""
    right = after.strip() if after else ""
    if left and right:
        return f'"{left} [HERE] {right}"'
    if left:
        return f'"{left} [HERE]"'
    if right:
        return f'"[HERE] {right}"'
    return "[HERE]"
