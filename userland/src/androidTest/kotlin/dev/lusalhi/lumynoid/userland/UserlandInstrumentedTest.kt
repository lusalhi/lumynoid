package dev.lusalhi.lumynoid.userland

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

class UserlandInstrumentedTest {
    @Test
    fun approvalDenialThenInstallAndBuiltInOperations() =
        runCheck(UserlandChecks::approvalDenialThenInstallAndBuiltInOperations)

    @Test
    fun alteredArchiveAndCancelledTransferLeaveNoInstallAndCanRetry() =
        runCheck(UserlandChecks::alteredArchiveAndCancelledTransferLeaveNoInstallAndCanRetry)

    @Test
    fun normalExitFailureAndDeadlineReapDescendantsBeforeNextTask() =
        runCheck(UserlandChecks::normalExitFailureAndDeadlineReapDescendantsBeforeNextTask)

    @Test
    fun cancellationAndApprovalReleaseAnActiveTreeBeforeNextTask() =
        runCheck(UserlandChecks::cancellationAndApprovalReleaseAnActiveTreeBeforeNextTask)

    @Test
    fun releaseStopsOtherProcessesWhenOneCleanupFails() =
        runCheck(UserlandChecks::releaseStopsOtherProcessesWhenOneCleanupFails)

    @Test
    fun codingFilesStayInTheActiveWorkspace() =
        runCheck(UserlandChecks::codingFilesStayInTheActiveWorkspace)

    @Test
    fun codingReplaceRequiresCurrentApproval() =
        runCheck(UserlandChecks::codingReplaceRequiresCurrentApproval)

    @Test
    fun codingNpmAndFormatterUseOnlyBundledAssets() =
        runCheck(UserlandChecks::codingNpmAndFormatterUseOnlyBundledAssets)

    private fun runCheck(check: suspend (UserlandChecks) -> Unit) = runBlocking {
        assumeTrue("arm64 runtime", "arm64-v8a" in Build.SUPPORTED_64_BIT_ABIS)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(UserlandChecks(instrumentation.targetContext, instrumentation.context.assets))
    }
}
