package com.jondude.anzu.assist

import com.jondude.anzu.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Connections: Atlas's reach. Any service that speaks MCP (the Model Context Protocol) over
 * Streamable HTTP can be added by address, and its tools become things Atlas can do.
 * Home Assistant has this built in at /api/mcp; most other tools have an MCP server, and
 * stdio-only servers can be put on the network with a bridge such as mcp-proxy.
 */
data class McpServer(val name: String, val url: String, val token: String, val enabled: Boolean = true)

data class McpTool(val server: String, val name: String, val description: String, val schema: JSONObject) {
    /** The name Claude sees: server and tool, made safe for the API's naming rules. */
    val apiName: String get() = Mcp.apiName(server, name)
}

object Mcp {
    private const val PROTOCOL = "2025-06-18"

    /** Per-server status for the settings screen: "12 tools", "connecting", or an error. */
    val status = MutableStateFlow<Map<String, String>>(emptyMap())
    private val tools = ConcurrentHashMap<String, List<McpTool>>()
    private val sessions = ConcurrentHashMap<String, String>()
    private val ids = AtomicInteger(1)
    @Volatile
    private var loadedAt = 0L

    // ---------- the server list ----------

    const val HOME_ASSISTANT = "Home Assistant"

    /** Home Assistant's built-in MCP server, set up automatically once its token is in Settings. */
    private fun builtIn(): McpServer? {
        if (Prefs.haUrl.isBlank() || Prefs.haToken.isBlank()) return null
        return McpServer(HOME_ASSISTANT, Prefs.haUrl.trim().trimEnd('/') + "/api/mcp", Prefs.haToken.trim(), Prefs.haMcp)
    }

    private fun stored(): List<McpServer> = try {
        val a = JSONArray(Prefs.sp.getString("mcpServers", "[]") ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            McpServer(it.optString("name"), it.optString("url"), it.optString("token"), it.optBoolean("enabled", true))
        }.filter { it.name.isNotBlank() && it.url.isNotBlank() && it.name != HOME_ASSISTANT }
    } catch (e: Exception) {
        emptyList()
    }

    fun servers(): List<McpServer> = listOfNotNull(builtIn()) + stored()

    private fun save(list: List<McpServer>) {
        val a = JSONArray()
        list.filter { it.name != HOME_ASSISTANT }.forEach { a.put(JSONObject().put("name", it.name).put("url", it.url).put("token", it.token).put("enabled", it.enabled)) }
        Prefs.sp.edit().putString("mcpServers", a.toString()).apply()
        loadedAt = 0
    }

    /** Adds or replaces a connection by name. */
    fun upsert(server: McpServer) {
        save(stored().filter { !it.name.equals(server.name, true) } + server)
        tools.remove(server.name)
        sessions.remove(server.name)
    }

    fun remove(name: String) {
        if (name == HOME_ASSISTANT) Prefs.haMcp = false
        save(stored().filter { !it.name.equals(name, true) })
        tools.remove(name)
        sessions.remove(name)
        status.value = status.value - name
    }

    fun setEnabled(name: String, on: Boolean) {
        if (name == HOME_ASSISTANT) Prefs.haMcp = on
        save(stored().map { if (it.name.equals(name, true)) it.copy(enabled = on) else it })
        if (!on) tools.remove(name)
    }

    // ---------- names ----------

