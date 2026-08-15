import json
from pathlib import Path

from ultidraft.domain.export import export_notes_markdown
from ultidraft.domain.notes import Note, Sidecar, load_sidecar, save_sidecar, sidecar_path


def _sample_sidecar() -> Sidecar:
    sidecar = Sidecar.new("SODOM.md", "abc123")
    sidecar.add_note(
        Note(
            id=sidecar.next_note_id(),
            created="2026-08-14T20:00:00-06:00",
            chapter_title="Chapter 7: The Desert",
            anchor_quote="The angel did not look back.",
            context_before="The sun burned the ridge.",
            context_after="The sand took the rest.",
            sentence_index=184,
            body="This beat is rushed. Give Lot one more line of hesitation.",
        )
    )
    return sidecar


def test_sidecar_round_trip(tmp_path: Path):
    manuscript = tmp_path / "SODOM.md"
    manuscript.write_text("# draft\n", encoding="utf-8")
    sidecar = _sample_sidecar()
    path = save_sidecar(manuscript, sidecar)
    assert path == tmp_path / "SODOM.ultidraft.json"
    assert sidecar_path(manuscript) == path

    loaded = load_sidecar(manuscript, "abc123")
    assert loaded.manuscript_hash == "abc123"
    assert loaded.notes[0].id == "N001"
    assert loaded.notes[0].anchor_quote == "The angel did not look back."
    assert loaded.notes[0].body.startswith("This beat is rushed.")
    raw = json.loads(path.read_text(encoding="utf-8"))
    assert raw["version"] == 1
    assert raw["notes"][0]["sentence_index"] == 184


def test_export_markdown_contains_quote_and_body():
    markdown = export_notes_markdown(_sample_sidecar(), "SODOM.md")
    assert markdown.startswith("# Ultidraft notes — ")
    assert "Manuscript: SODOM.md" in markdown
    assert "Hash: abc123" in markdown
    assert "## N001" in markdown
    assert '- Anchor quote: "The angel did not look back."' in markdown
    assert "- Note: This beat is rushed. Give Lot one more line of hesitation." in markdown
    assert "[HERE]" in markdown


def test_next_note_id_increments():
    sidecar = _sample_sidecar()
    assert sidecar.next_note_id() == "N002"


def test_replace_note_keeps_id_and_updates_body():
    sidecar = _sample_sidecar()
    original = sidecar.note_by_id("N001")
    assert original is not None
    updated = Note(
        id=original.id,
        created=original.created,
        chapter_title=original.chapter_title,
        anchor_quote=original.anchor_quote,
        context_before=original.context_before,
        context_after=original.context_after,
        sentence_index=original.sentence_index,
        body="Give Lot one more line, and fix the typo.",
    )
    assert sidecar.replace_note(updated) is True
    assert sidecar.note_by_id("N001") is not None
    assert sidecar.note_by_id("N001").body == "Give Lot one more line, and fix the typo."
    assert sidecar.note_by_id("N001").created == original.created
    assert sidecar.replace_note(Note(
        id="N999",
        created=original.created,
        chapter_title=original.chapter_title,
        anchor_quote=original.anchor_quote,
        context_before="",
        context_after="",
        sentence_index=0,
        body="missing",
    )) is False
    markdown = export_notes_markdown(sidecar, "SODOM.md")
    assert "Give Lot one more line, and fix the typo." in markdown
    assert "This beat is rushed." not in markdown
