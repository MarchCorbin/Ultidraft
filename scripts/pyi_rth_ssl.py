"""PyInstaller runtime hook: put cacert.pem on SSL_CERT_FILE before imports."""

from __future__ import annotations

import os
import sys
from pathlib import Path


def _apply() -> None:
    meipass = getattr(sys, "_MEIPASS", None)
    if not meipass:
        return
    for candidate in (
        Path(meipass) / "certifi" / "cacert.pem",
        Path(meipass) / "cacert.pem",
    ):
        if candidate.is_file():
            os.environ.setdefault("SSL_CERT_FILE", str(candidate))
            os.environ.setdefault("REQUESTS_CA_BUNDLE", str(candidate))
            return


_apply()
