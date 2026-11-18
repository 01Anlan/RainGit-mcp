package me.rain.git.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.http.HttpMethod
import org.json.JSONException
import org.json.JSONObject

/** Minimal MCP 2025-03-26 Streamable HTTP server, without an external MCP runtime. */
class McpServer(private val tools: McpTools) {
    private var engine: ApplicationEngine? = null

    suspend fun start(port: Int) {
        require(engine == null) { "MCP 已经启动" }
        val server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            routing {
                get("/health") {
                    call.respondText(JSONObject().put("status", "ok").put("name", "RainGit").toString(), ContentType.Application.Json)
                }
                post("/mcp") {
                    val accept = call.request.headers[HttpHeaders.Accept].orEmpty()
                    if (!accept.contains("application/json") && !accept.contains("*/*")) {
                        call.respondText("Not Acceptable", status = HttpStatusCode.NotAcceptable)
                        return@post
                    }
                    val body = try { JSONObject(call.receiveText()) }
                    catch (_: JSONException) {
                        call.jsonRpc(null, -32700, "Parse error"); return@post
                    }
                    if (body.getStringOrNull("jsonrpc") != "2.0" || !body.has("method")) {
                        call.jsonRpc(body.opt("id"), -32600, "Invalid Request"); return@post
                    }
                    val id = body.opt("id")
                    val method = body.getString("method")
                    val params = body.optJSONObject("params") ?: JSONObject()
                    try {
                        when (method) {
                            "initialize" -> {
                                val version = params.optString("protocolVersion", "2025-03-26")
                                val result = JSONObject()
                                    .put("protocolVersion", version)
                                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                                    .put("serverInfo", JSONObject().put("name", "RainGit").put("version", "1.0.0"))
                                    .put("instructions", "Operate RainGit repositories. repository is a local directory name; full_name is owner/name for GitHub tools.")
                                call.jsonRpc(id, result = result)
                            }
                            "notifications/initialized", "notifications/cancelled" -> {
                                call.respondText("", status = HttpStatusCode.Accepted)
                            }
                            "ping" -> call.jsonRpc(id, result = JSONObject())
                            "tools/list" -> call.jsonRpc(id, result = JSONObject().put("tools", tools.definitions()))
                            "tools/call" -> {
                                val name = params.getString("name")
                                val args = params.optJSONObject("arguments") ?: JSONObject()
                                val result = try {
                                    val text = tools.call(name, args)
                                    McpSettings.update { it.copy(requests = it.requests + 1, lastTool = name, lastResult = "成功") }
                                    JSONObject().put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", text))).put("isError", false)
                                } catch (e: Exception) {
                                    McpSettings.update { it.copy(requests = it.requests + 1, lastTool = name, lastResult = e.message ?: "失败") }
                                    JSONObject().put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", e.message ?: "工具执行失败"))).put("isError", true)
                                }
                                call.jsonRpc(id, result = result)
                            }
                            else -> call.jsonRpc(id, -32601, "Method not found: $method")
                        }
                    } catch (e: Exception) {
                        call.jsonRpc(id, -32602, e.message ?: "Invalid params")
                    }
                }
            }
        }
        engine = server
        try {
            server.start(wait = false)
            val resolved = runCatching {
                @Suppress("UNCHECKED_CAST")
                (server.resolvedConnectors() as List<io.ktor.server.engine.EngineConnectorConfig>).first()
            }.getOrElse {
                engine = null; server.stop(0, 0); throw it
            }
            check(resolved.port == port) { "端口 $port 启动失败" }
        }
        catch (e: Exception) { engine = null; server.stop(0, 0); throw e }
    }

    fun stop() {
        engine?.stop(500, 1500)
        engine = null
    }

    fun close() { stop() }

    private suspend fun ApplicationCall.jsonRpc(id: Any?, code: Int? = null, message: String? = null, result: JSONObject? = null) {
        val response = JSONObject().put("jsonrpc", "2.0")
        if (id == null || id == JSONObject.NULL) response.put("id", JSONObject.NULL) else response.put("id", id)
        if (result != null) response.put("result", result)
        else response.put("error", JSONObject().put("code", code ?: -32603).put("message", message ?: "Internal error"))
        respondText(response.toString(), ContentType.Application.Json)
    }

    private fun JSONObject.getStringOrNull(name: String) = if (has(name) && !isNull(name)) getString(name) else null
}
