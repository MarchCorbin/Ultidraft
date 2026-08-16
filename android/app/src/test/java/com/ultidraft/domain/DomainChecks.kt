package com.ultidraft.domain

/**
 * The domain contract, expressed once.
 *
 * These are the desktop app's pytest cases ported to Kotlin, plus the cases the phone
 * adds (sidecar merge, paragraph editing, JSON round-trip). They live as plain functions
 * with no test-framework imports so the same bodies run under Gradle/JUnit and under a
 * bare `kotlinc` on a machine that cannot reach Maven.
 */

val SODOM_FIXTURE = """
## **Chapter 1: The Heist**

**Timeline: Present — Day 0, 4:00 AM**
**POV: Junk**

---

"It's time."

Eyes opened in a haze of leftover red glow.

"Watch your feet," Sk4ms growled as Junk slid into the passenger seat.

"You ever think about leaving?" Junk asked, watching a group of migrants.

Sk4ms snorted. "And go where?"

Dr. Hale checked the HUD. Mr. Vale did not.

## **Chapter 7: The Desert**

The angel did not look back. The sand took the rest.
""".trimStart()

class CheckFailure(message: String) : AssertionError(message)

fun assertEq(expected: Any?, actual: Any?, what: String = "") {
    if (expected != actual) {
        throw CheckFailure("$what\n  expected: $expected\n  actual:   $actual")
    }
}

fun assertThat(condition: Boolean, what: String) {
    if (!condition) throw CheckFailure(what)
}

// --------------------------------------------------------------- sentence splitting

fun checkSplitKeepsClosingDialoguePunctuation() {
    assertEq(listOf("\"It's time.\""), splitSentences("\"It's time.\""), "quoted line stays whole")
    assertEq(
        listOf("\"Watch your feet,\" Sk4ms growled as Junk slid into the passenger seat."),
        splitSentences("\"Watch your feet,\" Sk4ms growled as Junk slid into the passenger seat."),
        "dialogue tag stays attached",
    )
}

fun checkSplitKeepsQuestionWithItsClosingQuote() {
    val parts = splitSentences("\"You ever think about leaving?\" Junk asked, watching a group of migrants.")
    assertEq("\"You ever think about leaving?\"", parts[0], "question keeps its quote")
    assertEq("Junk asked, watching a group of migrants.", parts[1], "tag becomes its own sentence")
    assertEq(listOf("\"Hello?\" he said."), splitSentences("\"Hello?\" he said."), "lowercase tag stays joined")
}

fun checkSplitHandlesAdjacentSentencesAndAbbreviations() {
    assertEq(
        listOf("Sk4ms snorted.", "\"And go where?\""),
        splitSentences("Sk4ms snorted. \"And go where?\""),
        "adjacent sentences split",
    )
    assertEq(
        listOf("Dr. Hale checked the HUD.", "Mr. Vale did not."),
        splitSentences("Dr. Hale checked the HUD. Mr. Vale did not."),
        "abbreviations do not split",
    )
}

fun checkEllipsisDoesNotShatterASentence() {
    assertEq(
        listOf("He waited... and then he left."),
        splitSentences("He waited... and then he left."),
        "ellipsis mid-sentence",
    )
    assertEq(
        listOf("He waited...", "Then he left."),
        splitSentences("He waited... Then he left."),
        "ellipsis before a new sentence",
    )
}

// ------------------------------------------------------------------------- parsing

