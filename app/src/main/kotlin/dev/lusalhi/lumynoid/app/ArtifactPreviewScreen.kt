package dev.lusalhi.lumynoid.app

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.nio.charset.CharacterCodingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PreviewCard(artifact: ArtifactIdentity, onOpen: (ArtifactIdentity) -> Unit) {
    OutlinedCard(onClick = { onOpen(artifact) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.preview_open),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(artifact.path, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            Text(artifact.mediaType, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ArtifactTextDialog(artifact: ArtifactIdentity, index: Int, onClose: () -> Unit) {
    val graph = (LocalContext.current.applicationContext as LumynoidApp).graph
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                PreviewHeader(artifact.path, onClose)
                HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextFilePreview(graph, artifact, index)
                }
            }
        }
    }
}

@Composable
internal fun PreviewHeader(path: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.preview_open), style = MaterialTheme.typography.labelLarge)
            Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
        }
        TextButton(onClick = onClose) { Text(stringResource(R.string.preview_close)) }
    }
}

@Composable
private fun TextFilePreview(graph: AppGraph, artifact: ArtifactIdentity, index: Int) {
    var loaded by remember(artifact, index) { mutableStateOf(false) }
    val text by produceState<String?>(null, graph, artifact, index) {
        value = withContext(Dispatchers.IO) {
            try {
                val published = graph.previewArtifact(artifact.conversationId, artifact.resultEventId, index)
                published?.takeIf { it.artifact == artifact }?.let {
                    it.workspace.read(it.artifact.path)?.decodeToString(throwOnInvalidSequence = true)
                }
            } catch (_: CharacterCodingException) {
                null
            }
        }
        loaded = true
    }
    val content = text
    when {
        !loaded -> CircularProgressIndicator()
        content == null -> PreviewMessage(R.string.preview_unavailable)
        content.isEmpty() -> PreviewMessage(R.string.preview_empty)
        else -> {
            val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
            Column(Modifier.fillMaxSize()) {
                TextButton(
                    onClick = { clipboard?.setPrimaryClip(ClipData.newPlainText(artifact.path, content)) },
                    modifier = Modifier.align(Alignment.End).padding(horizontal = 8.dp),
                ) { Text(stringResource(R.string.preview_copy_file)) }
                val chunks = remember(content) { outputChunks(content) }
                SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxSize()) {
                        items(chunks.size) { index ->
                            Text(chunks[index], fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PreviewMessage(message: Int) {
    Text(stringResource(message), modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
}
