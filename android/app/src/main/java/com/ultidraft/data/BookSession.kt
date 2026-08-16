package com.ultidraft.data

import android.content.Context
import android.net.Uri
import com.ultidraft.domain.Manuscript
import com.ultidraft.domain.Note
import com.ultidraft.domain.Position
import com.ultidraft.domain.Sentence
import com.ultidraft.domain.Sidecar
import com.ultidraft.domain.loadManuscript
import com.ultidraft.domain.locateInMarkdown
import com.ultidraft.domain.nowIsoSeconds
import com.ultidraft.domain.paragraphSourceSpan
import com.ultidraft.domain.sentenceIndexAtOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The open book, shared by the UI and the playback service.
 *
 * A process-wide singleton rather than a ViewModel: the foreground service outlives the
 * activity by design (that is the whole point of listening with the screen off), and
 * both need to agree on which sentence is current.
 */
object BookSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _manuscript = MutableStateFlow<Manuscript?>(null)
    val manuscript: StateFlow<Manuscript?> = _manuscript.asStateFlow()

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _staleNotes = MutableStateFlow(false)
    val staleNotes: StateFlow<Boolean> = _staleNotes.asStateFlow()

    var sidecar: Sidecar? = null
        private set
    var treeUri: Uri? = null
        private set
    var bookUri: Uri? = null
        private set
    val bookName: String?
        get() = _manuscript.value?.name

    fun setStatus(message: String) {
        _status.value = message
    }

    fun setPlaying(value: Boolean) {
        _playing.value = value
    }

    fun setSpeed(context: Context, value: Float) {
        val clamped = value.coerceIn(0.5f, 2.5f)
        _speed.value = clamped
        Prefs(context).speed = clamped
    }

    fun currentSentence(): Sentence? = _manuscript.value?.sentenceAt(_index.value)

    /** Move the playhead. [fromNarrator] avoids echoing the service's own progress back. */
    fun moveTo(index: Int, fromNarrator: Boolean = false) {
        val manuscript = _manuscript.value ?: return
        if (manuscript.sentences.isEmpty()) return
        val clamped = index.coerceIn(0, manuscript.sentences.size - 1)
        if (_index.value == clamped) return
        _index.value = clamped
        if (!fromNarrator) _status.value = ""
    }

    // ------------------------------------------------------------------- opening

    suspend fun open(context: Context, treeUri: Uri, book: BookStore.BookRef, startIndex: Int? = null) {
        _busy.value = true
        try {
            val opened = withContext(Dispatchers.IO) { BookStore.openBook(context, treeUri, book) }
            this.treeUri = treeUri
            this.bookUri = opened.uri
            sidecar = opened.sidecar
            _manuscript.value = opened.manuscript
            _notes.value = opened.sidecar.notes.toList()
            _staleNotes.value = opened.sidecar.notes.isNotEmpty() &&
                opened.sidecar.manuscriptHash.isNotEmpty() &&
                opened.sidecar.manuscriptHash != opened.manuscript.hash
            opened.sidecar.manuscriptHash = opened.manuscript.hash

            val prefs = Prefs(context)
            val remembered = prefs.positionFor(book.name)
            val resume = startIndex
                ?: remembered.takeIf { it >= 0 }
                ?: opened.sidecar.position.sentenceIndex
            _index.value = resume.coerceIn(0, maxOf(0, opened.manuscript.sentences.size - 1))
            _speed.value = prefs.speed
            prefs.lastBookName = book.name
            _status.value = buildString {
                append(opened.manuscript.chapters.size)
                append(" chapters · ")
                append(opened.manuscript.sentences.size)
                append(" sentences")
                if (_staleNotes.value) append(" · notes were taken on an older draft")
            }
        } catch (error: Exception) {
            _status.value = error.message ?: "Could not open that book."
            throw error
        } finally {
            _busy.value = false
        }
    }

    fun close() {
        _manuscript.value = null
        _notes.value = emptyList()
        sidecar = null
        bookUri = null
        _index.value = 0
        _playing.value = false
        _status.value = ""
        _staleNotes.value = false
    }

    // --------------------------------------------------------------------- notes

    fun noteAt(sentenceIndex: Int, body: String, existing: Note?, context: Context) {
        val manuscript = _manuscript.value ?: return
        val sidecar = sidecar ?: return
        val target = manuscript.sentenceAt(sentenceIndex) ?: return
        val before = manuscript.sentenceAt(target.index - 1)
        val after = manuscript.sentenceAt(target.index + 1)
        val note = Note(
            id = existing?.id ?: sidecar.nextNoteId(),
            created = existing?.created ?: nowIsoSeconds(),
            chapterTitle = target.chapterTitle,
            anchorQuote = target.text,
            contextBefore = before?.text ?: "",
            contextAfter = after?.text ?: "",
            sentenceIndex = target.index,
            body = body.trim(),
        )
        if (existing == null || !sidecar.replaceNote(note)) sidecar.addNote(note)
        _notes.value = sidecar.notes.toList()
        persist(context, writeExport = true, message = "Saved ${note.id}")
    }

    fun deleteNote(context: Context, noteId: String) {
        val sidecar = sidecar ?: return
        if (!sidecar.removeNote(noteId)) return
        _notes.value = sidecar.notes.toList()
        persist(context, writeExport = true, message = "Deleted $noteId")
    }

    /**
     * Write the sidecar and the Cursor export.
     *
     * [writeExport] is false for position-only saves, which happen on pause rather than
     * on every sentence so a synced folder does not churn.
     */
    fun persist(context: Context, writeExport: Boolean, message: String? = null) {
        val sidecar = sidecar ?: return
        val manuscript = _manuscript.value ?: return
        val tree = treeUri ?: return
        val sentence = manuscript.sentenceAt(_index.value)
        sidecar.manuscriptPath = manuscript.name
        sidecar.manuscriptHash = manuscript.hash
        sidecar.position = Position(sentence?.chapterId ?: "", _index.value)
        Prefs(context).setPositionFor(manuscript.name, _index.value)

        val snapshot = sidecar.copyOf()
        scope.launch {
            try {
                val merged = withContext(Dispatchers.IO) {
                    BookStore.saveSidecar(context, tree, manuscript.name, snapshot, writeExport)
                }
                // Adopt the merged result so a note the PC added is visible here too.
                this@BookSession.sidecar?.let { current ->
                    current.notes.clear()
                    current.notes.addAll(merged.notes)
                    current.lexicon = merged.lexicon
                }
                _notes.value = merged.notes.toList()
                if (message != null) _status.value = message
            } catch (error: Exception) {
                _status.value = error.message ?: "Could not save notes."
            }
        }
    }

    fun rememberPosition(context: Context) {
        val manuscript = _manuscript.value ?: return
        Prefs(context).setPositionFor(manuscript.name, _index.value)
    }

    // ---------------------------------------------------------------- manuscript

    /** The source text of the paragraph at the playhead, for edit-at-the-playhead. */
    fun paragraphText(): String? {
        val manuscript = _manuscript.value ?: return null
        val span = paragraphSourceSpan(manuscript.raw, manuscript, _index.value) ?: return null
        return manuscript.raw.substring(span.first, span.last + 1)
    }

    /**
     * Splice an edited paragraph back into the draft, save it, and reparse.
     *
     * The playhead is restored by locating the edited text again rather than by index:
     * an edit can add or remove sentences, and landing on the wrong line after saving
     * would be worse than landing a sentence early.
     */
    suspend fun saveParagraph(context: Context, edited: String): Boolean {
        val manuscript = _manuscript.value ?: return false
        val uri = bookUri ?: return false
        val span = paragraphSourceSpan(manuscript.raw, manuscript, _index.value)
        if (span == null) {
            _status.value = "Could not find that paragraph in the file."
            return false
        }
        val cleaned = edited.trim()
        if (cleaned.isEmpty()) {
            _status.value = "An empty paragraph would delete it — use the notes panel instead."
            return false
        }
        val updated = manuscript.raw.substring(0, span.first) + cleaned +
            manuscript.raw.substring(span.last + 1)
        _busy.value = true
        return try {
            withContext(Dispatchers.IO) { BookStore.saveManuscript(context, uri, updated) }
            val reparsed = loadManuscript(manuscript.name, updated)
            _manuscript.value = reparsed
            sidecar?.manuscriptHash = reparsed.hash
            val landing = locateInMarkdown(updated, cleaned.lineSequence().first().trim())
            _index.value = if (landing != null) {
                sentenceIndexAtOffset(updated, reparsed.sentences, landing.first)
            } else {
                _index.value.coerceIn(0, maxOf(0, reparsed.sentences.size - 1))
            }
            _staleNotes.value = false
            _status.value = "Saved the paragraph."
            true
        } catch (error: Exception) {
            _status.value = error.message ?: "Could not save the manuscript."
            false
        } finally {
            _busy.value = false
        }
    }
}