fun checkParseSodomStyleChaptersAndIds() {
    val (chapters, sentences) = parseMarkdown(SODOM_FIXTURE)
    assertEq(listOf("ch-01", "ch-07"), chapters.map { it.id }, "chapter ids come from the heading number")
    assertEq("Chapter 1: The Heist", chapters[0].title, "first chapter title")
    assertEq("Chapter 7: The Desert", chapters[1].title, "second chapter title")
    assertEq(
        listOf("Chapter 1: The Heist", "Chapter 7: The Desert"),
        sentences.filter { it.kind == SentenceKind.HEADING }.map { it.text },
        "headings are spoken",
    )
    assertEq(
        listOf("Timeline: Present — Day 0, 4:00 AM", "POV: Junk"),
        sentences.filter { it.kind == SentenceKind.META }.map { it.text },
        "meta lines are tagged",
    )
    assertEq(
        listOf("\"It's time.\""),
        sentences.filter { it.text.startsWith("\"It's time.") }.map { it.text },
        "opening line survives markdown cleaning",
    )
    val desert = sentences.filter { it.chapterId == "ch-07" && it.kind == SentenceKind.BODY }
    assertEq("The angel did not look back.", desert[0].text, "chapter 7 opens correctly")
}

fun checkHorizontalRulesAreNotSpoken() {
    val (_, sentences) = parseMarkdown(SODOM_FIXTURE)
    assertThat(sentences.none { it.text == "---" }, "horizontal rules are never spoken")
}

fun checkChapterIdFallsBackToOrdinal() {
    // The ordinal counts the implicit "Front matter" block the desktop parser opens with,
    // so a draft that starts on a heading numbers from ch-02. That is a quirk, but it is
    // the desktop's quirk: chapter ids are written into the sidecar's `position`, so the
    // phone has to reproduce it exactly rather than fix it on one side only.
    val (chapters, _) = parseMarkdown("## Prologue\n\nA line.\n\n## Afterword\n\nAnother line.\n")
    assertEq(listOf("ch-02", "ch-03"), chapters.map { it.id }, "unnumbered chapters use their ordinal")

    val (numbered, _) = parseMarkdown("## Chapter 1: Start\n\nA line.\n\n## Chapter 7: End\n\nMore.\n")
    assertEq(listOf("ch-01", "ch-07"), numbered.map { it.id }, "numbered chapters ignore the ordinal")
}

// --------------------------------------------------------------------------- spans

fun checkSpanPacksAChapterWithNaturalParagraphBreaks() {
    val manuscript = loadManuscript("draft.md", SODOM_FIXTURE)
    val heading = manuscript.sentences[0]
    assertEq(SentenceKind.HEADING, heading.kind, "first sentence is the heading")
    val headingSpan = manuscript.spanFrom(heading.index)
    assertThat(headingSpan != null, "heading produces a span")
    assertEq(heading.index, headingSpan!!.startIndex, "span starts at the heading")
    assertThat(headingSpan.endIndex > heading.index, "span reaches past the heading")
    assertThat(
        headingSpan.text.contains("Chapter 1: The Heist\n\nTimeline:"),
        "heading and meta are separated by a paragraph break",
    )
    assertThat(
        headingSpan.text.contains("POV: Junk\n\n\"It's time.\""),
        "meta and body are separated by a paragraph break",
    )
    assertThat(
        headingSpan.sentences.all { it.chapterId == heading.chapterId },
        "a span never crosses a chapter",
    )

    val firstBody = manuscript.sentences.first { it.kind == SentenceKind.BODY }
    val span = manuscript.spanFrom(firstBody.index)!!
    assertThat(span.endIndex > span.startIndex, "body span packs more than one sentence")
    assertThat(span.text.contains("It's time."), "span opens with the current sentence")
    assertThat(span.text.contains("Eyes opened"), "span continues into the next paragraph")
    assertThat(span.sentences.all { it.kind == SentenceKind.BODY }, "kind change ends the span text run")
    val ahead = manuscript.spanTextsAhead(span.endIndex + 1, 2)
    assertThat(ahead.isNotEmpty() && ahead.all { it.isNotEmpty() }, "look-ahead spans are usable")
}

fun checkSpanStaysBelowChunkLimit() {
    val body = (0 until 100).joinToString("\n\n") {
        "Sentence $it carries enough words to exercise the byte budget."
    }
    val manuscript = loadManuscript("long.md", "## Chapter 1\n\n$body")
    val span = manuscript.spanFrom(0)!!
    assertThat(
        span.text.toByteArray(Charsets.UTF_8).size <= SPAN_MAX_BYTES,
        "span respects the byte budget",
    )
    assertThat(span.sentences.size <= SPAN_MAX_SENTENCES, "span respects the sentence budget")
}

