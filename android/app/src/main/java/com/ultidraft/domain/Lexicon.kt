package com.ultidraft.domain

/** Per-book pronunciation rules. Applied only to spoken text, never the manuscript. */
data class LexiconRule(val written: String, val spoken: String) {
    fun toJson(): JsonValue = JsonValue.obj(
        "written" to JsonValue.str(written),
        "spoken" to JsonValue.str(spoken),
    )

    companion object {
        fun fromJson(value: JsonValue): LexiconRule = LexiconRule(
            written = value.string("written").trim(),
            spoken = value.string("spoken").trim(),
        )
    }
}

fun applyLexicon(text: String, rules: List<LexiconRule>): String {
    var spoken = text
    for (rule in rules) {
        if (rule.written.isEmpty() || rule.spoken.isEmpty()) continue
        val pattern = Regex("\\b" + Regex.escape(rule.written) + "\\b", RegexOption.IGNORE_CASE)
        // Lambda form: the result is inserted literally, so "$" in a rule stays safe.
        spoken = pattern.replace(spoken) { rule.spoken }
    }
    return spoken
}
