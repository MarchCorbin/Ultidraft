package com.ultidraft.domain

/**
 * The exact string handed to the speech engine, plus where each sentence sits inside it.
 *
 * Android reports progress as character ranges into the string it was given. Pronunciation
 * rules change lengths ("Sk4ms" -> "scams"), so the offsets have to be measured on the
 * spoken text, not the manuscript text, or the highlight drifts further out of step with
 * the voice the longer a rule-heavy chapter runs.
 */
data class SpokenSpan(
    val text: String,
    val sentences: List<Sentence>,
    val ranges: List<IntRange>,
) {
    /** The sentence being spoken at [offset] in [text]. */
    fun sentenceAtOffset(offset: Int): Sentence? {
        for (i in ranges.indices) {
            if (offset <= ranges[i].last) return sentences[i]
        }
        return sentences.lastOrNull()
    }
}

fun spokenSpan(span: SpeakSpan, rules: List<LexiconRule>): SpokenSpan {
    val out = StringBuilder()
    val ranges = mutableListOf<IntRange>()
    span.sentences.forEachIndexed { i, sentence ->
        if (i > 0) {
            val previous = span.sentences[i - 1]
            val separator =
                if (sentence.paragraphIndex != previous.paragraphIndex || sentence.kind != previous.kind) "\n\n"
                else " "
            out.append(separator)
        }
        val start = out.length
        out.append(if (rules.isEmpty()) sentence.text else applyLexicon(sentence.text, rules))
        ranges.add(start until out.length)
    }
    return SpokenSpan(out.toString(), span.sentences, ranges)
}