fun checkNearbyIncludesCurrentAndNeighbours() {
    val manuscript = loadManuscript("draft.md", SODOM_FIXTURE)
    val firstBody = manuscript.sentences.first { it.kind == SentenceKind.BODY }
    val around = manuscript.nearby(firstBody.index, radius = 2)
    val indexes = around.map { it.index }
    assertThat(firstBody.index in indexes, "the current sentence is included")
    assertEq(indexes.sorted(), indexes, "neighbours stay in reading order")
    assertThat(around.size <= 5, "radius 2 yields at most five sentences")
}

fun checkParagraphNavigation() {
    val manuscript = loadManuscript("draft.md", SODOM_FIXTURE)
    val firstBody = manuscript.sentences.first { it.kind == SentenceKind.BODY }
    val next = manuscript.nextParagraphIndex(firstBody.index)
    assertThat(next > firstBody.index, "next paragraph moves forward")
    assertThat(
        manuscript.sentenceAt(next)!!.paragraphIndex > firstBody.paragraphIndex,
        "next paragraph really is a new paragraph",
    )
    assertEq(
        firstBody.paragraphIndex,
        manuscript.sentenceAt(manuscript.previousParagraphIndex(next))!!.paragraphIndex,
        "previous paragraph returns where we came from",
    )
    assertEq(0, manuscript.previousParagraphIndex(0), "previous from the top stays at the top")
}

// ------------------------------------------------------------------------ locating

fun checkLocateFindsCleanedHeadingAndDialogue() {
    val heading = locateInMarkdown(SODOM_FIXTURE, "Chapter 1: The Heist")
    assertThat(heading != null, "heading is locatable through ** markers")
    assertThat(
        SODOM_FIXTURE.substring(heading!!.first, heading.last + 1).contains("Chapter 1: The Heist"),
        "located heading covers the title text",
    )

    val first = locateInMarkdown(SODOM_FIXTURE, "\"It's time.\"")!!
    assertEq(
        "\"It's time.\"",
        SODOM_FIXTURE.substring(first.first, first.last + 1),
        "dialogue locates exactly",
    )

    val later = locateInMarkdown(SODOM_FIXTURE, "The angel did not look back.", start = first.last + 1)
    assertThat(later != null && later.first > first.last, "search honours the start offset")
}

fun checkSentenceIndexFollowsTheCursorOffset() {
    val manuscript = loadManuscript("draft.md", SODOM_FIXTURE)
    val desert = manuscript.sentences.first { it.text.contains("angel") }
    val span = locateInMarkdown(manuscript.raw, desert.text)!!
    assertEq(
        desert.index,
        sentenceIndexAtOffset(manuscript.raw, manuscript.sentences, span.first + 3),
        "a cursor inside a sentence resolves to that sentence",
    )
}

fun checkParagraphSourceSpanCoversTheWholeParagraph() {
    val raw = "## Chapter 1\n\nFirst line here. Second line here.\n\nA later paragraph.\n"
    val manuscript = loadManuscript("draft.md", raw)
    val second = manuscript.sentences.first { it.text.startsWith("Second") }
    val span = paragraphSourceSpan(raw, manuscript, second.index)!!
    assertEq(
        "First line here. Second line here.",
        raw.substring(span.first, span.last + 1),
        "the edit span is the whole paragraph, not one sentence",
    )

    val later = manuscript.sentences.first { it.text.startsWith("A later") }
    val laterSpan = paragraphSourceSpan(raw, manuscript, later.index)!!
    assertEq(
        "A later paragraph.",
        raw.substring(laterSpan.first, laterSpan.last + 1),
        "a following paragraph resolves to itself",
    )
}

