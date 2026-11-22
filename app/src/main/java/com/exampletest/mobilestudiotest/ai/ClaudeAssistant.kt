package com.exampletest.mobilestudiotest.ai

import android.content.Context
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import org.json.JSONObject
import java.time.Duration

/**
 * The in-app AI assistant: Claude, called through the official Anthropic SDK with the user's own
 * API key, using [StudioTools] to look at and build in the open place (a manual tool loop on the
 * Messages API: every tool runs here, on the phone, against the place in memory). The key is
 * stored only on this device (excluded from backups) and sent only to Anthropic.
 */
class ClaudeAssistant(private val ctx: Context, private val tools: StudioTools) {
    sealed class Event {
        /** More of Claude's reply text (append to the current bubble). */
        class TextDelta(val text: String) : Event()
        /** The current reply block ended. */
        object BlockEnd : Event()
        /** What Claude is doing right now ("Writing code…"). */
        class Status(val text: String) : Event()
        class ToolCall(val name: String, val summary: String, val result: String, val isError: Boolean) : Event()
        class Notice(val text: String) : Event()
        class Failed(val text: String) : Event()
        object Done : Event()
    }

    companion object {
        private const val PREFS = "ai_assistant"
        /** Models offered; Claude Opus 5.5 is the default. */
        val MODELS = listOf("claude-opus-5-5" to "Claude Opus 5.5", "claude-sonnet-5-5" to "Claude Sonnet 5.5", "claude-haiku-5-5" to "Claude Haiku 5.5")
        val EFFORTS = listOf("low", "medium", "high", "xhigh")
        private const val MAX_STEPS = 30
    }

    private fun prefs() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    var apiKey: String
        get() = prefs().getString("apiKey", "") ?: ""
        set(v) { prefs().edit().putString("apiKey", v.trim()).apply(); client = null }
    var model: String
        get() = prefs().getString("model", MODELS[0].first) ?: MODELS[0].first
        // Thinking blocks belong to the model that wrote them, so a new model starts a new chat.
        set(v) { if (v != model) { prefs().edit().putString("model", v).apply(); if (!busy) history.clear() } }
    var effort: String
        get() = prefs().getString("effort", "medium") ?: "medium"
        set(v) { prefs().edit().putString("effort", v).apply() }

    private var client: AnthropicClient? = null
    /** The conversation, append-only (Claude's thinking blocks are passed back unchanged). */
    private val history = ArrayList<MessageParam>()
    val isEmpty get() = history.isEmpty()
    @Volatile var busy = false; private set
    @Volatile private var cancelled = false

    fun reset() { if (!busy) history.clear() }
    fun cancel() { cancelled = true }

