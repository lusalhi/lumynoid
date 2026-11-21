package dev.lusalhi.lumynoid.app

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.lusalhi.lumynoid.core.ApprovalDecision
import dev.lusalhi.lumynoid.core.ApprovalResolution
import dev.lusalhi.lumynoid.core.RiskAssessment
import dev.lusalhi.lumynoid.core.UiText
import dev.lusalhi.lumynoid.core.parseToolArguments
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive

@StringRes
internal fun toolTitle(description: UiText): Int = when (description.key) {
    "shell_read_file" -> R.string.tool_read_file
    "shell_create_file" -> R.string.tool_create_file
    "shell_replace_file" -> R.string.tool_replace_file
    "shell_npm_install" -> R.string.tool_npm_install
    "shell_run_script" -> R.string.tool_run_script
    "runtime_setup" -> R.string.tool_runtime_setup
    "runtime_probe" -> R.string.tool_runtime_probe
    else -> R.string.tool_unknown
}

@Composable
internal fun ActivityCard(row: ChatRow.Activity) {
    val outcome = row.outcome
    val description = row.call.description
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(toolTitle(description)), style = MaterialTheme.typography.titleMedium)
            if (outcome == null) {
                Text(stringResource(R.string.tool_no_result), style = MaterialTheme.typography.bodySmall)
            } else {
                outcome.target?.let { ToolField(R.string.tool_field_target, it) }
                outcome.exitCode?.let { ToolField(R.string.tool_field_exit_code, it.toString()) }
                for (artifact in outcome.artifacts) ToolField(R.string.tool_field_artifact, artifact.path)
                if (row.call.call.name == "shell_run_script" && outcome.artifacts.isEmpty()) {
                    Text(stringResource(R.string.tool_no_artifact), style = MaterialTheme.typography.bodySmall)
                }
            }
            ToolDetails {
                CallFields(description)
                if (outcome != null) {
                    for (source in outcome.sourceUrls) ToolField(R.string.tool_field_source, source)
                    outcome.truncationHint?.let { ToolField(R.string.tool_field_note, it) }
                    if (outcome.output.isNotEmpty()) {
                        val preview = remember(outcome.output) { outcome.output.take(OUTPUT_PREVIEW_LENGTH) }
                        ToolText(R.string.tool_field_output, preview)
                        if (preview.length < outcome.output.length) {
                            Text(stringResource(R.string.tool_output_preview), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ApprovalCard(row: ChatRow.Approval, elapsedDeadline: Long?, onDecision: (ApprovalDecision) -> Unit) {
    var now by remember(elapsedDeadline) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LifecycleResumeEffect(elapsedDeadline) {
        now = SystemClock.elapsedRealtime()
        onPauseOrDispose {}
    }
    LaunchedEffect(elapsedDeadline) {
        if (elapsedDeadline != null) {
            delay((elapsedDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            now = SystemClock.elapsedRealtime()
        }
    }
    val pending = row.canDecide(elapsedDeadline, now)
    val approval = row.event.approval
    val description = approval.call.description
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(toolTitle(description)), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(when (row.resolution) {
                ApprovalResolution.APPROVED -> R.string.approval_approved
                ApprovalResolution.DENIED -> R.string.approval_denied
                ApprovalResolution.EXPIRED -> R.string.approval_expired
                ApprovalResolution.CANCELLED -> R.string.approval_cancelled
                null -> when {
                    pending -> R.string.approval_required
                    !row.closed && elapsedDeadline != null -> R.string.approval_expired
                    else -> R.string.approval_inactive
                }
            }), style = MaterialTheme.typography.labelLarge)
            for (key in APPROVAL_SUMMARY_FIELDS) {
                description.args[key]?.let { ToolField(fieldLabel(key), it.content) }
            }
            ToolDetails {
                val riskLabel = when (approval.risk) {
                    RiskAssessment.DESTRUCTIVE -> R.string.tool_risk_destructive
                    RiskAssessment.EGRESS -> R.string.tool_risk_egress
                    RiskAssessment.FINANCIAL -> R.string.tool_risk_financial
                    RiskAssessment.PERSISTENT -> R.string.tool_risk_persistent
                    else -> R.string.tool_unknown
                }
                ToolField(R.string.tool_field_risk, stringResource(riskLabel))
                CallFields(description, exclude = APPROVAL_SUMMARY_FIELDS)
                if (approval.call.call.name == "shell_replace_file") {
                    val proposed = remember(approval.call.call.argsJson) {
                        (parseToolArguments(approval.call.call.argsJson)?.get("content") as? JsonPrimitive)?.content
                    }
                    if (proposed != null) ToolText(R.string.tool_field_new_content, proposed)
                }
            }
            if (pending) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = { onDecision(ApprovalDecision.DENY) }) { Text(stringResource(R.string.approval_deny)) }
                    Button(onClick = { onDecision(ApprovalDecision.APPROVE) }) { Text(stringResource(R.string.approval_approve)) }
                }
            }
        }
    }
}

@Composable
private fun ToolDetails(content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.tool_hide_details else R.string.tool_show_details))
    }
    if (expanded) content()
}

@Composable
internal fun CallFields(description: UiText, exclude: Set<String> = emptySet()) {
    for ((key, value) in description.args) {
        if (key in exclude) continue
        val label = fieldLabel(key)
        if (label == 0) LabeledValue(key, value.content) else ToolField(label, value.content)
    }
}

@Composable
internal fun ToolField(@StringRes label: Int, value: String) = LabeledValue(stringResource(label), value)

@Composable
private fun LabeledValue(label: String, value: String) {
    Text(stringResource(R.string.tool_field_value, label, value), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ToolText(@StringRes label: Int, value: String) {
    Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
    SelectionContainer(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@StringRes
private fun fieldLabel(key: String): Int = when (key) {
    "path" -> R.string.tool_field_path
    "input" -> R.string.tool_field_input
    "output" -> R.string.tool_field_destination
    "package" -> R.string.tool_field_package
    "script" -> R.string.tool_field_script
    "operation" -> R.string.tool_field_operation
    "expectedSha256" -> R.string.tool_field_expected_hash
    "version" -> R.string.tool_field_version
    "sizeBytes" -> R.string.tool_field_size_bytes
    "source" -> R.string.tool_field_source
    else -> 0
}

private val APPROVAL_SUMMARY_FIELDS = setOf("path", "version", "source", "sizeBytes")
private const val OUTPUT_PREVIEW_LENGTH = 4_096
