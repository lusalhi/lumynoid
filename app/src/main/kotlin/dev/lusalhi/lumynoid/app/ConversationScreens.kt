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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lusalhi.lumynoid.core.Conversation
import dev.lusalhi.lumynoid.core.Project
import dev.lusalhi.lumynoid.core.ProjectPort

@Composable
fun ConversationListScreen(
    conversations: List<Conversation>,
    openId: String?,
    pendingIds: Set<String>?,
    providerConfigured: Boolean,
    onOpenProvider: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    projectName: String? = null,
    onProjects: (() -> Unit)? = null,
    projectInstructions: String? = null,
    onSaveInstructions: ((String, (Boolean) -> Unit) -> Unit)? = null,
) {
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var editingInstructions by remember { mutableStateOf(false) }

    fun canDeleteConversation(id: String): Boolean = pendingIds != null && id !in pendingIds

    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                projectName ?: stringResource(R.string.conversations_title),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge,
            )
            Row {
                TextButton(onClick = onNew) {
                    Text(stringResource(if (projectName == null) R.string.conversation_new_chat else R.string.project_new_conversation))
                }
                TextButton(onClick = onBack) { Text(stringResource(R.string.conversations_back)) }
            }
        }
        if (onProjects != null) {
            TextButton(onClick = onProjects, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.projects_title))
            }
        }
        if (projectInstructions != null && onSaveInstructions != null) {
            TextButton(onClick = { editingInstructions = true }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.project_instructions))
            }
        }
        if (conversations.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (providerConfigured) {
                    Text(stringResource(R.string.conversations_empty), Modifier.padding(24.dp))
                } else {
                    SetupPrompt(R.string.setup_first, onOpenProvider, Modifier.padding(24.dp))
                }
            }
        } else {
            if (!providerConfigured) SetupBanner(onOpenProvider, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(conversations, key = { it.id }) { c ->
                    ConversationRow(
                        conversation = c,
                        isOpen = c.id == openId,
                        canDelete = canDeleteConversation(c.id),
                        onOpen = { onOpen(c.id) },
                        onRename = { renaming = c },
                        onDelete = { deletingId = c.id },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    renaming?.let { c ->
        NameDialog(
            initial = c.title,
            title = R.string.conversation_rename_title,
            label = R.string.conversation_name,
            confirm = R.string.conversation_save,
            onConfirm = { onRename(c.id, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    deletingId?.let { id -> conversations.firstOrNull { it.id == id } }?.let { c ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text(stringResource(R.string.conversation_delete_title)) },
            text = {
                Text(stringResource(
                    if (c.projectId == null) R.string.conversation_delete_body
                    else R.string.project_conversation_delete_body,
                ))
            },
            confirmButton = {
                Button(
                    onClick = { onDelete(c.id); deletingId = null },
                    enabled = canDeleteConversation(c.id),
                ) { Text(stringResource(R.string.conversation_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.conversation_cancel)) }
            },
        )
    }
    if (editingInstructions && projectInstructions != null && onSaveInstructions != null) {
        InstructionsDialog(
            initial = projectInstructions,
            onSave = onSaveInstructions,
            onDismiss = { editingInstructions = false },
        )
    }
}

@Composable
internal fun ProjectsScreen(
    projects: List<Project>,
    conversations: List<Conversation>,
    pendingIds: Set<String>?,
    deletingIds: Set<String>,
    onOpen: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    // Projects with unfinished member work, derived once per list change; null until the runner is ready.
    val pendingProjectIds = remember(conversations, pendingIds) {
        pendingIds?.let { pending -> conversations.mapNotNull { c -> c.projectId?.takeIf { c.id in pending } }.toSet() }
    }

    fun canDeleteProject(id: String): Boolean =
        pendingProjectIds != null && id !in pendingProjectIds && id !in deletingIds

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.projects_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { creating = true }) { Text(stringResource(R.string.project_new)) }
            TextButton(onClick = onBack) { Text(stringResource(R.string.conversations_back)) }
        }
        if (projects.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.projects_empty), Modifier.padding(24.dp))
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(projects, key = { it.id }) { project ->
                    if (project.deleting) {
                        DeletingProjectRow(
                            project = project,
                            canResume = project.id !in deletingIds,
                            onResume = { onDelete(project.id) },
                        )
                    } else {
                        val inFlight = project.id in deletingIds
                        ProjectRow(
                            project = project,
                            canOpen = !inFlight,
                            canDelete = canDeleteProject(project.id),
                            onOpen = { onOpen(project.id) },
                            onDelete = { deletingId = project.id },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    if (creating) {
        NameDialog(
            initial = "",
            title = R.string.project_new,
            label = R.string.project_name,
            confirm = R.string.project_create,
            onConfirm = { onCreate(it); creating = false },
            onDismiss = { creating = false },
        )
    }
    deletingId?.let { id -> projects.firstOrNull { it.id == id && !it.deleting } }?.let { project ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text(stringResource(R.string.project_delete_title, project.name)) },
            text = { Text(stringResource(R.string.project_delete_body)) },
            confirmButton = {
                Button(
                    onClick = { onDelete(project.id); deletingId = null },
                    enabled = canDeleteProject(project.id),
                ) { Text(stringResource(R.string.project_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.conversation_cancel)) }
            },
        )
    }
}

@Composable
private fun ProjectRow(
    project: Project,
    canOpen: Boolean,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val moreActions = stringResource(R.string.project_more_actions, project.name)
    Row(
        Modifier.fillMaxWidth().clickable(enabled = canOpen, onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            project.name,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
        )
        Box {
            TextButton(
                onClick = { menu = true },
                modifier = Modifier.semantics { contentDescription = moreActions },
            ) { Text(stringResource(R.string.conversations_more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.project_delete)) },
                    enabled = canDelete,
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

/** A project marked for deletion cannot be opened; the row keeps its resume action until cleanup succeeds. */
@Composable
private fun DeletingProjectRow(
    project: Project,
    canResume: Boolean,
    onResume: () -> Unit,
) {
    val resumeDeletion = stringResource(R.string.project_resume_deletion, project.name)
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                project.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.project_deleting_status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onResume,
            enabled = canResume,
            modifier = Modifier.semantics { contentDescription = resumeDeletion },
        ) { Text(stringResource(R.string.project_delete_resume)) }
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
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.conversation_delete)) },
                    enabled = canDelete,
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun NameDialog(
    initial: String,
    title: Int,
    label: Int,
    confirm: Int,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.conversation_cancel)) } },
    )
}

@Composable
private fun InstructionsDialog(
    initial: String,
    onSave: (String, (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val count = draft.codePointCount(0, draft.length)
    val overLimit = count > ProjectPort.MAX_INSTRUCTION_CODE_POINTS
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.project_instructions_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.project_instructions_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it; failed = false },
                    label = { Text(stringResource(R.string.project_instructions_label)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    minLines = 4,
                    maxLines = 6,
                    enabled = !saving,
                )
                Text(
                    stringResource(R.string.project_instructions_count, count, ProjectPort.MAX_INSTRUCTION_CODE_POINTS),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (overLimit) {
                    Text(
                        stringResource(R.string.project_instructions_over_limit, ProjectPort.MAX_INSTRUCTION_CODE_POINTS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (failed) {
                    Text(
                        stringResource(R.string.project_instructions_save_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (saving || overLimit) return@Button
                    saving = true
                    failed = false
                    onSave(draft) { saved ->
                        saving = false
                        if (saved) onDismiss() else failed = true
                    }
                },
                enabled = !saving && !overLimit,
            ) { Text(stringResource(R.string.conversation_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.conversation_cancel)) }
        },
    )
}
