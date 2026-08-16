package com.ultidraft.domain

import java.security.MessageDigest

/**
 * Parse a markdown manuscript into chapters and speakable sentences.
 *
 * A direct port of `ultidraft.domain.manuscript` from the desktop app. No Android
 * types here: this file is the contract both clients slice a draft with, and it is
 * unit-tested against the same fixtures as the Python original.
 */

private val HEADING_RE = Regex("^(#{1,6})\\s+(.*)$")
private val HR_RE = Regex("^-{3,}$")
private val CHAPTER_NUM_RE = Regex("chapter\\s+(\\d+)", RegexOption.IGNORE_CASE)
private val WORD_RE = Regex("[A-Za-z0-9']+")
private val WHITESPACE_RUN = Regex("\\s+")

private val ABBREVIATIONS = setOf(
    "mr", "mrs", "ms", "dr", "jr", "sr", "vs", "etc", "eg", "ie", "st",
    "am", "pm", "prof", "rev", "gen", "col", "lt", "sgt",
)

private val CLOSING_QUOTES = setOf('"', '\'', '”', '’')

private val MARKUP = setOf('#', '*', '_', '`')

/** Keep a few minutes of prose in one utterance so the narrator does not restart constantly. */
const val SPAN_MAX_BYTES = 2400
const val SPAN_MAX_SENTENCES = 24

enum class SentenceKind { HEADING, META, BODY;

    val wire: String
        get() = name.lowercase()
}

data class Sentence(
    val index: Int,
    val chapterId: String,
    val chapterTitle: String,
    val paragraphIndex: Int,
    val text: String,
    val kind: SentenceKind,
)

data class Chapter(
    val id: String,
    val title: String,
    val startIndex: Int,
    val sentenceCount: Int,
)

data class SpeakSpan(
    val startIndex: Int,
    val endIndex: Int,
    val text: String,
    val sentences: List<Sentence>,
)

data class Manuscript(
    val name: String,
    val hash: String,
    val raw: String,
    val chapters: List<Chapter>,
    val sentences: List<Sentence>,
) {
    fun sentenceAt(index: Int): Sentence? =
        if (index in sentences.indices) sentences[index] else null

    fun chapterStart(chapterId: String): Int? =
        chapters.firstOrNull { it.id == chapterId }?.startIndex

    fun chapterOf(index: Int): Chapter? {
        val sentence = sentenceAt(index) ?: return null
        return chapters.firstOrNull { it.id == sentence.chapterId }
    }

    fun nextParagraphIndex(index: Int): Int {
        val current = sentenceAt(index) ?: return index
        for (i in index + 1 until sentences.size) {
            if (sentences[i].paragraphIndex != current.paragraphIndex) return sentences[i].index
        }
        return maxOf(sentences.size - 1, 0)
    }

    fun previousParagraphIndex(index: Int): Int {
        val current = sentenceAt(index) ?: return index
        val target = current.paragraphIndex - 1
        if (target < 0) return 0
        return sentences.firstOrNull { it.paragraphIndex == target }?.index ?: 0
    }

    /** Pack consecutive same-chapter text into one long, naturally paced utterance. */
    fun spanFrom(index: Int): SpeakSpan? {
        val first = sentenceAt(index) ?: return null
        val parts = mutableListOf(first)
        val spoken = StringBuilder(first.text)
        var byteCount = first.text.toByteArray(Charsets.UTF_8).size
        for (i in index + 1 until sentences.size) {
            val sentence = sentences[i]
            if (sentence.chapterId != first.chapterId) break
            val previous = parts.last()
            val separator =
                if (sentence.paragraphIndex != previous.paragraphIndex || sentence.kind != previous.kind) "\n\n"
                else " "
            val extra = separator + sentence.text
            val extraBytes = extra.toByteArray(Charsets.UTF_8).size
            if (parts.size >= SPAN_MAX_SENTENCES || byteCount + extraBytes > SPAN_MAX_BYTES) break
            parts.add(sentence)
            spoken.append(extra)
            byteCount += extraBytes
        }
        return SpeakSpan(parts.first().index, parts.last().index, spoken.toString(), parts.toList())
    }

    fun nearby(index: Int, radius: Int = 5): List<Sentence> {
        val start = maxOf(0, index - radius)
        val end = minOf(sentences.size, index + radius + 1)
        if (start >= end) return emptyList()
        return sentences.subList(start, end).toList()
    }

    fun spanTextsAhead(index: Int, count: Int): List<String> {
        val texts = mutableListOf<String>()
        var cursor = index
        while (texts.size < count && cursor < sentences.size) {
            val span = spanFrom(cursor) ?: break
            texts.add(span.text)
            cursor = span.endIndex + 1
        }
        return texts
    }

    /** Every sentence sharing a paragraph with [index], in order. */
    fun paragraphSentences(index: Int): List<Sentence> {
        val current = sentenceAt(index) ?: return emptyList()
        return sentences.filter { it.paragraphIndex == current.paragraphIndex }
    }
}

