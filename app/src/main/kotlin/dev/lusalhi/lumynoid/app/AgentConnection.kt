package dev.lusalhi.lumynoid.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import dev.lusalhi.lumynoid.core.TaskRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Binds to [AgentService] and exposes its [TaskRunner] once connected. The service starts itself while busy. */
class AgentConnection(private val context: Context) {

    private val _runner = MutableStateFlow<TaskRunner?>(null)
    val runner: StateFlow<TaskRunner?> = _runner.asStateFlow()
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            _runner.value = (service as AgentService.LocalBinder).runner
        }

        override fun onServiceDisconnected(name: ComponentName) {
            _runner.value = null
        }
    }

    fun connect() {
        if (bound) return
        bound = context.bindService(Intent(context, AgentService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    fun disconnect() {
        if (!bound) return
        bound = false
        context.unbindService(connection)
        _runner.value = null
    }
}
