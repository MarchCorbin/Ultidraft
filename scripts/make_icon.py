"""Write a small Ultidraft .ico with the standard library only."""

from __future__ import annotations

import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SIZES = (16, 32, 48, 256)

INK = (30, 30, 30, 255)
PAGE = (236, 226, 208, 255)
RULE = (196, 165, 116, 255)
MARK = (61, 79, 102, 255)


def _px(size: int, x: int, y: int) -> tuple[int, int, int, int]:
    margin = max(1, size // 16)
    if x < margin or y < margin or x >= size - margin or y >= size - margin:
        return (0, 0, 0, 0)

    # Soft rounded square by knocking the far corners transparent.
    corner = size // 6
    dx = min(x, size - 1 - x)
    dy = min(y, size - 1 - y)
    if dx + dy < corner:
        return (0, 0, 0, 0)

    # Manuscript page inset.
    inset = size // 5
    if inset <= x < size - inset and inset <= y < size - inset:
        # Left margin rule
        if x < inset + max(1, size // 18):
            return RULE
        # A few "lines" of draft text
        line_h = max(1, size // 16)
        gap = max(1, size // 22)
        rel = y - inset - gap
        if rel > 0 and (rel % (line_h + gap)) < line_h and x < size - inset - gap:
            return MARK
        return PAGE
    return INK


def _png(size: int) -> bytes:
    raw = bytearray()
    for y in range(size):
        raw.append(0)
        for x in range(size):
            r, g, b, a = _px(size, x, y)
            raw.extend((r, g, b, a))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    return b"".join(
        [
            b"\x89PNG\r\n\x1a\n",
            chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)),
            chunk(b"IDAT", zlib.compress(bytes(raw), 9)),
            chunk(b"IEND", b""),
        ]
    )


def write_ico(path: Path) -> None:
    images = [_png(size) for size in SIZES]
    header = struct.pack("<HHH", 0, 1, len(images))
    offset = 6 + 16 * len(images)
    entries = bytearray()
    for size, data in zip(SIZES, images):
        entries.extend(
            struct.pack(
                "<BBBBHHII",
                size % 256,
                size % 256,
                0,
                0,
                1,
                32,
                len(data),
                offset,
            )
        )
        offset += len(data)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(header + bytes(entries) + b"".join(images))
    print(f"wrote {path} ({path.stat().st_size} bytes)")


def main() -> int:
    write_ico(ROOT / "src" / "ultidraft" / "assets" / "ultidraft.ico")
    write_ico(ROOT / "ultidraft.ico")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
