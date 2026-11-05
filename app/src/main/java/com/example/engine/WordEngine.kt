package com.example.engine

import android.graphics.Canvas
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
enum class WordAlignment { RIGHT, CENTER, LEFT, JUSTIFY }

@Serializable
enum class WordStyle { NORMAL, HEADING_1, HEADING_2, HEADING_3, QUOTE }

@Serializable
enum class WordListType { NONE, BULLET, NUMBERED }

@Serializable
enum class PageSizeType(val widthPt: Int, val heightPt: Int, val label: String) {
    A4(595, 842, "A4"),
    LETTER(612, 792, "Letter")
}

@Serializable
data class WordParagraph(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "",
    val style: WordStyle = WordStyle.NORMAL,
    val alignment: WordAlignment = WordAlignment.RIGHT,
    val isRtl: Boolean = true,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val isStrikethrough: Boolean = false,
    val fontFamily: String = "Cairo",
    val fontSizeSp: Int = 14,
    val textColorHex: String = "#0F172A",
    val highlightColorHex: String? = null,
    val listType: WordListType = WordListType.NONE,
    val hyperlinkUrl: String? = null,
    val imageBase64: String? = null
)

@Serializable
data class WordTableCell(
    val text: String = "",
    val colSpan: Int = 1,
    val isHeader: Boolean = false
)

@Serializable
data class WordTable(
    val id: String = UUID.randomUUID().toString(),
    val afterParagraphIndex: Int = 0,
    val rows: List<List<WordTableCell>> = listOf(
        listOf(WordTableCell("العمود 1", isHeader = true), WordTableCell("العمود 2", isHeader = true)),
        listOf(WordTableCell(""), WordTableCell(""))
    )
)

@Serializable
data class WordShape(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "شكل توضيحي",
    val shapeType: String = "RECTANGLE",
    val fillColorHex: String = "#DBEAFE",
    val borderColorHex: String = "#1D4ED8"
)

@Serializable
data class WordDocumentModel(
    val title: String = "مستند جديد",
    val pageSize: PageSizeType = PageSizeType.A4,
    val isLandscape: Boolean = false,
    val marginPt: Int = 48,
    val lineSpacingMultiplier: Float = 1.25f,
    val paragraphSpacingPt: Int = 8,
    val headerText: String = "",
    val footerText: String = "",
    val showPageNumbers: Boolean = true,
    val paragraphs: List<WordParagraph> = listOf(WordParagraph(text = "")),
    val tables: List<WordTable> = emptyList(),
    val shapes: List<WordShape> = emptyList()
)

object WordEngine {

    fun loadDocument(file: File): WordDocumentModel {
        return when (file.extension.lowercase()) {
            "docx" -> readDocx(file)
            "rtf" -> readRtf(file)
            "txt" -> readTxt(file)
            "doc" -> readLegacyDoc(file)
            else -> readTxt(file)
        }
    }

    fun saveDocument(file: File, doc: WordDocumentModel) {
        when (file.extension.lowercase()) {
            "docx" -> writeDocx(file, doc)
            "rtf" -> writeRtf(file, doc)
            "txt" -> writeTxt(file, doc)
            else -> writeDocx(file, doc)
        }
    }

    private fun readTxt(file: File): WordDocumentModel {
        val content = file.readText(Charsets.UTF_8).removeSuffix("\n")
        val lines = content.split("\n")
        val paragraphs = if (lines.isEmpty()) {
            listOf(WordParagraph())
        } else {
            lines.map { line ->
                val isArabic = line.any { it in '\u0600'..'\u06FF' }
                WordParagraph(
                    text = line.trimEnd('\r'),
                    isRtl = isArabic,
                    alignment = if (isArabic) WordAlignment.RIGHT else WordAlignment.LEFT
                )
            }
        }
        return WordDocumentModel(title = file.nameWithoutExtension, paragraphs = paragraphs)
    }

