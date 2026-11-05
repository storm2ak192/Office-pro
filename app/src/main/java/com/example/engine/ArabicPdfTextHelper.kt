package com.example.engine

import android.content.Context
import com.example.R
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.text.Bidi

/**
 * Provides Unicode/Arabic font loading (Cairo TTF) and Arabic contextual shaping + Bidi
 * visual reordering for PdfBox-Android content streams.
 */
object ArabicPdfTextHelper {

    fun loadUnicodeFont(context: Context?, doc: PDDocument): PDFont {
        if (context != null) {
            val loaded = runCatching {
                context.resources.openRawResource(R.font.cairo).use { stream ->
                    PDType0Font.load(doc, stream, true)
                }
            }.getOrNull()
            if (loaded != null) return loaded
        }
        return PDType1Font.HELVETICA
    }

    fun containsArabic(text: String): Boolean =
        text.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\uFB50'..'\uFEFF' }

    /**
     * Shapes Arabic letters into Presentation Forms-B and reorders Bidi runs
     * into visual Left-to-Right order for PDF `showText`.
     */
    fun prepareForPdfShowText(text: String, font: PDFont): String {
        if (font is PDType1Font) {
            // Standard Type1 fonts only support WinAnsi (ASCII/Latin-1)
            return text.map { ch ->
                if (ch.code in 32..255) ch else '?'
            }.joinToString("")
        }
        if (!containsArabic(text)) return text

        val shaped = shapeArabic(text)
        return reorderBidiVisual(shaped)
    }

    private fun reorderBidiVisual(input: String): String {
        return runCatching {
            val bidi = Bidi(input, Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT)
            val runCount = bidi.runCount
            if (runCount <= 1) {
                return if (bidi.baseIsLeftToRight()) input else input.reversed()
            }
            val runs = (0 until runCount).map { idx ->
                val start = bidi.getRunStart(idx)
                val limit = bidi.getRunLimit(idx)
                val level = bidi.getRunLevel(idx)
                val sub = input.substring(start, limit)
                if (level % 2 == 1) sub.reversed() else sub
            }
            if (bidi.baseIsLeftToRight()) {
                runs.joinToString("")
            } else {
                runs.reversed().joinToString("")
            }
        }.getOrDefault(input)
    }

    // Arabic contextual forms: [isolated, final, initial, medial]
    private val arabicForms: Map<Char, CharArray> = mapOf(
        '\u0621' to charArrayOf('\uFE80', '\uFE80', '\uFE80', '\uFE80'), // Hamza
        '\u0622' to charArrayOf('\uFE81', '\uFE82', '\uFE81', '\uFE82'), // Alef Madda
        '\u0623' to charArrayOf('\uFE83', '\uFE84', '\uFE83', '\uFE84'), // Alef Hamza Above
        '\u0624' to charArrayOf('\uFE85', '\uFE86', '\uFE85', '\uFE86'), // Waw Hamza
        '\u0625' to charArrayOf('\uFE87', '\uFE88', '\uFE87', '\uFE88'), // Alef Hamza Below
        '\u0626' to charArrayOf('\uFE89', '\uFE8A', '\uFE8B', '\uFE8C'), // Yeh Hamza
        '\u0627' to charArrayOf('\uFE8D', '\uFE8E', '\uFE8D', '\uFE8E'), // Alef
        '\u0628' to charArrayOf('\uFE8F', '\uFE90', '\uFE91', '\uFE92'), // Beh
        '\u0629' to charArrayOf('\uFE93', '\uFE94', '\uFE93', '\uFE94'), // Teh Marbuta
        '\u062A' to charArrayOf('\uFE95', '\uFE96', '\uFE97', '\uFE98'), // Teh
        '\u062B' to charArrayOf('\uFE99', '\uFE9A', '\uFE9B', '\uFE9C'), // Theh
        '\u062C' to charArrayOf('\uFE9D', '\uFE9E', '\uFE9F', '\uFEA0'), // Jeem
        '\u062D' to charArrayOf('\uFEA1', '\uFEA2', '\uFEA3', '\uFEA4'), // Hah
        '\u062E' to charArrayOf('\uFEA5', '\uFEA6', '\uFEA7', '\uFEA8'), // Khah
        '\u062F' to charArrayOf('\uFEA9', '\uFEAA', '\uFEA9', '\uFEAA'), // Dal
        '\u0630' to charArrayOf('\uFEAB', '\uFEAC', '\uFEAB', '\uFEAC'), // Thal
        '\u0631' to charArrayOf('\uFEAD', '\uFEAE', '\uFEAD', '\uFEAE'), // Reh
        '\u0632' to charArrayOf('\uFEAF', '\uFEB0', '\uFEAF', '\uFEB0'), // Zain
        '\u0633' to charArrayOf('\uFEB1', '\uFEB2', '\uFEB3', '\uFEB4'), // Seen
        '\u0634' to charArrayOf('\uFEB5', '\uFEB6', '\uFEB7', '\uFEB8'), // Sheen
        '\u0635' to charArrayOf('\uFEB9', '\uFEBA', '\uFEBB', '\uFEBC'), // Sad
        '\u0636' to charArrayOf('\uFEBD', '\uFEBE', '\uFEBF', '\uFEC0'), // Dad
        '\u0637' to charArrayOf('\uFEC1', '\uFEC2', '\uFEC3', '\uFEC4'), // Tah
        '\u0638' to charArrayOf('\uFEC5', '\uFEC6', '\uFEC7', '\uFEC8'), // Zah
        '\u0639' to charArrayOf('\uFEC9', '\uFECA', '\uFECB', '\uFECC'), // Ain
        '\u063A' to charArrayOf('\uFECD', '\uFECE', '\uFECF', '\uFED0'), // Ghain
        '\u0640' to charArrayOf('\u0640', '\u0640', '\u0640', '\u0640'), // Tatweel
        '\u0641' to charArrayOf('\uFED1', '\uFED2', '\uFED3', '\uFED4'), // Feh
        '\u0642' to charArrayOf('\uFED5', '\uFED6', '\uFED7', '\uFED8'), // Qaf
        '\u0643' to charArrayOf('\uFED9', '\uFEDA', '\uFEDB', '\uFEDC'), // Kaf
        '\u0644' to charArrayOf('\uFEDD', '\uFEDE', '\uFEDF', '\uFEE0'), // Lam
        '\u0645' to charArrayOf('\uFEE1', '\uFEE2', '\uFEE3', '\uFEE4'), // Meem
        '\u0646' to charArrayOf('\uFEE5', '\uFEE6', '\uFEE7', '\uFEE8'), // Noon
        '\u0647' to charArrayOf('\uFEE9', '\uFEEA', '\uFEEB', '\uFEEC'), // Heh
        '\u0648' to charArrayOf('\uFEED', '\uFEEE', '\uFEED', '\uFEEE'), // Waw
        '\u0649' to charArrayOf('\uFEEF', '\uFEF0', '\uFEEF', '\uFEF0'), // Alef Maksura
        '\u064A' to charArrayOf('\uFEF1', '\uFEF2', '\uFEF3', '\uFEF4')  // Yeh
    )

