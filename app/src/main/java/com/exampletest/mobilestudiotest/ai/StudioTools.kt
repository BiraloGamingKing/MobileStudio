package com.exampletest.mobilestudiotest.ai

import com.exampletest.mobilestudiotest.core.OutputLog
import com.exampletest.mobilestudiotest.core.Studio
import com.exampletest.mobilestudiotest.model.*

/**
 * What an AI can do in Studio (the in-app Claude assistant and external AIs over MCP): run Luau
 * in edit mode (it builds things with Instance.new, like Roblox Studio's command bar), look at
 * the Explorer tree, properties and scripts, edit scripts, read the selection and the Output.
 * Everything runs in the same sandbox as plugins: no access to the Roblox sign-in or files.
 */
class StudioTools(private val studio: Studio) {
    class Def(val name: String, val description: String, val properties: Map<String, Map<String, Any>>, val required: List<String>)
    class Result(val text: String, val isError: Boolean = false)

    /** Called before the first change in a while, so the panel can offer "Undo AI changes". */
    var beforeChange: (() -> Unit)? = null

    val defs = listOf(
        Def("run_luau",
            "Run Luau code in Roblox Studio's edit mode, like the command bar (game, workspace, Instance.new, services, Enum, CFrame, " +
                "Vector3, Color3, TweenService...). Use it to create, change or delete things in the open place. Parent new instances " +
                "(e.g. to workspace) or they won't appear. Anything you print() is returned, as are errors. Code runs once, right away; " +
                "task.wait() pauses only the script, so put long-running game logic in Scripts instead.",
            mapOf("code" to mapOf("type" to "string", "description" to "Luau source to run")), listOf("code")),
        Def("get_tree",
            "List the Explorer tree under an instance: each line is the instance name and (ClassName), indented by depth. " +
                "Paths are dot-separated from game, e.g. \"Workspace.Map.Tower\" or \"ServerScriptService\".",
            mapOf("path" to mapOf("type" to "string", "description" to "Instance path; \"game\" for the whole place"),
                "depth" to mapOf("type" to "integer", "description" to "How many levels to list (1-6)")), listOf("path", "depth")),
        Def("get_properties", "Read an instance's properties (name = value lines) and its child count.",
            mapOf("path" to mapOf("type" to "string", "description" to "Instance path, e.g. \"Workspace.Baseplate\"")), listOf("path")),
        Def("get_script", "Read the Source of a Script, LocalScript or ModuleScript.",
            mapOf("path" to mapOf("type" to "string", "description" to "Path of the script")), listOf("path")),
        Def("set_script",
            "Replace a script's Source. If the script doesn't exist, it's created at that path with the given class " +
                "(Script runs on the server, LocalScript on the client, ModuleScript is required by others).",
            mapOf("path" to mapOf("type" to "string", "description" to "Path of the script, e.g. \"ServerScriptService.GameLoop\""),
                "source" to mapOf("type" to "string", "description" to "The complete new Luau source"),
                "class_name" to mapOf("type" to "string", "enum" to listOf("Script", "LocalScript", "ModuleScript"), "description" to "Class used when creating the script")),
            listOf("path", "source", "class_name")),
        Def("get_selection", "List the paths of what's selected in Studio (the user may refer to \"this\" or \"the selected part\").",
            emptyMap(), emptyList()),
        Def("get_output", "Read the most recent lines of Studio's Output window (prints, warnings and script errors).",
            mapOf("lines" to mapOf("type" to "integer", "description" to "How many recent lines (1-200)")), listOf("lines")),
    )

    fun isMutating(name: String) = name == "run_luau" || name == "set_script"

    /** Runs a tool; never throws (errors come back as a Result with isError). */
    fun call(name: String, args: Map<String, Any?>): Result {
        if (isMutating(name) && studio.isPlaying) return Result("Studio is playtesting right now. Ask the user to stop the playtest, then try again.", true)
        return try {
            when (name) {
                "run_luau" -> runLuau(args["code"]?.toString() ?: return Result("Missing \"code\"", true))
                "get_tree" -> tree(args["path"]?.toString() ?: "game", (args["depth"] as? Number)?.toInt() ?: 2)
                "get_properties" -> properties(args["path"]?.toString() ?: return Result("Missing \"path\"", true))
                "get_script" -> getScript(args["path"]?.toString() ?: return Result("Missing \"path\"", true))
                "set_script" -> setScript(args["path"]?.toString() ?: return Result("Missing \"path\"", true),
                    args["source"]?.toString() ?: return Result("Missing \"source\"", true), args["class_name"]?.toString() ?: "Script")
                "get_selection" -> selection()
                "get_output" -> output((args["lines"] as? Number)?.toInt() ?: 40)
                else -> Result("Unknown tool $name", true)
            }
        } catch (e: Exception) {
            Result("Tool failed: ${e.message ?: e}", true)
        }
    }