    /** Claude's tool names allow letters, digits, _ and -, up to 64 characters. */
    fun apiName(server: String, tool: String): String {
        fun clean(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_')
        val full = "x_${clean(server)}__${clean(tool)}"
        if (full.length <= 64) return full
        val hash = Integer.toHexString(full.hashCode()).takeLast(6)
        return full.take(57) + "_" + hash
    }

    // ---------- protocol ----------

    /**
     * Pulls the JSON-RPC reply with the given id out of a response body, which is either plain
     * JSON or a server-sent-events stream of "data:" lines.
     */
    fun parseReply(body: String, contentType: String?, id: Int): JSONObject? {
        val candidates = ArrayList<String>()
        if (contentType?.contains("text/event-stream") == true || body.trimStart().startsWith("event:") || body.trimStart().startsWith("data:")) {
            val data = StringBuilder()
            for (line in body.lines()) {
                when {
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart()).append('\n')
                    line.isBlank() -> {
                        if (data.isNotEmpty()) candidates.add(data.toString().trim())
                        data.setLength(0)
                    }
                }
            }
            if (data.isNotEmpty()) candidates.add(data.toString().trim())
        } else candidates.add(body)
        for (c in candidates) {
            try {
                val t = c.trim()
                if (t.startsWith("[")) {
                    val arr = JSONArray(t)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        if (o.optInt("id", -1) == id) return o
                    }
                } else {
                    val o = JSONObject(t)
                    if (o.optInt("id", -1) == id) return o
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private class Reply(val code: Int, val body: String, val type: String?, val session: String?)

    private fun post(server: McpServer, payload: JSONObject, timeoutMs: Int): Reply {
        val conn = URL(server.url.trim()).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            conn.setRequestProperty("MCP-Protocol-Version", PROTOCOL)
            if (server.token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${server.token.trim()}")
            sessions[server.name]?.let { conn.setRequestProperty("Mcp-Session-Id", it) }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Reply(code, body, conn.contentType, conn.getHeaderField("Mcp-Session-Id"))
        } finally {
            conn.disconnect()
        }
    }

    private fun rpc(server: McpServer, method: String, params: JSONObject, timeoutMs: Int = 20_000): JSONObject {
        val id = ids.getAndIncrement()
        val payload = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        var r = post(server, payload, timeoutMs)
        if (r.code == 404 && sessions.containsKey(server.name) && method != "initialize") {
            // The server forgot our session (it restarted): start a new one and try once more.
            sessions.remove(server.name)
            initialize(server)
            r = post(server, payload.put("id", id), timeoutMs)
        }
        when (r.code) {
            401, 403 -> throw RuntimeException("the token was refused")
            404 -> throw RuntimeException("nothing is listening at that address")
        }
        if (r.code !in 200..299) throw RuntimeException("server answered ${r.code}")
        val reply = parseReply(r.body, r.type, id) ?: throw RuntimeException("no reply to $method")
        reply.optJSONObject("error")?.let { throw RuntimeException(it.optString("message", "error")) }
        return reply.optJSONObject("result") ?: JSONObject()
    }

    private fun initialize(server: McpServer) {
        val id = ids.getAndIncrement()
        val payload = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "initialize").put(
            "params",
            JSONObject().put("protocolVersion", PROTOCOL).put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "atlas").put("version", "1"))
        )
        val r = post(server, payload, 15_000)
        when (r.code) {
            401, 403 -> throw RuntimeException("the token was refused")
            404 -> throw RuntimeException("nothing is listening at that address")
        }
        if (r.code !in 200..299) throw RuntimeException("server answered ${r.code}")
        r.session?.let { sessions[server.name] = it }
        val reply = parseReply(r.body, r.type, id) ?: throw RuntimeException("not an MCP server")
        reply.optJSONObject("error")?.let { throw RuntimeException(it.optString("message", "error")) }
        try {
            post(server, JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), 8_000)
        } catch (_: Exception) {
        }
    }

    /** Blocking. Connects and lists the server's tools. */
    fun discover(server: McpServer): List<McpTool> {
        status.value = status.value + (server.name to "connecting")
        return try {
            sessions.remove(server.name)
            initialize(server)
            val out = ArrayList<McpTool>()
            var cursor: String? = null
            do {
                val params = JSONObject()
                if (cursor != null) params.put("cursor", cursor)
                val result = rpc(server, "tools/list", params)
                val arr = result.optJSONArray("tools") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    val schema = t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object").put("properties", JSONObject())
                    if (!schema.has("type")) schema.put("type", "object")
                    out.add(McpTool(server.name, t.optString("name"), t.optString("description").take(900), schema))
                }
                cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
            } while (cursor != null && out.size < 200)
            tools[server.name] = out
            status.value = status.value + (server.name to "${out.size} tools")
            out
        } catch (e: Exception) {
            tools.remove(server.name)
            status.value = status.value + (server.name to "unreachable: ${e.message}")
            emptyList()
        }
    }

    /** Blocking. Rediscovers every enabled connection if the cache is older than ten minutes. */
    fun refresh(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - loadedAt < 10 * 60_000) return
        loadedAt = System.currentTimeMillis()
        for (s in servers()) if (s.enabled) discover(s)
    }

    /** Every tool Atlas can use right now, capped so the request stays a sensible size. */
    fun available(): List<McpTool> {
        val enabled = servers().filter { it.enabled }.map { it.name }.toSet()
        return tools.filterKeys { it in enabled }.values.flatten().take(80)
    }

    fun find(apiName: String): McpTool? = available().firstOrNull { it.apiName == apiName }

    /** Blocking. Calls the tool and returns its text for the model, never throws. */
    fun call(tool: McpTool, args: JSONObject): String {
        val server = servers().firstOrNull { it.name == tool.server } ?: return "That connection was removed."
        return try {
            if (!sessions.containsKey(server.name)) initialize(server)
            val result = rpc(server, "tools/call", JSONObject().put("name", tool.name).put("arguments", args), 90_000)
            val content = result.optJSONArray("content") ?: JSONArray()
            val text = StringBuilder()
            for (i in 0 until content.length()) {
                val c = content.getJSONObject(i)
                when (c.optString("type")) {
                    "text" -> text.append(c.optString("text")).append('\n')
                    "resource" -> text.append(c.optJSONObject("resource")?.optString("text") ?: "").append('\n')
                    else -> text.append("[${c.optString("type")} content]\n")
                }
            }
            if (text.isBlank()) result.optJSONObject("structuredContent")?.let { text.append(it.toString()) }
            val out = text.toString().trim().ifBlank { "Done." }.take(6000)
            if (result.optBoolean("isError")) "The tool reported a problem: $out" else out
        } catch (e: Exception) {
            "${server.name} couldn't do that: ${e.message}"
        }
    }

    /** The Claude tool definitions for every connected tool. */
    fun toolDefinitions(): JSONArray {
        val a = JSONArray()
        for (t in available()) {
            a.put(
                JSONObject().put("name", t.apiName)
                    .put("description", "[${t.server}] ${t.description}".take(1000))
                    .put("input_schema", t.schema)
            )
        }
        return a
    }

    /** One line per connection for the system prompt, so Atlas knows what it can reach. */
    fun summary(): String {
        val byServer = available().groupBy { it.server }
        if (byServer.isEmpty()) return ""
        return byServer.entries.joinToString("; ") { (s, list) ->
            "$s (" + list.take(12).joinToString(", ") { it.name } + (if (list.size > 12) ", and ${list.size - 12} more" else "") + ")"
        }
    }
}
