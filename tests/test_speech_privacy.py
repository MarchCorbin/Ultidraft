from __future__ import annotations

import pytest

from ultidraft.stt.recognizer import (
    SpeechPrivacyError,
    finalize_dictation,
    is_speech_privacy_error,
    join_phrases,
    level_from_state,
    speech_privacy_accepted,
    transcribe_once,
)


def test_missing_registry_key_means_privacy_not_accepted(monkeypatch):
    import ultidraft.stt.recognizer as rec

    def boom(*_args, **_kwargs):
        raise OSError(2, "not found")

    monkeypatch.setattr(rec.winreg, "OpenKey", boom)
    assert speech_privacy_accepted() is False


def test_has_accepted_one_means_ready(monkeypatch):
    import ultidraft.stt.recognizer as rec

    class FakeKey:
        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return False

    monkeypatch.setattr(rec.winreg, "OpenKey", lambda *_args, **_kwargs: FakeKey())
    monkeypatch.setattr(rec.winreg, "QueryValueEx", lambda *_args: (1, rec.winreg.REG_DWORD))
    assert speech_privacy_accepted() is True


def test_privacy_error_detected_from_message_and_codes():
    assert is_speech_privacy_error(
        OSError("The speech privacy policy was not accepted prior to attempting a speech recognition")
    )
    err = OSError("win error")
    err.winerror = 2147199735
    assert is_speech_privacy_error(err)
    hresult = OSError("com")
    hresult.hresult = 0x80045509
    assert is_speech_privacy_error(hresult)
    assert not is_speech_privacy_error(OSError("microphone unplugged"))


def test_transcribe_once_raises_before_calling_winrt_when_policy_is_off(monkeypatch):
    import ultidraft.stt.recognizer as rec

    monkeypatch.setattr(rec, "speech_privacy_accepted", lambda: False)

    def fail_run(_coro):
        raise AssertionError("WinRT should not start until speech privacy is accepted")

    monkeypatch.setattr(rec.asyncio, "run", fail_run)
    with pytest.raises(SpeechPrivacyError):
        transcribe_once()


def test_join_phrases_skips_blank_chunks():
    assert join_phrases(["  Hello. ", "", "Keep going."]) == "Hello. Keep going."


def test_finalize_keeps_a_partial_if_windows_never_finalized():
    assert finalize_dictation([], "the sentence was too long") == "the sentence was too long"
    assert finalize_dictation(["Done."], "ignored leftover") == "Done."


def test_speech_detected_drives_the_meter_higher_than_idle():
    assert level_from_state("SPEECH_DETECTED") > level_from_state("IDLE")
    assert level_from_state("SpeechRecognizerState.SOUND_STARTED") > 0.5
