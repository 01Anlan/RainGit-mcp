package me.rain.git.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/** Small Android-native HTTP transport for MCP. It uses only ServerSocket and org.json. */
class McpServer(private val tools: McpTools) {
    private var socket: ServerSocket? = null
    private var scope: CoroutineScope? = null

    suspend fun start(port: Int) = withIO {
        require(socket == null) { "MCP 已经启动" }
        val listener = ServerSocket()
        listener.reuseAddress = true
        listener.bind(InetSocketAddress("127.0.0.1", port), 32)
        val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        socket = listener; scope = workers
        workers.launch {
            while (socket != null) {
                val connection = try { listener.accept() } catch (_: SocketException) { break }
                launch { serve(connection) }
            }
        }
    }

    private suspend fun serve(connection: Socket) {
        try {
            connection.soTimeout = 30_000
            val input = connection.getInputStream().buffered()
            val requestLine = readLine(input)
            val parts = requestLine.split(' ')
            require(parts.size == 3 && parts[2].matches(Regex("HTTP/1\\.[01]"))) { "HTTP 请求行无效" }
            val method = parts[0]
            val path = parts[1].substringBefore('?').trimEnd('/').ifEmpty { "/" }
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            require(length in 0..2_000_000) { "请求体超过 2 MB" }
            val body = ByteArray(length); var offset = 0
            while (offset < length) {
                val read = input.read(body, offset, length - offset)
                if (read < 0) break
                offset += read
            }
            require(offset == length) { "请求体不完整" }
            val response = when (path) {
                "/health" -> if (method == "GET") JSONObject().put("status", "ok").put("name", "RainGit")
                    .put("requests", McpSettings.state.value.requests)
                    .put("lastTool", McpSettings.state.value.lastTool ?: "")
                    .put("lastResult", McpSettings.state.value.lastResult ?: "").toString() else ""
                "/mcp" -> if (method == "POST") handle(String(body, Charsets.UTF_8)) else ""
                else -> ""
            }
            val status = if (path !in listOf("/health", "/mcp")) 404
                else if ((path == "/health" && method != "GET") || (path == "/mcp" && method != "POST")) 405
                else if (response.isEmpty()) 202 else 200
            respond(connection, status, response)
        } catch (e: Exception) {
            runCatching { respond(connection, 400, error(null, -32600, e.message ?: "HTTP 请求错误")) }
        } finally { runCatching { connection.close() } }
    }

    private suspend fun handle(text: String): String {
        val body = try { JSONObject(text) } catch (_: JSONException) { return error(null, -32700, "JSON 格式错误") }
        if (body.optString("jsonrpc") != "2.0" || !body.has("method")) return error(body.opt("id"), -32600, "JSON-RPC 请求格式错误")
        val id = body.opt("id"); val method = body.getString("method")
        val params = body.optJSONObject("params") ?: JSONObject()
        return try {
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

    private fun readLine(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val c = input.read(); require(c >= 0) { "HTTP 请求头不完整" }
            if (c == 13) { require(input.read() == 10) { "HTTP 换行无效" }; return out.toString(Charsets.UTF_8.name()) }
            require(c != 10 && out.size() < 8192) { "HTTP 请求头过长" }
            out.write(c)
        }
    }

    private fun respond(connection: Socket, status: Int, text: String) {
        val data = text.toByteArray(Charsets.UTF_8)
        val label = mapOf(200 to "OK", 202 to "Accepted", 400 to "Bad Request", 404 to "Not Found", 405 to "Method Not Allowed")[status].orEmpty()
        val head = "HTTP/1.1 $status $label\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n"
        connection.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(data); flush() }
    }

    private fun result(id: Any?, result: JSONObject): String = JSONObject().put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL).put("result", result).toString()

    private fun error(id: Any?, code: Int, message: String): String = JSONObject().put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL).put("error", JSONObject().put("code", code).put("message", message)).toString()

    private suspend fun <T> withIO(block: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    fun close() {
        val current = socket; socket = null
        runCatching { current?.close() }; scope?.cancel(); scope = null
    }
}
