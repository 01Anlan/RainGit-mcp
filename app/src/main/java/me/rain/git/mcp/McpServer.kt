package me.rain.git.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/** Android-safe MCP Streamable HTTP endpoint; no Ktor static initialization. */
class McpServer(private val tools: McpTools) {
    private var server: HttpServer? = null
    private var dispatcher: java.util.concurrent.ExecutorService? = null

    suspend fun start(port: Int) {
        require(server == null) { "MCP 已经启动" }
        val instance = HttpServer.create(InetSocketAddress("127.0.0.1", port), 32)
        val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "RainGit-MCP").apply { isDaemon = true }
        }
        instance.executor = executor
        instance.createContext("/health") { exchange ->
            if (exchange.requestMethod != "GET") { exchange.respond(405, "Method Not Allowed"); return@createContext }
            exchange.respond(200, JSONObject().put("status", "ok").put("name", "RainGit").toString())
        }
        instance.createContext("/mcp") { exchange ->
            if (exchange.requestMethod != "POST") { exchange.respond(405, "Method Not Allowed"); return@createContext }
            val id = try { handle(exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()) }
            catch (e: Exception) { error(null, -32603, e.message ?: "服务内部错误") }
            exchange.respond(200, id)
        }
        server = instance; dispatcher = executor
        try { instance.start() }
        catch (e: Throwable) { close(); throw e }
    }

    private suspend fun handle(text: String): String = withContext(Dispatchers.IO) {
        val body = try { JSONObject(text) } catch (_: JSONException) { return@withContext error(null, -32700, "JSON 格式错误") }
        if (body.optString("jsonrpc") != "2.0" || !body.has("method")) return@withContext error(body.opt("id"), -32600, "JSON-RPC 请求格式错误")
        val id = body.opt("id"); val method = body.getString("method")
        val params = body.optJSONObject("params") ?: JSONObject()
        try {
            when (method) {
                "initialize" -> result(id, JSONObject()
                    .put("protocolVersion", params.optString("protocolVersion", "2025-03-26"))
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject().put("name", "RainGit").put("version", "1.1.0"))
                    .put("instructions", "操作 RainGit 本地与 GitHub 仓库。repository 是本地仓库目录名；full_name 是 所有者/仓库名。"))
                "notifications/initialized", "notifications/cancelled" -> ""
                "ping" -> result(id, JSONObject())
                "tools/list" -> result(id, JSONObject().put("tools", tools.definitions()))
                "tools/call" -> {
                    val name = params.getString("name")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    val response = try {
                        val text = tools.call(name, args)
                        McpSettings.update { it.copy(requests = it.requests + 1, lastTool = name, lastResult = "成功") }
                        JSONObject().put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", text))).put("isError", false)
                    } catch (e: Exception) {
                        McpSettings.update { it.copy(requests = it.requests + 1, lastTool = name, lastResult = e.message ?: "失败") }
                        JSONObject().put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", e.message ?: "工具执行失败"))).put("isError", true)
                    }
                    result(id, response)
                }
                else -> error(id, -32601, "方法不存在：$method")
            }
        } catch (e: Exception) { error(id, -32602, e.message ?: "参数错误") }
    }

    private fun result(id: Any?, result: JSONObject): String = JSONObject().put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL).put("result", result).toString()

    private fun error(id: Any?, code: Int, message: String): String = JSONObject().put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL).put("error", JSONObject().put("code", code).put("message", message)).toString()

    private fun HttpExchange.respond(status: Int, text: String) {
        val data = text.toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        if (text.isEmpty()) sendResponseHeaders(202, -1)
        else sendResponseHeaders(status, data.size.toLong())
        if (data.isNotEmpty()) responseBody.use { it.write(data) } else close()
    }

    fun close() {
        server?.stop(0); server = null
        dispatcher?.shutdownNow(); dispatcher = null
    }
}