    private fun writeTxt(file: File, doc: WordDocumentModel) {
        val sb = StringBuilder()
        doc.paragraphs.forEachIndexed { idx, p ->
            if (p.listType == WordListType.BULLET) sb.append("• ")
            else if (p.listType == WordListType.NUMBERED) sb.append("${idx + 1}. ")
            sb.append(p.text)
            if (!p.hyperlinkUrl.isNullOrBlank()) sb.append(" (${p.hyperlinkUrl})")
            sb.append("\n")
        }
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun readRtf(file: File): WordDocumentModel {
        val raw = file.readText(Charsets.UTF_8)
        val paragraphs = mutableListOf<WordParagraph>()
        val current = StringBuilder()
        var isBold = false
        var isItalic = false
        var isUnderline = false
        var align = WordAlignment.RIGHT

        var i = 0
        while (i < raw.length) {
            if (raw.startsWith("{\\fonttbl", i) || raw.startsWith("{\\colortbl", i) || raw.startsWith("{\\stylesheet", i)) {
                var depth = 0
                while (i < raw.length) {
                    if (raw[i] == '{') depth++
                    else if (raw[i] == '}') {
                        depth--
                        if (depth <= 0) { i++; break }
                    }
                    i++
                }
                continue
            }
            val c = raw[i]
            if (c == '\\') {
                if (i + 2 < raw.length && raw[i + 1] == 'u' && (raw[i + 2].isDigit() || raw[i + 2] == '-')) {
                    // Unicode escape \uN?
                    val endIdx = raw.indexOf('?', i + 2)
                    if (endIdx != -1) {
                        val code = raw.substring(i + 2, endIdx).trim().toIntOrNull()
                        if (code != null) {
                            current.append(code.toChar())
                            i = endIdx + 1
                            continue
                        }
                    }
                }
                val match = Regex("^\\\\([a-zA-Z]+)(-?\\d+)?\\s?").find(raw.substring(i))
                if (match != null) {
                    val cmd = match.groupValues[1]
                    val param = match.groupValues[2]
                    when (cmd) {
                        "par", "line" -> {
                            val text = current.toString()
                            paragraphs.add(
                                WordParagraph(
                                    text = text,
                                    isBold = isBold,
                                    isItalic = isItalic,
                                    isUnderline = isUnderline,
                                    alignment = align,
                                    isRtl = align == WordAlignment.RIGHT
                                )
                            )
                            current.clear()
                        }
                        "b" -> isBold = param != "0"
                        "i" -> isItalic = param != "0"
                        "ul" -> isUnderline = param != "0"
                        "ulnone" -> isUnderline = false
                        "qr" -> align = WordAlignment.RIGHT
                        "qc" -> align = WordAlignment.CENTER
                        "ql" -> align = WordAlignment.LEFT
                        "qj" -> align = WordAlignment.JUSTIFY
                    }
                    i += match.value.length
                    continue
                }
            } else if (c != '{' && c != '}' && c != '\r' && c != '\n') {
                current.append(c)
            }
            i++
        }
        if (current.isNotEmpty() || paragraphs.isEmpty()) {
            paragraphs.add(
                WordParagraph(
                    text = current.toString(),
                    isBold = isBold,
                    isItalic = isItalic,
                    isUnderline = isUnderline,
                    alignment = align,
                    isRtl = align == WordAlignment.RIGHT
                )
            )
        }
        return WordDocumentModel(title = file.nameWithoutExtension, paragraphs = paragraphs)
    }

    private fun writeRtf(file: File, doc: WordDocumentModel) {
        val sb = StringBuilder()
        sb.append("{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Cairo;}{\\f1 Arial;}}\n")
        doc.paragraphs.forEach { p ->
            sb.append("\\pard")
            when (p.alignment) {
                WordAlignment.RIGHT -> sb.append("\\qr\\rtlpar ")
                WordAlignment.CENTER -> sb.append("\\qc ")
                WordAlignment.LEFT -> sb.append("\\ql\\ltrpar ")
                WordAlignment.JUSTIFY -> sb.append("\\qj ")
            }
            if (p.isBold) sb.append("\\b ")
            if (p.isItalic) sb.append("\\i ")
            if (p.isUnderline) sb.append("\\ul ")
            sb.append("\\fs${p.fontSizeSp * 2} ")
            for (ch in p.text) {
                if (ch.code > 127) {
                    sb.append("\\u${ch.code}?")
                } else if (ch == '\\' || ch == '{' || ch == '}') {
                    sb.append("\\$ch")
                } else {
                    sb.append(ch)
                }
            }
            if (p.isBold) sb.append("\\b0 ")
            if (p.isItalic) sb.append("\\i0 ")
            if (p.isUnderline) sb.append("\\ulnone ")
            sb.append("\\par\n")
        }
        sb.append("}")
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun readLegacyDoc(file: File): WordDocumentModel {
        // Extract readable UTF-16LE and ASCII/Arabic text runs from legacy binary .doc
        val bytes = file.readBytes()
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < bytes.size) {
            val low = bytes[i].toInt() and 0xFF
            val high = bytes[i + 1].toInt() and 0xFF
            val code = (high shl 8) or low
            if ((code in 0x0600..0x06FF) || (code in 0x0020..0x007E) || code == 0x000D || code == 0x000A) {
                sb.append(code.toChar())
                i += 2
            } else {
                i++
            }
        }
        val cleaned = sb.toString()
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.length >= 2 }
        val paragraphs = if (cleaned.isEmpty()) {
            listOf(WordParagraph(text = ""))
        } else {
            cleaned.map { line ->
                val isAr = line.any { it in '\u0600'..'\u06FF' }
                WordParagraph(
                    text = line,
                    isRtl = isAr,
                    alignment = if (isAr) WordAlignment.RIGHT else WordAlignment.LEFT
                )
            }
        }
        return WordDocumentModel(title = file.nameWithoutExtension, paragraphs = paragraphs)
    }

