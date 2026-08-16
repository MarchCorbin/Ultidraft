package com.ultidraft.domain

import org.junit.Test

/**
 * JUnit entry points for the shared domain checks.
 *
 * The bodies live in DomainChecks.kt so the identical assertions can also be run by a
 * bare `kotlinc` outside Gradle. One test method per check keeps the failure report
 * pointing at the behaviour that broke rather than at a single omnibus test.
 */
class DomainTest {

    @Test
    fun splitKeepsClosingDialoguePunctuation() = checkSplitKeepsClosingDialoguePunctuation()

    @Test
    fun splitKeepsQuestionWithItsClosingQuote() = checkSplitKeepsQuestionWithItsClosingQuote()

    @Test
    fun splitHandlesAdjacentSentencesAndAbbreviations() =
        checkSplitHandlesAdjacentSentencesAndAbbreviations()

    @Test
    fun ellipsisDoesNotShatterASentence() = checkEllipsisDoesNotShatterASentence()

    @Test
    fun parseSodomStyleChaptersAndIds() = checkParseSodomStyleChaptersAndIds()

    @Test
    fun horizontalRulesAreNotSpoken() = checkHorizontalRulesAreNotSpoken()

    @Test
    fun chapterIdFallsBackToOrdinal() = checkChapterIdFallsBackToOrdinal()

    @Test
    fun spanPacksAChapterWithNaturalParagraphBreaks() =
        checkSpanPacksAChapterWithNaturalParagraphBreaks()

    @Test
    fun spanStaysBelowChunkLimit() = checkSpanStaysBelowChunkLimit()

    @Test
    fun nearbyIncludesCurrentAndNeighbours() = checkNearbyIncludesCurrentAndNeighbours()

    @Test
    fun paragraphNavigation() = checkParagraphNavigation()

    @Test
    fun locateFindsCleanedHeadingAndDialogue() = checkLocateFindsCleanedHeadingAndDialogue()

    @Test
    fun sentenceIndexFollowsTheCursorOffset() = checkSentenceIndexFollowsTheCursorOffset()

    @Test
    fun paragraphSourceSpanCoversTheWholeParagraph() =
        checkParagraphSourceSpanCoversTheWholeParagraph()

    @Test
    fun editingPreservesTheRestOfTheDraft() = checkEditingPreservesTheRestOfTheDraft()

    @Test
    fun lexiconRewritesInAnyCase() = checkLexiconRewritesInAnyCase()

    @Test
    fun lexiconDoesNotEatUnrelatedWords() = checkLexiconDoesNotEatUnrelatedWords()

    @Test
    fun spokenSpanMatchesTheSpanTextWithoutRules() =
        checkSpokenSpanMatchesTheSpanTextWithoutRules()

    @Test
    fun spokenSpanRangesTrackLengthChangingRules() =
        checkSpokenSpanRangesTrackLengthChangingRules()

    @Test
    fun sidecarRoundTrip() = checkSidecarRoundTrip()

    @Test
    fun sidecarSurvivesGarbageAndMissingFields() = checkSidecarSurvivesGarbageAndMissingFields()

    @Test
    fun unicodeAndQuotesSurviveJson() = checkUnicodeAndQuotesSurviveJson()

    @Test
    fun nextNoteIdIncrementsPerDevice() = checkNextNoteIdIncrementsPerDevice()

    @Test
    fun replaceNoteKeepsIdAndUpdatesBody() = checkReplaceNoteKeepsIdAndUpdatesBody()

    @Test
    fun exportMarkdownContainsQuoteAndBody() = checkExportMarkdownContainsQuoteAndBody()

    @Test
    fun exportHandlesAnEmptySidecar() = checkExportHandlesAnEmptySidecar()

    @Test
    fun mergeKeepsBothDevicesNotes() = checkMergeKeepsBothDevicesNotes()

    @Test
    fun mergeTakesDesktopNotesAddedWhileWeWereReading() =
        checkMergeTakesDesktopNotesAddedWhileWeWereReading()

    @Test
    fun mergePrefersLocalEditsAndUnionsLexicon() = checkMergePrefersLocalEditsAndUnionsLexicon()

    @Test
    fun mergeHonoursADesktopDeletion() = checkMergeHonoursADesktopDeletion()

    /** Guards the registry itself: a check added to the list must have a test here. */
    @Test
    fun everyCheckIsCoveredByATestMethod() {
        val methods = DomainTest::class.java.declaredMethods.map { it.name }.toSet()
        val missing = ALL_CHECKS.map { it.first }.filterNot { it in methods }
        assertThat(missing.isEmpty(), "checks with no @Test wrapper: $missing")
    }
}
