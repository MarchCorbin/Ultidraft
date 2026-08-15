"""Per-book pronunciation rules. Applied only to spoken text, never the manuscript."""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass


@dataclass
class LexiconRule:
    written: str
    spoken: str

    def to_dict(self) -> dict:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict) -> LexiconRule:
        return cls(
            written=str(data.get("written", "")).strip(),
            spoken=str(data.get("spoken", "")).strip(),
        )


def apply_lexicon(text: str, rules: list[LexiconRule]) -> str:
    spoken = text
    for rule in rules:
        if not rule.written or not rule.spoken:
            continue
        pattern = re.compile(r"\b" + re.escape(rule.written) + r"\b", re.IGNORECASE)
        spoken = pattern.sub(rule.spoken, spoken)
    return spoken
