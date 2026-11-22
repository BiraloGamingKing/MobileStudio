package com.exampletest.mobilestudiotest.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.exampletest.mobilestudiotest.model.ClassDb
import java.io.File
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile

/**
 * Studio icons. The app ships its own drawn icon set ([BuiltinIcons]), so nothing needs to be
 * downloaded. The Vanilla 3 icon pack can't be bundled (its license only allows personal use inside
 * Roblox Studio, with no redistribution), but a user who has it can still import their own copy and
 * it takes priority over the built-in icons.
 */
object Icons {
    private var dir: File? = null
    private val cache = HashMap<String, Bitmap?>()
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun init(ctx: Context) {
        if (dir == null) dir = File(ctx.filesDir, "iconpack")
    }

    val installed: Boolean get() = dir?.let { File(it, "general").isDirectory || File(it, "instance").isDirectory } == true

    /**
     * Icon from the imported pack for the current theme. Light themes use the pack's Light palette;
     * if the pack was imported without one, the built-in (theme-aware) icons are used instead,
     * because the Dark palette's pale icons disappear on a light background.
     */
    private fun load(kind: String, name: String): Bitmap? {
        // Icon themes that recolor everything (Monochrome, Accent) start from the built-in icons.
        if (!IconTheme.usePack) return null
        val light = Ui.LIGHT
        val key = (if (light) "light/" else "") + "$kind/$name"
        synchronized(cache) {
            if (cache.containsKey(key)) return cache[key]
            val f = dir?.let { File(it, "$key.png") }
            val bmp = if (f != null && f.isFile) try { BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null } else null
            cache[key] = bmp
            return bmp
        }
    }

    /** Instance icon, walking up the class hierarchy until the pack has one. */
    fun instanceBitmap(cls: String): Bitmap? {
        var c: String? = cls
        var guard = 0
        while (c != null && guard++ < 12) {
            load("instance", c)?.let { return it }
            if (c == "Instance") break
            c = ClassDb.superclass(c)
        }
        return null
    }

    fun generalBitmap(name: String): Bitmap? = load("general", name)
    fun uiBitmap(name: String): Bitmap? = load("ui", name)

    /** Order: imported icon pack (if the user added one) → built-in drawn icons → letter badge. */
    fun instance(ctx: Context, cls: String): Drawable =
        instanceBitmap(cls)?.let { pixelated(ctx, it) } ?: BuiltinIcons.instance(cls) ?: Badge(abbrev(cls), categoryColor(cls))

    fun general(ctx: Context, name: String, fallback: String = name): Drawable =
        generalBitmap(name)?.let { pixelated(ctx, it) } ?: BuiltinIcons.general(name)
        ?: (if (ClassDb.isServiceName(name) || name in knownClassIcons) BuiltinIcons.instance(name) else null)
        ?: Badge(abbrev(fallback), 0xFF5A6B7D.toInt())

    private val knownClassIcons = setOf("Script", "LocalScript", "ModuleScript", "Model", "Folder", "Plugin", "Humanoid", "Sound", "Decal")

    fun ui(ctx: Context, name: String, fallback: String): Drawable =
        uiBitmap(name)?.let { pixelated(ctx, it) } ?: BuiltinIcons.ui(name) ?: Badge(fallback, 0, plain = true)

    private fun pixelated(ctx: Context, b: Bitmap) = BitmapDrawable(ctx.resources, b).apply { isFilterBitmap = true; colorFilter = IconTheme.filter() }

    private fun abbrev(s: String): String {
        val caps = s.filter { it.isUpperCase() || it.isDigit() }
        return when {
            caps.length >= 2 -> caps.take(2)
            s.isNotEmpty() -> s.take(2).replaceFirstChar { it.uppercase() }
            else -> "?"
        }
    }

    private fun categoryColor(cls: String): Int = when {
        ClassDb.isA(cls, "LuaSourceContainer") -> 0xFF3A7BD5.toInt()
        ClassDb.isA(cls, "BasePart") -> 0xFF8C8C8C.toInt()
        ClassDb.isA(cls, "GuiBase2d") || ClassDb.isA(cls, "UIBase") -> 0xFF3FA66B.toInt()
        cls == "Model" || cls == "Folder" -> 0xFFC9A227.toInt()
        ClassDb.isA(cls, "ValueBase") -> 0xFF8E6CC7.toInt()
        ClassDb.isServiceName(cls) -> 0xFF4E6C88.toInt()
        else -> 0xFF6B6B6B.toInt()
    }

