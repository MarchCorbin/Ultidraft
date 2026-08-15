from ultidraft.tts.voices import (
    DEFAULT_VOICE_ID,
    edge_choices,
    local_choices,
    parse_voice_id,
    speed_to_edge_rate,
)


def test_default_voice_is_jenny_neural():
    assert DEFAULT_VOICE_ID == "edge:en-US-JennyNeural"
    backend, engine, name = parse_voice_id(DEFAULT_VOICE_ID)
    assert backend == "edge"
    assert engine == ""
    assert name == "en-US-JennyNeural"


def test_parse_local_voice_id():
    backend, engine, name = parse_voice_id("local:winrt:Microsoft Zira")
    assert backend == "local"
    assert engine == "winrt"
    assert name == "Microsoft Zira"


def test_edge_rate_mapping():
    assert speed_to_edge_rate(1.0) == "+0%"
    assert speed_to_edge_rate(0.5) == "-50%"
    assert speed_to_edge_rate(1.5) == "+50%"
    assert speed_to_edge_rate(2.0) == "+100%"


def test_catalog_lists_neural_and_dedupes_local():
    choices = edge_choices()
    assert any(choice.name == "en-US-JennyNeural" for choice in choices)
    local = local_choices(
        [
            ("sapi", "Microsoft Zira Desktop"),
            ("winrt", "Microsoft Zira"),
            ("winrt", "Microsoft Mark"),
        ]
    )
    names = [choice.name for choice in local]
    assert names[0] == "Microsoft Mark"
    assert "Microsoft Zira" in names
    assert "Microsoft Zira Desktop" not in names
