package dev.lusalhi.lumynoid.userland.checks

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import dev.lusalhi.lumynoid.userland.UserlandChecks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChecksActivity : Activity() {
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var removeFixture: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_checks)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            findViewById<View>(R.id.screen).setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        val engine = application as ChecksApplication
        val run = findViewById<Button>(R.id.run_checks)
        val summary = findViewById<TextView>(R.id.summary)
        val details = findViewById<TextView>(R.id.details)
        findViewById<TextView>(R.id.device).text = getString(
            R.string.device, Build.MODEL, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        )
        run.setOnClickListener { engine.runChecks() }
        removeFixture = findViewById(R.id.remove_fixture)
        removeFixture.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", OLD_FIXTURE, null)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.no_uninstaller, Toast.LENGTH_LONG).show()
            }
        }
        ui.launch {
            engine.report.collect { report ->
                run.isEnabled = !report.running
                run.keepScreenOn = report.running
                val passed = report.results.count { it.failure == null }
                summary.text = when {
                    report.running -> getString(R.string.running, report.results.size, CASES.size)
                    report.results.isEmpty() -> getString(R.string.ready, CASES.size)
                    else -> getString(R.string.finished, passed, CASES.size)
                }
                details.text = report.results.joinToString("\n\n") { result ->
                    if (result.failure == null) {
                        getString(R.string.passed, getString(result.label))
                    } else {
                        getString(R.string.failed, getString(result.label), result.failure)
                    }
                }
                details.visibility = if (report.results.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val installed = try {
            packageManager.getApplicationInfo(OLD_FIXTURE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        removeFixture.isEnabled = installed
        removeFixture.setText(if (installed) R.string.remove_fixture else R.string.no_fixture)
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }
}

class ChecksApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(CheckReport())
    internal val report = state.asStateFlow()

    internal fun runChecks() {
        if (state.value.running) return
        state.value = CheckReport(running = true)
        scope.launch {
            try {
                val checks = UserlandChecks(this@ChecksApplication, assets)
                for ((label, check) in CASES) {
                    val failure = try {
                        check(checks)
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e
                    } catch (e: AssertionError) {
                        e
                    }
                    state.update { it.copy(results = it.results + CheckResult(label, failure?.diagnostic())) }
                }
            } finally {
                state.update { it.copy(running = false) }
            }
        }
    }
}

internal data class CheckReport(val running: Boolean = false, val results: List<CheckResult> = emptyList())
internal data class CheckResult(val label: Int, val failure: String?)

private fun Throwable.diagnostic(): String = listOfNotNull(
    toString(), stackTrace.firstOrNull { it.className.startsWith("dev.lusalhi.lumynoid.") }?.toString(),
).joinToString("\n")

private const val OLD_FIXTURE = "dev.lusalhi.lumynoid.userland.test"
private val CASES: List<Pair<Int, suspend (UserlandChecks) -> Unit>> = listOf(
    R.string.check_install to UserlandChecks::approvalDenialThenInstallAndBuiltInOperations,
    R.string.check_corruption to UserlandChecks::alteredArchiveAndCancelledTransferLeaveNoInstallAndCanRetry,
    R.string.check_exit to UserlandChecks::normalExitFailureAndDeadlineReapDescendantsBeforeNextTask,
    R.string.check_cancel to UserlandChecks::cancellationAndApprovalReleaseAnActiveTreeBeforeNextTask,
    R.string.check_cleanup to UserlandChecks::releaseStopsOtherProcessesWhenOneCleanupFails,
    R.string.check_workspace to UserlandChecks::codingFilesStayInTheActiveWorkspace,
    R.string.check_replace to UserlandChecks::codingReplaceRequiresCurrentApproval,
    R.string.check_coding to UserlandChecks::codingNpmAndFormatterUseOnlyBundledAssets,
)
