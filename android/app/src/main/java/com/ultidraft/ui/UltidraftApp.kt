package com.ultidraft.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ultidraft.data.BookSession
import com.ultidraft.data.BookStore
import com.ultidraft.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Three states, in the order you meet them: pick the synced folder, pick a book, listen.
 */
@Composable
fun UltidraftApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(context) }

    var treeUri by remember { mutableStateOf(prefs.treeUri) }
    var books by remember { mutableStateOf<List<BookStore.BookRef>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }

    val manuscript by BookSession.manuscript.collectAsState()

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            BookStore.persistFolderPermission(context, uri)
            prefs.treeUri = uri
            treeUri = uri
            loadError = null
        } catch (error: Exception) {
            loadError = error.message ?: "Android would not keep access to that folder."
        }
    }

    suspend fun rescan(uri: Uri) {
        scanning = true
        try {
            books = withContext(Dispatchers.IO) { BookStore.listBooks(context, uri) }
            loadError = if (books.isEmpty()) "No .md or .txt files in that folder yet." else null
        } catch (error: Exception) {
            books = emptyList()
            loadError = error.message ?: "Could not read that folder."
        } finally {
            scanning = false
        }
    }

    LaunchedEffect(treeUri, manuscript) {
        val uri = treeUri
        if (uri != null && manuscript == null) rescan(uri)
    }

    when {
        treeUri == null -> WelcomeScreen(
            error = loadError,
            onPick = { pickFolder.launch(null) },
        )

        manuscript == null -> LibraryScreen(
            books = books,
            scanning = scanning,
            error = loadError,
            onOpen = { book ->
                scope.launch {
                    try {
                        BookSession.open(context, treeUri!!, book)
                    } catch (error: Exception) {
                        loadError = error.message ?: "Could not open that book."
                    }
                }
            },
            onRescan = { scope.launch { treeUri?.let { rescan(it) } } },
            onChangeFolder = { pickFolder.launch(null) },
        )

        else -> ReaderScreen()
    }
}

@Composable
private fun WelcomeScreen(error: String?, onPick: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Filled.MenuBook,
                contentDescription = null,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text("Ultidraft", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Point Ultidraft at the folder your draft lives in — the one Syncthing keeps " +
                    "in step with your PC. Notes are written back into that same folder.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onPick) {
                Icon(Icons.Filled.Folder, contentDescription = null)
                Text("  Choose the book folder")
            }
            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    books: List<BookStore.BookRef>,
    scanning: Boolean,
    error: String?,
    onOpen: (BookStore.BookRef) -> Unit,
    onRescan: () -> Unit,
    onChangeFolder: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your drafts") },
                actions = {
                    IconButton(onClick = onRescan) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Rescan the folder")
                    }
                    IconButton(onClick = onChangeFolder) {
                        Icon(Icons.Filled.Folder, contentDescription = "Change folder")
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (scanning) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            if (error != null) {
                Text(
                    error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = onChangeFolder,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text("Choose a different folder")
                }
            }
            LazyColumn {
                items(books, key = { it.uri.toString() }) { book ->
                    ListItem(
                        headlineContent = { Text(book.name) },
                        leadingContent = { Icon(Icons.Filled.MenuBook, contentDescription = null) },
                        modifier = Modifier.clickable { onOpen(book) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
