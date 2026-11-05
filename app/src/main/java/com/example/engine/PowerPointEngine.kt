package com.example.engine

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.core.DetectedFormat
import com.example.core.DocumentDetector
import com.example.core.OoxmlPackage
import com.example.core.SafeSaveManager
import com.example.core.SafeSaveResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

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
    val isHidden: Boolean = false,
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
    val activeSlideIndex: Int = 0,
    @Transient val sourcePackage: OoxmlPackage? = null
)

object PowerPointEngine {

    private const val SLIDE_WIDTH_EMU = 9144000f
    private const val SLIDE_HEIGHT_EMU = 5143500f

    fun loadPresentation(file: File): PresentationModel {
        val detected = DocumentDetector.detect(file).format
        return when (detected) {
            DetectedFormat.PPTX, DetectedFormat.PPSX, DetectedFormat.POTX -> readPptx(file)
            DetectedFormat.ODP -> readOdp(file)
            DetectedFormat.PPT, DetectedFormat.PPS, DetectedFormat.POT -> readLegacyPpt(file)
            else -> readPptx(file)
        }
    }

    fun savePresentation(file: File, presentation: PresentationModel, context: Context? = null): SafeSaveResult {
        val ext = file.extension.lowercase()
        return SafeSaveManager.saveAtomically(
            targetFile = file,
            context = context,
            validator = { OoxmlPackage.isValidZip(it) }
        ) { tempOut ->
            if (ext == "odp") {
                writeOdp(tempOut, presentation)
            } else {
                writePptx(tempOut, presentation)
            }
        }
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

    private fun readOdp(file: File): PresentationModel {
        val pkg = OoxmlPackage.readFrom(file)
        val slides = mutableListOf<SlideModel>()
        pkg.getEntry("content.xml")?.let { xmlBytes ->
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(xmlBytes.inputStream(), "UTF-8")
            var inPage = false
            val elements = mutableListOf<SlideElement>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name?.substringAfter(':') ?: ""
                when (event) {
                    XmlPullParser.START_TAG -> when (tag) {
                        "page" -> {
                            inPage = true
                            elements.clear()
                        }
                        "p" -> if (inPage) {
                            val txt = parser.nextText().trim()
                            if (txt.isNotEmpty()) {
                                val idx = elements.size
                                elements.add(
                                    SlideElement(
                                        type = SlideElementType.TEXT,
                                        xFraction = 0.08f,
                                        yFraction = (0.12f + idx * 0.22f).coerceAtMost(0.78f),
                                        widthFraction = 0.84f,
                                        heightFraction = 0.22f,
                                        text = txt,
                                        fontSizeSp = if (idx == 0) 24 else 16,
                                        isBold = idx == 0
                                    )
                                )
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> if (tag == "page" && inPage) {
                        inPage = false
                        slides.add(SlideModel(elements = elements.ifEmpty { listOf(SlideElement(text = "")) }))
                    }
                }
                event = parser.next()
            }
        }
        return PresentationModel(
            title = file.nameWithoutExtension,
            slides = slides.ifEmpty { listOf(SlideModel()) },
            sourcePackage = pkg
        )
    }

    private fun writeOdp(file: File, presentation: PresentationModel) {
        val pkg = presentation.sourcePackage?.copy() ?: OoxmlPackage()
        if (!pkg.hasEntry("mimetype")) {
            pkg.putEntry("mimetype", "application/vnd.oasis.opendocument.presentation".toByteArray(Charsets.UTF_8))
        }
        val pagesXml = presentation.slides.mapIndexed { idx, slide ->
            val frames = slide.elements.joinToString("\n") { el ->
                "      <draw:frame><draw:text-box><text:p>${escapeXml(el.text)}</text:p></draw:text-box></draw:frame>"
            }
            "    <draw:page draw:name=\"page${idx + 1}\">\n$frames\n    </draw:page>"
        }.joinToString("\n")
        val contentXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2">
  <office:body>
    <office:presentation>
$pagesXml
    </office:presentation>
  </office:body>
</office:document-content>"""
        pkg.putEntry("content.xml", contentXml.toByteArray(Charsets.UTF_8))
        pkg.writeTo(file)
    }

    private fun readPptx(file: File): PresentationModel {
        val pkg = OoxmlPackage.readFrom(file)
        val slideXmlMap = mutableMapOf<Int, ByteArray>()
        val notesXmlMap = mutableMapOf<Int, ByteArray>()

        pkg.listEntries("ppt/slides/slide")
            .filter { it.endsWith(".xml") }
            .forEach { name ->
                val num = name.removePrefix("ppt/slides/slide").removeSuffix(".xml").toIntOrNull()
                    ?: (slideXmlMap.size + 1)
                pkg.getEntry(name)?.let { slideXmlMap[num] = it }
            }

        pkg.listEntries("ppt/notesSlides/notesSlide")
            .filter { it.endsWith(".xml") }
            .forEach { name ->
                val num = name.removePrefix("ppt/notesSlides/notesSlide").removeSuffix(".xml").toIntOrNull()
                    ?: (notesXmlMap.size + 1)
                pkg.getEntry(name)?.let { notesXmlMap[num] = it }
            }

        val slides = slideXmlMap.keys.sorted().map { num ->
            val parsed = parseSlideXml(slideXmlMap[num]!!)
            val notes = notesXmlMap[num]?.let { extractAllText(it) } ?: ""
            parsed.copy(
                speakerNotes = notes,
                elements = if (parsed.elements.isEmpty()) listOf(SlideElement(text = "")) else parsed.elements
            )
        }

        return PresentationModel(
            title = file.nameWithoutExtension,
            slides = if (slides.isEmpty()) listOf(SlideModel()) else slides,
            sourcePackage = pkg
        )
    }

    private fun parseSlideXml(xmlBytes: ByteArray): SlideModel {
        val elements = mutableListOf<SlideElement>()
        var bgHex = "#FFFFFF"
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xmlBytes.inputStream(), "UTF-8")

        var inBg = false
        var inSp = false
        val sb = StringBuilder()
        var xFrac: Float? = null
        var yFrac: Float? = null
        var wFrac: Float? = null
        var hFrac: Float? = null

        var event = parser.eventType
        var idx = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfter(':') ?: ""
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (tag) {
                        "bg" -> inBg = true
                        "srgbClr" -> if (inBg) {
                            val v = parser.getAttributeValue(null, "val")
                            if (!v.isNullOrBlank() && v.length == 6) {
                                bgHex = "#$v"
                            }
                        }
                        "sp" -> {
                            inSp = true
                            sb.clear()
                            xFrac = null
                            yFrac = null
                            wFrac = null
                            hFrac = null
                        }
                        "off" -> if (inSp) {
                            val x = parser.getAttributeValue(null, "x")?.toFloatOrNull()
                            val y = parser.getAttributeValue(null, "y")?.toFloatOrNull()
                            if (x != null && y != null) {
                                xFrac = (x / SLIDE_WIDTH_EMU).coerceIn(0.02f, 0.9f)
                                yFrac = (y / SLIDE_HEIGHT_EMU).coerceIn(0.02f, 0.9f)
                            }
                        }
                        "ext" -> if (inSp) {
                            val cx = parser.getAttributeValue(null, "cx")?.toFloatOrNull()
                            val cy = parser.getAttributeValue(null, "cy")?.toFloatOrNull()
                            if (cx != null && cy != null) {
                                wFrac = (cx / SLIDE_WIDTH_EMU).coerceIn(0.1f, 0.96f)
                                hFrac = (cy / SLIDE_HEIGHT_EMU).coerceIn(0.08f, 0.9f)
                            }
                        }
                        "t" -> if (inSp) sb.append(parser.nextText())
                        "p" -> if (inSp && sb.isNotEmpty()) sb.append("\n")
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (tag == "bg") inBg = false
                    if (tag == "sp" && inSp) {
                        inSp = false
                        val txt = sb.toString().trim()
                        if (txt.isNotEmpty()) {
                            val isTitle = idx == 0
                            elements.add(
                                SlideElement(
                                    type = SlideElementType.TEXT,
                                    xFraction = xFrac ?: 0.08f,
                                    yFraction = yFrac ?: if (isTitle) 0.10f else (0.34f + (idx - 1) * 0.22f).coerceAtMost(0.75f),
                                    widthFraction = wFrac ?: 0.84f,
                                    heightFraction = hFrac ?: if (isTitle) 0.2f else 0.3f,
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
        return SlideModel(backgroundColorHex = bgHex, elements = elements)
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
        val pkg = presentation.sourcePackage?.copy() ?: OoxmlPackage()

        if (!pkg.hasEntry("[Content_Types].xml")) {
            val slideOverrides = presentation.slides.indices.joinToString("\n") { i ->
                """  <Override PartName="/ppt/slides/slide${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>"""
            }
            pkg.putEntry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
$slideOverrides
</Types>""".toByteArray(Charsets.UTF_8)
            )
        }

        if (!pkg.hasEntry("_rels/.rels")) {
            pkg.putEntry(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>""".toByteArray(Charsets.UTF_8)
            )
        }

        if (!pkg.hasEntry("ppt/_rels/presentation.xml.rels")) {
            val slideRels = presentation.slides.indices.joinToString("\n") { i ->
                """  <Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide${i + 1}.xml"/>"""
            }
            pkg.putEntry(
                "ppt/_rels/presentation.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
$slideRels
</Relationships>""".toByteArray(Charsets.UTF_8)
            )
        }

        if (!pkg.hasEntry("ppt/presentation.xml")) {
            val sldIdLst = presentation.slides.indices.joinToString("\n") { i ->
                """    <p:sldId id="${256 + i}" r:id="rId${i + 1}"/>"""
            }
            pkg.putEntry(
                "ppt/presentation.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:sldIdLst>
$sldIdLst
  </p:sldIdLst>
  <p:sldSz cx="9144000" cy="5143500" type="screen16x9"/>
</p:presentation>""".toByteArray(Charsets.UTF_8)
            )
        }

        presentation.slides.forEachIndexed { idx, slide ->
            pkg.putEntry(
                "ppt/slides/slide${idx + 1}.xml",
                buildSlideXml(slide).toByteArray(Charsets.UTF_8)
            )
        }

        pkg.writeTo(file)
    }

    private fun buildSlideXml(slide: SlideModel): String {
        val bgHexClean = slide.backgroundColorHex.removePrefix("#").take(6).ifEmpty { "FFFFFF" }
        val shapesXml = slide.elements.mapIndexed { idx, el ->
            val x = (el.xFraction * SLIDE_WIDTH_EMU).toLong()
            val y = (el.yFraction * SLIDE_HEIGHT_EMU).toLong()
            val cx = (el.widthFraction * SLIDE_WIDTH_EMU).toLong()
            val cy = (el.heightFraction * SLIDE_HEIGHT_EMU).toLong()
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
    <p:bg><p:bgPr><a:solidFill><a:srgbClr val="$bgHexClean"/></a:solidFill></p:bgPr></p:bg>
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