fun checkEditingPreservesTheRestOfTheDraft() {
    val raw = "## Chapter 1\n\nFirst line here. Second line here.\n\nA later paragraph.\n"
    val manuscript = loadManuscript("draft.md", raw)
    val second = manuscript.sentences.first { it.text.startsWith("Second") }
    val span = paragraphSourceSpan(raw, manuscript, second.index)!!
    val edited = raw.substring(0, span.first) + "One better line." + raw.substring(span.last + 1)
    assertEq(
        "## Chapter 1\n\nOne better line.\n\nA later paragraph.\n",
        edited,
        "splicing an edited paragraph leaves the rest byte-identical",
    )
}

// ------------------------------------------------------------------------- lexicon

fun checkLexiconRewritesInAnyCase() {
    val rules = listOf(LexiconRule("Sk4ms", "scams"))
    assertEq("scams fired up the engine.", applyLexicon("Sk4ms fired up the engine.", rules), "plain case")
    assertEq("scams didn't answer.", applyLexicon("SK4MS didn't answer.", rules), "upper case")
    assertEq(
        "\"Watch your feet,\" scams growled.",
        applyLexicon("\"Watch your feet,\" Sk4ms growled.", rules),
        "inside dialogue",
    )
}

fun checkLexiconDoesNotEatUnrelatedWords() {
    val rules = listOf(LexiconRule("OD", "oh dee"))
    assertEq("The code was older.", applyLexicon("The code was older.", rules), "no substring matches")
    assertEq("He offered oh dee.", applyLexicon("He offered OD.", rules), "whole words are rewritten")
}

// -------------------------------------------------------------------- spoken span

fun checkSpokenSpanMatchesTheSpanTextWithoutRules() {
    val manuscript = loadManuscript("draft.md", SODOM_FIXTURE)
    val span = manuscript.spanFrom(0)!!
    val spoken = spokenSpan(span, emptyList())
    assertEq(span.text, spoken.text, "with no rules the spoken text is the span text verbatim")
    assertEq(span.sentences.size, spoken.ranges.size, "every sentence gets a range")
    span.sentences.forEachIndexed { i, sentence ->
        assertEq(
            sentence.text,
            spoken.text.substring(spoken.ranges[i].first, spoken.ranges[i].last + 1),
            "range $i points at its own sentence",
        )
    }
}

fun checkSpokenSpanRangesTrackLengthChangingRules() {
    val manuscript = loadManuscript("draft.md", SODOM_FIXTURE)
    val start = manuscript.sentences.first { it.text.contains("Sk4ms") }.index
    val span = manuscript.spanFrom(start)!!
    // "Sk4ms" -> "a much longer spoken form" shifts every following sentence.
    val rules = listOf(LexiconRule("Sk4ms", "a much longer spoken form"))
    val spoken = spokenSpan(span, rules)

    assertThat(!spoken.text.contains("Sk4ms"), "the rule is applied to the spoken text")
    span.sentences.forEachIndexed { i, sentence ->
        assertEq(
            applyLexicon(sentence.text, rules),
            spoken.text.substring(spoken.ranges[i].first, spoken.ranges[i].last + 1),
            "range $i still points at its own sentence after rewriting",
        )
    }
    // The highlight lookup is what the narrator actually calls.
    span.sentences.forEachIndexed { i, sentence ->
        assertEq(
            sentence.index,
            spoken.sentenceAtOffset(spoken.ranges[i].first)?.index,
            "an offset at the start of sentence $i resolves to it",
        )
        assertEq(
            sentence.index,
            spoken.sentenceAtOffset(spoken.ranges[i].last)?.index,
            "an offset at the end of sentence $i resolves to it",
        )
    }
    assertEq(
        span.sentences.last().index,
        spoken.sentenceAtOffset(spoken.text.length + 50)?.index,
        "an offset past the end clamps to the last sentence",
    )
}

// ------------------------------------------------------------- sidecar and export

