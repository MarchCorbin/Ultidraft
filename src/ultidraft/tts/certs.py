"""Keep Edge TTS from dying when a frozen build cannot see cacert.pem."""

from __future__ import annotations

import os
import ssl
import sys
from pathlib import Path


def prepare_edge_ssl() -> Path | None:
    """Point SSL at a real CA bundle, or the OS store, before importing edge_tts.

    edge-tts calls ``ssl.create_default_context(cafile=certifi.where())`` at
    import time. In a broken PyInstaller folder that file is missing, which
    surfaces as ``[Errno 2] No such file or directory``.
    """
    pem = _resolve_cacert()
    if pem is not None:
        os.environ.setdefault("SSL_CERT_FILE", str(pem))
        os.environ.setdefault("REQUESTS_CA_BUNDLE", str(pem))
        _point_certifi_at(pem)
        return pem
    _allow_missing_cafile()
    return None


def _resolve_cacert() -> Path | None:
    candidates: list[Path] = []
    try:
        import certifi

        candidates.append(Path(certifi.where()))
    except Exception:
        pass
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        root = Path(meipass)
        candidates.extend((root / "certifi" / "cacert.pem", root / "cacert.pem"))
    if getattr(sys, "frozen", False):
        exe_dir = Path(sys.executable).resolve().parent
        candidates.extend(
            (
                exe_dir / "_internal" / "certifi" / "cacert.pem",
                exe_dir / "certifi" / "cacert.pem",
            )
        )
    seen: set[Path] = set()
    for path in candidates:
        resolved = path.resolve()
        if resolved in seen:
            continue
        seen.add(resolved)
        if resolved.is_file():
            return resolved
    return None


def _point_certifi_at(pem: Path) -> None:
    try:
        import certifi
    except Exception:
        return
    if Path(certifi.where()) == pem:
        return
    certifi.where = lambda: str(pem)  # type: ignore[method-assign]


def _allow_missing_cafile() -> None:
    original = ssl.create_default_context

    def patched(
        purpose: ssl.Purpose = ssl.Purpose.SERVER_AUTH,
        *,
        cafile: str | None = None,
        capath: str | None = None,
        cadata: str | bytes | None = None,
    ) -> ssl.SSLContext:
        if cafile and not Path(cafile).is_file():
            cafile = None
        return original(purpose, cafile=cafile, capath=capath, cadata=cadata)

    ssl.create_default_context = patched  # type: ignore[assignment]
