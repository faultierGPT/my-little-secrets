package app.mls.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.mls.android.session.NoteUi

/**
 * The unlocked workspace. Shows the note list, or a full-screen editor for the selected/new note.
 * Edits are saved when leaving the editor; copying a note body schedules a clipboard auto-clear.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    state: UiState,
    onSave: (String?, String, String, List<String>) -> Unit,
    onDelete: (String) -> Unit,
    onSync: () -> Unit,
    onLock: () -> Unit,
    onEnableBiometric: (() -> Unit)?,
) {
    var editing by remember { mutableStateOf<NoteUi?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val current = editing
    if (current != null) {
        // Hardware/gesture back must pop the editor back to the list (with auto-save), NOT close
        // the activity. Without this, Android's back button in the editor exits the whole app.
        BackHandler {
            val title = current.title
            val body = current.body
            val tags = current.tags
            if (isNew && title.isBlank() && body.isBlank() && tags.isEmpty()) {
                // discard an empty new note
            } else {
                onSave(current.id.ifBlank { null }, title, body, tags)
            }
            editing = null
        }
        NoteEditor(
            note = current,
            isNew = isNew,
            onBack = { title, body, tags ->
                if (isNew && title.isBlank() && body.isBlank() && tags.isEmpty()) {
                    // discard an empty new note
                } else {
                    onSave(current.id.ifBlank { null }, title, body, tags)
                }
                editing = null
            },
            onDelete = {
                if (current.id.isNotBlank()) onDelete(current.id)
                editing = null
            },
        )
        return
    }

    // Client-side search: case-insensitive substring match against title, body and tags.
    // Empty query shows everything; empty result shows a contextual hint instead of nothing.
    val filtered by remember(state.notes, query) {
        derivedStateOf { filterNotes(state.notes, query) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("my-little-secrets", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    if (onEnableBiometric != null) {
                        TextButton(onClick = onEnableBiometric) { Text("Biometric") }
                    }
                    TextButton(onClick = onSync, enabled = !state.busy) { Text("Sync") }
                    TextButton(onClick = onLock) { Text("Lock") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editing = NoteUi("", "", "", emptyList(), 0, false)
                isNew = true
            }) { Text("+", style = MaterialTheme.typography.titleLarge) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.status.isNotBlank()) {
                Text(
                    state.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            // Search bar — sits between the status line and the list. Single line, clear button
            // appears only when the field has content. Filtering is done above (`filtered`).
            SearchField(
                query = query,
                onQueryChange = { query = it },
            )
            if (state.notes.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
                    Text(
                        "No notes yet — tap + to write one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (filtered.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
                    Text(
                        "No notes match \"${query.trim()}\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { note ->
                        NoteRow(note) { editing = note; isNew = false }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search notes") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        trailingIcon = {
            if (query.isNotEmpty()) {
                TextButton(onClick = { onQueryChange("") }) { Text("✕") }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Case-insensitive substring match against title, body and joined tags. Empty/whitespace query
 * returns the input unchanged. Pure function so it's safe inside `derivedStateOf`.
 */
private fun filterNotes(notes: List<NoteUi>, query: String): List<NoteUi> {
    val q = query.trim()
    if (q.isEmpty()) return notes
    return notes.filter { it.matches(q) }
}

private fun NoteUi.matches(query: String): Boolean {
    val needle = query.lowercase()
    if (title.lowercase().contains(needle)) return true
    if (body.lowercase().contains(needle)) return true
    if (tags.any { it.lowercase().contains(needle) }) return true
    return false
}

@Composable
private fun NoteRow(note: NoteUi, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row {
            Text(note.displayTitle, style = MaterialTheme.typography.labelLarge)
            if (note.dirty) {
                Spacer(Modifier.height(0.dp))
                Text("  •", color = MaterialTheme.colorScheme.primary)
            }
        }
        val snippet = note.snippet
        if (snippet.isNotBlank()) {
            Text(
                snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoteEditor(
    note: NoteUi,
    isNew: Boolean,
    onBack: (String, String, List<String>) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(note.title) }
    var tags by remember { mutableStateOf(note.tags.joinToString(", ")) }
    var body by remember { mutableStateOf(note.body) }

    fun parsedTags() = tags.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onBack(title, body, parsedTags()) }) { Text("Back") }
            Spacer(Modifier.weight(1f, fill = true))
            TextButton(onClick = { copyToClipboard(context, body) }) { Text("Copy") }
            if (!isNew) {
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = tags,
            onValueChange = { tags = it },
            label = { Text("Tags (comma separated)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("Write something only you can read…") },
            modifier = Modifier.fillMaxWidth().weight(1f, fill = true),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onBack(title, body, parsedTags()) }, modifier = Modifier.fillMaxWidth()) {
            Text("Save")
        }
    }
}

private val mainHandler = Handler(Looper.getMainLooper())

/** Copy [text] and clear it from the clipboard after 30s, but only if it's still ours. */
private fun copyToClipboard(context: Context, text: String) {
    if (text.isEmpty()) return
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("note", text))
    mainHandler.postDelayed({
        val current = cm.primaryClip?.getItemAt(0)?.text?.toString()
        if (current == text) {
            cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }, 30_000)
}
