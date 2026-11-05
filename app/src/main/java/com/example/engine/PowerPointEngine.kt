package com.example.engine

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.serialization.Serializable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
enum class SlideElementType { TEXT, SHAPE, TABLE, CHART, IMAGE }

@Serializable
enum class SlideShapeType { RECTANGLE, ROUNDED_RECT, CIRCLE, ARROW, STAR }

@Serializable
enum class SlideTransition { NONE, FADE, SLIDE, ZOOM }

@Serializable
enum class ElementAnimation { NONE, FADE_IN, SLIDE_UP, ZOOM_IN }

@Serializable
data class SlideElement(
    val id: String = UUID.randomUUID().toString(),
    val type: SlideElementType = SlideElementType.TEXT,
    val xFraction: Float = 0.1f,
    val yFraction: Float = 0.15f,
    val widthFraction: Float = 0.8f,
    val heightFraction: Float = 0.2f,
    val text: String = "",
    val fontSizeSp: Int = 20,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val textColorHex: String = "#0F172A",
    val fillColorHex: String = "#DBEAFE",
    val shapeType: SlideShapeType = SlideShapeType.ROUNDED_RECT,
    val animation: ElementAnimation = ElementAnimation.NONE,
    val tableRows: List<List<String>> = listOf(listOf("بند", "القيمة"), listOf("عنصر 1", "100")),
    val chartValues: List<Pair<String, Float>> = listOf("Q1" to 40f, "Q2" to 75f, "Q3" to 60f, "Q4" to 90f),
    val imageBase64: String? = null
)

@Serializable
data class SlideModel(
    val id: String = UUID.randomUUID().toString(),
    val backgroundColorHex: String = "#FFFFFF",
    val transition: SlideTransition = SlideTransition.FADE,
    val speakerNotes: String = "",
    val elements: List<SlideElement> = listOf(
        SlideElement(
            type = SlideElementType.TEXT,
            xFraction = 0.08f,
            yFraction = 0.12f,
            widthFraction = 0.84f,
            heightFraction = 0.22f,
            text = "عنوان الشريحة",
            fontSizeSp = 26,
            isBold = true,
            textColorHex = "#1E3A8A"
        ),
        SlideElement(
            type = SlideElementType.TEXT,
            xFraction = 0.08f,
            yFraction = 0.38f,
            widthFraction = 0.84f,
            heightFraction = 0.45f,
            text = "اضغط هنا لإضافة المحتوى أو النقاط الرئيسية...",
            fontSizeSp = 16,
            textColorHex = "#334155"
        )
    )
)

@Serializable
data class PresentationModel(
    val title: String = "عرض تقديمي جديد",
    val slides: List<SlideModel> = listOf(SlideModel()),
    val activeSlideIndex: Int = 0
)

object PowerPointEngine {

    fun loadPresentation(file: File): PresentationModel {
        return when (file.extension.lowercase()) {
            "pptx" -> readPptx(file)
            "ppt" -> readLegacyPpt(file)
            else -> readPptx(file)
        }
    }

    fun savePresentation(file: File, presentation: PresentationModel) {
        writePptx(file, presentation)
    }

