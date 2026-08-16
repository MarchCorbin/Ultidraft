package com.ultidraft.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Toc
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ultidraft.data.BookSession
import com.ultidraft.data.Prefs
import com.ultidraft.domain.Note
import com.ultidraft.domain.SentenceKind
import com.ultidraft.player.Narration
import kotlinx.coroutines.launch

private sealed interface Sheet {
    data object Chapters : Sheet
    data object Notes : Sheet
    data object Settings : Sheet
    data object Paragraph : Sheet
    data class NoteEditor(val noteId: String?, val sentenceIndex: Int) : Sheet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen() {
    val context = LocalContext.current

    val manuscript by BookSession.manuscript.collectAsState()
    val index by BookSession.index.collectAsState()
    val playing by BookSession.playing.collectAsState()
    val status by BookSession.status.collectAsState()
    val notes by BookSession.notes.collectAsState()
    val book = manuscript ?: return

    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val listState = rememberLazyListState()

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Playback works either way; without it there is simply no lock-screen card. */ }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    BackHandler {
        Narration.stop(context)
        BookSession.close()
    }

    // Follow the voice, but only while it is reading: otherwise a scroll to browse the
    // chapter would be yanked back to the playhead on every recomposition.
    LaunchedEffect(index, playing) {
        if (playing) listState.animateScrollToItem(maxOf(0, index - 2))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(book.name, style = MaterialTheme.typography.titleMedium)
                        val chapter = book.chapterOf(index)?.title
                        if (chapter != null) {
                            Text(
                                chapter,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { sheet = Sheet.Chapters }) {
                        Icon(Icons.Filled.Toc, contentDescription = "Chapters")
                    }
                    IconButton(onClick = { sheet = Sheet.Notes }) {
                        Icon(Icons.Filled.PostAdd, contentDescription = "Notes")
                    }
                    IconButton(onClick = { sheet = Sheet.Settings }) {
                        Icon(Icons.Filled.Speed, contentDescription = "Voice and speed")
                    }
                },
            )
        },
        bottomBar = {
            TransportBar(
                playing = playing,
                status = status,
                noteCount = notes.size,
                onToggle = {
                    ensureNotificationPermission()
                    Narration.toggle(context)
                },
                onPreviousSentence = { Narration.previousSentence(context) },
                onNextSentence = { Narration.nextSentence(context) },
                onPreviousParagraph = { Narration.previousParagraph(context) },
                onNextParagraph = { Narration.nextParagraph(context) },
                onNote = { sheet = Sheet.NoteEditor(null, index) },
                onEdit = { sheet = Sheet.Paragraph },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 20.dp,
                vertical = 12.dp,
            ),
        ) {
            items(book.sentences, key = { it.index }) { sentence ->
                val current = sentence.index == index
                val style = when (sentence.kind) {
                    SentenceKind.HEADING -> MaterialTheme.typography.titleLarge
                    SentenceKind.META -> MaterialTheme.typography.bodyMedium
                    SentenceKind.BODY -> MaterialTheme.typography.bodyLarge
                }
                Text(
                    text = sentence.text,
                    style = style,
                    fontWeight = if (sentence.kind == SentenceKind.HEADING) FontWeight.Bold else null,
                    fontStyle = if (sentence.kind == SentenceKind.META) FontStyle.Italic else null,
                    color = if (sentence.kind == SentenceKind.META) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = if (sentence.kind == SentenceKind.HEADING) 18.dp else 2.dp)
                        .background(
                            color = if (current) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                            shape = RoundedCornerShape(6.dp),
                        )
                        .clickable { Narration.jumpTo(context, sentence.index) }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val active = sheet
    if (active != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = sheetState) {
            when (active) {
                Sheet.Chapters -> ChaptersSheet(
                    onPick = { start ->
                        Narration.jumpTo(context, start)
                        sheet = null
                    }
                )

                Sheet.Notes -> NotesSheet(
                    notes = notes,
                    onJump = {
                        Narration.jumpTo(context, it)
                        sheet = null
                    },
                    onEdit = { note -> sheet = Sheet.NoteEditor(note.id, note.sentenceIndex) },
                    onDelete = { BookSession.deleteNote(context, it) },
                )

                Sheet.Settings -> SettingsSheet()

                Sheet.Paragraph -> ParagraphSheet(
                    onDone = { sheet = null },
                )

                is Sheet.NoteEditor -> NoteEditorSheet(
                    existing = active.noteId?.let { id -> notes.firstOrNull { it.id == id } },
                    sentenceIndex = active.sentenceIndex,
                    onSave = { anchorIndex, body, existing ->
                        BookSession.noteAt(anchorIndex, body, existing, context)
                        sheet = null
                    },
                    onCancel = { sheet = null },
                )
            }
        }
    }

    // Opening a note should not leave the voice running over the top of you typing.
    LaunchedEffect(sheet) {
        if (sheet is Sheet.NoteEditor || sheet is Sheet.Paragraph) {
            if (BookSession.playing.value) Narration.pause(context)
        }
    }
}

