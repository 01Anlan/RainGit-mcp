package me.rain.git.mcp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings and process-wide, observable server state. */
class McpSettings(context: Context) {
    private val prefs = context.getSharedPreferences("rain_mcp_settings", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt("port", 8765)
        set(value) {
            require(value in 1024..65535) { "端口范围为 1024–65535" }
            prefs.edit().putInt("port", value).apply()
        }

    var autoStart: Boolean
        get() = prefs.getBoolean("auto_start", false)
        set(value) { prefs.edit().putBoolean("auto_start", value).apply() }

    var overlayEnabled: Boolean
        get() = prefs.getBoolean("overlay", false)
        set(value) { prefs.edit().putBoolean("overlay", value).apply() }

    var allowWrites: Boolean
        get() = prefs.getBoolean("allow_writes", true)
        set(value) { prefs.edit().putBoolean("allow_writes", value).apply() }

    companion object {
        private val _state = MutableStateFlow(McpState())
        val state = _state.asStateFlow()

        fun update(transform: (McpState) -> McpState) { _state.value = transform(_state.value) }
    }
}

data class McpState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val startedAt: Long = 0L,
    val port: Int = 8765,
    val address: String = "127.0.0.1",
    val error: String? = null,
    val lastTool: String? = null,
    val lastResult: String? = null,
    val requests: Long = 0L
)
