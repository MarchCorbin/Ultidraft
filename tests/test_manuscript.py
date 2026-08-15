from ultidraft.domain.manuscript import (
    load_manuscript,
    locate_in_markdown,
    parse_markdown,
    sentence_index_at_offset,
    split_sentences,
)

SODOM_FIXTURE = """\
## **Chapter 1: The Heist**

**Timeline: Present — Day 0, 4:00 AM**
**POV: Junk**

---

"It's time."

Eyes opened in a haze of leftover red glow.

"Watch your feet," Sk4ms growled as Junk slid into the passenger seat.

"You ever think about leaving?" Junk asked, watching a group of migrants.

Sk4ms snorted. "And go where?"

Dr. Hale checked the HUD. Mr. Vale did not.

## **Chapter 7: The Desert**

The angel did not look back. The sand took the rest.
"""


def test_split_keeps_closing_dialogue_punctuation():
    assert split_sentences('"It\'s time."') == ['"It\'s time."']
    assert split_sentences('"Watch your feet," Sk4ms growled as Junk slid into the passenger seat.') == [
        '"Watch your feet," Sk4ms growled as Junk slid into the passenger seat.'
    ]


def test_split_keeps_question_with_its_closing_quote():
    parts = split_sentences('"You ever think about leaving?" Junk asked, watching a group of migrants.')
    assert parts[0] == '"You ever think about leaving?"'
    assert parts[1] == "Junk asked, watching a group of migrants."
    assert split_sentences('"Hello?" he said.') == ['"Hello?" he said.']


def test_split_handles_adjacent_sentences_and_abbreviations():
    parts = split_sentences('Sk4ms snorted. "And go where?"')
    assert parts == ["Sk4ms snorted.", '"And go where?"']
    parts = split_sentences("Dr. Hale checked the HUD. Mr. Vale did not.")
    assert parts == ["Dr. Hale checked the HUD.", "Mr. Vale did not."]


def test_parse_sodom_style_chapters_and_ids():
    chapters, sentences = parse_markdown(SODOM_FIXTURE)
    assert [chapter.id for chapter in chapters] == ["ch-01", "ch-07"]
    assert chapters[0].title == "Chapter 1: The Heist"
    assert chapters[1].title == "Chapter 7: The Desert"
    headings = [s.text for s in sentences if s.kind == "heading"]
    assert headings == ["Chapter 1: The Heist", "Chapter 7: The Desert"]
    metas = [s.text for s in sentences if s.kind == "meta"]
    assert metas == ["Timeline: Present — Day 0, 4:00 AM", "POV: Junk"]
    quotes = [s.text for s in sentences if s.text.startswith('"It\'s time.')]
    assert quotes == ['"It\'s time."']
    desert = [s for s in sentences if s.chapter_id == "ch-07" and s.kind == "body"]
    assert desert[0].text == "The angel did not look back."


def test_horizontal_rules_are_not_spoken():
    _chapters, sentences = parse_markdown(SODOM_FIXTURE)
    assert all(s.text != "---" for s in sentences)


def test_span_packs_a_chapter_with_natural_paragraph_breaks(tmp_path):
    path = tmp_path / "draft.md"
    path.write_text(SODOM_FIXTURE, encoding="utf-8")
    manuscript = load_manuscript(path)
    heading = manuscript.sentences[0]
    assert heading.kind == "heading"
    heading_span = manuscript.span_from(heading.index)
    assert heading_span is not None
    assert heading_span.start_index == heading.index
    assert heading_span.end_index > heading.index
    assert "Chapter 1: The Heist\n\nTimeline:" in heading_span.text
    assert "POV: Junk\n\n\"It's time.\"" in heading_span.text
    assert all(part.chapter_id == heading.chapter_id for part in heading_span.sentences)

    first_body = next(s for s in manuscript.sentences if s.kind == "body")
    span = manuscript.span_from(first_body.index)
    assert span is not None
    assert span.end_index > span.start_index
    assert "It's time." in span.text
    assert "Eyes opened" in span.text
    assert all(part.kind == "body" for part in span.sentences)
    ahead = manuscript.span_texts_ahead(span.end_index + 1, 2)
    assert ahead
    assert all(isinstance(text, str) and text for text in ahead)


def test_span_stays_below_neural_service_chunk_limit(tmp_path):
    long_fixture = "## Chapter 1\n\n" + "\n\n".join(
        f"Sentence {number} carries enough words to exercise the byte budget."
        for number in range(100)
    )
    path = tmp_path / "long.md"
    path.write_text(long_fixture, encoding="utf-8")

    span = load_manuscript(path).span_from(0)

    assert span is not None
    assert len(span.text.encode("utf-8")) <= 2400
    assert len(span.sentences) <= 24


def test_nearby_includes_current_and_neighbors(tmp_path):
    path = tmp_path / "draft.md"
    path.write_text(SODOM_FIXTURE, encoding="utf-8")
    manuscript = load_manuscript(path)
    first_body = next(s for s in manuscript.sentences if s.kind == "body")
    around = manuscript.nearby(first_body.index, radius=2)
    indexes = [sentence.index for sentence in around]
    assert first_body.index in indexes
    assert indexes == sorted(indexes)
    assert len(around) <= 5


def test_locate_finds_cleaned_heading_and_dialogue_in_markdown():
    span = locate_in_markdown(SODOM_FIXTURE, "Chapter 1: The Heist")
    assert span is not None
    assert "Chapter 1: The Heist" in SODOM_FIXTURE[span[0] : span[1]]

    first = locate_in_markdown(SODOM_FIXTURE, '"It\'s time."')
    assert first is not None
    assert SODOM_FIXTURE[first[0] : first[1]] == '"It\'s time."'

    later = locate_in_markdown(SODOM_FIXTURE, "The angel did not look back.", start=first[1])
    assert later is not None
    assert later[0] > first[1]


def test_sentence_index_follows_the_cursor_offset(tmp_path):
    path = tmp_path / "draft.md"
    path.write_text(SODOM_FIXTURE, encoding="utf-8")
    manuscript = load_manuscript(path)
    desert = next(s for s in manuscript.sentences if "angel" in s.text)
    span = locate_in_markdown(manuscript.raw, desert.text)
    assert span is not None
    assert sentence_index_at_offset(manuscript.raw, manuscript.sentences, span[0] + 3) == desert.index