    private fun readDocx(file: File): WordDocumentModel {
        val paragraphs = mutableListOf<WordParagraph>()
        val tables = mutableListOf<WordTable>()
        var headerText = ""
        var footerText = ""

        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                when (entry.name) {
                    "word/document.xml" -> {
                        val xmlBytes = zip.readBytes()
                        parseDocumentXml(xmlBytes, paragraphs, tables)
                    }
                    "word/header1.xml" -> {
                        headerText = extractSimpleXmlText(zip.readBytes())
                    }
                    "word/footer1.xml" -> {
                        footerText = extractSimpleXmlText(zip.readBytes())
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (paragraphs.isEmpty()) {
            paragraphs.add(WordParagraph(text = ""))
        }

        return WordDocumentModel(
            title = file.nameWithoutExtension,
            headerText = headerText,
            footerText = footerText,
            paragraphs = paragraphs,
            tables = tables
        )
    }

    private fun extractSimpleXmlText(xmlBytes: ByteArray): String {
        val sb = StringBuilder()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xmlBytes.inputStream(), "UTF-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name?.endsWith("t") == true) {
                sb.append(parser.nextText())
            }
            event = parser.next()
        }
        return sb.toString().trim()
    }

    private fun parseDocumentXml(
        xmlBytes: ByteArray,
        paragraphs: MutableList<WordParagraph>,
        tables: MutableList<WordTable>
    ) {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xmlBytes.inputStream(), "UTF-8")

        var inParagraph = false
        var inTable = false
        var inTableRow = false
        var inTableCell = false

        var pText = StringBuilder()
        var pStyle = WordStyle.NORMAL
        var pAlign = WordAlignment.RIGHT
        var pRtl = true
        var pBold = false
        var pItalic = false
        var pUnderline = false
        var pStrike = false
        var pSize = 14
        var pColor = "#0F172A"
        var pList = WordListType.NONE

