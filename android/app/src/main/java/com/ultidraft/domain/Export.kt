package com.ultidraft.domain

import java.time.LocalDate

/** Write Cursor-ready listening notes. Quote anchors, not page numbers. */

const val EXPORT_FILE_NAME = "listening-notes.md"

fun exportNotesMarkdown(
    sidecar: Sidecar,
    manuscriptName: String,
    today: String = LocalDate.now().toString(),
): String {
    val lines = mutableListOf(
        "# Ultidraft notes — $today",
        "Manuscript: $manuscriptName",
        "Hash: ${sidecar.manuscriptHash}",
        "",
    )
    if (sidecar.notes.isEmpty()) {
        lines.add("_No notes yet._")
        lines.add("")
        return lines.joinToString("\n")
    }

    for (note in sidecar.notes) {
        lines.add("## ${note.id}")
        lines.add("- Chapter: ${note.chapterTitle}")
        lines.add("- Anchor quote: \"${note.anchorQuote}\"")
        lines.add("- Context: ${contextLine(note.contextBefore, note.contextAfter)}")
        lines.add("- Note: ${note.body}")
        lines.add("")
    }
    return lines.joinToString("\n")
}

private fun contextLine(before: String, after: String): String {
    val left = before.trim()
    val right = after.trim()
    return when {
        left.isNotEmpty() && right.isNotEmpty() -> "\"$left [HERE] $right\""
        left.isNotEmpty() -> "\"$left [HERE]\""
        right.isNotEmpty() -> "\"[HERE] $right\""
        else -> "[HERE]"
    }
}
