package com.exampletest.mobilestudiotest.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.exampletest.mobilestudiotest.ai.ClaudeAssistant
import com.exampletest.mobilestudiotest.ai.ForgeCommandEngine
import com.exampletest.mobilestudiotest.ai.McpServer
import com.exampletest.mobilestudiotest.ai.StudioTools
import com.exampletest.mobilestudiotest.core.OutputLog
import com.exampletest.mobilestudiotest.core.Studio

/**
 * The AI Assistant dock panel, like Roblox Studio's Assistant: chat with Claude to build in the
 * open place (it runs Luau, reads the tree and writes scripts), with "Undo AI changes". The
 * Connect page runs an MCP server so Claude Desktop / Claude Code, Cursor or other AI apps on a
 * computer can do the same.
 */
@SuppressLint("ViewConstructor")
class AssistantPanel(ctx: Context, private val studio: Studio) : LinearLayout(ctx) {
    private val prefs = ctx.getSharedPreferences("ai_assistant", Context.MODE_PRIVATE)
    private val tools = StudioTools(studio)
    private val assistant = ClaudeAssistant(ctx.applicationContext, tools)
    private val mcp = McpServer(tools, prefs.getString("mcpToken", null)).also { prefs.edit().putString("mcpToken", it.token).apply() }

    private var page = "Chat"
    private val tabs = LinearLayout(ctx).apply { orientation = HORIZONTAL }
    private val content = LinearLayout(ctx).apply { orientation = VERTICAL }