    private fun client(): AnthropicClient = client ?: AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofMinutes(10))
        .maxRetries(2)
        .build().also { client = it }

    private val toolDefs: List<Tool> by lazy {
        tools.defs.map { d ->
            val props = Tool.InputSchema.Properties.builder()
            for ((k, v) in d.properties) props.putAdditionalProperty(k, JsonValue.from(v))
            Tool.builder()
                .name(d.name)
                .description(d.description)
                // Stream tool inputs (whole scripts) as they're written; runTool validates them.
                .eagerInputStreaming(true)
                .inputSchema(Tool.InputSchema.builder()
                    .properties(props.build())
                    .required(d.required)
                    .build())
                .build()
        }
    }

    private fun params(): MessageCreateParams {
        val m = model
        val b = MessageCreateParams.builder()
            .model(m)
            .maxTokens(16000L)
            // Stable prefix first (tools + system) so repeated turns hit the prompt cache.
            .systemOfTextBlockParams(listOf(TextBlockParam.builder().text(StudioTools.GUIDE).build()))
            .messages(history)
            .thinking(ThinkingConfigAdaptive.builder().build())
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(effort)).build())
            .cacheControl(CacheControlEphemeral.builder().build())
        for (t in toolDefs) b.addTool(t)
        // If a safety classifier declines, Anthropic re-runs the request on a suitable model
        // instead of stopping (Haiku has no server-side fallback).
        if (m != "claude-haiku-5-5") {
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return b.build()
    }

    /** Sends [text] and runs the tool loop on the calling (background) thread, reporting [onEvent] as it goes. */
    fun send(text: String, onEvent: (Event) -> Unit) {
        if (apiKey.isBlank()) { onEvent(Event.Failed("Add your Anthropic API key first (console.anthropic.com → API keys).")); onEvent(Event.Done); return }
        busy = true; cancelled = false
        val startSize = history.size
        try {
            history.add(MessageParam.ofUser(text))
            var steps = 0
            while (true) {
                if (cancelled) { onEvent(Event.Notice("Stopped.")); break }
                if (steps++ >= MAX_STEPS) { onEvent(Event.Notice("Stopped after $MAX_STEPS steps. Send another message to continue.")); break }
                val resp = stream(onEvent) ?: break
                val reason = resp.stopReason().orElse(null)
                if (reason == StopReason.REFUSAL) {
                    // Never run a declined turn's tools; drop the turn so it isn't replayed.
                    rollback(startSize)
                    val why = resp.stopDetails().flatMap { it.explanation() }.orElse("")
                    onEvent(Event.Failed("Claude declined this request" + if (why.isNotEmpty()) ": $why" else ". Try asking differently."))
                    break
                }
                history.add(resp.toParam())
                val calls = resp.content().mapNotNull { it.toolUse().orElse(null) }
                if (calls.isEmpty() || (reason != StopReason.TOOL_USE && reason != StopReason.MAX_TOKENS)) {
                    if (reason == StopReason.MAX_TOKENS) onEvent(Event.Notice("The reply hit its length limit."))
                    break
                }
                // Run every tool call, then send all results back in one message.
                val results = ArrayList<ContentBlockParam>()
                for (c in calls) {
                    val r = if (reason == StopReason.MAX_TOKENS) {
                        // The input was cut off part-way: don't run it.
                        StudioTools.Result("Your tool input was cut off at the length limit and was not run. Do the work in smaller steps (shorter code per call).", true)
                    } else runTool(c, onEvent)
                    results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(c.id()).content(r.text).isError(r.isError).build()))
                }
                history.add(MessageParam.ofUserOfContentBlockParams(results))
            }
        } catch (e: UnauthorizedException) {
            rollback(startSize); onEvent(Event.Failed("Your API key was rejected. Check it in the assistant settings."))
        } catch (e: PermissionDeniedException) {
            rollback(startSize); onEvent(Event.Failed("This API key can't use ${model}. Pick another model in the settings."))
        } catch (e: RateLimitException) {
            rollback(startSize); onEvent(Event.Failed("Rate limited by Anthropic. Wait a moment and try again."))
        } catch (e: BadRequestException) {
            rollback(startSize); onEvent(Event.Failed("Request rejected: ${e.message ?: "bad request"}"))
        } catch (e: AnthropicServiceException) {
            rollback(startSize); onEvent(Event.Failed("Anthropic error (${e.statusCode()}): ${e.message ?: ""}"))
        } catch (e: AnthropicIoException) {
            rollback(startSize); onEvent(Event.Failed("Couldn't reach Anthropic. Check your internet connection."))
        } catch (e: Exception) {
            rollback(startSize); onEvent(Event.Failed("Something went wrong: ${e.message ?: e}"))
        } finally {
            busy = false
            onEvent(Event.Done)
        }
    }

    /**
     * One streamed request (so long replies, like a whole script, don't time out). Text is shown as
     * it arrives; returns the complete message, or null when the user pressed Stop.
     */
    private fun stream(onEvent: (Event) -> Unit): Message? {
        val acc = MessageAccumulator.create()
        client().messages().createStreaming(params()).use { s ->
            for (ev in s) {
                acc.accumulate(ev)
                ev.contentBlockStart().ifPresent { st ->
                    st.contentBlock().toolUse().ifPresent { onEvent(Event.Status(statusFor(it.name()))) }
                }
                ev.contentBlockDelta().ifPresent { d -> d.delta().text().ifPresent { t -> if (t.text().isNotEmpty()) onEvent(Event.TextDelta(t.text())) } }
                ev.contentBlockStop().ifPresent { onEvent(Event.BlockEnd) }
                if (cancelled) { onEvent(Event.Notice("Stopped.")); return null }
            }
        }
        return acc.message()
    }

    private fun statusFor(tool: String) = when (tool) {
        "run_luau" -> "Writing code…"
        "set_script" -> "Writing a script…"
        "get_tree", "get_properties", "get_selection" -> "Looking at your place…"
        "get_script" -> "Reading a script…"
        "get_output" -> "Reading the Output…"
        else -> "Working…"
    }

    /** Validates a (streamed, unbuffered) tool input against its schema, then runs it. */
    private fun runTool(c: ToolUseBlock, onEvent: (Event) -> Unit): StudioTools.Result {
        @Suppress("UNCHECKED_CAST")
        val args = try { c._input().convert(Map::class.java) as? Map<String, Any?> } catch (_: Exception) { null }
        val def = tools.defs.firstOrNull { it.name == c.name() }
        val problem = when {
            def == null -> "Unknown tool ${c.name()}"
            args == null -> "not a JSON object"
            else -> validate(def, args)
        }
        val r = if (problem != null || args == null) {
            val raw = try { JSONObject(args ?: emptyMap<String, Any?>()).toString() } catch (_: Exception) { c._input().toString() }
            StudioTools.Result(JSONObject().put("INVALID_JSON", raw).put("problem", problem ?: "invalid").toString(), true)
        } else tools.call(c.name(), args)
        onEvent(Event.ToolCall(c.name(), summarize(c.name(), args), r.text, r.isError))
        return r
    }

    private fun validate(d: StudioTools.Def, args: Map<String, Any?>): String? {
        for (k in d.required) if (args[k] == null) return "missing \"$k\""
        for ((k, v) in args) {
            val spec = d.properties[k] ?: continue
            when (spec["type"]) {
                "string" -> if (v !is String) return "\"$k\" must be a string"
                "integer" -> if (v !is Number || v.toDouble() != Math.floor(v.toDouble())) return "\"$k\" must be an integer"
            }
            val allowed = spec["enum"] as? List<*>
            if (allowed != null && v !in allowed) return "\"$k\" must be one of ${allowed.joinToString()}"
        }
        return null
    }

    /**
     * Checks a key without using any credit (lists the models it can use). Returns null when it
     * works, or what's wrong in plain words. Call off the UI thread.
     */
    fun checkKey(key: String): String? {
        val c = AnthropicOkHttpClient.builder().apiKey(key.trim()).timeout(Duration.ofSeconds(20)).maxRetries(1).build()
        return try {
            c.models().list().data()
            null
        } catch (e: UnauthorizedException) {
            "Anthropic didn't accept this key. Copy it again from the Console (it starts with sk-ant-)."
        } catch (e: PermissionDeniedException) {
            "This key isn't allowed to use the API. Check it in the Anthropic Console."
        } catch (e: AnthropicIoException) {
            "Couldn't reach Anthropic. Check your internet connection."
        } catch (e: Exception) {
            "Couldn't check the key: ${e.message ?: e}"
        } finally {
            try { c.close() } catch (_: Exception) {}
        }
    }

    /** After a failed request, drop the unanswered turn so the conversation stays valid. */
    private fun rollback(size: Int) { while (history.size > size) history.removeAt(history.size - 1) }

    private fun summarize(name: String, args: Map<String, Any?>?): String = when (name) {
        "run_luau" -> (args?.get("code")?.toString() ?: "").lines().firstOrNull { it.isNotBlank() && !it.trim().startsWith("--") }?.trim()?.take(70) ?: ""
        "set_script", "get_tree", "get_properties", "get_script" -> args?.get("path")?.toString() ?: ""
        else -> ""
    }
}
