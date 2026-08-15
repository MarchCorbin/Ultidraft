"""Parse a markdown manuscript into chapters and speakable sentences.

No Qt types here — this module is the Android contract for how a draft is sliced.
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from pathlib import Path

HEADING_RE = re.compile(r"^(#{1,6})\s+(.*)$")
HR_RE = re.compile(r"^-{3,}$")
CHAPTER_NUM_RE = re.compile(r"chapter\s+(\d+)", re.IGNORECASE)
ABBREVIATIONS = {
    "mr",
    "mrs",
    "ms",
    "dr",
    "jr",
    "sr",
    "vs",
    "etc",
    "eg",
    "ie",
    "st",
    "am",
    "pm",
    "prof",
    "rev",
    "gen",
    "col",
    "lt",
    "sgt",
}
CLOSING_QUOTES = set("\"'”’")


@dataclass(frozen=True)
class Sentence:
    index: int
    chapter_id: str
    chapter_title: str
    paragraph_index: int
    text: str
    kind: str  # heading | meta | body


@dataclass(frozen=True)
class Chapter:
    id: str
    title: str
    start_index: int
    sentence_count: int


@dataclass(frozen=True)
class SpeakSpan:
    start_index: int
    end_index: int
    text: str
    sentences: tuple[Sentence, ...]


# Keep a few minutes of prose in one neural-voice request. The service accepts
# 4096-byte chunks, so staying comfortably below that avoids both a service-side
# split and an application-side player restart every handful of sentences.
SPAN_MAX_BYTES = 2400
SPAN_MAX_SENTENCES = 24


@dataclass(frozen=True)
class Manuscript:
    path: Path
    hash: str
    raw: str
    chapters: tuple[Chapter, ...]
    sentences: tuple[Sentence, ...]

    def sentence_at(self, index: int) -> Sentence | None:
        if 0 <= index < len(self.sentences):
            return self.sentences[index]
        return None

    def chapter_start(self, chapter_id: str) -> int | None:
        for chapter in self.chapters:
            if chapter.id == chapter_id:
                return chapter.start_index
        return None

    def next_paragraph_index(self, index: int) -> int:
        current = self.sentence_at(index)
        if current is None:
            return index
        for sentence in self.sentences[index + 1 :]:
            if sentence.paragraph_index != current.paragraph_index:
                return sentence.index
        return max(len(self.sentences) - 1, 0)

    def previous_paragraph_index(self, index: int) -> int:
        current = self.sentence_at(index)
        if current is None:
            return index
        target_para = current.paragraph_index - 1
        if target_para < 0:
            return 0
        for sentence in self.sentences:
            if sentence.paragraph_index == target_para:
                return sentence.index
        return 0

    def span_from(self, index: int) -> SpeakSpan | None:
        """Pack consecutive chapter text into one long, naturally paced clip."""
        first = self.sentence_at(index)
        if first is None:
            return None
        parts = [first]
        spoken = first.text
        byte_count = len(spoken.encode("utf-8"))
        for sentence in self.sentences[index + 1 :]:
            if sentence.chapter_id != first.chapter_id:
                break
            previous = parts[-1]
            separator = (
                "\n\n"
                if sentence.paragraph_index != previous.paragraph_index
                or sentence.kind != previous.kind
                else " "
            )
            extra = separator + sentence.text
            extra_bytes = len(extra.encode("utf-8"))
            if (
                len(parts) >= SPAN_MAX_SENTENCES
                or byte_count + extra_bytes > SPAN_MAX_BYTES
            ):
                break
            parts.append(sentence)
            spoken += extra
            byte_count += extra_bytes
        return SpeakSpan(parts[0].index, parts[-1].index, spoken, tuple(parts))

    def nearby(self, index: int, radius: int = 5) -> tuple[Sentence, ...]:
        start = max(0, index - radius)
        end = min(len(self.sentences), index + radius + 1)
        return self.sentences[start:end]

    def span_texts_ahead(self, index: int, count: int) -> list[str]:
        texts: list[str] = []
        cursor = index
        while len(texts) < count and cursor < len(self.sentences):
            span = self.span_from(cursor)
            if span is None:
                break
            texts.append(span.text)
            cursor = span.end_index + 1
        return texts


_MARKUP = set("#*_`")


def locate_in_markdown(raw: str, needle: str, start: int = 0) -> tuple[int, int] | None:
    """Find cleaned prose inside markdown, ignoring emphasis marks and extra space."""
    needle = re.sub(r"\s+", " ", needle).strip()
    if not needle:
        return None
    first = needle[0]
    for origin in range(max(0, start), len(raw)):
        end = _match_cleaned(raw, origin, needle)
        if end is None:
            continue
        begin = origin
        while begin < end and raw[begin] != first:
            begin += 1
        return begin, end
    return None


def sentence_index_at_offset(raw: str, sentences: tuple[Sentence, ...] | list[Sentence], offset: int) -> int:
    """Return the sentence whose source span contains, or most recently passed, offset."""
    after = 0
    best = 0
    for sentence in sentences:
        span = locate_in_markdown(raw, sentence.text, after)
        if span is None:
            continue
        start, end = span
        after = end
        if start <= offset <= end:
            return sentence.index
        if start <= offset:
            best = sentence.index
    return best


def _match_cleaned(raw: str, origin: int, needle: str) -> int | None:
    ri = origin
    ni = 0
    n = len(raw)
    while ni < len(needle):
        if ri >= n:
            return None
        want = needle[ni]
        got = raw[ri]
        if want.isspace():
            if not (got.isspace() or got in _MARKUP):
                return None
            while ri < n and (raw[ri].isspace() or raw[ri] in _MARKUP):
                ri += 1
            ni += 1
            continue
        if got in _MARKUP and got != want:
            ri += 1
            continue
        if got.isspace():
            ri += 1
            continue
        if got == want:
            ri += 1
            ni += 1
            continue
        return None
    return ri


def load_manuscript(path: Path) -> Manuscript:
    raw = path.read_text(encoding="utf-8")
    digest = hashlib.sha256(raw.encode("utf-8")).hexdigest()
    chapters, sentences = parse_markdown(raw)
    return Manuscript(
        path=path,
        hash=digest,
        raw=raw,
        chapters=tuple(chapters),
        sentences=tuple(sentences),
    )


def parse_markdown(raw: str) -> tuple[list[Chapter], list[Sentence]]:
    blocks: list[tuple[str, list[str]]] = []
    current_title = "Front matter"
    current_lines: list[str] = []

    def flush() -> None:
        if current_lines or not blocks:
            blocks.append((current_title, list(current_lines)))

    for line in raw.splitlines():
        heading = HEADING_RE.match(line)
        if heading:
            flush()
            current_title = clean_inline_markdown(heading.group(2))
            current_lines = []
            continue
        current_lines.append(line)
    flush()

    if len(blocks) == 1 and blocks[0][0] == "Front matter" and not any(
        line.strip() for line in blocks[0][1]
    ):
        blocks = []

    sentences: list[Sentence] = []
    chapters: list[Chapter] = []
    paragraph_index = 0

    for chapter_ordinal, (title, lines) in enumerate(blocks):
        if title == "Front matter" and not any(line.strip() for line in lines):
            continue
        chapter_id = _chapter_id(title, chapter_ordinal)
        start = len(sentences)
        heading_para = paragraph_index
        sentences.append(
            Sentence(
                index=start,
                chapter_id=chapter_id,
                chapter_title=title,
                paragraph_index=heading_para,
                text=title,
                kind="heading",
            )
        )
        paragraph_index += 1

        paragraphs = _paragraphs(lines)
        for para in paragraphs:
            cleaned = clean_inline_markdown(para)
            if not cleaned:
                continue
            kind = "meta" if _looks_like_meta(cleaned) else "body"
            parts = split_sentences(cleaned)
            if not parts:
                continue
            for part in parts:
                sentences.append(
                    Sentence(
                        index=len(sentences),
                        chapter_id=chapter_id,
                        chapter_title=title,
                        paragraph_index=paragraph_index,
                        text=part,
                        kind=kind,
                    )
                )
            paragraph_index += 1

        chapters.append(
            Chapter(
                id=chapter_id,
                title=title,
                start_index=start,
                sentence_count=len(sentences) - start,
            )
        )

    return chapters, sentences


def split_sentences(text: str) -> list[str]:
    """Split prose into sentences without breaking closing dialogue punctuation."""
    text = re.sub(r"\s+", " ", text).strip()
    if not text:
        return []

    sentences: list[str] = []
    buf: list[str] = []
    i = 0
    n = len(text)

    while i < n:
        char = text[i]
        if char == "." and i + 2 < n and text[i + 1] == "." and text[i + 2] == ".":
            buf.extend("...")
            i += 3
            i = _consume_closing_quotes(text, i, buf)
            if _is_boundary(text, i, buf, ellipsis=True):
                sentences.append("".join(buf).strip())
                buf = []
                i = _skip_space(text, i)
            continue

        buf.append(char)
        if char in ".!?":
            i += 1
            i = _consume_closing_quotes(text, i, buf)
            if _is_boundary(text, i, buf, ellipsis=False):
                sentences.append("".join(buf).strip())
                buf = []
                i = _skip_space(text, i)
            continue
        i += 1

    leftover = "".join(buf).strip()
    if leftover:
        sentences.append(leftover)
    return sentences


def clean_inline_markdown(text: str) -> str:
    text = text.strip()
    text = re.sub(r"\*\*(.+?)\*\*", r"\1", text)
    text = re.sub(r"__(.+?)__", r"\1", text)
    text = re.sub(r"(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)", r"\1", text)
    text = re.sub(r"`(.+?)`", r"\1", text)
    return text.strip()


def _chapter_id(title: str, ordinal: int) -> str:
    match = CHAPTER_NUM_RE.search(title)
    if match:
        return f"ch-{int(match.group(1)):02d}"
    return f"ch-{ordinal + 1:02d}"


def _paragraphs(lines: list[str]) -> list[str]:
    paragraphs: list[str] = []
    buf: list[str] = []

    def flush_para() -> None:
        if buf:
            paragraphs.append(" ".join(buf).strip())
            buf.clear()

    for line in lines:
        stripped = line.strip()
        if not stripped or HR_RE.match(stripped):
            flush_para()
            continue
        if _looks_like_meta(clean_inline_markdown(stripped)):
            flush_para()
            paragraphs.append(stripped)
            continue
        buf.append(stripped)
    flush_para()
    return [para for para in paragraphs if para]


def _looks_like_meta(text: str) -> bool:
    lowered = text.lower()
    return lowered.startswith("timeline:") or lowered.startswith("pov:")


def _consume_closing_quotes(text: str, i: int, buf: list[str]) -> int:
    n = len(text)
    while i < n and text[i] in CLOSING_QUOTES:
        buf.append(text[i])
        i += 1
    return i


def _skip_space(text: str, i: int) -> int:
    n = len(text)
    while i < n and text[i].isspace():
        i += 1
    return i


def _last_word(buf: list[str]) -> str:
    run = "".join(buf)
    parts = re.findall(r"[A-Za-z0-9']+", run)
    return parts[-1] if parts else ""


def _is_boundary(text: str, i: int, buf: list[str], *, ellipsis: bool) -> bool:
    if i < len(text) and not text[i].isspace():
        return False
    word = _last_word(buf).rstrip(".").lower()
    if not ellipsis and "".join(buf).rstrip()[-1:] == "." and word in ABBREVIATIONS:
        return False
    rest = text[i:].lstrip()
    if not rest:
        return True
    first = rest[0]
    if first.islower():
        return False
    return True