    private val nonConnectingAfter = setOf(
        '\u0621', '\u0622', '\u0623', '\u0624', '\u0625', '\u0627',
        '\u0629', '\u062F', '\u0630', '\u0631', '\u0632', '\u0648', '\u0649'
    )

    private fun isDiacritic(ch: Char): Boolean = ch in '\u064B'..'\u065F' || ch == '\u0670'

    fun shapeArabic(input: String): String {
        val sb = StringBuilder(input.length)
        val n = input.length
        var i = 0
        while (i < n) {
            val ch = input[i]
            if (isDiacritic(ch)) {
                sb.append(ch)
                i++
                continue
            }
            val forms = arabicForms[ch]
            if (forms == null) {
                sb.append(ch)
                i++
                continue
            }

            // Find previous base char
            var prevIdx = i - 1
            while (prevIdx >= 0 && isDiacritic(input[prevIdx])) prevIdx--
            val prevChar = if (prevIdx >= 0) input[prevIdx] else null
            val connectsPrev = prevChar != null && arabicForms.containsKey(prevChar) && !nonConnectingAfter.contains(prevChar)

            // Find next base char
            var nextIdx = i + 1
            while (nextIdx < n && isDiacritic(input[nextIdx])) nextIdx++
            val nextChar = if (nextIdx < n) input[nextIdx] else null
            val connectsNext = nextChar != null && arabicForms.containsKey(nextChar) && !nonConnectingAfter.contains(ch)

            // Handle Lam-Alef ligatures
            if (ch == '\u0644' && nextChar != null) {
                val ligature = when (nextChar) {
                    '\u0622' -> if (connectsPrev) '\uFEF6' else '\uFEF5'
                    '\u0623' -> if (connectsPrev) '\uFEF8' else '\uFEF7'
                    '\u0625' -> if (connectsPrev) '\uFEFA' else '\uFEF9'
                    '\u0627' -> if (connectsPrev) '\uFEFC' else '\uFEFB'
                    else -> null
                }
                if (ligature != null) {
                    sb.append(ligature)
                    i = nextIdx + 1
                    continue
                }
            }

            val formIndex = when {
                connectsPrev && connectsNext -> 3 // medial
                connectsPrev -> 1 // final
                connectsNext -> 2 // initial
                else -> 0 // isolated
            }
            sb.append(forms[formIndex])
            i++
        }
        return sb.toString()
    }
}