private fun sampleSidecar(): Sidecar {
    val sidecar = Sidecar.new("SODOM.md", "abc123")
    sidecar.addNote(
        Note(
            id = sidecar.nextNoteId(),
            created = "2026-08-14T20:00:00-06:00",
            chapterTitle = "Chapter 7: The Desert",
            anchorQuote = "The angel did not look back.",
            contextBefore = "The sun burned the ridge.",
            contextAfter = "The sand took the rest.",
            sentenceIndex = 184,
            body = "This beat is rushed. Give Lot one more line of hesitation.",
        )
    )
    return sidecar
}

fun checkSidecarRoundTrip() {
    val sidecar = sampleSidecar()
    assertEq("SODOM.ultidraft.json", sidecarName("SODOM.md"), "sidecar sits beside the book")
    val text = sidecar.toJsonString()
    val loaded = Sidecar.parse(text, "SODOM.md", "abc123")
    assertEq("abc123", loaded.manuscriptHash, "hash round-trips")
    assertEq("M001", loaded.notes[0].id, "phone notes are M-numbered")
    assertEq("The angel did not look back.", loaded.notes[0].anchorQuote, "anchor round-trips")
    assertThat(loaded.notes[0].body.startsWith("This beat is rushed."), "body round-trips")

    val reparsed = JsonValue.parse(text)!!
    assertEq(1, reparsed.int("version"), "version is written as an int, not a float")
    assertEq(184, reparsed.array("notes")[0].int("sentence_index"), "sentence_index is an int")
    assertThat(text.contains("\"lexicon\": []"), "an empty lexicon still serialises")
}

fun checkSidecarSurvivesGarbageAndMissingFields() {
    val fresh = Sidecar.parse("not json at all", "SODOM.md", "abc123")
    assertEq(0, fresh.notes.size, "a corrupt sidecar does not lose the session")
    assertEq("abc123", fresh.manuscriptHash, "a corrupt sidecar is rebuilt against this draft")

    val sparse = Sidecar.parse("""{"version": 1, "notes": [{"id": "N001"}]}""", "SODOM.md", "abc123")
    assertEq("N001", sparse.notes[0].id, "a sparse note still loads")
    assertEq(0, sparse.notes[0].sentenceIndex, "missing numbers default to zero")
    assertEq("", sparse.notes[0].body, "missing strings default to empty")
}

fun checkUnicodeAndQuotesSurviveJson() {
    val sidecar = Sidecar.new("SODOM.md", "abc123")
    val tricky = "He said \"no\" — then a backslash \\ and a newline\nand an emoji 🜂"
    sidecar.addNote(
        Note(
            id = "M001",
            created = "2026-08-14T20:00:00-06:00",
            chapterTitle = "Chapitre — Été",
            anchorQuote = tricky,
            contextBefore = "",
            contextAfter = "",
            sentenceIndex = 3,
            body = tricky,
        )
    )
    val loaded = Sidecar.parse(sidecar.toJsonString(), "SODOM.md", "abc123")
    assertEq(tricky, loaded.notes[0].anchorQuote, "quotes, backslashes, newlines and emoji round-trip")
    assertEq("Chapitre — Été", loaded.notes[0].chapterTitle, "accented titles round-trip")
}

fun checkNextNoteIdIncrementsPerDevice() {
    val sidecar = sampleSidecar()
    assertEq("M002", sidecar.nextNoteId(), "phone ids increment")
    sidecar.addNote(sampleSidecar().notes[0].copy(id = "N007"))
    assertEq("M002", sidecar.nextNoteId(), "desktop ids never shift the phone sequence")
    assertEq("N008", sidecar.nextNoteId(DESKTOP_NOTE_PREFIX), "desktop numbering is still readable")
}

