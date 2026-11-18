package me.rain.git.mcp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The HTTP listener is the source of truth, including after process recreation. */
object McpStatus {
    fun running(context: Context): McpState? {
        val port = McpSettings(context).port
        val connection = runCatching {
            URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
        }.getOrNull() ?: return null
        return try {
            connection.connectTimeout = 700
            connection.readTimeout = 700
            connection.useCaches = false
            connection.requestMethod = "GET"
            if (connection.responseCode != 200) return null
            val text = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(text)
            if (json.optString("name") != "RainGit") null
            else McpState(running = true, port = port,
                lastTool = json.optString("lastTool").ifEmpty { null },
                lastResult = json.optString("lastResult").ifEmpty { null },
                requests = json.optLong("requests"))
        } catch (_: Exception) { null }
        finally { connection.disconnect() }
    }

    fun updates(context: Context, intervalMs: Long = 1000) = flow {
        val appContext = context.applicationContext
        while (true) {
            val actual = kotlinx.coroutines.withContext(Dispatchers.IO) { running(appContext) }
            if (actual != null) {
                val local = McpSettings.state.value
                McpSettings.update { actual.copy(
                    starting = local.starting && SystemClockState.elapsed(local) < 15_000L,
                    error = if (local.starting) local.error else null) }
            } else if (McpSettings.state.value.running) {
                McpSettings.update { it.copy(running = false, starting = false) }
            }
            emit(McpSettings.state.value)
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)
}

private object SystemClockState {
    fun elapsed(state: McpState) = android.os.SystemClock.elapsedRealtime() - state.startedAt
}
