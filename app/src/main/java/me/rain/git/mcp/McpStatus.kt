package me.rain.git.mcp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The listening socket is the source of truth. A plain TCP connect avoids the app's
 * cleartext-HTTP policy, which blocks requests to http://127.0.0.1.
 */
object McpStatus {
    private const val START_TIMEOUT_MS = 12_000L

    fun listeningPort(context: Context): Int {
        val state = McpSettings.state.value
        return if (state.port in 1024..65535) state.port else McpSettings(context).port
    }

    fun isListening(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400) }
        true
    }.getOrDefault(false)

    fun probe(context: Context): McpState? {
        val port = listeningPort(context)
        if (!isListening(port)) return null
        val local = McpSettings.state.value
        return McpState(running = true, starting = false, port = port,
            lastTool = local.lastTool, lastResult = local.lastResult, requests = local.requests)
    }

    fun updates(context: Context, intervalMs: Long = 800) = flow {
        val appContext = context.applicationContext
        var confirmed = false
        while (true) {
            val actual = withContext(Dispatchers.IO) { probe(appContext) }
            // View-local only: never overwrite the service's starting state.
            if (actual != null) confirmed = true
            emit(actual ?: McpState())
            delay(if (confirmed) intervalMs else 400)
        }
    }.flowOn(Dispatchers.IO)
}
