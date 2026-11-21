package dev.lusalhi.lumynoid.userland

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path

internal sealed interface ProcessResult {
    data class Exited(val code: Int, val output: String) : ProcessResult
    data object Deadline : ProcessResult
}

internal class UserlandProcesses(private val supervisor: Path) {
    private val active = mutableMapOf<String, MutableSet<Session>>()

    inner class Session internal constructor(
        private val taskId: String,
        private val process: Process,
        private val afterExit: () -> Unit,
    ) {
        private var closed = false

        suspend fun await(timeoutMillis: Long): ProcessResult = coroutineScope {
            val output = async(Dispatchers.IO) { process.inputStream.bufferedReader().use { it.readText() } }
            try {
                val code = withTimeoutOrNull(timeoutMillis) {
                    while (process.isAlive) delay(25)
                    process.exitValue()
                }
                if (code == null) return@coroutineScope ProcessResult.Deadline
                ProcessResult.Exited(code, output.await())
            } finally {
                close()
            }
        }

        suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
            synchronized(this@Session) {
                if (closed) return@synchronized
                runCatching { process.outputStream.close() }
                // The supervisor exits only after killing and reaping its children.
                process.waitFor()
                try {
                    afterExit()
                } finally {
                    closed = true
                    synchronized(active) {
                        active[taskId]?.let { sessions ->
                            sessions.remove(this@Session)
                            if (sessions.isEmpty()) active.remove(taskId)
                        }
                    }
                }
            }
        }
    }

    fun start(
        taskId: String,
        argv: List<String>,
        directory: Path,
        environment: Map<String, String>,
        afterExit: () -> Unit = {},
    ): Session = synchronized(active) {
        val process = ProcessBuilder(listOf(supervisor.toString()) + argv)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment().putAll(environment)
            }
            .start()
        Session(taskId, process, afterExit).also { active.getOrPut(taskId, ::mutableSetOf).add(it) }
    }

    suspend fun run(
        taskId: String,
        argv: List<String>,
        directory: Path,
        environment: Map<String, String>,
        timeoutMillis: Long,
    ): ProcessResult = start(taskId, argv, directory, environment).await(timeoutMillis)

    suspend fun release(taskId: String) {
        val sessions = synchronized(active) { active[taskId]?.toList().orEmpty() }
        var failure: Exception? = null
        for (session in sessions) {
            try {
                session.close()
            } catch (e: Exception) {
                if (failure == null) failure = e
                else if (failure !== e) failure.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }
}