    // Chat views (kept across page switches so the conversation stays).
    private val list = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(Ui.dp(ctx, 8f), Ui.dp(ctx, 6f), Ui.dp(ctx, 8f), Ui.dp(ctx, 6f)) }
    private val scroll = ScrollView(ctx).apply { addView(list); isFillViewport = true }
    private val status = mt("", 12f, Ui.DIM).apply { setTypeface(typeface, Typeface.ITALIC); setPadding(Ui.dp(ctx, 10f), Ui.dp(ctx, 2f), Ui.dp(ctx, 10f), Ui.dp(ctx, 2f)); visibility = GONE }
    private val input = Ui.input(ctx, "", "Ask Claude to build or script something…", multiLine = true).apply { maxLines = 5 }
    private lateinit var sendBtn: TextView
    private lateinit var undoBtn: TextView
    private lateinit var modelBtn: TextView
    private var bubble: TextView? = null

    /** Copies of the place from before AI requests ("Undo AI changes" puts the latest back). */
    private val snapshots = ArrayDeque<ByteArray>()
    @Volatile private var snapshotForRequest = false
    @Volatile private var lastMcpChange = 0L

    /** Text that wraps onto several lines (Ui.text is single-line). */
    private fun mt(s: String, size: Float = 13f, color: Int = Ui.TEXT, bold: Boolean = false): TextView =
        Ui.text(context, s, size, color, bold).apply { isSingleLine = false }

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.PANEL)
        addView(Ui.header(ctx, "AI Assistant", Icons.general(ctx, "Assistant", "Script")), Ui.lp(Ui.MATCH, Ui.WRAP))
        addView(tabs, Ui.lp(Ui.MATCH, Ui.WRAP))
        addView(content, Ui.lp(Ui.MATCH, 0, 1f))
        tools.beforeChange = { takeSnapshot() }
        mcp.onActivity = { post { if (page == "Connect") build() } }
        build()
        welcome()
    }

    private fun build() {
        tabs.removeAllViews()
        for ((p, label) in listOf("Forge" to "FORGE", "Chat" to "Chat", "Connect" to "Connect")) {
            tabs.addView(mt(label, 13f, if (p == page) Ui.TEXT else Ui.DIM, p == page).apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(context, 8f), 0, Ui.dp(context, 8f))
                background = if (p == page) GradientDrawable().apply { setColor(Ui.HOVER) } else null
                setOnClickListener { page = p; build() }
            }, Ui.lp(0, Ui.WRAP, 1f))
        }
        content.removeAllViews()
        when (page) { "Forge" -> buildForge(); "Chat" -> buildChat(); else -> buildConnect() }
    }

    // ------------------------------------------------------------------ FORGE offline builder

    private fun buildForge() {
        val body = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(Ui.dp(context, 12f), Ui.dp(context, 10f), Ui.dp(context, 12f), Ui.dp(context, 12f))
        }
        content.addView(ScrollView(context).apply { addView(body); isFillViewport = true }, Ui.lp(Ui.MATCH, 0, 1f))
        body.addView(mt("FORGE · Offline Builder", 16f, Ui.TEXT, true))
        body.addView(mt("No API key, no credits, no internet required. Describe a basic Roblox GUI and FORGE will build it in the open place using Mobile Studio's Luau engine.", 12f, Ui.DIM).apply {
            setPadding(0, Ui.dp(context, 5f), 0, Ui.dp(context, 10f))
        })
        val prompt = Ui.input(context, "", "Example: create a shop GUI", multiLine = true).apply { minLines = 2; maxLines = 4 }
        body.addView(prompt, Ui.lp(Ui.MATCH, Ui.WRAP))
        val result = mt("Supported: shop GUI, inventory GUI, HUD, menu, 3D map, arena, obby, house, tower, and bridge.", 12f, Ui.DIM).apply {
            setPadding(0, Ui.dp(context, 8f), 0, Ui.dp(context, 8f))
        }
        body.addView(result, Ui.lp(Ui.MATCH, Ui.WRAP))
        val buildButton = Ui.button(context, "Build with FORGE", Ui.ACCENT) {
            val request = prompt.text.toString().trim()
            if (request.isEmpty()) {
                result.text = "Pehle command likho, jaise: create a shop GUI"
                return@button
            }
            val plan = ForgeCommandEngine.build(request)
            if (plan == null) {
                result.text = "Is command ka offline template abhi available nahi hai. Try: shop GUI, inventory GUI, HUD, or menu."
                return@button
            }
            result.text = "Building \${plan.title}…"
            Thread({
                val outcome = tools.call("run_luau", mapOf("code" to plan.code))
                post {
                    result.text = if (outcome.isError) "Build failed: \${outcome.text}" else "\${plan.description}\n\n\${outcome.text}"
                }
            }, "FORGE offline build").start()
        }.apply { setTextColor(0xFFFFFFFF.toInt()) }
        body.addView(buildButton, Ui.lp(Ui.MATCH, Ui.WRAP))
        val aiBuildButton = Ui.button(context, "Build custom command with AI", Ui.PLAY) {
            if (requestBusy()) {
                result.text = "FORGE AI is already working. Wait for it to finish."
                return@button
            }
            if (assistant.apiKey.isBlank()) {
                result.text = "Custom AI building needs the existing Chat API key. Offline templates above still work without it."
                editKey()
                return@button
            }
            val command = prompt.text.toString().trim()
            if (command.isEmpty()) {
                result.text = "Pehle apna custom build command likho."
                return@button
            }
            result.text = "FORGE AI: planning your build…"
            Thread({
                assistant.send(
                    "FORGE BUILD REQUEST: ${command}\n\nBuild this directly in the currently open place, not just explain how. First inspect the existing place with get_tree or get_selection when useful. Then create the requested objects, UI, and scripts using the available Studio tools. Keep changes grouped in clearly named Models/ScreenGuis, place new world builds near the existing spawn or selected object, and report exactly what was created. If something cannot be implemented with the available tools, say so clearly."
                ) { event ->
                    post {
                        when (event) {
                            is ClaudeAssistant.Event.Status -> result.text = "FORGE AI: ${event.text}"
                            is ClaudeAssistant.Event.TextDelta -> result.append(event.text)
                            is ClaudeAssistant.Event.ToolCall -> result.append("\n\n[${event.name}] ${if (event.isError) "ERROR: " else ""}${event.result.take(500)}")
                            is ClaudeAssistant.Event.Failed -> result.append("\n\nFailed: ${event.text}")
                            is ClaudeAssistant.Event.Notice -> result.append("\n\n${event.text}")
                            ClaudeAssistant.Event.BlockEnd -> Unit
                            ClaudeAssistant.Event.Done -> result.append("\n\nFORGE AI finished.")
                        }
                    }
                }
            }, "FORGE custom AI build").start()
        }.apply { setTextColor(0xFFFFFFFF.toInt()) }
        body.addView(aiBuildButton, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(context, 6f) })
        body.addView(mt("AI mode uses the configured Chat API provider and may incur provider charges. Offline template mode above needs no key or credits.", 11f, Ui.DIM).apply {
            setPadding(0, Ui.dp(context, 5f), 0, Ui.dp(context, 8f))
        })
        body.addView(mt("Quick commands", 13f, Ui.TEXT, true).apply { setPadding(0, Ui.dp(context, 14f), 0, Ui.dp(context, 5f)) })
        for (example in listOf("Create a shop GUI", "Make an inventory menu", "Build a player HUD", "Create a main menu with a button", "Build a dark arena map", "Create an obby course", "Build a small house", "Create a tall tower", "Build a bridge")) {
            body.addView(chip(example) {
                prompt.setText(example)
                buildButton.performClick()
            }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { bottomMargin = Ui.dp(context, 6f) })
        }
        body.addView(mt("GUI templates are placed in StarterGui. 3D layouts are placed in Workspace.FORGE_Generated. Press Play to preview GUIs; inspect maps in Explorer.", 11f, Ui.DIM).apply {
            setPadding(0, Ui.dp(context, 12f), 0, 0)
        })
    }

    private fun requestBusy(): Boolean = assistant.busy

    // ------------------------------------------------------------------ chat

    private fun buildChat() {
        (scroll.parent as? LinearLayout)?.removeView(scroll)
        (status.parent as? LinearLayout)?.removeView(status)
        content.addView(scroll, Ui.lp(Ui.MATCH, 0, 1f))
        content.addView(status, Ui.lp(Ui.MATCH, Ui.WRAP))
        // Input row.
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(Ui.dp(context, 6f), Ui.dp(context, 4f), Ui.dp(context, 6f), Ui.dp(context, 4f)) }
        (input.parent as? LinearLayout)?.removeView(input)
        row.addView(input, Ui.lp(0, Ui.WRAP, 1f))
        sendBtn = Ui.button(context, if (assistant.busy) "Stop" else "Send", if (assistant.busy) Ui.STOP else Ui.ACCENT) { onSend() }.apply { setTextColor(0xFFFFFFFF.toInt()) }
        row.addView(sendBtn, Ui.lp(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.dp(context, 6f) })
        // Until a key is set up, the setup steps get the whole panel (there is nothing to type yet).
        val ready = assistant.apiKey.isNotBlank()
        if (ready) content.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
        // Footer (one scrollable row): model, new chat, undo, key.
        val foot = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(Ui.dp(context, 6f), 0, Ui.dp(context, 6f), Ui.dp(context, 6f)) }
        fun add(v: View) = foot.addView(v, Ui.lp(Ui.WRAP, Ui.WRAP).apply { rightMargin = Ui.dp(context, 6f) })
        undoBtn = chip("Undo AI changes") { undoAi() }
        add(undoBtn)
        add(chip("New chat") { newChat() })
        modelBtn = chip(modelLabel()) { pickModel() }
        add(modelBtn)
        add(chip(if (assistant.apiKey.isBlank()) "Add API key" else "API key") { editKey() })
        if (ready) content.addView(android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(foot) }, Ui.lp(Ui.MATCH, Ui.WRAP))
        refreshButtons()
    }

    private fun chip(label: String, onClick: () -> Unit) = mt(label, 12f, Ui.TEXT).apply {
        setPadding(Ui.dp(context, 10f), Ui.dp(context, 4f), Ui.dp(context, 10f), Ui.dp(context, 4f))
        background = GradientDrawable().apply { setColor(Ui.HOVER); cornerRadius = Ui.dp(context, 14f).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun modelLabel(): String {
        val m = ClaudeAssistant.MODELS.firstOrNull { it.first == assistant.model }?.second?.removePrefix("Claude ") ?: assistant.model
        return "$m · ${assistant.effort}"
    }

    private fun refreshButtons() {
        if (!::sendBtn.isInitialized) return
        sendBtn.text = if (assistant.busy) "Stop" else "Send"
        sendBtn.background = Ui.rippleBg(context, if (assistant.busy) Ui.STOP else Ui.ACCENT)
        undoBtn.alpha = if (snapshots.isEmpty() || assistant.busy) 0.4f else 1f
        modelBtn.text = modelLabel()
    }

    private fun primary(label: String, onClick: () -> Unit) = Ui.button(context, label, Ui.ACCENT) { onClick() }.apply { setTextColor(0xFFFFFFFF.toInt()) }

    /** One numbered setup step: a number badge, a title, a line of help and (optionally) a button. */
    private fun step(parent: LinearLayout, n: Int, title: String, help: String, done: Boolean, button: View?) {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(0, Ui.dp(context, 8f), 0, Ui.dp(context, 4f)) }
        val badge = mt(if (done) "✓" else "$n", 13f, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (done) Ui.PLAY else Ui.ACCENT) }
        }
        row.addView(badge, Ui.lp(Ui.dp(context, 24f), Ui.dp(context, 24f)).apply { rightMargin = Ui.dp(context, 10f) })
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        col.addView(mt(title, 13.5f, Ui.TEXT, true))
        col.addView(mt(help, 12f, Ui.DIM).apply { setPadding(0, Ui.dp(context, 2f), 0, Ui.dp(context, 4f)) })
        button?.let { col.addView(it, Ui.lp(Ui.WRAP, Ui.WRAP)) }
        row.addView(col, Ui.lp(0, Ui.WRAP, 1f))
        parent.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    private fun openUrl(url: String) {
        try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        catch (_: Exception) { Toast.makeText(context, "No browser found to open $url", Toast.LENGTH_LONG).show() }
    }

    /** The key on the clipboard, if what's copied looks like one. */
    private fun keyOnClipboard(): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = try { cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim() } catch (_: Exception) { null } ?: return null
        return Regex("sk-ant-[A-Za-z0-9_\\-]{20,}").find(t)?.value
    }

    /** Saves [key] after checking with Anthropic that it works (free), then shows the chat. */
    private fun useKey(key: String) {
        status.text = "Checking your key…"; status.visibility = VISIBLE
        Thread({
            val problem = assistant.checkKey(key)
            post {
                status.visibility = GONE
                if (problem != null) {
                    Dialogs.themed(context).setTitle("That key didn't work").setMessage(problem).setPositiveButton("OK", null).show()
                } else {
                    assistant.apiKey = key
                    Toast.makeText(context, "Connected to Claude", Toast.LENGTH_SHORT).show()
                    welcome(); build()
                }
            }
        }, "AI key check").start()
    }

    private fun welcome() {
        list.removeAllViews()
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(Ui.dp(context, 12f), Ui.dp(context, 10f), Ui.dp(context, 12f), Ui.dp(context, 10f))
            background = GradientDrawable().apply { setColor(Ui.CARD); cornerRadius = Ui.dp(context, 8f).toFloat() }
        }
        card.addView(mt("Build with Claude", 15f, Ui.TEXT, true))
        card.addView(mt(if (assistant.apiKey.isBlank()) "Describe what you want and Claude builds it in your place."
            else "Describe what you want and Claude builds it in your place: parts and models, terrain, lighting, GUIs and scripts. " +
                "It can see the Explorer, your selection and the Output.", 12f, Ui.DIM).apply { setPadding(0, Ui.dp(context, 4f), 0, Ui.dp(context, 6f)) })
        if (assistant.apiKey.isBlank()) {
            // Everything happens on this phone: the browser for the key, then back here to paste it.
            card.addView(mt("Set up (about 2 minutes, all on this phone)", 13f, Ui.ACCENT, true).apply { setPadding(0, Ui.dp(context, 4f), 0, 0) })
            step(card, 1, "Get an Anthropic API key",
                "Opens the Anthropic Console in your browser. Sign up or sign in, add a little credit under Billing (Claude is paid per use), then tap \"Create Key\" and copy it.",
                false, primary("Open Console") { openUrl("https://console.anthropic.com/settings/keys") })
            step(card, 2, "Come back and paste it",
                "Return to Mobile Studio and tap Paste. The key is checked (free) and saved only on this phone.",
                false, primary("Paste key") {
                    val k = keyOnClipboard()
                    if (k != null) useKey(k) else editKey()
                })
            step(card, 3, "Ask Claude to build", "Type what you want below, like \"build a castle by the spawn\".", false, null)
            card.addView(mt("Your Roblox sign-in is never shared. Prefer another AI app (Claude app, Cursor…)? Use the \"Connect an AI app\" tab.", 11.5f, Ui.DIM).apply { setPadding(0, Ui.dp(context, 6f), 0, 0) })
        } else {
            card.addView(mt("Try:", 12f, Ui.DIM))
            val wrap = FlowLayout(context)
            for (s in listOf("Build a small wooden house next to the spawn", "Make a 10-stage obby with checkpoints", "Add a day and night cycle",
                "Make the selected part spin slowly", "Add a leaderboard with Coins", "Generate a mountain island with terrain")) {
                wrap.addView(chip(s) { input.setText(s); onSend() })
            }
            card.addView(wrap)
        }
        list.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP))
        card.addView(mt("What you send, and the parts of your place Claude reads, go to Anthropic to get a reply.", 11f, Ui.DIM).apply { setPadding(0, Ui.dp(context, 8f), 0, 0) })
    }

    private fun addUser(text: String) {
        val t = mt(text, 13f, 0xFFFFFFFF.toInt()).apply {
            setPadding(Ui.dp(context, 10f), Ui.dp(context, 7f), Ui.dp(context, 10f), Ui.dp(context, 7f))
            background = GradientDrawable().apply { setColor(Ui.ACCENT); cornerRadius = Ui.dp(context, 10f).toFloat() }
            setTextIsSelectable(true)
        }
        val wrap = LinearLayout(context).apply { gravity = Gravity.END; setPadding(Ui.dp(context, 40f), Ui.dp(context, 6f), 0, Ui.dp(context, 2f)); addView(t) }
        list.addView(wrap, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    private fun appendAssistant(delta: String) {
        val b = bubble ?: mt("", 13f, Ui.TEXT).apply {
            setPadding(Ui.dp(context, 10f), Ui.dp(context, 7f), Ui.dp(context, 10f), Ui.dp(context, 7f))
            background = GradientDrawable().apply { setColor(Ui.CARD); cornerRadius = Ui.dp(context, 10f).toFloat() }
            setTextIsSelectable(true)
            list.addView(LinearLayout(context).apply { setPadding(0, Ui.dp(context, 4f), Ui.dp(context, 30f), Ui.dp(context, 2f)); addView(this@apply) }, Ui.lp(Ui.MATCH, Ui.WRAP))
            bubble = this
        }
        b.append(delta)
    }

    private fun addTool(name: String, summary: String, result: String, isError: Boolean) {
        val label = when (name) {
            "run_luau" -> "Ran code"; "set_script" -> "Wrote script"; "get_tree" -> "Looked at Explorer"
            "get_properties" -> "Read properties"; "get_script" -> "Read script"; "get_selection" -> "Checked selection"
            "get_output" -> "Read Output"; else -> name
        }
        val head = mt((if (isError) "⚠ " else "✓ ") + label + if (summary.isNotEmpty()) "  ·  $summary" else "", 12f, if (isError) Ui.WARN else Ui.DIM)
        val detail = mt(result.take(4000), 11f, Ui.TEXT).apply {
            typeface = Typeface.MONOSPACE
            visibility = GONE
            setPadding(Ui.dp(context, 8f), Ui.dp(context, 4f), Ui.dp(context, 8f), Ui.dp(context, 4f))
            background = GradientDrawable().apply { setColor(Ui.INPUT); cornerRadius = Ui.dp(context, 4f).toFloat() }
            setTextIsSelectable(true)
        }
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(Ui.dp(context, 4f), Ui.dp(context, 3f), 0, Ui.dp(context, 3f))
            addView(head); addView(detail)
            setOnClickListener { detail.visibility = if (detail.visibility == GONE) VISIBLE else GONE }
        }
        head.isSingleLine = true
        head.ellipsize = android.text.TextUtils.TruncateAt.END
        list.addView(box, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    private fun addNote(text: String, color: Int) {
        list.addView(mt(text, 12f, color).apply { setPadding(Ui.dp(context, 4f), Ui.dp(context, 4f), 0, Ui.dp(context, 4f)) }, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    private fun scrollDown() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    private fun onSend() {
        if (assistant.busy) { assistant.cancel(); status.text = "Stopping…"; return }
        if (assistant.apiKey.isBlank()) { editKey(); return }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (assistant.isEmpty) list.removeAllViews()
        input.setText("")
        addUser(text)
        bubble = null
        snapshotForRequest = false
        status.text = "Thinking…"; status.visibility = VISIBLE
        scrollDown()
        Thread({
            assistant.send(text) { e -> post { onEvent(e) } }
        }, "AI Assistant").start()
        refreshButtons()
    }

    private fun onEvent(e: ClaudeAssistant.Event) {
        when (e) {
            is ClaudeAssistant.Event.TextDelta -> { appendAssistant(e.text); status.text = "Writing…" }
            is ClaudeAssistant.Event.BlockEnd -> bubble = null
            is ClaudeAssistant.Event.Status -> status.text = e.text
            is ClaudeAssistant.Event.ToolCall -> { bubble = null; addTool(e.name, e.summary, e.result, e.isError); status.text = "Thinking…" }
            is ClaudeAssistant.Event.Notice -> addNote(e.text, Ui.DIM)
            is ClaudeAssistant.Event.Failed -> addNote(e.text, Ui.ERROR)
            ClaudeAssistant.Event.Done -> { status.visibility = GONE; bubble = null }
        }
        refreshButtons()
        scrollDown()
    }

    private fun newChat() {
        if (assistant.busy) return
        assistant.reset()
        bubble = null
        welcome()
    }

    // ------------------------------------------------------------------ undo

    /** Called (off the UI thread) right before the AI first changes the place in a request. */
    private fun takeSnapshot() {
        val now = SystemClock.uptimeMillis()
        val fromChat = assistant.busy
        if (fromChat) {
            if (snapshotForRequest) return
            snapshotForRequest = true
        } else {
            // MCP: one snapshot per burst of changes (a minute apart).
            val last = lastMcpChange
            lastMcpChange = now
            if (now - last < 60_000L) return
        }
        val bytes = try { studio.serializePlace() } catch (e: Exception) { OutputLog.warn("Couldn't save a copy for Undo AI changes: ${e.message}"); return }
        post {
            snapshots.addLast(bytes)
            while (snapshots.size > 5) snapshots.removeFirst()
            refreshButtons()
        }
    }

    private fun undoAi() {
        if (assistant.busy) return
        val snap = snapshots.lastOrNull() ?: run { Toast.makeText(context, "No AI changes to undo", Toast.LENGTH_SHORT).show(); return }
        Dialogs.confirm(context, "Undo AI changes", "Put the place back the way it was before the last AI request? Edits you made since then are undone too.", "Undo") {
            snapshots.removeLast()
            try {
                studio.restoreSnapshot(snap)
                addNote("Undid the last AI changes.", Ui.DIM)
                OutputLog.info("AI changes undone")
            } catch (e: Exception) {
                addNote("Couldn't undo: ${e.message}", Ui.ERROR)
            }
            refreshButtons()
        }
    }

    // ------------------------------------------------------------------ settings

    private fun editKey() {
        val e = Ui.input(context, assistant.apiKey, "sk-ant-…").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val wrap = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(Ui.dp(context, 16f), Ui.dp(context, 8f), Ui.dp(context, 16f), 0)
            addView(mt("Paste the key you copied from the Anthropic Console (it starts with sk-ant-). It's saved only on this phone (not in backups) and sent only to Anthropic.", 12f, Ui.DIM).apply { setPadding(0, 0, 0, Ui.dp(context, 8f)) })
            addView(e, Ui.lp(Ui.MATCH, Ui.WRAP))
            addView(Ui.button(context, "Paste from clipboard") { keyOnClipboard()?.let { e.setText(it) } ?: Toast.makeText(context, "No key on the clipboard. Copy it in the Anthropic Console first.", Toast.LENGTH_LONG).show() },
                Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.dp(context, 6f) })
            addView(Ui.button(context, "Open Anthropic Console") { openUrl("https://console.anthropic.com/settings/keys") }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.dp(context, 4f) })
        }
        val d = Dialogs.themed(context).setTitle("Anthropic API key").setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                val k = e.text.toString().trim()
                if (k.isEmpty()) return@setPositiveButton
                if (!k.startsWith("sk-ant-")) { Toast.makeText(context, "That doesn't look like an Anthropic key (it starts with sk-ant-)", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                useKey(k)
            }
            .setNegativeButton("Cancel", null)
        if (assistant.apiKey.isNotBlank()) d.setNeutralButton("Remove key") { _, _ -> assistant.apiKey = ""; assistant.reset(); welcome(); build() }
        d.show()
    }

    private fun pickModel() {
        if (assistant.busy) return
        val names = ClaudeAssistant.MODELS.map { it.second }.toTypedArray()
        val cur = ClaudeAssistant.MODELS.indexOfFirst { it.first == assistant.model }.coerceAtLeast(0)
        Dialogs.themed(context).setTitle("Model").setSingleChoiceItems(names, cur) { d, which ->
            d.dismiss()
            val newModel = ClaudeAssistant.MODELS[which].first
            if (newModel != assistant.model) { assistant.model = newModel; welcome() }
            val efforts = ClaudeAssistant.EFFORTS
            val labels = arrayOf("Low (fastest)", "Medium (recommended)", "High", "Extra high (slowest, most careful)")
            Dialogs.themed(context).setTitle("Effort").setSingleChoiceItems(labels, efforts.indexOf(assistant.effort).coerceAtLeast(0)) { d2, w ->
                d2.dismiss(); assistant.effort = efforts[w]; refreshButtons()
            }.show()
        }.show()
    }

    // ------------------------------------------------------------------ MCP (Connect page)

    private var showComputer = false

    private fun buildConnect() {
        val body = LinearLayout(context).apply { orientation = VERTICAL; setPadding(Ui.dp(context, 10f), Ui.dp(context, 8f), Ui.dp(context, 10f), Ui.dp(context, 14f)) }
        content.addView(ScrollView(context).apply { addView(body) }, Ui.lp(Ui.MATCH, 0, 1f))
        fun para(s: String, size: Float = 12f, color: Int = Ui.DIM, bold: Boolean = false) =
            body.addView(mt(s, size, color, bold).apply { setPadding(0, Ui.dp(context, 4f), 0, Ui.dp(context, 4f)) })

        para("Connect another AI app", 15f, Ui.TEXT, true)
        para("For Claude on this phone, you don't need this page: use the Chat tab. This page is for other AI apps that support MCP " +
            "(like Roblox Studio's MCP server). They can then build in this place with the same tools as the chat.")

        val running = mcp.running
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(Ui.dp(context, 12f), Ui.dp(context, 6f), Ui.dp(context, 12f), Ui.dp(context, 10f))
            background = GradientDrawable().apply { setColor(Ui.CARD); cornerRadius = Ui.dp(context, 8f).toFloat() }
        }
        body.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(context, 6f) })
        step(card, 1, "Turn on the connection",
            if (running) (if (mcp.calls == 0) "On. Waiting for an app to connect…" else "On · ${mcp.calls} actions so far" + (mcp.lastClient?.let { " · last from $it" } ?: ""))
            else "Off. Nothing can connect until you turn it on.",
            running, Ui.button(context, if (running) "Turn off" else "Turn on", if (running) Ui.STOP else Ui.ACCENT) {
                try { if (mcp.running) mcp.stop() else mcp.start(prefs.getBoolean("mcpLan", false)) }
                catch (e: Exception) { Toast.makeText(context, "Couldn't start: ${e.message}", Toast.LENGTH_LONG).show() }
                build()
            }.apply { setTextColor(0xFFFFFFFF.toInt()) })
        val local = "http://127.0.0.1:${mcp.port}/mcp"
        step(card, 2, "Add it in the AI app (on this phone)",
            "In the app's MCP / connector settings, add a server with this address and the secret key below (as an \"Authorization: Bearer\" header). " +
                "Keep Mobile Studio open while it works.", false, null)
        card.addView(mt("Address", 12f, Ui.TEXT, true))
        copyRow(card, local)
        card.addView(mt("Secret key (only give it to apps you trust: it lets them change your place)", 12f, Ui.TEXT, true))
        copyRow(card, "Bearer ${mcp.token}")
        card.addView(Ui.button(context, "Copy setup for the app") {
            copy("{\n  \"mcpServers\": {\n    \"mobile-studio\": {\n      \"type\": \"http\",\n      \"url\": \"$local\",\n      \"headers\": { \"Authorization\": \"Bearer ${mcp.token}\" }\n    }\n  }\n}")
        }, Ui.lp(Ui.WRAP, Ui.WRAP))
        step(card, 3, "Ask it to build", "For example: \"In Mobile Studio, build a castle on the baseplate\". Changes show up live; \"Undo AI changes\" in the Chat tab puts the place back.", false, null)

        // Computer setup (optional, folded away).
        body.addView(mt((if (showComputer) "▾ " else "▸ ") + "Using an AI app on a computer instead (optional)", 13f, Ui.ACCENT, true).apply {
            setPadding(0, Ui.dp(context, 14f), 0, Ui.dp(context, 4f))
            setOnClickListener { showComputer = !showComputer; build() }
        })
        if (!showComputer) return
        body.addView(CheckBox(context).apply {
            text = "Allow devices on my Wi-Fi (only on a network you trust)"
            setTextColor(Ui.TEXT); textSize = 12f
            isChecked = prefs.getBoolean("mcpLan", false)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean("mcpLan", on).apply()
                if (mcp.running) try { mcp.start(on) } catch (e: Exception) { Toast.makeText(context, "Couldn't restart: ${e.message}", Toast.LENGTH_LONG).show() }
                build()
            }
        })
        val url = mcp.urls().let { u -> if (mcp.lan) u.lastOrNull() ?: u.first() else u.first() }
        if (!mcp.lan) {
            para("With a USB cable (USB debugging on), run this on the computer first:")
            copyRow(body, "adb forward tcp:${mcp.port} tcp:${mcp.port}")
            para("Or tick the Wi-Fi box above to connect without a cable.")
        } else {
            para("Addresses on your Wi-Fi:")
            for (u in mcp.urls().drop(1)) copyRow(body, u)
        }
        para("Claude Code (terminal):")
        copyRow(body, "claude mcp add --transport http mobile-studio $url --header \"Authorization: Bearer ${mcp.token}\"")
        para("Claude Desktop, Cursor and others (needs Node.js; uses mcp-remote):")
        copyRow(body, "{\n  \"mcpServers\": {\n    \"mobile-studio\": {\n      \"command\": \"npx\",\n      \"args\": [\"-y\", \"mcp-remote\", \"$url\", \"--allow-http\", \"--header\", \"Authorization: Bearer ${mcp.token}\"]\n    }\n  }\n}")
        body.addView(Ui.button(context, "Make a new secret key") {
            Dialogs.confirm(context, "New secret key", "Apps set up with the old key will stop working until you give them the new one.", "New key") {
                mcp.regenerateToken(); prefs.edit().putString("mcpToken", mcp.token).apply(); build()
            }
        }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.dp(context, 8f) })
    }

    private fun copy(value: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Mobile Studio", value))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun copyRow(body: LinearLayout, value: String) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 8f), Ui.dp(context, 6f), Ui.dp(context, 4f), Ui.dp(context, 6f))
            background = GradientDrawable().apply { setColor(Ui.INPUT); cornerRadius = Ui.dp(context, 4f).toFloat() }
        }
        row.addView(mt(value, 11f, Ui.TEXT).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }, Ui.lp(0, Ui.WRAP, 1f))
        row.addView(Ui.button(context, "Copy") {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Mobile Studio", value))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }, Ui.lp(Ui.WRAP, Ui.WRAP))
        body.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = Ui.dp(context, 6f) })
    }

    /** Stops the MCP server (activity going away). */
    fun shutdown() {
        assistant.cancel()
        mcp.stop()
    }
}