fun checkReplaceNoteKeepsIdAndUpdatesBody() {
    val sidecar = sampleSidecar()
    val original = sidecar.noteById("M001")!!
    assertThat(
        sidecar.replaceNote(original.copy(body = "Give Lot one more line, and fix the typo.")),
        "replacing an existing note reports success",
    )
    assertEq(
        "Give Lot one more line, and fix the typo.",
        sidecar.noteById("M001")!!.body,
        "body is updated",
    )
    assertEq(original.created, sidecar.noteById("M001")!!.created, "created is preserved")
    assertThat(!sidecar.replaceNote(original.copy(id = "M999")), "replacing a missing note reports failure")

    val markdown = exportNotesMarkdown(sidecar, "SODOM.md")
    assertThat(markdown.contains("Give Lot one more line, and fix the typo."), "export shows the edit")
    assertThat(!markdown.contains("This beat is rushed."), "export drops the old body")
}

fun checkExportMarkdownContainsQuoteAndBody() {
    val markdown = exportNotesMarkdown(sampleSidecar(), "SODOM.md", today = "2026-08-16")
    assertThat(markdown.startsWith("# Ultidraft notes — "), "export header matches the desktop")
    assertThat(markdown.contains("Manuscript: SODOM.md"), "export names the manuscript")
    assertThat(markdown.contains("Hash: abc123"), "export records the draft hash")
    assertThat(markdown.contains("## M001"), "export lists the note id")
    assertThat(
        markdown.contains("- Anchor quote: \"The angel did not look back.\""),
        "export quotes the anchor",
    )
    assertThat(
        markdown.contains("- Note: This beat is rushed. Give Lot one more line of hesitation."),
        "export carries the note body",
    )
    assertThat(markdown.contains("[HERE]"), "export marks the position in context")
}

fun checkExportHandlesAnEmptySidecar() {
    val markdown = exportNotesMarkdown(Sidecar.new("SODOM.md", "abc123"), "SODOM.md", today = "2026-08-16")
    assertThat(markdown.contains("_No notes yet._"), "an empty sidecar exports a placeholder")
}

// --------------------------------------------------------------------------- merge

fun checkMergeKeepsBothDevicesNotes() {
    val disk = Sidecar.new("SODOM.md", "abc123")
    disk.addNote(sampleSidecar().notes[0].copy(id = "N001", body = "desktop note"))
    val local = disk.copyOf()
    local.addNote(sampleSidecar().notes[0].copy(id = "M001", body = "phone note"))

    val merged = mergeSidecars(disk, local)
    assertEq(listOf("N001", "M001"), merged.notes.map { it.id }, "both devices' notes survive")
    assertEq("desktop note", merged.noteById("N001")!!.body, "the desktop note is untouched")
}

fun checkMergeTakesDesktopNotesAddedWhileWeWereReading() {
    val diskAtRead = Sidecar.new("SODOM.md", "abc123")
    val local = diskAtRead.copyOf()
    local.addNote(sampleSidecar().notes[0].copy(id = "M001", body = "phone note"))

    // Syncthing lands a desktop note between our read and our write.
    val diskNow = Sidecar.new("SODOM.md", "abc123")
    diskNow.addNote(sampleSidecar().notes[0].copy(id = "N009", body = "written on the PC"))

    val merged = mergeSidecars(diskNow, local)
    assertEq(listOf("N009", "M001"), merged.notes.map { it.id }, "the newly synced note is not clobbered")
}

fun checkMergePrefersLocalEditsAndUnionsLexicon() {
    val disk = Sidecar.new("SODOM.md", "abc123")
    disk.addNote(sampleSidecar().notes[0].copy(id = "M001", body = "old phone body"))
    disk.lexicon = listOf(LexiconRule("Sk4ms", "scams"))
    val local = disk.copyOf()
    local.replaceNote(local.notes[0].copy(body = "edited on the phone"))
    local.lexicon = listOf(LexiconRule("sk4ms", "scams"), LexiconRule("HUD", "hud"))

    val merged = mergeSidecars(disk, local)
    assertEq("edited on the phone", merged.noteById("M001")!!.body, "the local edit wins")
    assertEq(
        listOf("Sk4ms", "HUD"),
        merged.lexicon.map { it.written },
        "lexicon rules union without case-duplicates",
    )
}

