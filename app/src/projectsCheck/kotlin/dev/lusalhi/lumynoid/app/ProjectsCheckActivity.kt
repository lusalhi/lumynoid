package dev.lusalhi.lumynoid.app

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class ProjectsCheckActivity : ComponentActivity() {
    private val model: ProjectsCheckViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val report by model.report.collectAsStateWithLifecycle()
            val view = LocalView.current
            DisposableEffect(view, report.running) {
                view.keepScreenOn = report.running
                onDispose { view.keepScreenOn = false }
            }
            LumynoidTheme {
                Surface(Modifier.fillMaxSize()) { ProjectsCheckScreen(report, model::runChecks) }
            }
        }
    }
}

@Composable
private fun ProjectsCheckScreen(report: ProjectsCheckReport, run: () -> Unit) {
    val failures = report.results.count { it != null }
    val complete = report.results.size == PROJECT_CHECKS.size
    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.projects_check_kicker), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.projects_check_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.projects_check_intro), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = run, enabled = !report.running, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.projects_check_run))
        }
        if (report.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (report.running || complete) {
            Text(stringResource(R.string.projects_check_summary, report.results.size - failures, failures, PROJECT_CHECKS.size),
                style = MaterialTheme.typography.titleSmall)
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(PROJECT_CHECKS.size) { index ->
                val scenario = PROJECT_CHECKS[index]
                val done = index < report.results.size
                CheckRow(
                    stringResource(scenario.title),
                    when {
                        done && report.results[index] != null -> R.string.projects_check_failed
                        done -> R.string.projects_check_passed
                        report.running && index == report.results.size -> R.string.projects_check_running
                        else -> R.string.projects_check_waiting
                    },
                    if (done) report.results[index] else null,
                )
            }
            item {
                val combined = when {
                    !complete -> R.string.projects_check_waiting
                    failures > 0 -> R.string.projects_check_failed
                    else -> R.string.projects_check_passed
                }
                CheckRow(stringResource(R.string.projects_check_regression), combined, null)
                CheckRow(stringResource(R.string.projects_check_integration), combined, stringResource(R.string.projects_check_fixture_only))
                CheckRow(stringResource(R.string.projects_check_signed), R.string.projects_check_manual,
                    stringResource(R.string.projects_check_signed_note))
            }
        }
    }
}

@Composable
private fun CheckRow(title: String, status: Int, detail: String?) {
    HorizontalDivider()
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(status), style = MaterialTheme.typography.labelLarge, color = when (status) {
            R.string.projects_check_failed -> MaterialTheme.colorScheme.error
            R.string.projects_check_passed -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        })
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

internal data class ProjectsCheckReport(val running: Boolean = false, val results: List<String?> = emptyList())

class ProjectsCheckViewModel(application: Application) : AndroidViewModel(application) {
    private val state = MutableStateFlow(ProjectsCheckReport())
    internal val report = state.asStateFlow()

    internal fun runChecks() {
        if (state.value.running) return
        state.value = ProjectsCheckReport(running = true)
        viewModelScope.launch {
            try {
                for ((index, scenario) in PROJECT_CHECKS.withIndex()) {
                    val failure = withContext(Dispatchers.IO) { runCase(index, scenario) }
                    state.value = state.value.copy(results = state.value.results + failure)
                }
            } finally {
                state.value = state.value.copy(running = false)
            }
        }
    }

    private suspend fun runCase(index: Int, scenario: ProjectCheck): String? = try {
        val app = getApplication<Application>()
        withTimeout(120_000) {
            val directory = app.filesDir.toPath().resolve("projects-check/${index + 1}")
            deleteFixture(directory)
            try {
                scenario.run(ProjectCheckContext(app, directory))
            } finally {
                deleteFixture(directory)
            }
        }
        null
    } catch (_: TimeoutCancellationException) {
        "Waktu tunggu tes habis."
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.toString()
    } catch (e: AssertionError) {
        e.toString()
    }
}

private class ProjectCheckContext(base: Context, root: Path) : ContextWrapper(base) {
    private val files = Files.createDirectories(root.resolve("files")).toFile()
    private val cache = Files.createDirectories(root.resolve("cache")).toFile()
    private val databases = Files.createDirectories(root.resolve("databases")).toFile()

    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = files
    override fun getCacheDir(): File = cache
    override fun getDatabasePath(name: String): File = File(databases, name)
}

private fun deleteFixture(directory: Path) {
    if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
            error?.let { throw it }
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

private data class ProjectCheck(val title: Int, val run: suspend (Context) -> Unit)

private val PROJECT_CHECKS = listOf(
    ProjectCheck(R.string.projects_check_shared, ::checkSharedProject),
    ProjectCheck(R.string.projects_check_upgrade, ::checkLegacyUpgrade),
    ProjectCheck(R.string.projects_check_preview, ::checkProjectPreview),
    ProjectCheck(R.string.projects_check_instructions, ::checkProjectInstructions),
    ProjectCheck(R.string.projects_check_chat_delete, ::checkConversationDeletion),
    ProjectCheck(R.string.projects_check_project_delete, ::checkProjectDeletion),
)