    // ------------------------------------------------------------------ paths

    /** Resolves "Workspace.Model.Part" (or "game.Workspace...") from the place being edited. Names may contain dots. */
    fun resolve(path: String): Instance? {
        val root = studio.editModel
        var rest = path.trim().removePrefix("game").removePrefix(".").trim()
        if (rest.isEmpty()) return root
        var cur: Instance = root
        while (rest.isNotEmpty()) {
            // Longest child name matching the start of the rest.
            val match = cur.children.filter { c -> rest == c.name || rest.startsWith(c.name + ".") }.maxByOrNull { it.name.length } ?: return null
            cur = match
            rest = rest.removePrefix(match.name).removePrefix(".")
        }
        return cur
    }

    fun pathOf(i: Instance): String = if (i is DataModel) "game" else i.fullName()

    // ------------------------------------------------------------------ tools

    private fun runLuau(code: String): Result {
        beforeChange?.invoke()
        val captured = ArrayList<OutputLog.Entry>()
        val l: (OutputLog.Entry?) -> Unit = { e -> if (e != null) synchronized(captured) { captured.add(e) } }
        OutputLog.listeners.add(l)
        try {
            synchronized(studio.lock) {
                OutputLog.info("[AI] ran code (${code.lines().size} lines)")
                studio.editRuntime.runCommand(code)
            }
        } finally {
            OutputLog.listeners.remove(l)
        }
        studio.markDirty()
        val lines = synchronized(captured) { captured.toList() }.filter { !it.text.startsWith("[AI] ran code") }
        val err = lines.any { it.level == OutputLog.Level.ERROR }
        val text = lines.joinToString("\n") { (if (it.level == OutputLog.Level.ERROR) "ERROR: " else if (it.level == OutputLog.Level.WARN) "WARN: " else "") + it.text }
        return Result(text.ifEmpty { "OK (no output)" }.take(12000), err)
    }

    private fun tree(path: String, depth: Int): Result = synchronized(studio.lock) {
        val start = resolve(path) ?: return Result("No instance at \"$path\"", true)
        val sb = StringBuilder()
        var lines = 0
        fun walk(i: Instance, d: Int) {
            if (lines >= 400) return
            sb.append("  ".repeat(d)).append(if (i is DataModel) "game" else i.name).append(" (").append(i.className).append(")")
            if (d >= depth.coerceIn(1, 6) && i.children.isNotEmpty()) sb.append(" [${i.children.size} children]")
            sb.append('\n'); lines++
            if (d < depth.coerceIn(1, 6)) for (c in i.children) walk(c, d + 1)
        }
        walk(start, 0)
        if (lines >= 400) sb.append("… (cut off; ask for a deeper path)\n")
        Result(sb.toString())
    }

    private fun properties(path: String): Result = synchronized(studio.lock) {
        val i = resolve(path) ?: return Result("No instance at \"$path\"", true)
        val sb = StringBuilder("${pathOf(i)} (${i.className}), ${i.children.size} children\n")
        for ((k, v) in i.props) {
            if (k == "Source" || v is BinaryString || v is SharedStr || v is RawPropValue || v is UniqueId) continue
            val shown = when (v) {
                is Instance -> pathOf(v)
                is EnumValue -> Enums.typeForProp(i.className, k)?.let { t -> Enums.itemName(t, v.value)?.let { "Enum.$t.$it" } } ?: v.value.toString()
                else -> v?.toString() ?: "nil"
            }
            sb.append(PropNames.display(k)).append(" = ").append(shown.take(200)).append('\n')
        }
        for ((k, v) in i.attributes) if (!k.startsWith("__")) sb.append("@").append(k).append(" = ").append(v).append('\n')
        Result(sb.toString())
    }

    private fun getScript(path: String): Result = synchronized(studio.lock) {
        val i = resolve(path) ?: return Result("No instance at \"$path\"", true)
        if (!i.isA("LuaSourceContainer")) return Result("${pathOf(i)} is a ${i.className}, not a script", true)
        Result((i["Source"] as? String).orEmpty().ifEmpty { "(empty script)" })
    }

