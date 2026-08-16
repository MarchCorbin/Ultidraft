package com.ultidraft.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ultidraft.domain.EXPORT_FILE_NAME
import com.ultidraft.domain.Manuscript
import com.ultidraft.domain.Sidecar
import com.ultidraft.domain.exportNotesMarkdown
import com.ultidraft.domain.loadManuscript
import com.ultidraft.domain.mergeSidecars
import com.ultidraft.domain.sidecarName
import java.io.IOException

/**
 * Reading and writing a book folder through the Storage Access Framework.
 *
 * The folder is expected to be the one Syncthing keeps in step with the PC, so every
 * write re-reads what is on disk first and merges: Syncthing can drop a newer sidecar
 * underneath us at any moment, and losing a note taken on the desktop would be worse
 * than losing the round trip.
 */
object BookStore {

    private const val MIME_MARKDOWN = "text/markdown"
    private const val MIME_JSON = "application/json"

    data class BookRef(val name: String, val uri: Uri)

    class StorageError(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** Remember a folder the user picked, across app restarts. */
    fun persistFolderPermission(context: Context, treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        } catch (error: SecurityException) {
            throw StorageError("Android would not keep access to that folder.", error)
        }
    }

    fun hasFolderPermission(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }

    private fun tree(context: Context, treeUri: Uri): DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw StorageError("That folder is no longer reachable.")

    /** Every manuscript in the folder: `.md` and `.txt`, minus the files we generate. */
    fun listBooks(context: Context, treeUri: Uri): List<BookRef> =
        tree(context, treeUri).listFiles()
            .asSequence()
            .filter { it.isFile }
            .mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                val lowered = name.lowercase()
                val readable = lowered.endsWith(".md") || lowered.endsWith(".txt")
                val generated = lowered == EXPORT_FILE_NAME || lowered.endsWith(".ultidraft.json")
                if (readable && !generated) BookRef(name, file.uri) else null
            }
            .sortedBy { it.name.lowercase() }
            .toList()

    data class OpenBook(val manuscript: Manuscript, val sidecar: Sidecar, val uri: Uri)

    fun openBook(context: Context, treeUri: Uri, book: BookRef): OpenBook {
        val raw = readText(context, book.uri)
            ?: throw StorageError("Could not read ${book.name}.")
        val manuscript = loadManuscript(book.name, raw)
        val sidecarText = findChild(context, treeUri, sidecarName(book.name))
            ?.let { readText(context, it.uri) }
        val sidecar = Sidecar.parse(sidecarText, book.name, manuscript.hash)
        return OpenBook(manuscript, sidecar, book.uri)
    }

    /**
     * Merge [local] into whatever the sidecar on disk says now, write it, and refresh
     * the Cursor export. Returns the sidecar that actually landed on disk.
     */
    fun saveSidecar(
        context: Context,
        treeUri: Uri,
        manuscriptName: String,
        local: Sidecar,
        writeExport: Boolean,
    ): Sidecar {
        val name = sidecarName(manuscriptName)
        val existing = findChild(context, treeUri, name)
        val onDisk = existing?.let { readText(context, it.uri) }
        val merged = if (onDisk.isNullOrBlank()) {
            local
        } else {
            mergeSidecars(Sidecar.parse(onDisk, manuscriptName, local.manuscriptHash), local)
        }

        val target = existing ?: createChild(context, treeUri, name, MIME_JSON)
        writeText(context, target.uri, merged.toJsonString())

        if (writeExport) {
            val export = findChild(context, treeUri, EXPORT_FILE_NAME)
                ?: createChild(context, treeUri, EXPORT_FILE_NAME, MIME_MARKDOWN)
            writeText(context, export.uri, exportNotesMarkdown(merged, manuscriptName))
        }
        return merged
    }

    fun saveManuscript(context: Context, uri: Uri, raw: String) {
        writeText(context, uri, raw)
    }

    // --------------------------------------------------------------------- plumbing

    private fun findChild(context: Context, treeUri: Uri, name: String): DocumentFile? =
        tree(context, treeUri).listFiles().firstOrNull { it.name == name }

    private fun createChild(
        context: Context,
        treeUri: Uri,
        name: String,
        mimeType: String,
    ): DocumentFile =
        tree(context, treeUri).createFile(mimeType, name)
            ?: throw StorageError("Could not create $name in that folder.")

    private fun readText(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
    } catch (error: Exception) {
        throw StorageError("Could not read that file.", error)
    }

    private fun writeText(context: Context, uri: Uri, text: String) {
        try {
            // "wt" truncates. Plain "w" leaves the tail of a longer previous version
            // behind on several providers, which would corrupt a shrinking sidecar.
            context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.flush()
            } ?: throw StorageError("Could not open that file for writing.")
        } catch (error: StorageError) {
            throw error
        } catch (error: Exception) {
            throw StorageError("Could not save that file.", error)
        }
    }
}