    private fun readLegacyPpt(file: File): PresentationModel {
        val bytes = file.readBytes()
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < bytes.size) {
            val low = bytes[i].toInt() and 0xFF
            val high = bytes[i + 1].toInt() and 0xFF
            val code = (high shl 8) or low
            if ((code in 0x0600..0x06FF) || (code in 0x0020..0x007E)) {
                sb.append(code.toChar())
                i += 2
            } else {
                if (sb.length >= 3) lines.add(sb.toString().trim())
                sb.clear()
                i++
            }
        }
        val slides = if (lines.isEmpty()) {
            listOf(SlideModel())
        } else {
            lines.chunked(4).map { chunk ->
                SlideModel(
                    elements = listOf(
                        SlideElement(
                            type = SlideElementType.TEXT,
                            xFraction = 0.08f,
                            yFraction = 0.15f,
                            widthFraction = 0.84f,
                            heightFraction = 0.7f,
                            text = chunk.joinToString("\n"),
                            fontSizeSp = 18
                        )
                    )
                )
            }
        }
        return PresentationModel(title = file.nameWithoutExtension, slides = slides)
    }

    private fun readPptx(file: File): PresentationModel {
        val slideXmlMap = mutableMapOf<Int, ByteArray>()
        val notesXmlMap = mutableMapOf<Int, ByteArray>()

        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name.startsWith("ppt/slides/slide") && entry.name.endsWith(".xml") -> {
                        val num = entry.name.removePrefix("ppt/slides/slide")
                            .removeSuffix(".xml").toIntOrNull() ?: (slideXmlMap.size + 1)
                        slideXmlMap[num] = zip.readBytes()
                    }
                    entry.name.startsWith("ppt/notesSlides/notesSlide") && entry.name.endsWith(".xml") -> {
                        val num = entry.name.removePrefix("ppt/notesSlides/notesSlide")
                            .removeSuffix(".xml").toIntOrNull() ?: (notesXmlMap.size + 1)
                        notesXmlMap[num] = zip.readBytes()
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val slides = slideXmlMap.keys.sorted().map { num ->
            val elements = parseSlideXml(slideXmlMap[num]!!)
            val notes = notesXmlMap[num]?.let { extractAllText(it) } ?: ""
            SlideModel(
                speakerNotes = notes,
                elements = if (elements.isEmpty()) listOf(SlideElement(text = "")) else elements
            )
        }

        return PresentationModel(
            title = file.nameWithoutExtension,
            slides = if (slides.isEmpty()) listOf(SlideModel()) else slides
        )
    }

    private fun parseSlideXml(xmlBytes: ByteArray): List<SlideElement> {
        val elements = mutableListOf<SlideElement>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xmlBytes.inputStream(), "UTF-8")

        var inSp = false
        val sb = StringBuilder()
        var event = parser.eventType
        var idx = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfter(':') ?: ""
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (tag == "sp") {
                        inSp = true
                        sb.clear()
                    } else if (tag == "t" && inSp) {
                        sb.append(parser.nextText())
                    } else if (tag == "p" && inSp && sb.isNotEmpty()) {
                        sb.append("\n")
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (tag == "sp" && inSp) {
                        inSp = false
                        val txt = sb.toString().trim()
                        if (txt.isNotEmpty()) {
                            val isTitle = idx == 0
                            elements.add(
                                SlideElement(
                                    type = SlideElementType.TEXT,
                                    xFraction = 0.08f,
                                    yFraction = if (isTitle) 0.10f else (0.34f + (idx - 1) * 0.22f).coerceAtMost(0.75f),
                                    widthFraction = 0.84f,
                                    heightFraction = if (isTitle) 0.2f else 0.3f,
                                    text = txt,
                                    fontSizeSp = if (isTitle) 24 else 16,
                                    isBold = isTitle
                                )
                            )
                            idx++
                        }
                    }
                }
            }
            event = parser.next()
        }
        return elements
    }

    private fun extractAllText(xmlBytes: ByteArray): String {
        val sb = StringBuilder()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xmlBytes.inputStream(), "UTF-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name?.substringAfter(':') == "t") {
                sb.append(parser.nextText()).append(" ")
            }
            event = parser.next()
        }
        return sb.toString().trim()
    }

    private fun writePptx(file: File, presentation: PresentationModel) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            val slideOverrides = presentation.slides.indices.joinToString("\n") { i ->
                """  <Override PartName="/ppt/slides/slide${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>"""
            }
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-officedocument.package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
$slideOverrides
</Types>""".toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>""".toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()

            val slideRels = presentation.slides.indices.joinToString("\n") { i ->
                """  <Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide${i + 1}.xml"/>"""
            }
            zip.putNextEntry(ZipEntry("ppt/_rels/presentation.xml.rels"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
$slideRels
</Relationships>""".toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()

            val sldIdLst = presentation.slides.indices.joinToString("\n") { i ->
                """    <p:sldId id="${256 + i}" r:id="rId${i + 1}"/>"""
            }
            zip.putNextEntry(ZipEntry("ppt/presentation.xml"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:sldIdLst>
$sldIdLst
  </p:sldIdLst>
  <p:sldSz cx="9144000" cy="5143500" type="screen16x9"/>
</p:presentation>""".toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()

            presentation.slides.forEachIndexed { idx, slide ->
                zip.putNextEntry(ZipEntry("ppt/slides/slide${idx + 1}.xml"))
                zip.write(buildSlideXml(slide).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun buildSlideXml(slide: SlideModel): String {
        val shapesXml = slide.elements.mapIndexed { idx, el ->
            val x = (el.xFraction * 9144000).toLong()
            val y = (el.yFraction * 5143500).toLong()
            val cx = (el.widthFraction * 9144000).toLong()
            val cy = (el.heightFraction * 5143500).toLong()
            val textContent = when (el.type) {
                SlideElementType.TABLE -> el.tableRows.joinToString("\n") { it.joinToString(" | ") }
                SlideElementType.CHART -> el.chartValues.joinToString(", ") { "${it.first}: ${it.second}" }
                else -> el.text
            }
            """      <p:sp>
        <p:nvSpPr><p:cNvPr id="${idx + 2}" name="Shape ${idx + 1}"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
        <p:spPr>
          <a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>
          <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
        </p:spPr>
        <p:txBody>
          <a:bodyPr/><a:lstStyle/>
          <a:p><a:r><a:t>${escapeXml(textContent)}</a:t></a:r></a:p>
        </p:txBody>
      </p:sp>"""
        }.joinToString("\n")

        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
      <p:grpSpPr/>
$shapesXml
    </p:spTree>
  </p:cSld>
</p:sld>"""
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    fun exportToPdf(presentation: PresentationModel, outPdf: File) {
        val pdf = PdfDocument()
        val w = 960
        val h = 540
        presentation.slides.forEachIndexed { idx, slide ->
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, idx + 1).create())
            val canvas = page.canvas
            val bgPaint = Paint().apply {
                color = runCatching { Color.parseColor(slide.backgroundColorHex) }.getOrDefault(Color.WHITE)
            }
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

            slide.elements.forEach { el ->
                val left = el.xFraction * w
                val top = el.yFraction * h
                val elW = (el.widthFraction * w).toInt().coerceAtLeast(40)
                val elH = el.heightFraction * h

                if (el.type == SlideElementType.SHAPE) {
                    val fillPaint = Paint().apply {
                        color = runCatching { Color.parseColor(el.fillColorHex) }.getOrDefault(Color.LTGRAY)
                    }
                    canvas.drawRoundRect(left, top, left + elW, top + elH, 16f, 16f, fillPaint)
                }

                val txt = when (el.type) {
                    SlideElementType.TABLE -> el.tableRows.joinToString("\n") { it.joinToString("  |  ") }
                    SlideElementType.CHART -> el.chartValues.joinToString("  •  ") { "${it.first}: ${it.second.toInt()}" }
                    else -> el.text
                }
                if (txt.isNotBlank()) {
                    val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = el.fontSizeSp.toFloat()
                        typeface = if (el.isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        color = runCatching { Color.parseColor(el.textColorHex) }.getOrDefault(Color.BLACK)
                    }
                    val sl = StaticLayout.Builder.obtain(txt, 0, txt.length, tp, elW)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .build()
                    canvas.save()
                    canvas.translate(left + 8f, top + 8f)
                    sl.draw(canvas)
                    canvas.restore()
                }
            }
            pdf.finishPage(page)
        }
        FileOutputStream(outPdf).use { pdf.writeTo(it) }
        pdf.close()
    }
}
