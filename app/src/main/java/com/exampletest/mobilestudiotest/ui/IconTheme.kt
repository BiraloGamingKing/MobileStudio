package com.exampletest.mobilestudiotest.ui

import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import com.exampletest.mobilestudiotest.core.Settings

/**
 * Icon themes (Studio Settings → Icons): one look applied to every icon in the app, built-in or
 * imported, as a color transform. Monochrome matches the flat single-color icons of Roblox
 * Studio's newer UI; Classic uses the imported icon pack when there is one.
 */
object IconTheme {
    class Option(val name: String, val description: String)

    val OPTIONS = listOf(
        Option("Colorful", "Studio's familiar colored icons"),
        Option("Monochrome", "Flat single-color icons, like Roblox Studio's new UI"),
        Option("Accent", "Two-tone icons in your theme's accent color"),
        Option("Vibrant", "Extra-saturated, punchy colors"),
        Option("Soft", "Muted pastel colors that are easy on the eyes"),
        Option("Classic", "Your imported icon pack (Studio Settings → Import icon pack)"),
    )

    @Volatile private var cached: Pair<String, ColorFilter?>? = null

    val name: String get() = Settings.iconTheme

    /** Whether imported pack icons should be used (they're used for Colorful too when present). */
    val usePack: Boolean get() = name != "Monochrome" && name != "Accent"

    /** The filter for the current theme (null: draw icons as they are). Cached per theme + UI colors. */
    fun filter(): ColorFilter? {
        val key = "$name:${Ui.TEXT}:${Ui.ACCENT}:${Ui.LIGHT}"
        cached?.let { if (it.first == key) return it.second }
        val f = build(name)
        cached = key to f
        return f
    }

    /** The filter a given theme would use (for previews in Settings). */
    fun filterFor(n: String): ColorFilter? = build(n)

    private fun build(n: String): ColorFilter? {
        val m = ColorMatrix()
        when (n) {
            "Monochrome" -> {
                // Luminance -> shades of the theme's text color (details stay as lighter/darker strokes).
                val c = Ui.TEXT
                val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
                val lo = if (Ui.LIGHT) 0.25f else 0.55f; val span = 1f - lo
                fun row(k: Float) = floatArrayOf(0.299f * span * k, 0.587f * span * k, 0.114f * span * k, 0f, 255f * lo * k)
                m.set(row(r) + row(g) + row(b) + floatArrayOf(0f, 0f, 0f, 1f, 0f))
            }
            "Accent" -> {
                // Duotone: dark parts in the accent color, light parts toward white.
                val c = Ui.ACCENT
                val ar = ((c shr 16) and 0xFF).toFloat(); val ag = ((c shr 8) and 0xFF).toFloat(); val ab = (c and 0xFF).toFloat()
                fun row(a: Float) = floatArrayOf(0.299f * (255f - a) / 255f, 0.587f * (255f - a) / 255f, 0.114f * (255f - a) / 255f, 0f, a)
                m.set(row(ar) + row(ag) + row(ab) + floatArrayOf(0f, 0f, 0f, 1f, 0f))
            }
            "Vibrant" -> m.setSaturation(1.6f)
            "Soft" -> {
                m.setSaturation(0.55f)
                val lift = ColorMatrix(floatArrayOf(0.85f, 0f, 0f, 0f, 30f, 0f, 0.85f, 0f, 0f, 30f, 0f, 0f, 0.85f, 0f, 30f, 0f, 0f, 0f, 1f, 0f))
                m.postConcat(lift)
            }
            else -> return null
        }
        return ColorMatrixColorFilter(m)
    }
}
