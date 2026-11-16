package dev.lusalhi.lumynoid.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lusalhi.lumynoid.core.Conversation

@Composable
fun ConversationListScreen(
    conversations: List<Conversation>,
    openId: String?,
    streamingId: String?,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.conversations_title), style = MaterialTheme.typography.titleLarge)
            Row {
                TextButton(onClick = onNew) { Text(stringResource(R.string.chat_new)) }
                TextButton(onClick = onBack) { Text(stringResource(R.string.conversations_back)) }
            }
        }
        if (conversations.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.conversations_empty), Modifier.padding(24.dp))
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(conversations, key = { it.id }) { c ->
                    ConversationRow(
                        conversation = c,
                        isOpen = c.id == openId,
                        canDelete = c.id != streamingId,
                        onOpen = { onOpen(c.id) },
                        onRename = { renaming = c },
                        onDelete = { deleting = c },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    renaming?.let { c ->
        RenameDialog(
            initial = c.title,
            onConfirm = { onRename(c.id, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.conversation_delete_title)) },
            text = { Text(stringResource(R.string.conversation_delete_body)) },
            confirmButton = {
                Button(onClick = { onDelete(c.id); deleting = null }) { Text(stringResource(R.string.conversation_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.conversation_cancel)) }
            },
        )
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    isOpen: Boolean,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            conversation.title.ifBlank { stringResource(R.string.conversation_untitled) },
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isOpen) FontWeight.Bold else FontWeight.Normal,
        )
        Box {
            TextButton(onClick = { menu = true }) { Text(stringResource(R.string.conversations_more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.conversation_rename)) },
                    onClick = { menu = false; onRename() },
                )
                if (canDelete) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.conversation_delete)) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conversation_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.conversation_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.conversation_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.conversation_cancel)) } },
    )
}
