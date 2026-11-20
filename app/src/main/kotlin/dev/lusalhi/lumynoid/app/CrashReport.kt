package dev.lusalhi.lumynoid.app

import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/*
 * Local crash records (AD-25). Pure Kotlin, no android.*, so every rule here runs in JVM tests.
 *
 * The report holds only what code produces: versions, device model, time, thread name, and for each throwable its
 * class name and stack frames. Exception messages, toString(), and suppressed exceptions are deliberately left out:
 * a message can carry an API key or prompt text and no filter reliably removes it (AD-15).
 */

const val MAX_REPORTS = 5
const val MAX_FRAMES = 100
const val MAX_CAUSES = 5

data class CrashEnv(val appVersion: String, val androidVersion: String, val deviceModel: String)

fun formatCrashReport(env: CrashEnv, timeMillis: Long, threadName: String, throwable: Throwable): String {
    val out = StringBuilder()
    out.append("Lumynoid crash report\n")
    out.append("App: ").append(env.appVersion).append('\n')
    out.append("Android: ").append(env.androidVersion).append('\n')
    out.append("Device: ").append(env.deviceModel).append('\n')
    out.append("Time: ").append(Instant.ofEpochMilli(timeMillis)).append('\n')
    out.append("Thread: ").append(threadName).append('\n')

    val seen = java.util.IdentityHashMap<Throwable, Unit>()
    var current: Throwable? = throwable
    var index = 0
    while (current != null) {
        if (index > MAX_CAUSES) {
            out.append("[truncated: more causes not shown]\n")
            break
        }
        if (seen.put(current, Unit) != null) {
            out.append("[truncated: cause chain loops back]\n")
            break
        }
        out.append('\n')
        if (index > 0) out.append("Caused by: ")
        out.append(current.javaClass.name).append('\n')
        val frames = current.stackTrace
        for (frame in frames.take(MAX_FRAMES)) {
            out.append("    at ")
                .append(frame.className).append('.').append(frame.methodName)
                .append('(').append(frame.fileName ?: "Unknown Source")
            if (frame.lineNumber >= 0) out.append(':').append(frame.lineNumber)
            out.append(")\n")
        }
        if (frames.size > MAX_FRAMES) {
            out.append("[truncated: ").append(frames.size - MAX_FRAMES).append(" more frames not shown]\n")
        }
        current = current.cause
        index++
    }
    return out.toString()
}

/** A directory of crash reports. Names sort by time, so the newest is the last name. */
class CrashStore(private val dir: File, private val maxReports: Int = MAX_REPORTS) {
    private val sequence = AtomicInteger()

    /** Writes [text] as a new report, then keeps only the newest [maxReports]. */
    fun write(text: String, nowMillis: Long): File {
        dir.mkdirs()
        // Fixed-width, Locale.ROOT digits so names sort correctly as strings; the sequence keeps two crashes in the
        // same millisecond from overwriting each other.
        val name = String.format(Locale.ROOT, "crash-%015d-%03d.txt", nowMillis, sequence.getAndIncrement() % 1000)
        val file = File(dir, name)
        file.writeText(text)
        reports().dropLast(maxReports).forEach { it.delete() }
        return file
    }

    fun newest(): File? = reports().lastOrNull()

    fun clear() {
        reports().forEach { it.delete() }
    }

    private fun reports(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            .orEmpty()
}

/** Copies [report] into [exportDir] for sharing, after emptying it so at most one report is ever exposed. */
fun copyForExport(report: File, exportDir: File): File {
    exportDir.mkdirs()
    exportDir.listFiles()?.forEach { it.delete() }
    return report.copyTo(File(exportDir, report.name), overwrite = true)
}

/**
 * The uncaught-exception handler: records the crash, never throws, and always hands the crash to [previous] so the
 * platform behaves as it would without us. No network (AD-17).
 */
fun crashRecordingHandler(
    store: CrashStore,
    env: CrashEnv,
    now: () -> Long,
    previous: Thread.UncaughtExceptionHandler?,
): Thread.UncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, error ->
    try {
        val time = now()
        store.write(formatCrashReport(env, time, thread.name, error), time)
    } catch (_: Throwable) {
        // A failed write must not hide the real crash.
    }
    previous?.uncaughtException(thread, error)
}
