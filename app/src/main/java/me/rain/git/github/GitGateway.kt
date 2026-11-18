package me.rain.git.github

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Public Git read gateways. Authenticated API and push traffic always stays on GitHub. */
class GitGateway(context: Context) {
    private val prefs = context.getSharedPreferences("rain_network_settings", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()

    var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) { prefs.edit().putBoolean("automatic", value).apply() }

    var selected: String
        get() = prefs.getString("selected", "official").orEmpty()
        set(value) { require(channels.any { it.id == value }); prefs.edit().putString("selected", value).apply() }

    val results = _results.asStateFlow()

    fun channel() = channels.firstOrNull { it.id == selected } ?: channels.first()

    fun readUrl(original: String): String = channel().prefix + original

    suspend fun measure() = withContext(Dispatchers.IO) {
        if (_results.value.testing) return@withContext
        _results.value = _results.value.copy(testing = true, message = "正在测速…")
        val measured = coroutineScope {
            channels.map { channel -> async { probe(channel) } }.map { it.await() }
        }
        val fastest = measured.filter { it.latency != null }.minByOrNull { it.latency!! }
        if (automatic && fastest != null) selected = fastest.id
        _results.value = GatewayResults(false, measured,
            if (fastest == null) "没有可用节点，将使用 GitHub 官方通道" else "已选择 ${channel().name} · ${fastest.latency} ms")
    }

    private fun probe(channel: Channel): GatewayResult {
        val url = channel.prefix + TEST_URL
        val start = System.nanoTime()
        return try {
            client.newCall(Request.Builder().url(url).header("User-Agent", "RainGit-Android").build()).execute().use {
                if (it.code !in 200..299) throw IllegalStateException("HTTP ${it.code}")
                it.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(4096)
                    if (input.read(buffer) < 0) throw IllegalStateException("空响应")
                }
                val ms = (System.nanoTime() - start) / 1_000_000
                GatewayResult(channel.id, channel.name, ms, null)
            }
        } catch (e: Exception) {
            GatewayResult(channel.id, channel.name, null, e.message ?: "连接失败")
        }
    }

    companion object {
        val channels = listOf(
            Channel("official", "GitHub 官方", ""),
            Channel("ghproxy", "ghproxy.net", "https://ghproxy.net/"),
            Channel("gh_proxy", "gh-proxy.com", "https://gh-proxy.com/"),
            Channel("ghfast", "ghfast.top", "https://ghfast.top/"),
            Channel("kkgithub", "kkgithub.com", "https://kkgithub.com/")
        )
        private const val TEST_URL = "https://github.com/octocat/Hello-World.git/info/refs?service=git-upload-pack"
        private val _results = MutableStateFlow(GatewayResults())
    }
}

data class Channel(val id: String, val name: String, val prefix: String)
data class GatewayResult(val id: String, val name: String, val latency: Long?, val error: String?)
data class GatewayResults(val testing: Boolean = false, val items: List<GatewayResult> = emptyList(), val message: String = "尚未测速")