    private fun setScript(path: String, source: String, cls: String): Result {
        beforeChange?.invoke()
        val msg = synchronized(studio.lock) {
            var s = resolve(path)
            if (s == null) {
                val parentPath = path.substringBeforeLast('.', "")
                val parent = resolve(parentPath.ifEmpty { "ServerScriptService" }) ?: return Result("No parent at \"$parentPath\" to create the script in", true)
                val c = if (cls in setOf("Script", "LocalScript", "ModuleScript")) cls else "Script"
                s = InstanceFactory.create(c)
                s.name = path.substringAfterLast('.')
                studio.undo.begin("AI: create script")
                studio.reparent(s, parent)
                studio.setProp(s, "Source", source)
                studio.undo.commit()
                "Created ${c} ${pathOf(s)}"
            } else {
                if (!s.isA("LuaSourceContainer")) return Result("${pathOf(s)} is a ${s.className}, not a script", true)
                studio.undo.begin("AI: edit script")
                studio.setProp(s, "Source", source)
                studio.undo.commit()
                "Updated ${pathOf(s)}"
            }
        }
        studio.markDirty()
        return Result(msg)
    }

    private fun selection(): Result = synchronized(studio.lock) {
        val sel = studio.selection.toList()
        Result(if (sel.isEmpty()) "Nothing is selected" else sel.joinToString("\n") { "${pathOf(it)} (${it.className})" })
    }

    private fun output(n: Int): Result {
        val e = OutputLog.snapshot().takeLast(n.coerceIn(1, 200))
        return Result(e.joinToString("\n") { "[${it.level}] ${it.text}" }.ifEmpty { "(Output is empty)" })
    }

    companion object {
        const val GUIDE = "You are FORGE, the natural-language builder inside Mobile Studio, a Roblox Studio for Android. " +
            "Turn the user's request into actual changes in the currently open place; do not merely describe steps. The user may ask " +
            "for custom maps, buildings, props, GUIs, gameplay systems, scripts, or edits to selected objects. Use Roblox Luau and " +
            "the available Studio tools.\n\n" +
            "WORKFLOW: Treat every natural-language request as a small project brief, not a template keyword. Inspect the place first. " +
            "Translate the request into a concrete checklist covering requested objects, visuals, UI, gameplay behavior, scripts, and connections. " +
            "Build in stages: (1) core hierarchy and world objects, (2) visual polish, (3) UI, (4) gameplay logic and data flow, " +
            "(5) integration and verification. Use multiple focused run_luau/set_script calls; avoid giant scripts that are hard to debug. " +
            "After each stage, inspect the relevant tree/properties and resolve tool errors before moving on. Use meaningful names and group " +
            "all generated content under a clearly named root Model/Folder/ScreenGui. Place world content near spawn or the selected object. " +
            "At the end, inspect the final tree, read back scripts you created or edited, and call get_output. Repair concrete errors with " +
            "the smallest focused change, then verify again. A successful tool call only confirms an edit was applied; it does not prove " +
            "a Script or LocalScript works at runtime. Explicitly identify anything that still needs Play-mode testing.\n\n" +
            "CUSTOMIZATION: Respect sizes, styles, colors, themes, counts, positions, and feature details from the user's wording. " +
            "When a detail is unspecified, choose a coherent default and continue instead of asking many questions. For large requests, " +
            "build a useful first version in stages and clearly say what remains. If the user refers to 'this' or 'selected', call " +
            "get_selection first and operate only on those selected instances.\n\n" +
            "SAFE EDITING: Preserve existing content by default. Do not delete, overwrite, or replace unrelated objects or scripts " +
            "unless the user explicitly requests it. Prefer adding a new named Model/Folder and minimal targeted edits. Never claim " +
            "success for a tool call that returned an error. If an API or feature is unsupported by this runtime, explain the limitation " +
            "and offer the closest working alternative.\n\n" +
            "Use real Roblox APIs: Instance.new, Vector3, CFrame, Color3, Enum, services, workspace.Terrain:FillBlock/FillBall/FillCylinder. " +
            "Use studs for distances and Anchored parts for static builds. Server Scripts belong in ServerScriptService; client UI logic " +
            "belongs in LocalScripts under PlayerGui/StarterGui. Group related parts and scripts clearly. Keep the final response concise: " +
            "report what was created or changed, where it lives, any repairs made, and what still needs Play-mode testing."
    }
}
