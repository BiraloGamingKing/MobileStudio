package com.exampletest.mobilestudiotest.ai

import com.exampletest.mobilestudiotest.core.OutputLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Roblox-Studio-style MCP server: lets an AI app (Claude Desktop / Claude Code, Cursor, or any
 * MCP client) use [StudioTools] on the open place. Streamable HTTP transport, JSON responses,
 * one endpoint (/mcp). Off until the user starts it; every request needs the bearer token shown
 * in the app; by default only this device can connect (127.0.0.1, e.g. through adb forward),
 * and "other devices on my Wi-Fi" is a separate opt-in.
 */
class McpServer(private val tools: StudioTools, savedToken: String?) {
    companion object {
        const val DEFAULT_PORT = 8765
        private val PROTOCOLS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        private val LOCAL_ORIGIN = Regex("^https?://(localhost|127\\.0\\.0\\.1)(:\\d+)?$")
    }

    @Volatile var running = false; private set
    var port = DEFAULT_PORT; private set
    var lan = false; private set
    var token: String = savedToken?.takeIf { it.length >= 32 } ?: newToken(); private set
    @Volatile var lastClient: String? = null; private set
    @Volatile var calls = 0; private set
    var onActivity: (() -> Unit)? = null
    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)

    private fun newToken(): String {
        val b = ByteArray(18); SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    fun regenerateToken() { token = newToken() }

    @Synchronized fun start(lanAccess: Boolean, wantPort: Int = DEFAULT_PORT) {
        stop()
        lan = lanAccess
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(if (lanAccess) InetAddress.getByName("0.0.0.0") else InetAddress.getLoopbackAddress(), wantPort))
        socket = s; port = s.localPort; running = true
        Thread({
            while (running) {
                val c = try { s.accept() } catch (_: Exception) { break }
                pool.execute { handle(c) }
            }
        }, "MCP server").apply { isDaemon = true }.start()
        OutputLog.info("MCP server started on port $port" + if (lanAccess) " (Wi-Fi devices can connect with the token)" else " (this device only)")
    }

    @Synchronized fun stop() {
        if (!running && socket == null) return
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        OutputLog.info("MCP server stopped")
    }

    /** Addresses an AI app can use to reach the server. */
    fun urls(): List<String> {
        val out = ArrayList<String>()
        out.add("http://127.0.0.1:$port/mcp")
        if (lan) try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) if (a is java.net.Inet4Address && !a.isLoopbackAddress) out.add("http://${a.hostAddress}:$port/mcp")
            }
        } catch (_: Exception) {}
        return out
    }

    // ------------------------------------------------------------------ HTTP

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private fun readLine(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(c)
            if (b.size() > 16384) return null
        }
    }

    private fun read(s: Socket): Request? {
        val i = BufferedInputStream(s.getInputStream())
        val first = readLine(i) ?: return null
        val parts = first.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(i) ?: break
            if (l.isEmpty()) break
            val k = l.substringBefore(':').trim().lowercase()
            headers[k] = l.substringAfter(':', "").trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > 4 * 1024 * 1024) return null
        val body = ByteArray(len)
        var off = 0
        while (off < len) { val n = i.read(body, off, len - off); if (n < 0) break; off += n }
        return Request(parts[0].uppercase(), parts[1], headers, body)
    }

    private fun respond(s: Socket, code: Int, status: String, body: String?, extra: Map<String, String> = emptyMap()) {
        val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val sb = StringBuilder("HTTP/1.1 $code $status\r\n")
        if (body != null) sb.append("Content-Type: application/json\r\n")
        sb.append("Content-Length: ${bytes.size}\r\nConnection: close\r\n")
        for ((k, v) in extra) sb.append("$k: $v\r\n")
        sb.append("\r\n")
        val o = s.getOutputStream()
        o.write(sb.toString().toByteArray(Charsets.ISO_8859_1)); o.write(bytes); o.flush()
    }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 15000
            val r = read(s) ?: return
            if (!r.path.startsWith("/mcp")) { respond(s, 404, "Not Found", null); return }
            // Every request must carry the token (a web page can't read it, so it can't drive Studio).
            // Browsers send Origin: only local tools (no Origin, or a localhost page) may talk to us.
            val origin = r.headers["origin"]
            if (origin != null && origin != "null" && !LOCAL_ORIGIN.matches(origin)) {
                respond(s, 403, "Forbidden", JSONObject().put("error", "origin not allowed").toString()); return
            }
            val auth = r.headers["authorization"] ?: ""
            if (!java.security.MessageDigest.isEqual(auth.toByteArray(), "Bearer $token".toByteArray())) {
                respond(s, 401, "Unauthorized", JSONObject().put("error", "missing or wrong bearer token").toString(), mapOf("WWW-Authenticate" to "Bearer"))
                return
            }
            when (r.method) {
                "POST" -> {
                    val text = String(r.body, Charsets.UTF_8).trim()
                    val out: String? = if (text.startsWith("[")) {
                        val arr = JSONArray(text); val res = JSONArray()
                        for (k in 0 until arr.length()) handleRpc(arr.getJSONObject(k))?.let { res.put(it) }
                        if (res.length() == 0) null else res.toString()
                    } else handleRpc(JSONObject(text))?.toString()
                    lastClient = s.inetAddress.hostAddress
                    onActivity?.invoke()
                    if (out == null) respond(s, 202, "Accepted", null) else respond(s, 200, "OK", out, mapOf("Mcp-Session-Id" to "mobile-studio"))
                }
                "DELETE" -> respond(s, 200, "OK", null)
                // No server-initiated stream: clients fall back to plain request/response.
                else -> respond(s, 405, "Method Not Allowed", null, mapOf("Allow" to "POST, DELETE"))
            }
        } catch (e: Exception) {
            try { respond(s, 400, "Bad Request", JSONObject().put("error", e.message ?: "bad request").toString()) } catch (_: Exception) {}
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ JSON-RPC (MCP)

    private fun ok(id: Any?, result: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
    private fun err(id: Any?, code: Int, msg: String) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", JSONObject().put("code", code).put("message", msg))

    /** One JSON-RPC message; null for notifications (no reply). */
    private fun handleRpc(m: JSONObject): JSONObject? {
        val id = if (m.has("id")) m.get("id") else null
        val method = m.optString("method")
        val params = m.optJSONObject("params") ?: JSONObject()
        if (id == null) return null
        return when (method) {
            "initialize" -> {
                val want = params.optString("protocolVersion")
                ok(id, JSONObject()
                    .put("protocolVersion", if (want in PROTOCOLS) want else PROTOCOLS[0])
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject().put("name", "mobile-studio").put("version", "2.0"))
                    .put("instructions", StudioTools.GUIDE))
            }
            "ping" -> ok(id, JSONObject())
            "tools/list" -> {
                val arr = JSONArray()
                for (d in tools.defs) {
                    val props = JSONObject()
                    for ((k, v) in d.properties) props.put(k, JSONObject(v))
                    arr.put(JSONObject().put("name", d.name).put("description", d.description)
                        .put("inputSchema", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(d.required))))
                }
                ok(id, JSONObject().put("tools", arr))
            }
            "tools/call" -> {
                val name = params.optString("name")
                val a = params.optJSONObject("arguments") ?: JSONObject()
                val args = HashMap<String, Any?>()
                for (k in a.keys()) args[k] = a.get(k).let { if (it == JSONObject.NULL) null else it }
                calls++
                if (tools.isMutating(name)) OutputLog.info("[MCP] $name from ${lastClient ?: "client"}")
                val r = tools.call(name, args)
                ok(id, JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", r.text))).put("isError", r.isError))
            }
            "resources/list" -> ok(id, JSONObject().put("resources", JSONArray()))
            "prompts/list" -> ok(id, JSONObject().put("prompts", JSONArray()))
            else -> err(id, -32601, "Method not found: $method")
        }
    }
}