        val currentTableRows = mutableListOf<List<WordTableCell>>()
        val currentRowCells = mutableListOf<WordTableCell>()
        val cellText = StringBuilder()
        var cellColSpan = 1

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfter(':') ?: ""
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (tag) {
                        "tbl" -> {
                            inTable = true
                            currentTableRows.clear()
                        }
                        "tr" -> if (inTable) {
                            inTableRow = true
                            currentRowCells.clear()
                        }
                        "tc" -> if (inTableRow) {
                            inTableCell = true
                            cellText.clear()
                            cellColSpan = 1
                        }
                        "gridSpan" -> if (inTableCell) {
                            cellColSpan = parser.getAttributeValue(null, "w:val")?.toIntOrNull()
                                ?: parser.getAttributeValue(null, "val")?.toIntOrNull() ?: 1
                        }
                        "p" -> if (!inTable) {
                            inParagraph = true
                            pText = StringBuilder()
                            pStyle = WordStyle.NORMAL
                            pAlign = WordAlignment.RIGHT
                            pRtl = true
                            pBold = false
                            pItalic = false
                            pUnderline = false
                            pStrike = false
                            pSize = 14
                            pColor = "#0F172A"
                            pList = WordListType.NONE
                        }
                        "pStyle" -> if (inParagraph) {
                            val v = parser.getAttributeValue(null, "w:val")
                                ?: parser.getAttributeValue(null, "val") ?: ""
                            pStyle = when {
                                v.contains("Heading1", true) -> WordStyle.HEADING_1
                                v.contains("Heading2", true) -> WordStyle.HEADING_2
                                v.contains("Heading3", true) -> WordStyle.HEADING_3
                                v.contains("Quote", true) -> WordStyle.QUOTE
                                else -> WordStyle.NORMAL
                            }
                        }
                        "jc" -> if (inParagraph) {
                            val v = parser.getAttributeValue(null, "w:val")
                                ?: parser.getAttributeValue(null, "val") ?: ""
                            pAlign = when (v.lowercase()) {
                                "center" -> WordAlignment.CENTER
                                "left", "start" -> WordAlignment.LEFT
                                "both", "distribute" -> WordAlignment.JUSTIFY
                                else -> WordAlignment.RIGHT
                            }
                        }
                        "bidi" -> if (inParagraph) {
                            val v = parser.getAttributeValue(null, "w:val")
                                ?: parser.getAttributeValue(null, "val")
                            pRtl = v != "0"
                        }
                        "numPr" -> if (inParagraph) {
                            pList = WordListType.BULLET
                        }
                        "b" -> if (inParagraph) {
                            val v = parser.getAttributeValue(null, "w:val")
                                ?: parser.getAttributeValue(null, "val")
                            pBold = v != "0"
                        }
                        "i" -> if (inParagraph) {
                            val v = parser.getAttributeValue(null, "w:val")
                                ?: parser.getAttributeValue(null, "val")
                            pItalic = v != "0"
                        }
                        "u" -> if (inParagraph) {
                            val v = parser.getAttributeValue(null, "w:val")
                                ?: parser.getAttributeValue(null, "val")
                            pUnderline = v != "none"
                        }
                        "strike" -> if (inParagraph) {
                            pStrike = true
                        }
                        "sz" -> if (inParagraph) {
                            val halfPts = (parser.getAttributeValue(null, "w:val")
                                ?: parser.getAttributeValue(null, "val"))?.toIntOrNull()
                            if (halfPts != null && halfPts > 0) pSize = halfPts / 2
                        }
                        "color" -> if (inParagraph) {
                            val hex = parser.getAttributeValue(null, "w:val")
                                ?: parser.getAttributeValue(null, "val")
                            if (!hex.isNullOrBlank() && hex != "auto") {
                                pColor = "#$hex"
                            }
                        }
                        "t" -> {
                            val txt = parser.nextText()
                            if (inTableCell) {
                                cellText.append(txt)
                            } else if (inParagraph) {
                                pText.append(txt)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (tag) {
                        "p" -> if (inParagraph && !inTable) {
                            inParagraph = false
                            paragraphs.add(
                                WordParagraph(
                                    text = pText.toString(),
                                    style = pStyle,
                                    alignment = pAlign,
                                    isRtl = pRtl,
                                    isBold = pBold,
                                    isItalic = pItalic,
                                    isUnderline = pUnderline,
                                    isStrikethrough = pStrike,
                                    fontSizeSp = pSize,
                                    textColorHex = pColor,
                                    listType = pList
                                )
                            )
                        }
                        "tc" -> if (inTableCell) {
                            inTableCell = false
                            currentRowCells.add(
                                WordTableCell(
                                    text = cellText.toString().trim(),
                                    colSpan = cellColSpan,
                                    isHeader = currentTableRows.isEmpty()
                                )
                            )
                        }
                        "tr" -> if (inTableRow) {
                            inTableRow = false
                            if (currentRowCells.isNotEmpty()) {
                                currentTableRows.add(currentRowCells.toList())
                            }
                        }
                        "tbl" -> if (inTable) {
                            inTable = false
                            if (currentTableRows.isNotEmpty()) {
                                tables.add(
                                    WordTable(
                                        afterParagraphIndex = (paragraphs.size - 1).coerceAtLeast(0),
                                        rows = currentTableRows.toList()
                                    )
                                )
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
    }

    private fun writeDocx(file: File, doc: WordDocumentModel) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            // 1. [Content_Types].xml
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>
  <Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>
</Types>""".toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()

            // 2. _rels/.rels
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""".toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()

            // 3. word/_rels/document.xml.rels
            zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rIdHeader" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>
  <Relationship Id="rIdFooter" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>
</Relationships>""".toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()

            // 4. word/header1.xml & word/footer1.xml
            zip.putNextEntry(ZipEntry("word/header1.xml"))
            zip.write(buildHeaderFooterXml(doc.headerText).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("word/footer1.xml"))
            zip.write(buildHeaderFooterXml(doc.footerText).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 5. word/document.xml
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(buildDocumentXml(doc).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun buildHeaderFooterXml(text: String): String {
        val escaped = escapeXml(text)
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:p><w:r><w:t xml:space="preserve">$escaped</w:t></w:r></w:p>
</w:hdr>"""
    }

    private fun buildDocumentXml(doc: WordDocumentModel): String {
        val sb = StringBuilder()
        sb.append(
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
"""
        )
        doc.paragraphs.forEachIndexed { index, p ->
            sb.append("    <w:p>\n      <w:pPr>\n")
            if (p.style != WordStyle.NORMAL) {
                val styleName = when (p.style) {
                    WordStyle.HEADING_1 -> "Heading1"
                    WordStyle.HEADING_2 -> "Heading2"
                    WordStyle.HEADING_3 -> "Heading3"
                    WordStyle.QUOTE -> "Quote"
                    else -> "Normal"
                }
                sb.append("        <w:pStyle w:val=\"$styleName\"/>\n")
            }
            if (p.isRtl) sb.append("        <w:bidi/>\n")
            val jc = when (p.alignment) {
                WordAlignment.RIGHT -> "right"
                WordAlignment.CENTER -> "center"
                WordAlignment.LEFT -> "left"
                WordAlignment.JUSTIFY -> "both"
            }
            sb.append("        <w:jc w:val=\"$jc\"/>\n")
            sb.append("      </w:pPr>\n")

            val prefix = when (p.listType) {
                WordListType.BULLET -> "• "
                WordListType.NUMBERED -> "${index + 1}. "
                WordListType.NONE -> ""
            }
            val fullText = escapeXml(prefix + p.text)
            val colorClean = p.textColorHex.removePrefix("#")
            val szHalf = p.fontSizeSp * 2

            sb.append("      <w:r>\n        <w:rPr>\n")
            if (p.isBold || p.style != WordStyle.NORMAL) sb.append("          <w:b/>\n")
            if (p.isItalic || p.style == WordStyle.QUOTE) sb.append("          <w:i/>\n")
            if (p.isUnderline) sb.append("          <w:u w:val=\"single\"/>\n")
            if (p.isStrikethrough) sb.append("          <w:strike/>\n")
            sb.append("          <w:color w:val=\"$colorClean\"/>\n")
            sb.append("          <w:sz w:val=\"$szHalf\"/>\n")
            sb.append("        </w:rPr>\n")
            sb.append("        <w:t xml:space=\"preserve\">$fullText</w:t>\n")
            sb.append("      </w:r>\n    </w:p>\n")

            // Append any tables anchored after this paragraph
            doc.tables.filter { it.afterParagraphIndex == index }.forEach { tbl ->
                sb.append("    <w:tbl>\n")
                tbl.rows.forEach { row ->
                    sb.append("      <w:tr>\n")
                    row.forEach { cell ->
                        sb.append("        <w:tc>\n")
                        if (cell.colSpan > 1) {
                            sb.append("          <w:tcPr><w:gridSpan w:val=\"${cell.colSpan}\"/></w:tcPr>\n")
                        }
                        sb.append("          <w:p><w:r><w:t xml:space=\"preserve\">${escapeXml(cell.text)}</w:t></w:r></w:p>\n")
                        sb.append("        </w:tc>\n")
                    }
                    sb.append("      </w:tr>\n")
                }
                sb.append("    </w:tbl>\n")
            }
        }

        val wPt = if (doc.isLandscape) doc.pageSize.heightPt else doc.pageSize.widthPt
        val hPt = if (doc.isLandscape) doc.pageSize.widthPt else doc.pageSize.heightPt
        val wTwips = wPt * 20
        val hTwips = hPt * 20
        val mTwips = doc.marginPt * 20
        sb.append(
            """    <w:sectPr>
      <w:headerReference w:type="default" w:id="rIdHeader"/>
      <w:footerReference w:type="default" w:id="rIdFooter"/>
      <w:pgSz w:w="$wTwips" w:h="$hTwips"/>
      <w:pgMar w:top="$mTwips" w:right="$mTwips" w:bottom="$mTwips" w:left="$mTwips"/>
    </w:sectPr>
  </w:body>
</w:document>"""
        )
        return sb.toString()
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    fun exportToPdf(doc: WordDocumentModel, outPdf: File) {
        val pdf = PdfDocument()
        val pageWidth = if (doc.isLandscape) doc.pageSize.heightPt else doc.pageSize.widthPt
        val pageHeight = if (doc.isLandscape) doc.pageSize.widthPt else doc.pageSize.heightPt
        val margin = doc.marginPt.toFloat()
        val contentWidth = (pageWidth - margin * 2).toInt().coerceAtLeast(100)

        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
        var page = pdf.startPage(pageInfo)
        var canvas = page.canvas
        var y = margin + 16f

        fun drawHeaderFooter(c: Canvas, pNum: Int) {
            val hfPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.GRAY
                textSize = 9f
            }
            if (doc.headerText.isNotBlank()) {
                c.drawText(doc.headerText, margin, margin * 0.6f, hfPaint)
            }
            if (doc.footerText.isNotBlank()) {
                c.drawText(doc.footerText, margin, pageHeight - margin * 0.4f, hfPaint)
            }
            if (doc.showPageNumbers) {
                val pLabel = "$pNum"
                c.drawText(pLabel, pageWidth - margin - 20f, pageHeight - margin * 0.4f, hfPaint)
            }
        }

        drawHeaderFooter(canvas, pageNum)

        doc.paragraphs.forEachIndexed { idx, p ->
            val sizePt = when (p.style) {
                WordStyle.HEADING_1 -> (p.fontSizeSp + 8).toFloat()
                WordStyle.HEADING_2 -> (p.fontSizeSp + 5).toFloat()
                WordStyle.HEADING_3 -> (p.fontSizeSp + 2).toFloat()
                else -> p.fontSizeSp.toFloat()
            }
            val styleFlags = when {
                (p.isBold || p.style != WordStyle.NORMAL) && (p.isItalic || p.style == WordStyle.QUOTE) -> Typeface.BOLD_ITALIC
                p.isBold || p.style != WordStyle.NORMAL -> Typeface.BOLD
                p.isItalic || p.style == WordStyle.QUOTE -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = sizePt
                typeface = Typeface.create(Typeface.DEFAULT, styleFlags)
                color = runCatching { Color.parseColor(p.textColorHex) }.getOrDefault(Color.BLACK)
                isUnderlineText = p.isUnderline
                isStrikeThruText = p.isStrikethrough
            }
            val prefix = when (p.listType) {
                WordListType.BULLET -> "• "
                WordListType.NUMBERED -> "${idx + 1}. "
                WordListType.NONE -> ""
            }
            val align = when (p.alignment) {
                WordAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                WordAlignment.LEFT -> Layout.Alignment.ALIGN_NORMAL
                WordAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                WordAlignment.JUSTIFY -> Layout.Alignment.ALIGN_NORMAL
            }
            val staticLayout = StaticLayout.Builder
                .obtain(prefix + p.text, 0, (prefix + p.text).length, paint, contentWidth)
                .setAlignment(align)
                .setLineSpacing(0f, doc.lineSpacingMultiplier)
                .build()

            if (y + staticLayout.height > pageHeight - margin) {
                pdf.finishPage(page)
                pageNum++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                page = pdf.startPage(pageInfo)
                canvas = page.canvas
                drawHeaderFooter(canvas, pageNum)
                y = margin + 16f
            }

            if (!p.highlightColorHex.isNullOrBlank()) {
                val bgPaint = Paint().apply {
                    color = runCatching { Color.parseColor(p.highlightColorHex) }.getOrDefault(Color.YELLOW)
                }
                canvas.drawRect(margin, y, margin + contentWidth, y + staticLayout.height, bgPaint)
            }

            canvas.save()
            canvas.translate(margin, y)
            staticLayout.draw(canvas)
            canvas.restore()
            y += staticLayout.height + doc.paragraphSpacingPt

            // Render tables anchored after this paragraph
            doc.tables.filter { it.afterParagraphIndex == idx }.forEach { tbl ->
                val rowH = 26f
                tbl.rows.forEach { row ->
                    if (y + rowH > pageHeight - margin) {
                        pdf.finishPage(page)
                        pageNum++
                        pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                        page = pdf.startPage(pageInfo)
                        canvas = page.canvas
                        drawHeaderFooter(canvas, pageNum)
                        y = margin + 16f
                    }
                    val totalCols = row.sumOf { it.colSpan }.coerceAtLeast(1)
                    val colW = contentWidth.toFloat() / totalCols
                    var xCursor = margin
                    val borderPaint = Paint().apply {
                        color = Color.DKGRAY
                        style = Paint.Style.STROKE
                        strokeWidth = 1f
                    }
                    val cellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 11f
                    }
                    row.forEach { cell ->
                        val w = colW * cell.colSpan
                        canvas.drawRect(xCursor, y, xCursor + w, y + rowH, borderPaint)
                        canvas.drawText(cell.text.take(25), xCursor + 4f, y + 17f, cellPaint)
                        xCursor += w
                    }
                    y += rowH
                }
                y += 8f
            }
        }

        pdf.finishPage(page)
        FileOutputStream(outPdf).use { pdf.writeTo(it) }
        pdf.close()
    }
}
