package dev.lusalhi.lumynoid.app

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lusalhi.lumynoid.core.ApprovalResolution
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.ToolOutcome
import kotlinx.coroutines.launch

/**
 * The conversation's read-only action log, opened from chat. Entries show what was attempted and its
 * factual status; tapping one opens the full stored result as plain text and any published previews.
 * Historical actions themselves cannot be approved or run from here.
 */
@Composable
internal fun ActionLogScreen(
    conversationId: String,
    events: List<TaskEvent>,
    title: String,
    onBack: () -> Unit,
    onOpenArtifact: (ArtifactIdentity) -> Unit,
) {
    val rows = remember(events) { actionLogRows(events) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val selected = remember(rows, selectedKey) {
        rows.firstOrNull { it.key == selectedKey } as? ActionLogRow.Action
    }
    BackHandler { if (selectedKey != null) selectedKey = null else onBack() }

    if (selected != null) {
        ActionDetail(selected, conversationId, onBack = { selectedKey = null }, onOpenArtifact = onOpenArtifact)
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.action_log_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    title.ifBlank { stringResource(R.string.conversation_untitled) },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_log_back)) }
        }
        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.action_log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is ActionLogRow.Action -> ActionEntry(row) { selectedKey = row.key }
                        is ActionLogRow.Notice -> NoticeRow(row.event)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionEntry(row: ActionLogRow.Action, onOpen: () -> Unit) {
    val status = row.status()
    val target = actionTarget(row.call, row.outcome)
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(toolTitle(row.call.description)), style = MaterialTheme.typography.titleMedium)
            if (target != null) Text(target, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(status.labelRes()), style = MaterialTheme.typography.labelLarge, color = status.color())
        }
    }
}

@Composable
private fun NoticeRow(event: TaskEvent) {
    val modifier = Modifier.padding(horizontal = 4.dp)
    when (event) {
        is TaskEvent.TaskFailed -> Text(
            stringResource(event.reason.messageRes()),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )
        is TaskEvent.TaskCancelled -> Text(stringResource(R.string.task_cancelled), style = MaterialTheme.typography.bodySmall, modifier = modifier)
        is TaskEvent.TaskFinished -> Text(stringResource(R.string.action_notice_finished), style = MaterialTheme.typography.bodySmall, modifier = modifier)
        is TaskEvent.ToolCallRejected -> Text(
            stringResource(R.string.action_notice_rejected, event.callNumber),
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier,
        )
        is TaskEvent.LlmRetry -> Text(
            stringResource(R.string.action_notice_retry, event.callNumber),
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier,
        )
        is TaskEvent.Unknown -> Text(stringResource(R.string.action_notice_unknown), style = MaterialTheme.typography.bodySmall, modifier = modifier)
        else -> Unit
    }
}

