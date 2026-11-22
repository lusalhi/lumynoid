package dev.lusalhi.lumynoid.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.userland.WorkspacePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only this nonexported, internet-denied process renders untrusted HTML. */
class ArtifactHtmlActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var loading by mutableStateOf(true)
        var networkDenied by mutableStateOf(false)
        var artifact by mutableStateOf<ArtifactIdentity?>(null)
        setContent {
            LumynoidTheme {
                Surface(Modifier.fillMaxSize()) {
                    val file = artifact
                    if (file != null && networkDenied) {
                        HtmlFilePreview(file, remember(file.conversationId) { WorkspacePreview(this, file.conversationId) }, ::finish)
                    } else {
                        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                            PreviewHeader(file?.path.orEmpty(), ::finish)
                            HorizontalDivider()
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                when {
                                    loading -> CircularProgressIndicator()
                                    file == null -> PreviewMessage(R.string.preview_unavailable)
                                    else -> PreviewMessage(R.string.preview_sandbox_unavailable)
                                }
                            }
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            artifact = lookupArtifact()
            networkDenied = withContext(Dispatchers.IO) { networkSocketsDenied() } && WebView.getCurrentWebViewPackage() != null
            loading = false
        }
    }

    private suspend fun lookupArtifact(): ArtifactIdentity? {
        val conversation = intent.getStringExtra(CONVERSATION) ?: return null
        val resultId = intent.getStringExtra(RESULT) ?: return null
        val index = intent.getIntExtra(ARTIFACT_INDEX, -1)
        if (index < 0) return null
        val result = (application as LumynoidApp).graph.data.event(conversation, resultId) as? TaskEvent.ToolResult ?: return null
        val file = result.outcome.artifacts.getOrNull(index) ?: return null
        return ArtifactIdentity(conversation, resultId, file.path, file.mediaType).takeIf { it.isHtml() }
    }

    companion object {
        private const val CONVERSATION = "conversation"
        private const val RESULT = "result"
        private const val ARTIFACT_INDEX = "artifact_index"

        internal fun intent(context: Context, file: ArtifactIdentity, index: Int) =
            Intent(context, ArtifactHtmlActivity::class.java)
                .putExtra(CONVERSATION, file.conversationId)
                .putExtra(RESULT, file.resultEventId)
                .putExtra(ARTIFACT_INDEX, index)
    }
}

// Older or incompatible Android builds may not enforce the manifest permission at process level.
// Do not initialize a WebView if its host can create Internet sockets.
private fun networkSocketsDenied(): Boolean =
    socketDenied(OsConstants.AF_INET) && socketDenied(OsConstants.AF_INET6)

private fun socketDenied(family: Int): Boolean {
    val fd = try {
        Os.socket(family, OsConstants.SOCK_STREAM, 0)
    } catch (error: ErrnoException) {
        return error.errno == OsConstants.EPERM || error.errno == OsConstants.EACCES
    }
    Os.close(fd)
    return false
}

@Composable
private fun HtmlFilePreview(file: ArtifactIdentity, workspace: WorkspacePreview, onClose: () -> Unit) {
    var javascript by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        PreviewHeader(file.path, onClose)
        TextButton(onClick = { javascript = !javascript }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(if (javascript) R.string.preview_disable_js else R.string.preview_enable_js))
        }
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            key(javascript) {
                var unavailable by remember { mutableStateOf(false) }
                if (unavailable) {
                    PreviewMessage(R.string.preview_unavailable)
                } else {
                    AndroidView(
                        factory = { context ->
                            createOfflinePreviewWebView(context, file.path, javascript, workspace::read) { unavailable = true }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onReset = null,
                        onRelease = { it.destroy() },
                    )
                }
            }
        }
    }
}