    /** Fallback icon: a rounded square with a short label. */
    class Badge(private val label: String, private val color: Int, private val plain: Boolean = false) : Drawable() {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        override fun draw(c: Canvas) {
            val b = bounds
            val r = RectF(b)
            val s = minOf(r.width(), r.height())
            r.inset((r.width() - s) / 2f + s * 0.06f, (r.height() - s) / 2f + s * 0.06f)
            bg.colorFilter = IconTheme.filter()
            if (!plain) { bg.color = color; c.drawRoundRect(r, s * 0.2f, s * 0.2f, bg) }
            tp.color = if (plain) Ui.TEXT else 0xFFFFFFFF.toInt()
            tp.textSize = s * (if (label.length > 1) 0.42f else 0.6f)
            c.drawText(label, r.centerX(), r.centerY() - (tp.descent() + tp.ascent()) / 2f, tp)
        }
        override fun setAlpha(alpha: Int) { bg.alpha = alpha; tp.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth() = 32
        override fun getIntrinsicHeight() = 32
    }

    /**
     * Imports a Vanilla icon pack zip (as downloaded from itch.io). Picks the Colourful/Dark palette
     * when present, otherwise the first Dark palette found. Returns the number of icons installed.
     */
    fun importZip(ctx: Context, input: InputStream): Int {
        init(ctx)
        val tmp = File(ctx.cacheDir, "iconpack_import.zip")
        tmp.outputStream().use { input.copyTo(it) }
        val rx = Regex("""(?:^|/)(?:([^/]+)/)?(Dark|Light)/RobloxCustom/(instance|general|ui)/(16x|32x)/200/([^/]+)\.png$""", RegexOption.IGNORE_CASE)
        val loose = Regex("""(?:^|/)(instance|general|ui)/(16x|32x)/200/([^/]+)\.png$""", RegexOption.IGNORE_CASE)
        var count = 0
        ZipFile(tmp).use { zip ->
            class E(val palette: String, val theme: String, val kind: String, val res: String, val name: String, val entry: java.util.zip.ZipEntry)
            val all = ArrayList<E>()
            for (e in zip.entries()) {
                if (e.isDirectory) continue
                val n = e.name.replace('\\', '/')
                val m = rx.find(n)
                if (m != null) {
                    all.add(E(m.groupValues[1], m.groupValues[2], m.groupValues[3].lowercase(), m.groupValues[4].lowercase(), m.groupValues[5], e))
                } else loose.find(n)?.let { l -> all.add(E("", "Dark", l.groupValues[1].lowercase(), l.groupValues[2].lowercase(), l.groupValues[3], e)) }
            }
            if (all.isEmpty()) throw IllegalArgumentException("No Vanilla icons found in this zip. Pick the \"Vanilla 3 for Roblox Studio\" zip from itch.io.")
            val dark = all.filter { it.theme.equals("Dark", true) }.ifEmpty { all }
            val palettes = dark.map { it.palette }.distinct()
            val palette = palettes.firstOrNull { it.equals("Colourful", true) } ?: palettes.first()
            val out = File(ctx.filesDir, "iconpack.new")
            out.deleteRecursively()
            // Dark palette at the top level; the Light palette (for light Studio themes) under light/.
            val sets = listOf("" to dark.filter { it.palette == palette },
                "light/" to all.filter { it.theme.equals("Light", true) && it.palette == palette })
            for ((prefix, chosen) in sets) for (kind in listOf("instance", "general", "ui")) {
                val ofKind = chosen.filter { it.kind == kind }
                if (ofKind.isEmpty()) continue
                // General (ribbon) icons look best at 32x; instance/ui icons only come in 16x.
                val best = ofKind.groupBy { it.name }.mapValues { (_, v) -> v.firstOrNull { it.res == "32x" } ?: v.first() }
                val d = File(out, prefix + kind).also { it.mkdirs() }
                for ((name, e) in best) {
                    zip.getInputStream(e.entry).use { ins -> File(d, "$name.png").outputStream().use { ins.copyTo(it) } }
                    if (prefix.isEmpty()) count++
                }
            }
            val target = File(ctx.filesDir, "iconpack")
            target.deleteRecursively()
            out.renameTo(target)
            dir = target
        }
        tmp.delete()
        synchronized(cache) { cache.clear() }
        listeners.forEach { it() }
        return count
    }

    fun remove(ctx: Context) {
        File(ctx.filesDir, "iconpack").deleteRecursively()
        synchronized(cache) { cache.clear() }
        listeners.forEach { it() }
    }
}