@Composable
private fun TransportBar(
    playing: Boolean,
    status: String,
    noteCount: Int,
    onToggle: () -> Unit,
    onPreviousSentence: () -> Unit,
    onNextSentence: () -> Unit,
    onPreviousParagraph: () -> Unit,
    onNextParagraph: () -> Unit,
    onNote: () -> Unit,
    onEdit: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            if (status.isNotEmpty()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPreviousParagraph) {
                    Icon(Icons.Filled.FastRewind, contentDescription = "Previous paragraph")
                }
                IconButton(onClick = onPreviousSentence) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous sentence")
                }
                FilledIconButton(onClick = onToggle) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                    )
                }
                IconButton(onClick = onNextSentence) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next sentence")
                }
                IconButton(onClick = onNextParagraph) {
                    Icon(Icons.Filled.FastForward, contentDescription = "Next paragraph")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit this paragraph")
                }
                IconButton(onClick = onNote) {
                    Icon(
                        Icons.Filled.PostAdd,
                        contentDescription = if (noteCount == 0) "Add a note" else "Add a note ($noteCount so far)",
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChaptersSheet(onPick: (Int) -> Unit) {
    val book = BookSession.manuscript.collectAsState().value ?: return
    val index by BookSession.index.collectAsState()
    Column(modifier = Modifier.fillMaxWidth()) {
        SheetTitle("Chapters")
        LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
            items(book.chapters, key = { it.id }) { chapter ->
                val current = book.chapterOf(index)?.id == chapter.id
                ListItem(
                    headlineContent = {
                        Text(
                            chapter.title,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    supportingContent = { Text("${chapter.sentenceCount} sentences") },
                    modifier = Modifier.clickable { onPick(chapter.startIndex) },
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesSheet(
    notes: List<Note>,
    onJump: (Int) -> Unit,
    onEdit: (Note) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SheetTitle(if (notes.isEmpty()) "No notes yet" else "${notes.size} notes")
        if (notes.isEmpty()) {
            Text(
                "Notes you take here are written into the book folder as listening-notes.md, " +
                    "ready for Cursor on the PC.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
            items(notes, key = { it.id }) { note ->
                ListItem(
                    overlineContent = { Text("${note.id} · ${note.chapterTitle}") },
                    headlineContent = { Text(note.body) },
                    supportingContent = { Text("“${note.anchorQuote}”") },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { onEdit(note) }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit ${note.id}")
                            }
                            IconButton(onClick = { onDelete(note.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete ${note.id}")
                            }
                        }
                    },
                    modifier = Modifier.clickable { onJump(note.sentenceIndex) },
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditorSheet(
    existing: Note?,
    sentenceIndex: Int,
    onSave: (Int, String, Note?) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val book = BookSession.manuscript.collectAsState().value ?: return
    var body by remember(existing?.id) { mutableStateOf(existing?.body ?: "") }
    var anchor by remember(existing?.id) { mutableStateOf(sentenceIndex) }
    var dictationError by remember { mutableStateOf<String?>(null) }

    val dictate = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val heard = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
        if (heard.isNotBlank()) body = (body.trim() + " " + heard.trim()).trim()
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        SheetTitle(if (existing == null) "Note on this sentence" else "Edit ${existing.id}")

        Text(
            "Anchor",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
            itemsIndexed(book.nearby(sentenceIndex, radius = 3)) { _, sentence ->
                val picked = sentence.index == anchor
                Text(
                    sentence.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (picked) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = if (picked) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                            shape = RoundedCornerShape(6.dp),
                        )
                        .clickable { anchor = sentence.index }
                        .padding(8.dp),
                )
            }
        }

        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("What snagged?") },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            minLines = 3,
        )

        if (dictationError != null) {
            Text(
                dictationError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your note")
                    try {
                        dictationError = null
                        dictate.launch(intent)
                    } catch (_: ActivityNotFoundException) {
                        dictationError = "No dictation app is installed on this phone."
                    }
                }
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null)
                Text("  Speak")
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(
                onClick = { onSave(anchor, body, existing) },
                enabled = body.isNotBlank(),
            ) {
                Text("Save")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParagraphSheet(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val original = remember { BookSession.paragraphText() }
    var text by remember { mutableStateOf(original.orEmpty()) }
    var saving by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        SheetTitle("Edit this paragraph")
        if (original == null) {
            Text(
                "Ultidraft could not find this paragraph in the file — it may have changed " +
                    "underneath the app. Reopen the book and try again.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDone) { Text("Close") }
            }
            return@Column
        }

        Text(
            "Saving rewrites just this paragraph in the file on disk. The rest of the draft " +
                "is untouched.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDone, enabled = !saving) { Text("Cancel") }
            Button(
                enabled = !saving && text.isNotBlank() && text != original,
                onClick = {
                    saving = true
                    scope.launch {
                        BookSession.saveParagraph(context, text)
                        saving = false
                        onDone()
                    }
                },
            ) {
                Text(if (saving) "Saving…" else "Save")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet() {
    val context = LocalContext.current
    val speed by BookSession.speed.collectAsState()
    // Deliberately not remembered: the engine fills this in asynchronously, so
    // reopening the sheet after the first play is what makes the list appear.
    val voices = Narration.voices()
    var chosenVoice by remember { mutableStateOf(Prefs(context).voiceName) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        SheetTitle("Voice and speed")
        Text("Speed  ${"%.2f".format(speed)}×", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = speed,
            onValueChange = { Narration.setSpeed(context, it) },
            valueRange = 0.5f..2.5f,
            steps = 19,
        )
        Text(
            "Voices",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        if (voices.isEmpty()) {
            Text(
                "No voices yet — the speech engine starts with the first play. " +
                    "Press play once, then reopen this sheet.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
            items(voices, key = { it.name }) { voice ->
                val picked = voice.name == chosenVoice
                ListItem(
                    headlineContent = {
                        Text(
                            voice.label,
                            fontWeight = if (picked) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    modifier = Modifier.clickable {
                        chosenVoice = voice.name
                        Narration.setVoice(context, voice.name)
                    },
                )
                HorizontalDivider()
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    Narration.stop(context)
                    BookSession.close()
                }
            ) {
                Icon(Icons.Filled.Close, contentDescription = null)
                Text("  Close this book")
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}
