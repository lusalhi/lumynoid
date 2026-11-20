package dev.lusalhi.lumynoid.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/** The Android edge of crash recording (AD-25): wires platform values into the pure code in CrashReport.kt. */
object CrashHandler {
    fun store(context: Context): CrashStore = CrashStore(File(context.filesDir, "crash"))

    fun install(context: Context) {
        val app = context.applicationContext
        val handler = crashRecordingHandler(
            store = store(app),
            env = environment(app),
            now = System::currentTimeMillis,
            previous = Thread.getDefaultUncaughtExceptionHandler(),
        )
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    /** Copies [report] into `cacheDir/export/` and builds a share-sheet intent that grants read access to that copy. */
    fun shareIntent(context: Context, report: File): Intent {
        val copy = copyForExport(report, File(context.cacheDir, "export"))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(copy.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun environment(context: Context): CrashEnv {
        val version = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        } catch (_: Exception) {
            "unknown"
        }
        return CrashEnv(
            appVersion = version,
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }
}
