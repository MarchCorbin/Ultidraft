"""Voice catalog. Local Windows voices plus a short list of Edge neural narrators."""

from __future__ import annotations

from dataclasses import dataclass

DEFAULT_VOICE_ID = "edge:en-US-JennyNeural"

EDGE_VOICES: tuple[tuple[str, str], ...] = (
    ("en-US-JennyNeural", "Jenny — US neural"),
    ("en-US-AriaNeural", "Aria — US neural"),
    ("en-US-GuyNeural", "Guy — US neural"),
    ("en-US-ChristopherNeural", "Christopher — US neural"),
    ("en-US-EricNeural", "Eric — US neural"),
    ("en-US-MichelleNeural", "Michelle — US neural"),
    ("en-GB-SoniaNeural", "Sonia — British neural"),
    ("en-GB-RyanNeural", "Ryan — British neural"),
    ("en-AU-NatashaNeural", "Natasha — Australian neural"),
    ("en-AU-WilliamNeural", "William — Australian neural"),
)


@dataclass(frozen=True)
class VoiceChoice:
    id: str
    label: str
    backend: str
    engine: str
    name: str


def edge_choices() -> list[VoiceChoice]:
    return [
        VoiceChoice(
            id=f"edge:{name}",
            label=f"{label}  (internet)",
            backend="edge",
            engine="",
            name=name,
        )
        for name, label in EDGE_VOICES
    ]


def local_choices(voices: list[tuple[str, str]]) -> list[VoiceChoice]:
    """voices is (engine, voice_name) from QTextToSpeech."""
    seen: set[str] = set()
    out: list[VoiceChoice] = []
    ranked = sorted(voices, key=_local_rank)
    for engine, name in ranked:
        key = name.casefold().removesuffix(" desktop").strip()
        if key in seen:
            continue
        seen.add(key)
        out.append(
            VoiceChoice(
                id=f"local:{engine}:{name}",
                label=f"{name}  (this PC)",
                backend="local",
                engine=engine,
                name=name,
            )
        )
    return out


def parse_voice_id(voice_id: str) -> tuple[str, str, str]:
    """Return (backend, engine, name)."""
    if voice_id.startswith("edge:"):
        return "edge", "", voice_id.split(":", 1)[1]
    if voice_id.startswith("local:"):
        parts = voice_id.split(":", 2)
        if len(parts) == 3:
            return "local", parts[1], parts[2]
    return "edge", "", DEFAULT_VOICE_ID.split(":", 1)[1]


def speed_to_edge_rate(speed: float) -> str:
    speed = min(2.0, max(0.5, speed))
    percent = int(round((speed - 1.0) * 100))
    return f"{percent:+d}%"


def _local_rank(item: tuple[str, str]) -> tuple[int, str]:
    engine, name = item
    lowered = name.casefold()
    score = 50
    if "neural" in lowered or "natural" in lowered:
        score = 0
    elif "aria" in lowered or "jenny" in lowered:
        score = 1
    elif "mark" in lowered:
        score = 10
    elif "zira" in lowered:
        score = 11
    elif "david" in lowered:
        score = 12
    engine_score = 0 if engine == "winrt" else 1
    return (engine_score, score, lowered)
