from __future__ import annotations

import ssl
from pathlib import Path

from ultidraft.tts.certs import prepare_edge_ssl, _allow_missing_cafile


def test_prepare_edge_ssl_finds_certifi_bundle():
    pem = prepare_edge_ssl()
    assert pem is not None
    assert pem.is_file()
    assert pem.stat().st_size > 1000


def test_missing_cafile_does_not_raise(tmp_path, monkeypatch):
    missing = tmp_path / "gone.pem"
    _allow_missing_cafile()
    ctx = ssl.create_default_context(cafile=str(missing))
    assert ctx is not None


def test_pointing_certifi_at_a_real_file(tmp_path, monkeypatch):
    import certifi
    from ultidraft.tts import certs

    fake = tmp_path / "cacert.pem"
    fake.write_text("not-a-real-bundle", encoding="utf-8")
    monkeypatch.setattr(certs, "_resolve_cacert", lambda: fake)
    found = certs.prepare_edge_ssl()
    assert found == fake.resolve()
    assert Path(certifi.where()) == fake.resolve()