/** One stored result in full: fields first, then the exact output in bounded lazy chunks. */
@Composable
private fun ActionDetail(
    row: ActionLogRow.Action,
    conversationId: String,
    onBack: () -> Unit,
    onOpenArtifact: (ArtifactIdentity) -> Unit,
) {
    val outcome = row.outcome
    val status = row.status()
    val chunks = remember(outcome?.output) { outputChunks(outcome?.output.orEmpty()) }
    val artifacts = remember(row, conversationId) { row.artifactIdentities(conversationId) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_log_back)) }
            Text(
                stringResource(toolTitle(row.call.description)),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (chunks.size > 1) {
            // Large stored output would otherwise take many swipes to reach its end.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = { scope.launch { listState.scrollToItem(0) } }) {
                    Text(stringResource(R.string.action_jump_top))
                }
                TextButton(onClick = { scope.launch { listState.scrollToItem(chunks.size + 2) } }) {
                    Text(stringResource(R.string.action_jump_end))
                }
            }
        }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = "fields") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(status.labelRes()), style = MaterialTheme.typography.labelLarge, color = status.color())
                        CallFields(row.call.description)
                        if (row.approvalId != null) {
                            ToolField(R.string.action_field_approval, stringResource(row.approvalLabelRes()))
                        }
                        if (outcome == null) {
                            Text(stringResource(R.string.tool_no_result), style = MaterialTheme.typography.bodySmall)
                        } else {
                            OutcomeFields(outcome)
                            for (artifact in artifacts) PreviewCard(artifact, onOpenArtifact)
                        }
                    }
                }
                if (outcome != null && outcome.output.isNotEmpty()) {
                    item(key = "output-header") {
                        Column {
                            Text(stringResource(R.string.tool_field_output), style = MaterialTheme.typography.labelMedium)
                            Text(
                                stringResource(R.string.action_output_size, outcome.output.length),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    // Each chunk is its own bounded Text, so even a very large stored result stays
                    // fully reachable by scrolling; concatenation reproduces the exact output.
                    items(chunks.size, key = { "output-$it" }) { index ->
                        Text(
                            chunks[index],
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // A final anchor reaches the tail even when the last chunk is taller than the viewport.
                    item(key = "output-end") { Spacer(Modifier.height(1.dp)) }
                }
            }
        }
    }
}

@Composable
private fun OutcomeFields(outcome: ToolOutcome) {
    outcome.target?.let { ToolField(R.string.tool_field_target, it) }
    outcome.exitCode?.let { ToolField(R.string.tool_field_exit_code, it.toString()) }
    for (source in outcome.sourceUrls) ToolField(R.string.tool_field_source, source)
    outcome.truncationHint?.let { ToolField(R.string.action_field_capture_note, it) }
}

@StringRes
internal fun ActionStatus.labelRes(): Int = when (this) {
    ActionStatus.SUCCEEDED -> R.string.action_status_succeeded
    ActionStatus.FAILED -> R.string.action_status_failed
    ActionStatus.UNCONFIRMED -> R.string.action_status_unconfirmed
    ActionStatus.DENIED_PREFLIGHT -> R.string.action_status_denied_preflight
    ActionStatus.DENIED_EXECUTION -> R.string.action_status_denied_execution
    ActionStatus.NOT_RUN_BATCH -> R.string.action_status_not_run_batch
    ActionStatus.APPROVAL_DENIED -> R.string.action_status_approval_denied
    ActionStatus.APPROVAL_EXPIRED -> R.string.action_status_approval_expired
    ActionStatus.APPROVAL_CANCELLED -> R.string.action_status_approval_cancelled
    ActionStatus.AWAITING_APPROVAL -> R.string.action_status_awaiting_approval
    ActionStatus.INTERRUPTED -> R.string.action_status_interrupted
    ActionStatus.CANCELLED -> R.string.action_status_cancelled
    ActionStatus.NO_RESULT -> R.string.action_status_no_result
    ActionStatus.PENDING -> R.string.action_status_pending
}

@Composable
private fun ActionStatus.color() = when (this) {
    ActionStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
    ActionStatus.FAILED, ActionStatus.DENIED_PREFLIGHT, ActionStatus.DENIED_EXECUTION,
    ActionStatus.APPROVAL_DENIED, ActionStatus.APPROVAL_EXPIRED, ActionStatus.APPROVAL_CANCELLED,
    ActionStatus.INTERRUPTED, ActionStatus.CANCELLED, ActionStatus.NOT_RUN_BATCH,
    ActionStatus.NO_RESULT,
    -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@StringRes
private fun ActionLogRow.Action.approvalLabelRes(): Int = when (approvalResolution) {
    ApprovalResolution.APPROVED -> R.string.approval_approved
    ApprovalResolution.DENIED -> R.string.approval_denied
    ApprovalResolution.EXPIRED -> R.string.approval_expired
    ApprovalResolution.CANCELLED -> R.string.approval_cancelled
    null -> if (taskEnd != null) R.string.approval_inactive else R.string.approval_required
}
