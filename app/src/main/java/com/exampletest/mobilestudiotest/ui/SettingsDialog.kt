package com.exampletest.mobilestudiotest.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.exampletest.mobilestudiotest.core.Settings
import com.exampletest.mobilestudiotest.core.Theme
import com.exampletest.mobilestudiotest.model.Color3
import com.exampletest.mobilestudiotest.model.fmtNum

/** File → Studio Settings: theme (light/dark/custom), camera, script editor, viewport and playtest options. */
object SettingsDialog {
    val flySpeeds = listOf(0.1f, 0.25f, 0.5f, 1f, 2f, 4f, 8f, 16f, 32f, 64f)

    fun show(ctx: Context, actions: MainActions, onImportIcons: () -> Unit, onRemoveIcons: (() -> Unit)?) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(ctx, 18f), Ui.dp(ctx, 4f), Ui.dp(ctx, 18f), Ui.dp(ctx, 12f)) }
        var dialog: AlertDialog? = null
        var themeChanged = false

        // ---------------------------------------------------------- Appearance
        section(ctx, box, "Appearance")
        val themes = Settings.allThemes()
        val themeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val themeScroll = android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(themeRow) }
        for (t in themes) themeRow.addView(themeChip(ctx, t, t.name == Settings.themeName) {
            Settings.themeName = t.name; themeChanged = true
            dialog?.dismiss()
        })
        box.addView(themeScroll, Ui.lp(Ui.MATCH, Ui.WRAP))
        val themeBtns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Ui.dp(ctx, 6f), 0, 0) }
        themeBtns.addView(Ui.button(ctx, "New custom theme…") { dialog?.dismiss(); editTheme(ctx, Settings.theme.copy(name = uniqueName("My Theme")), true, actions) },
            Ui.lp(0, Ui.WRAP, 1f).apply { rightMargin = Ui.dp(ctx, 6f) })
        if (Settings.customThemes().any { it.name == Settings.themeName }) {
            themeBtns.addView(Ui.button(ctx, "Edit current…") { dialog?.dismiss(); editTheme(ctx, Settings.theme, false, actions) }, Ui.lp(0, Ui.WRAP, 1f))
        }
        box.addView(themeBtns)

        // ---------------------------------------------------------- Camera
        section(ctx, box, "Camera")
        val flyIdx = flySpeeds.indexOfFirst { it == Settings.flySpeed }
        choice(ctx, box, "Fly speed", flySpeeds.map { "x" + fmtNum(it) }, if (flyIdx < 0) "x" + fmtNum(Settings.flySpeed) else null, flyIdx) { i ->
            Settings.flySpeed = flySpeeds[i]
        }
        slider(ctx, box, "Look sensitivity", 0.2f, 3f, Settings.cameraSensitivity) { Settings.cameraSensitivity = it }
        toggle(ctx, box, "Smooth camera movement", "Eases the camera in and out instead of starting and stopping instantly.", Settings.cameraSmoothing) { Settings.cameraSmoothing = it }

        // ---------------------------------------------------------- Script editor
        section(ctx, box, "Script Editor")
        slider(ctx, box, "Font size", 9f, 24f, Settings.editorFontSize, step = 1f) { Settings.editorFontSize = it }
        toggle(ctx, box, "Autocomplete", "Suggests keywords, globals, services, members and names from your place as you type.", Settings.autocomplete) { Settings.autocomplete = it }
        toggle(ctx, box, "Auto indent", null, Settings.autoIndent) { Settings.autoIndent = it }
        toggle(ctx, box, "Auto-close brackets and quotes", null, Settings.autoCloseBrackets) { Settings.autoCloseBrackets = it }

        // ---------------------------------------------------------- Viewport
        section(ctx, box, "Viewport & GUI Editing")
        toggle(ctx, box, "Show FPS", null, Settings.showFps) { Settings.showFps = it }
        toggle(ctx, box, "Lighting effects", "Bloom, blur, color correction and sun rays from Lighting (turn off if playtests are slow)", Settings.postEffects) { Settings.postEffects = it }
        toggle(ctx, box, "Terrain detail", "Textured-looking smooth terrain instead of flat colors (turn off on slow phones)", Settings.terrainDetail) { Settings.terrainDetail = it }
        choice(ctx, box, "Materials", listOf("2022 (Modern)", "Pre-2022"),
            "Which Roblox material textures parts use. A place can also ask for the old ones (MaterialService.Use2022Materials).",
            if (Settings.modernMaterials) 0 else 1) { Settings.modernMaterials = it == 0 }
        val dists = listOf("Automatic", "Low", "Medium", "High", "Ultra")
        choice(ctx, box, "Render distance", dists,
            "How far away small parts keep being drawn on big maps. Automatic adjusts to your device's frame rate (fast devices keep more loaded); Low helps slow tablets.",
            dists.indexOf(Settings.renderDistance).coerceAtLeast(0)) { Settings.renderDistance = dists[it] }
        toggle(ctx, box, "Smart guides when dragging GUIs", "Snaps GUI objects to the edges and centers of their parent and siblings.", Settings.guiSmartGuides) { Settings.guiSmartGuides = it }
        val grids = listOf(0, 1, 2, 4, 5, 8, 10, 16)
        choice(ctx, box, "GUI drag grid", grids.map { if (it == 0) "Off" else "$it px" }, null, grids.indexOf(Settings.guiGrid)) { Settings.guiGrid = grids[it] }
        // Plugin windows are made for desktop Studio; on a phone they're shrunk to fit.
        val scales = listOf(0f, 0.5f, 0.6f, 0.75f, 0.85f, 1f)
        choice(ctx, box, "Plugin UI size", scales.map { if (it == 0f) "Auto (fit screen)" else "${(it * 100).toInt()}%" }, null,
            scales.indexOf(Settings.pluginUiScale).coerceAtLeast(0)) { Settings.pluginUiScale = scales[it] }

        // ---------------------------------------------------------- Playtest
        section(ctx, box, "Playtest")
        toggle(ctx, box, "Hide windows while playtesting", "Gives the game the whole screen; your layout comes back when you stop.", Settings.hideWindowsInPlaytest) { Settings.hideWindowsInPlaytest = it }
        toggle(ctx, box, "Shift Lock Switch", "Shows the shift lock button on mobile (also in the in-game Roblox menu).", Settings.shiftLockSwitch) { Settings.shiftLockSwitch = it }
        choice(ctx, box, "Camera mode", listOf("Classic", "Follow"), null, if (Settings.cameraMode == "Classic") 0 else 1) { Settings.cameraMode = if (it == 0) "Classic" else "Follow" }
        choice(ctx, box, "Movement mode", listOf("Dynamic Thumbstick", "Thumbstick"), null, if (Settings.movementMode == "Thumbstick") 1 else 0) { Settings.movementMode = if (it == 1) "Thumbstick" else "Dynamic Thumbstick" }
        slider(ctx, box, "Camera sensitivity", 0.2f, 4f, Settings.playCameraSensitivity) { Settings.playCameraSensitivity = it }

        // ---------------------------------------------------------- Privacy
        section(ctx, box, "Privacy")
        box.addView(Ui.text(ctx, "Your Roblox sign-in and API key stay on this device: they're only ever sent to Roblox's own servers (over HTTPS), " +
            "are left out of Android backups, and scripts and plugins can't read them. Scripts and plugins must ask before connecting to any website.", 12f, Ui.DIM).apply { isSingleLine = false })
        val sites = com.exampletest.mobilestudiotest.net.WebPermissions.remembered(ctx)
        box.addView(Ui.text(ctx, if (sites.isEmpty()) "No websites allowed or blocked yet."
            else sites.entries.sortedBy { it.key }.joinToString("\n") { (h, ok) -> (if (ok) "Allowed: " else "Blocked: ") + h }, 12f, Ui.TEXT).apply {
            isSingleLine = false; setPadding(0, Ui.dp(ctx, 6f), 0, 0)
        })
        if (sites.isNotEmpty()) box.addView(Ui.button(ctx, "Forget website choices (ask again)") {
            com.exampletest.mobilestudiotest.net.WebPermissions.forgetAll(ctx); dialog?.dismiss()
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 6f) })

        // ---------------------------------------------------------- Icons
        section(ctx, box, "Icons")
        // Icon themes: a preview of a few icons in each look.
        val iconRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (opt in IconTheme.OPTIONS) {
            if (opt.name == "Classic" && !Icons.installed) continue
            val on = Settings.iconTheme == opt.name
            val chip = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL
                val p = Ui.dp(ctx, 6f); setPadding(p, p, p, p)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (on) Ui.SELECT else Ui.HOVER); cornerRadius = Ui.dp(ctx, 6f).toFloat()
                }
                setOnClickListener {
                    Settings.iconTheme = opt.name; themeChanged = true
                    for (i in 0 until iconRow.childCount) (iconRow.getChildAt(i).background as? android.graphics.drawable.GradientDrawable)?.setColor(Ui.HOVER)
                    (background as? android.graphics.drawable.GradientDrawable)?.setColor(Ui.SELECT)
                }
            }
            val icons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (n in listOf("Part_Block", "Move", "Toolbox", "Play")) {
                val d = BuiltinIcons.general(n) ?: continue
                if (opt.name != "Classic") d.colorFilter = IconTheme.filterFor(opt.name)
                icons.addView(android.widget.ImageView(ctx).apply { setImageDrawable(if (opt.name == "Classic") Icons.general(ctx, n) else d) },
                    LinearLayout.LayoutParams(Ui.dp(ctx, 22f), Ui.dp(ctx, 22f)).apply { marginEnd = Ui.dp(ctx, 3f) })
            }
            chip.addView(icons)
            chip.addView(Ui.text(ctx, opt.name, 11.5f).apply { gravity = android.view.Gravity.CENTER; setPadding(0, Ui.dp(ctx, 4f), 0, 0) })
            iconRow.addView(chip, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginEnd = Ui.dp(ctx, 6f) })
        }
        box.addView(android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(iconRow) }, Ui.lp(Ui.MATCH, Ui.WRAP))
        box.addView(Ui.text(ctx, IconTheme.OPTIONS.firstOrNull { it.name == Settings.iconTheme }?.description ?: "", 11f, Ui.DIM).apply { setPadding(0, Ui.dp(ctx, 4f), 0, 0) })
        box.addView(Ui.text(ctx, if (Icons.installed) "Icon pack imported (used by Colorful, Vibrant, Soft and Classic)." else "Using the built-in icons.", 12f, Ui.DIM).apply { isSingleLine = false })
        val iconBtns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Ui.dp(ctx, 6f), 0, 0) }
        iconBtns.addView(Ui.button(ctx, "Import icon pack (optional)…") { dialog?.dismiss(); onImportIcons() }, Ui.lp(0, Ui.WRAP, 1f).apply { rightMargin = Ui.dp(ctx, 6f) })
        if (onRemoveIcons != null && Icons.installed) iconBtns.addView(Ui.button(ctx, "Use built-in icons") { dialog?.dismiss(); onRemoveIcons() }, Ui.lp(0, Ui.WRAP, 1f))
        box.addView(iconBtns)

        dialog = Dialogs.themed(ctx).setTitle("Studio Settings").setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Done", null).create()
        dialog.setOnDismissListener { Settings.changed(); if (themeChanged) actions.applyThemeChange() }
        dialog.show()
    }

    private fun uniqueName(base: String): String {
        val names = Settings.allThemes().map { it.name }.toSet()
        if (base !in names) return base
        var i = 2
        while ("$base $i" in names) i++
        return "$base $i"
    }

    private fun section(ctx: Context, box: LinearLayout, title: String) {
        box.addView(Ui.text(ctx, title.uppercase(), 11f, if (Ui.LIGHT) Ui.ACCENT else 0xFF5AACFF.toInt(), true).apply { setPadding(0, Ui.dp(ctx, 14f), 0, Ui.dp(ctx, 4f)) })
    }

    private fun themeChip(ctx: Context, t: Theme, selected: Boolean, onClick: () -> Unit): View {
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(Ui.dp(ctx, 6f), Ui.dp(ctx, 6f), Ui.dp(ctx, 6f), Ui.dp(ctx, 4f))
            background = GradientDrawable().apply { setColor(0); cornerRadius = Ui.dp(ctx, 6f).toFloat(); setStroke(Ui.dp(ctx, 2f), if (selected) Ui.ACCENT else 0) }
            setOnClickListener { onClick() }
        }
        val preview = object : View(ctx) {
            override fun onDraw(c: android.graphics.Canvas) {
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                val w = width.toFloat(); val h = height.toFloat(); val r = 4f * resources.displayMetrics.density
                p.color = t.bg; c.drawRoundRect(0f, 0f, w, h, r, r, p)
                p.color = t.topbar; c.drawRect(0f, 0f, w, h * 0.18f, p)
                p.color = t.panel; c.drawRect(w * 0.68f, h * 0.18f, w, h, p)
                p.color = t.select; c.drawRect(w * 0.7f, h * 0.36f, w * 0.98f, h * 0.48f, p)
                p.color = t.text; c.drawRect(w * 0.72f, h * 0.58f, w * 0.94f, h * 0.62f, p); c.drawRect(w * 0.72f, h * 0.7f, w * 0.88f, h * 0.74f, p)
                p.color = t.accent; c.drawRect(w * 0.06f, h * 0.05f, w * 0.2f, h * 0.13f, p)
                p.style = android.graphics.Paint.Style.STROKE; p.color = t.border; p.strokeWidth = 1f
                c.drawRoundRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, r, r, p)
            }
        }
        v.addView(preview, LinearLayout.LayoutParams(Ui.dp(ctx, 84f), Ui.dp(ctx, 54f)))
        v.addView(Ui.text(ctx, t.name, 11f, Ui.TEXT).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dp(ctx, 3f), 0, 0) })
        return v
    }

    private fun row(ctx: Context, box: LinearLayout, label: String, hint: String?): LinearLayout {
        val outer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dp(ctx, 5f), 0, Ui.dp(ctx, 5f)) }
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(Ui.text(ctx, label, 14f, Ui.TEXT).apply { isSingleLine = false }, Ui.lp(0, Ui.WRAP, 1f))
        outer.addView(r)
        if (hint != null) outer.addView(Ui.text(ctx, hint, 11f, Ui.DIM).apply { isSingleLine = false })
        box.addView(outer)
        return r
    }

    private fun toggle(ctx: Context, box: LinearLayout, label: String, hint: String?, value: Boolean, onSet: (Boolean) -> Unit) {
        val r = row(ctx, box, label, hint)
        r.addView(Switch(ctx).apply { isChecked = value; setOnCheckedChangeListener { _, b -> onSet(b) } })
    }

    private fun slider(ctx: Context, box: LinearLayout, label: String, min: Float, max: Float, value: Float, step: Float = 0.1f, onSet: (Float) -> Unit) {
        val r = row(ctx, box, label, null)
        val valueText = Ui.text(ctx, fmtNum(value), 13f, Ui.DIM)
        r.addView(valueText)
        val steps = ((max - min) / step).toInt()
        val sb = SeekBar(ctx).apply {
            this.max = steps
            progress = ((value - min) / step).toInt().coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + p * step
                    valueText.text = fmtNum(v)
                    if (fromUser) onSet(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(sb, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    /** A row with the current value; tapping opens a list. [current] is the selected index, or [customLabel] when off-list. */
    private fun choice(ctx: Context, box: LinearLayout, label: String, items: List<String>, customLabel: String?, current: Int = -1, onSet: (Int) -> Unit) {
        val r = row(ctx, box, label, null)
        var idx = current
        val value = Ui.button(ctx, customLabel ?: items.getOrNull(idx) ?: items.firstOrNull() ?: "") {}
        value.setOnClickListener {
            Dialogs.themed(ctx).setTitle(label).setSingleChoiceItems(items.toTypedArray(), idx) { d, which ->
                idx = which; value.text = items[which]; onSet(which); d.dismiss()
            }.setNegativeButton("Cancel", null).show()
        }
        r.addView(value)
    }

    // ------------------------------------------------------------------ custom theme editor

    fun editTheme(ctx: Context, start: Theme, isNew: Boolean, actions: MainActions) {
        var t = start
        val oldName = start.name
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(ctx, 18f), Ui.dp(ctx, 6f), Ui.dp(ctx, 18f), Ui.dp(ctx, 8f)) }
        val name = Ui.input(ctx, t.name, "Theme name")
        box.addView(name)
        val lightRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.dp(ctx, 6f), 0, Ui.dp(ctx, 6f)) }
        lightRow.addView(Ui.text(ctx, "Light theme (dark text, light dialogs)", 13f), Ui.lp(0, Ui.WRAP, 1f))
        lightRow.addView(Switch(ctx).apply { isChecked = t.light; setOnCheckedChangeListener { _, b -> t = t.copy(light = b) } })
        box.addView(lightRow)
        val startRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val swatches = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun refresh() {
            swatches.removeAllViews()
            for ((slot, color) in t.colors()) {
                val r = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, Ui.dp(ctx, 4f), 0, Ui.dp(ctx, 4f))
                    background = Ui.rippleBg(ctx, 0)
                }
                r.addView(View(ctx).apply { background = GradientDrawable().apply { setColor(color); setStroke(Ui.dp(ctx, 1f), 0xFF888888.toInt()); cornerRadius = Ui.dp(ctx, 3f).toFloat() } },
                    Ui.lp(Ui.dp(ctx, 26f), Ui.dp(ctx, 20f)).apply { rightMargin = Ui.dp(ctx, 10f) })
                r.addView(Ui.text(ctx, slot, 13f), Ui.lp(0, Ui.WRAP, 1f))
                r.addView(Ui.text(ctx, String.format("#%06X", color and 0xFFFFFF), 12f, Ui.DIM))
                r.setOnClickListener {
                    Dialogs.colorPicker(ctx, slot, Color3(((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f)) { c ->
                        t = t.withColor(slot, (c as Color3).toArgb() or (0xFF shl 24)); refresh()
                    }
                }
                swatches.addView(r)
            }
        }
        for (base in Theme.builtIn) startRow.addView(Ui.button(ctx, "From ${base.name}") {
            t = base.copy(name = name.text.toString().ifBlank { t.name }); refresh()
        }.apply { textSize = 11f }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { rightMargin = Ui.dp(ctx, 4f) })
        box.addView(android.widget.HorizontalScrollView(ctx).apply { addView(startRow) })
        box.addView(swatches)
        refresh()
        val b = Dialogs.themed(ctx).setTitle(if (isNew) "New Theme" else "Edit Theme").setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Save & use") { _, _ ->
                val n = name.text.toString().trim().ifBlank { "Custom" }
                if (Theme.builtIn.any { it.name == n }) { android.widget.Toast.makeText(ctx, "\"$n\" is a built-in theme name; pick another", android.widget.Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val list = Settings.customThemes().filter { it.name != oldName && it.name != n }.toMutableList()
                list.add(t.copy(name = n))
                Settings.saveCustomThemes(list)
                Settings.themeName = n
                actions.applyThemeChange()
            }
            .setNegativeButton("Cancel", null)
        if (!isNew) b.setNeutralButton("Delete") { _, _ ->
            Settings.saveCustomThemes(Settings.customThemes().filter { it.name != oldName })
            if (Settings.themeName == oldName) Settings.themeName = "Dark"
            actions.applyThemeChange()
        }
        b.show()
    }

    /** Quick fly-speed chooser (ribbon button / viewport speed button). */
    fun pickFlySpeed(ctx: Context, onSet: () -> Unit) {
        val labels = flySpeeds.map { "x" + fmtNum(it) } + "Custom…"
        val cur = flySpeeds.indexOfFirst { it == Settings.flySpeed }
        Dialogs.themed(ctx).setTitle("Fly speed").setSingleChoiceItems(labels.toTypedArray(), cur) { d, i ->
            d.dismiss()
            if (i < flySpeeds.size) { Settings.flySpeed = flySpeeds[i]; onSet() }
            else Dialogs.prompt(ctx, "Fly speed multiplier (0.05 – 200)", fmtNum(Settings.flySpeed)) { s ->
                s.trim().toFloatOrNull()?.let { Settings.flySpeed = it.coerceIn(0.05f, 200f); onSet() }
            }
        }.setNegativeButton("Cancel", null).show()
    }

    @Suppress("unused")
    private fun label(ctx: Context, s: String): TextView = Ui.text(ctx, s, 13f)
}