fun loadManuscript(name: String, raw: String): Manuscript {
    val (chapters, sentences) = parseMarkdown(raw)
    return Manuscript(
        name = name,
        hash = sha256(raw),
        raw = raw,
        chapters = chapters,
        sentences = sentences,
    )
}

fun sha256(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val out = StringBuilder(digest.size * 2)
    for (byte in digest) {
        val value = byte.toInt() and 0xFF
        out.append("0123456789abcdef"[value ushr 4])
        out.append("0123456789abcdef"[value and 0x0F])
    }
    return out.toString()
}

fun parseMarkdown(raw: String): Pair<List<Chapter>, List<Sentence>> {
    val blocks = mutableListOf<Pair<String, List<String>>>()
    var currentTitle = "Front matter"
    var currentLines = mutableListOf<String>()

    fun flush() {
        if (currentLines.isNotEmpty() || blocks.isEmpty()) {
            blocks.add(currentTitle to currentLines.toList())
        }
    }

    for (line in raw.splitLines()) {
        val heading = HEADING_RE.find(line)
        if (heading != null) {
            flush()
            currentTitle = cleanInlineMarkdown(heading.groupValues[2])
            currentLines = mutableListOf()
            continue
        }
        currentLines.add(line)
    }
    flush()

    var usable: List<Pair<String, List<String>>> = blocks
    if (blocks.size == 1 && blocks[0].first == "Front matter" &&
        blocks[0].second.none { it.isNotBlank() }
    ) {
        usable = emptyList()
    }

    val sentences = mutableListOf<Sentence>()
    val chapters = mutableListOf<Chapter>()
    var paragraphIndex = 0

    usable.forEachIndexed { chapterOrdinal, (title, lines) ->
        if (title == "Front matter" && lines.none { it.isNotBlank() }) return@forEachIndexed
        val chapterId = chapterId(title, chapterOrdinal)
        val start = sentences.size
        sentences.add(
            Sentence(
                index = start,
                chapterId = chapterId,
                chapterTitle = title,
                paragraphIndex = paragraphIndex,
                text = title,
                kind = SentenceKind.HEADING,
            )
        )
        paragraphIndex += 1

        for (para in paragraphs(lines)) {
            val cleaned = cleanInlineMarkdown(para)
            if (cleaned.isEmpty()) continue
            val kind = if (looksLikeMeta(cleaned)) SentenceKind.META else SentenceKind.BODY
            val parts = splitSentences(cleaned)
            if (parts.isEmpty()) continue
            for (part in parts) {
                sentences.add(
                    Sentence(
                        index = sentences.size,
                        chapterId = chapterId,
                        chapterTitle = title,
                        paragraphIndex = paragraphIndex,
                        text = part,
                        kind = kind,
                    )
                )
            }
            paragraphIndex += 1
        }

        chapters.add(
            Chapter(
                id = chapterId,
                title = title,
                startIndex = start,
                sentenceCount = sentences.size - start,
            )
        )
    }

    return chapters to sentences
}

/** Split prose into sentences without breaking closing dialogue punctuation. */
fun splitSentences(input: String): List<String> {
    val text = input.replace(WHITESPACE_RUN, " ").trim()
    if (text.isEmpty()) return emptyList()

    val sentences = mutableListOf<String>()
    val buf = StringBuilder()
    var i = 0
    val n = text.length

    while (i < n) {
        val char = text[i]
        if (char == '.' && i + 2 < n && text[i + 1] == '.' && text[i + 2] == '.') {
            buf.append("...")
            i += 3
            i = consumeClosingQuotes(text, i, buf)
            if (isBoundary(text, i, buf, ellipsis = true)) {
                sentences.add(buf.toString().trim())
                buf.setLength(0)
                i = skipSpace(text, i)
            }
            continue
        }

        buf.append(char)
        if (char == '.' || char == '!' || char == '?') {
            i += 1
            i = consumeClosingQuotes(text, i, buf)
            if (isBoundary(text, i, buf, ellipsis = false)) {
                sentences.add(buf.toString().trim())
                buf.setLength(0)
                i = skipSpace(text, i)
            }
            continue
        }
        i += 1
    }

    val leftover = buf.toString().trim()
    if (leftover.isNotEmpty()) sentences.add(leftover)
    return sentences
}

fun cleanInlineMarkdown(input: String): String {
    var text = input.trim()
    text = text.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    text = text.replace(Regex("__(.+?)__"), "$1")
    text = text.replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)"), "$1")
    text = text.replace(Regex("`(.+?)`"), "$1")
    return text.trim()
}

/** Find cleaned prose inside markdown, ignoring emphasis marks and extra space. */
fun locateInMarkdown(raw: String, needle: String, start: Int = 0): IntRange? {
    val target = needle.replace(WHITESPACE_RUN, " ").trim()
    if (target.isEmpty()) return null
    val first = target[0]
    for (origin in maxOf(0, start) until raw.length) {
        val end = matchCleaned(raw, origin, target) ?: continue
        var begin = origin
        while (begin < end && raw[begin] != first) begin += 1
        return begin until end
    }
    return null
}