fun checkMergeHonoursADesktopDeletion() {
    val diskAtRead = Sidecar.new("SODOM.md", "abc123")
    diskAtRead.addNote(sampleSidecar().notes[0].copy(id = "N001"))
    val local = diskAtRead.copyOf()

    val diskNow = Sidecar.new("SODOM.md", "abc123")
    val merged = mergeSidecars(diskNow, local)
    assertEq(emptyList<String>(), merged.notes.map { it.id }, "a note deleted on the PC stays deleted")
}

// ----------------------------------------------------------------------- registry

val ALL_CHECKS: List<Pair<String, () -> Unit>> = listOf(
    "splitKeepsClosingDialoguePunctuation" to ::checkSplitKeepsClosingDialoguePunctuation,
    "splitKeepsQuestionWithItsClosingQuote" to ::checkSplitKeepsQuestionWithItsClosingQuote,
    "splitHandlesAdjacentSentencesAndAbbreviations" to ::checkSplitHandlesAdjacentSentencesAndAbbreviations,
    "ellipsisDoesNotShatterASentence" to ::checkEllipsisDoesNotShatterASentence,
    "parseSodomStyleChaptersAndIds" to ::checkParseSodomStyleChaptersAndIds,
    "horizontalRulesAreNotSpoken" to ::checkHorizontalRulesAreNotSpoken,
    "chapterIdFallsBackToOrdinal" to ::checkChapterIdFallsBackToOrdinal,
    "spanPacksAChapterWithNaturalParagraphBreaks" to ::checkSpanPacksAChapterWithNaturalParagraphBreaks,
    "spanStaysBelowChunkLimit" to ::checkSpanStaysBelowChunkLimit,
    "nearbyIncludesCurrentAndNeighbours" to ::checkNearbyIncludesCurrentAndNeighbours,
    "paragraphNavigation" to ::checkParagraphNavigation,
    "locateFindsCleanedHeadingAndDialogue" to ::checkLocateFindsCleanedHeadingAndDialogue,
    "sentenceIndexFollowsTheCursorOffset" to ::checkSentenceIndexFollowsTheCursorOffset,
    "paragraphSourceSpanCoversTheWholeParagraph" to ::checkParagraphSourceSpanCoversTheWholeParagraph,
    "editingPreservesTheRestOfTheDraft" to ::checkEditingPreservesTheRestOfTheDraft,
    "lexiconRewritesInAnyCase" to ::checkLexiconRewritesInAnyCase,
    "lexiconDoesNotEatUnrelatedWords" to ::checkLexiconDoesNotEatUnrelatedWords,
    "spokenSpanMatchesTheSpanTextWithoutRules" to ::checkSpokenSpanMatchesTheSpanTextWithoutRules,
    "spokenSpanRangesTrackLengthChangingRules" to ::checkSpokenSpanRangesTrackLengthChangingRules,
    "sidecarRoundTrip" to ::checkSidecarRoundTrip,
    "sidecarSurvivesGarbageAndMissingFields" to ::checkSidecarSurvivesGarbageAndMissingFields,
    "unicodeAndQuotesSurviveJson" to ::checkUnicodeAndQuotesSurviveJson,
    "nextNoteIdIncrementsPerDevice" to ::checkNextNoteIdIncrementsPerDevice,
    "replaceNoteKeepsIdAndUpdatesBody" to ::checkReplaceNoteKeepsIdAndUpdatesBody,
    "exportMarkdownContainsQuoteAndBody" to ::checkExportMarkdownContainsQuoteAndBody,
    "exportHandlesAnEmptySidecar" to ::checkExportHandlesAnEmptySidecar,
    "mergeKeepsBothDevicesNotes" to ::checkMergeKeepsBothDevicesNotes,
    "mergeTakesDesktopNotesAddedWhileWeWereReading" to ::checkMergeTakesDesktopNotesAddedWhileWeWereReading,
    "mergePrefersLocalEditsAndUnionsLexicon" to ::checkMergePrefersLocalEditsAndUnionsLexicon,
    "mergeHonoursADesktopDeletion" to ::checkMergeHonoursADesktopDeletion,
)
