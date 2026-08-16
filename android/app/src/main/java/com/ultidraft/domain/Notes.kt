package com.ultidraft.domain

import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * The sidecar stored beside a manuscript, byte-compatible with the desktop app.
 *
 * Phone-made notes are numbered `M001`, `M002`, … while the desktop numbers `N001`, …
 * The desktop's `next_note_id` ignores ids it cannot parse as `N<int>`, so the two
 * clients can both add notes to a Syncthing-synced book without ever colliding on an id.
 */

const val SIDECAR_VERSION = 1
const val MOBILE_NOTE_PREFIX = "M"
const val DESKTOP_NOTE_PREFIX = "N"

data class Note(
    val id: String,
    val created: String,
    val chapterTitle: String,
    val anchorQuote: String,
    val contextBefore: String,
    val contextAfter: String,
    val sentenceIndex: Int,
    val body: String,
) {
    fun toJson(): JsonValue = JsonValue.obj(
        "id" to JsonValue.str(id),
        "created" to JsonValue.str(created),
        "chapter_title" to JsonValue.str(chapterTitle),
        "anchor_quote" to JsonValue.str(anchorQuote),
        "context_before" to JsonValue.str(contextBefore),
        "context_after" to JsonValue.str(contextAfter),
        "sentence_index" to JsonValue.num(sentenceIndex),
        "body" to JsonValue.str(body),
    )

    companion object {
        fun fromJson(value: JsonValue): Note = Note(
            id = value.string("id"),
            created = value.string("created"),
            chapterTitle = value.string("chapter_title"),
            anchorQuote = value.string("anchor_quote"),
            contextBefore = value.string("context_before"),
            contextAfter = value.string("context_after"),
            sentenceIndex = value.int("sentence_index"),
            body = value.string("body"),
        )
    }
}

data class Position(val chapterId: String, val sentenceIndex: Int)

data class Sidecar(
    val version: Int = SIDECAR_VERSION,
    var manuscriptPath: String,
    var manuscriptHash: String,
    var position: Position = Position("", 0),
    val notes: MutableList<Note> = mutableListOf(),
    var lexicon: List<LexiconRule> = emptyList(),
) {
    /** The next free phone-side id, e.g. `M003`. */
    fun nextNoteId(prefix: String = MOBILE_NOTE_PREFIX): String {
        var highest = 0
        for (note in notes) {
            if (!note.id.startsWith(prefix)) continue
            val number = note.id.removePrefix(prefix).toIntOrNull() ?: continue
            if (number > highest) highest = number
        }
        return prefix + (highest + 1).toString().padStart(3, '0')
    }

    fun addNote(note: Note) {
        notes.add(note)
    }

    fun noteById(noteId: String): Note? = notes.firstOrNull { it.id == noteId }

    fun replaceNote(note: Note): Boolean {
        val index = notes.indexOfFirst { it.id == note.id }
        if (index < 0) return false
        notes[index] = note
        return true
    }

    fun removeNote(noteId: String): Boolean = notes.removeAll { it.id == noteId }

    fun toJson(): JsonValue = JsonValue.obj(
        "version" to JsonValue.num(version),
        "manuscript_path" to JsonValue.str(manuscriptPath),
        "manuscript_hash" to JsonValue.str(manuscriptHash),
        "position" to JsonValue.obj(
            "chapter_id" to JsonValue.str(position.chapterId),
            "sentence_index" to JsonValue.num(position.sentenceIndex),
        ),
        "notes" to JsonValue.arr(notes.map { it.toJson() }),
        "lexicon" to JsonValue.arr(lexicon.map { it.toJson() }),
    )

    fun toJsonString(): String = toJson().toJsonString() + "\n"

    fun copyOf(): Sidecar = Sidecar(
        version = version,
        manuscriptPath = manuscriptPath,
        manuscriptHash = manuscriptHash,
        position = position,
        notes = notes.toMutableList(),
        lexicon = lexicon.toList(),
    )

    companion object {
        fun new(manuscriptPath: String, manuscriptHash: String): Sidecar = Sidecar(
            manuscriptPath = manuscriptPath,
            manuscriptHash = manuscriptHash,
        )

        fun fromJson(value: JsonValue): Sidecar {
            val position = value.objectAt("position")
            return Sidecar(
                version = value.int("version", SIDECAR_VERSION),
                manuscriptPath = value.string("manuscript_path"),
                manuscriptHash = value.string("manuscript_hash"),
                position = Position(
                    chapterId = position.string("chapter_id"),
                    sentenceIndex = position.int("sentence_index"),
                ),
                notes = value.array("notes").map { Note.fromJson(it) }.toMutableList(),
                lexicon = value.array("lexicon").map { LexiconRule.fromJson(it) },
            )
        }

        /** Parse sidecar text, falling back to a fresh sidecar when the file is unreadable. */
        fun parse(text: String?, manuscriptPath: String, manuscriptHash: String): Sidecar {
            val value = text?.takeIf { it.isNotBlank() }?.let { JsonValue.parse(it) }
                ?: return new(manuscriptPath, manuscriptHash)
            val sidecar = fromJson(value)
            sidecar.manuscriptPath = manuscriptPath
            return sidecar
        }
    }
}

fun sidecarName(manuscriptName: String): String =
    manuscriptName.substringBeforeLast('.', manuscriptName) + ".ultidraft.json"

fun nowIsoSeconds(): String =
    OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()

/**
 * Fold phone edits into whatever the sidecar on disk says now.
 *
 * Syncthing can land a desktop version of the file between our read and our write, so
 * every save re-reads and merges instead of overwriting: notes are unioned by id, the
 * local copy wins for ids present in both, and disk order is preserved so the export
 * does not shuffle on every save.
 */
fun mergeSidecars(disk: Sidecar, local: Sidecar): Sidecar {
    val merged = disk.copyOf()
    merged.manuscriptPath = local.manuscriptPath
    merged.manuscriptHash = local.manuscriptHash
    merged.position = local.position

    val localById = local.notes.associateBy { it.id }
    for (index in merged.notes.indices) {
        val replacement = localById[merged.notes[index].id]
        if (replacement != null) merged.notes[index] = replacement
    }
    // Anything the desktop deleted while we held a copy stays deleted; only ids we
    // actually created or edited on the phone come back.
    val diskIds = disk.notes.map { it.id }.toSet()
    for (note in local.notes) {
        if (note.id !in diskIds && note.id.startsWith(MOBILE_NOTE_PREFIX)) merged.notes.add(note)
    }

    val lexicon = merged.lexicon.toMutableList()
    for (rule in local.lexicon) {
        if (lexicon.none { it.written.equals(rule.written, ignoreCase = true) }) lexicon.add(rule)
    }
    merged.lexicon = lexicon
    return merged
}