/** The sentence whose source span contains, or most recently passed, [offset]. */
fun sentenceIndexAtOffset(raw: String, sentences: List<Sentence>, offset: Int): Int {
    var after = 0
    var best = 0
    for (sentence in sentences) {
        val span = locateInMarkdown(raw, sentence.text, after) ?: continue
        after = span.last + 1
        if (span.first <= offset && offset <= span.last + 1) return sentence.index
        if (span.first <= offset) best = sentence.index
    }
    return best
}

/**
 * The source range of the paragraph containing [index], for edit-at-the-playhead.
 *
 * Phones edit a paragraph rather than the whole draft: a novel in one text field is
 * unusable, and a paragraph is the unit you actually want to fix after hearing it.
 */
fun paragraphSourceSpan(raw: String, manuscript: Manuscript, index: Int): IntRange? {
    val parts = manuscript.paragraphSentences(index)
    if (parts.isEmpty()) return null
    var cursor = 0
    var begin = -1
    var end = -1
    // Walk every sentence up to the paragraph so repeated prose resolves to the right copy.
    for (sentence in manuscript.sentences) {
        val span = locateInMarkdown(raw, sentence.text, cursor) ?: continue
        cursor = span.last + 1
        if (sentence.paragraphIndex == parts[0].paragraphIndex) {
            if (begin < 0) begin = span.first
            end = span.last + 1
        } else if (begin >= 0) {
            break
        }
    }
    if (begin < 0 || end <= begin) return null
    return begin until end
}

private fun matchCleaned(raw: String, origin: Int, needle: String): Int? {
    var ri = origin
    var ni = 0
    val n = raw.length
    while (ni < needle.length) {
        if (ri >= n) return null
        val want = needle[ni]
        val got = raw[ri]
        if (want.isWhitespace()) {
            if (!(got.isWhitespace() || got in MARKUP)) return null
            while (ri < n && (raw[ri].isWhitespace() || raw[ri] in MARKUP)) ri += 1
            ni += 1
            continue
        }
        if (got in MARKUP && got != want) {
            ri += 1
            continue
        }
        if (got.isWhitespace()) {
            ri += 1
            continue
        }
        if (got == want) {
            ri += 1
            ni += 1
            continue
        }
        return null
    }
    return ri
}

private fun chapterId(title: String, ordinal: Int): String {
    val match = CHAPTER_NUM_RE.find(title)
    if (match != null) {
        val number = match.groupValues[1].toIntOrNull()
        if (number != null) return "ch-" + number.toString().padStart(2, '0')
    }
    return "ch-" + (ordinal + 1).toString().padStart(2, '0')
}

private fun paragraphs(lines: List<String>): List<String> {
    val paragraphs = mutableListOf<String>()
    val buf = mutableListOf<String>()

    fun flushPara() {
        if (buf.isNotEmpty()) {
            paragraphs.add(buf.joinToString(" ").trim())
            buf.clear()
        }
    }

    for (line in lines) {
        val stripped = line.trim()
        if (stripped.isEmpty() || HR_RE.matches(stripped)) {
            flushPara()
            continue
        }
        if (looksLikeMeta(cleanInlineMarkdown(stripped))) {
            flushPara()
            paragraphs.add(stripped)
            continue
        }
        buf.add(stripped)
    }
    flushPara()
    return paragraphs.filter { it.isNotEmpty() }
}

private fun looksLikeMeta(text: String): Boolean {
    val lowered = text.lowercase()
    return lowered.startsWith("timeline:") || lowered.startsWith("pov:")
}

private fun consumeClosingQuotes(text: String, start: Int, buf: StringBuilder): Int {
    var i = start
    while (i < text.length && text[i] in CLOSING_QUOTES) {
        buf.append(text[i])
        i += 1
    }
    return i
}

private fun skipSpace(text: String, start: Int): Int {
    var i = start
    while (i < text.length && text[i].isWhitespace()) i += 1
    return i
}

private fun lastWord(buf: StringBuilder): String =
    WORD_RE.findAll(buf.toString()).lastOrNull()?.value ?: ""

private fun isBoundary(text: String, i: Int, buf: StringBuilder, ellipsis: Boolean): Boolean {
    if (i < text.length && !text[i].isWhitespace()) return false
    val word = lastWord(buf).trimEnd('.').lowercase()
    val trimmed = buf.toString().trimEnd()
    if (!ellipsis && trimmed.endsWith(".") && word in ABBREVIATIONS) return false
    val rest = text.substring(minOf(i, text.length)).trimStart()
    if (rest.isEmpty()) return true
    return !rest[0].isLowerCase()
}

/** Python's `str.splitlines()`: no trailing empty element for a final newline. */
private fun String.splitLines(): List<String> {
    if (isEmpty()) return emptyList()
    val out = split("\r\n", "\n", "\r")
    return if (out.isNotEmpty() && out.last().isEmpty()) out.dropLast(1) else out
}
