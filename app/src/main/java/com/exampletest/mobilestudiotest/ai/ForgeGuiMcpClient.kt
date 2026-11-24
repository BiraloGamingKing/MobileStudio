package com.exampletest.mobilestudiotest.ai

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Minimal ForgeGUI production MCP connector.
 * The account key is encrypted with an Android Keystore AES-GCM key and stored only
 * in the app's ai_assistant preferences (which are excluded from Android backups).
 */
class ForgeGuiMcpClient(private val context: Context) {
    data class VerifyResult(
        val success: Boolean,
        val message: String,
        val tools: List<String> = emptyList()
    )

    companion object {
        private const val PREFS = "ai_assistant"
        private const val KEY_NAME = "forgegui_mcp_key_encrypted"
        private const val ALIAS = "mobilestudio_forgegui_mcp_aes"
        private const val ENDPOINT =
            "https://azyjlkhpdgafuobyxoax.supabase.co/functions/v1/forgegui-mcp"
        private const val PROTOCOL_VERSION = "2025-03-26"
    }

    private val ids = AtomicInteger(1)
    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasKey(): Boolean = prefs().contains(KEY_NAME)

    fun savedKey(): String? = try {
        prefs().getString(KEY_NAME, null)?.let { decrypt(it) }
    } catch (_: Exception) {
        null
    }

    fun saveKey(key: String) {
        require(key.isNotBlank()) { "API key is empty." }
        prefs().edit().putString(KEY_NAME, encrypt(key.trim())).apply()
    }

    fun removeKey() {
        prefs().edit().remove(KEY_NAME).apply()
    }

    /**
     * Verifies using only MCP discovery and the free, read-only library_search tool.
     * This method never calls a generation or publishing tool.
     */
    fun verify(key: String): VerifyResult {
        if (key.isBlank()) return VerifyResult(false, "Paste your ForgeGUI MCP API key first.")
        var sessionId: String? = null
        return try {
            val init = request(
                key, sessionId, "initialize",
                JSONObject()
                    .put("protocolVersion", PROTOCOL_VERSION)
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "MobileStudio").put("version", "2.0"))
            )
            sessionId = init.second
            val initResult = init.first
            if (initResult.has("error")) {
                return VerifyResult(false, "ForgeGUI rejected the connection initialization. Check that this is a production MCP API key.")
            }

            notification(key, sessionId, "notifications/initialized")
            val listed = request(key, sessionId, "tools/list", JSONObject()).first
            if (listed.has("error")) {
                return VerifyResult(false, "ForgeGUI connected, but tool discovery failed. Check the key's scopes.")
            }
            val tools = listed.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
            val names = ArrayList<String>()
            var libraryTool: JSONObject? = null
            for (i in 0 until tools.length()) {
                val tool = tools.optJSONObject(i) ?: continue
                val name = tool.optString("name")
                if (name.isNotBlank()) names.add(name)
                if (name == "library_search") libraryTool = tool
            }
            val searchTool = libraryTool
                ?: return VerifyResult(false, "The key connected, but library_search is not available. Add the library:read scope in ForgeGUI and reconnect.", names)

            val args = readOnlySearchArgs(searchTool)
                ?: return VerifyResult(false, "ForgeGUI tools were discovered, but the read-only library_search input schema was not recognized. No generation was started.", names)
            val called = request(
                key, sessionId, "tools/call",
                JSONObject().put("name", "library_search").put("arguments", args)
            ).first
            val result = called.optJSONObject("result")
            if (called.has("error") || result?.optBoolean("isError", false) == true) {
                VerifyResult(false, "The key was received, but the free library:read check failed. Confirm the key has library:read scope.", names)
            } else {
                VerifyResult(true, "Connected. Read-only library search succeeded. No credits were spent.", names)
            }
        } catch (e: HttpFailure) {
            when (e.code) {
                401 -> VerifyResult(false, "ForgeGUI rejected this key (401). Check that it is a production MCP API key.")
                403 -> VerifyResult(false, "ForgeGUI denied access (403). The key may be missing the library:read scope.")
                else -> VerifyResult(false, "ForgeGUI connection failed (HTTP " + e.code + "). Try again later.")
            }
        } catch (_: Exception) {
            VerifyResult(false, "Couldn't reach ForgeGUI. Check your internet connection and try again.")
        }
    }

    private fun readOnlySearchArgs(tool: JSONObject): JSONObject? {
        val schema = tool.optJSONObject("inputSchema") ?: return null
        val properties = schema.optJSONObject("properties") ?: return null
        val args = JSONObject()
        val queryNames = listOf("query", "search_query", "search", "q")
        for (name in queryNames) {
            if (properties.has(name)) {
                args.put(name, "tree")
                return args
            }
        }
        val required = schema.optJSONArray("required") ?: return null
        if (required.length() == 1) {
            val name = required.optString(0)
            val spec = properties.optJSONObject(name)
            if (spec?.optString("type") == "string") {
                args.put(name, "tree")
                return args
            }
        }
        return null
    }

    private fun notification(key: String, session: String?, method: String) {
        val payload = JSONObject().put("jsonrpc", "2.0").put("method", method)
        post(key, session, payload, allowEmpty = true)
    }

    private fun request(
        key: String,
        session: String?,
        method: String,
        params: JSONObject
    ): Pair<JSONObject, String?> {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", ids.getAndIncrement())
            .put("method", method)
            .put("params", params)
        val result = post(key, session, payload, allowEmpty = false)
        return Pair(result.first, result.second)
    }

    private fun post(
        key: String,
        session: String?,
        payload: JSONObject,
        allowEmpty: Boolean
    ): Pair<JSONObject, String?> {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Authorization", "Bearer " + key)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("MCP-Protocol-Version", PROTOCOL_VERSION)
            setRequestProperty("X-ForgeGUI-Plugin-Version", "1.9.7")
            setRequestProperty("X-ForgeGUI-Plugin-Client", "mobile-studio")
            if (!session.isNullOrBlank()) setRequestProperty("MCP-Session-Id", session)
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw HttpFailure(code)
            val newSession = connection.getHeaderField("MCP-Session-Id") ?: session
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (raw.isBlank()) {
                if (allowEmpty) return Pair(JSONObject(), newSession)
                throw IllegalStateException("Empty MCP response")
            }
            val body = if (raw.trimStart().startsWith("data:") || raw.contains("\nevent:")) {
                raw.lineSequence()
                    .filter { it.startsWith("data:") }
                    .map { it.removePrefix("data:").trim() }
                    .lastOrNull { it.isNotBlank() && it != "[DONE]" }
                    ?: throw IllegalStateException("Empty MCP event stream")
            } else raw
            return Pair(JSONObject(body), newSession)
        } finally {
            connection.disconnect()
        }
    }

    private class HttpFailure(val code: Int) : Exception()

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val combined = Base64.decode(value, Base64.NO_WRAP)
        require(combined.size > 12) { "Encrypted key is invalid." }
        val iv = combined.copyOfRange(0, 12)
        val ciphertext = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
