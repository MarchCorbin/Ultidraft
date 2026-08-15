from pathlib import Path

from ultidraft.domain.lexicon import LexiconRule, apply_lexicon
from ultidraft.domain.notes import Sidecar, load_sidecar, save_sidecar


def test_sk4ms_becomes_scams_in_any_case():
    rules = [LexiconRule(written="Sk4ms", spoken="scams")]
    assert apply_lexicon("Sk4ms fired up the engine.", rules) == "scams fired up the engine."
    assert apply_lexicon("SK4MS didn't answer.", rules) == "scams didn't answer."
    assert apply_lexicon('"Watch your feet," Sk4ms growled.', rules) == (
        '"Watch your feet," scams growled.'
    )


def test_lexicon_does_not_eat_unrelated_words():
    rules = [LexiconRule(written="OD", spoken="oh dee")]
    assert apply_lexicon("The code was older.", rules) == "The code was older."
    assert apply_lexicon("He offered OD.", rules) == "He offered oh dee."


def test_sidecar_keeps_lexicon(tmp_path: Path):
    manuscript = tmp_path / "SODOM.md"
    manuscript.write_text("# draft\n", encoding="utf-8")
    sidecar = Sidecar.new("SODOM.md", "abc123")
    sidecar.lexicon = [LexiconRule(written="Sk4ms", spoken="scams")]
    save_sidecar(manuscript, sidecar)
    loaded = load_sidecar(manuscript, "abc123")
    assert loaded.lexicon[0].written == "Sk4ms"
    assert loaded.lexicon[0].spoken == "scams"
