package com.example.engine

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
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

@Serializable
enum class CellNumberFormat { GENERAL, CURRENCY, PERCENT, DATE }

@Serializable
enum class CellAlignment { RIGHT, CENTER, LEFT }

@Serializable
data class ExcelCell(
    val rawInput: String = "",
    val displayValue: String = "",
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val textColorHex: String = "#0F172A",
    val bgColorHex: String? = null,
    val alignment: CellAlignment = CellAlignment.RIGHT,
    val numberFormat: CellNumberFormat = CellNumberFormat.GENERAL,
    val hasBorder: Boolean = true
)

@Serializable
data class CellRange(
    val startRow: Int,
    val startCol: Int,
    val endRow: Int,
    val endCol: Int
)

@Serializable
enum class ConditionalOperator { GREATER_THAN, LESS_THAN, EQUALS, CONTAINS }

@Serializable
data class ConditionalRule(
    val operator: ConditionalOperator,
    val targetValue: String,
    val highlightHex: String = "#FEF08A"
)

@Serializable
enum class ExcelChartType { BAR, LINE, PIE }

@Serializable
data class ExcelChartConfig(
    val title: String = "رسم بياني",
    val type: ExcelChartType = ExcelChartType.BAR,
    val labelCol: Int = 0,
    val valueCol: Int = 1,
    val startRow: Int = 1,
    val endRow: Int = 6
)

@Serializable
data class ExcelSheet(
    val name: String = "ورقة 1",
    val rowCount: Int = 30,
    val colCount: Int = 12,
    val cells: Map<String, ExcelCell> = emptyMap(),
    val mergedRanges: List<CellRange> = emptyList(),
    val columnWidths: Map<Int, Float> = emptyMap(),
    val rowHeights: Map<Int, Float> = emptyMap(),
    val freezeRows: Int = 0,
    val freezeCols: Int = 0,
    val conditionalRules: List<ConditionalRule> = emptyList(),
    val dropdownValidations: Map<String, List<String>> = emptyMap(),
    val chartConfig: ExcelChartConfig? = null
)

@Serializable
data class ExcelWorkbookModel(
    val title: String = "جدول جديد",
    val sheets: List<ExcelSheet> = listOf(ExcelSheet()),
    val activeSheetIndex: Int = 0,
    val isReadOnlyBinary: Boolean = false,
    @Transient val sourcePackage: OoxmlPackage? = null
)

object ExcelEngine {

    fun loadWorkbook(file: File): ExcelWorkbookModel {
        val detected = DocumentDetector.detect(file).format
        val wb = when (detected) {
            DetectedFormat.XLSX, DetectedFormat.XLSM -> readXlsx(file)
            DetectedFormat.ODS -> readOds(file)
            DetectedFormat.CSV -> readCsv(file)
            DetectedFormat.XLS -> readLegacyXls(file, isBinaryXlsb = false)
            DetectedFormat.XLSB -> readLegacyXls(file, isBinaryXlsb = true)
            else -> readCsv(file)
        }
        return wb.copy(sheets = wb.sheets.map { FormulaEvaluator.recalculateSheet(it) })
    }

    fun saveWorkbook(file: File, workbook: ExcelWorkbookModel, context: Context? = null): SafeSaveResult {
        val ext = file.extension.lowercase()
        val validator: ((File) -> Boolean)? = when (ext) {
            "xlsx", "xlsm", "ods" -> { f -> OoxmlPackage.isValidZip(f) }
            else -> null
        }
        return SafeSaveManager.saveAtomically(file, context, validator) { tempOut ->
            when (ext) {
                "xlsx", "xlsm" -> writeXlsx(tempOut, workbook)
                "ods" -> writeOds(tempOut, workbook)
                "csv" -> writeCsv(tempOut, workbook.sheets.getOrElse(workbook.activeSheetIndex) { workbook.sheets.first() })
                else -> writeXlsx(tempOut, workbook)
            }
        }
    }

    private fun readCsv(file: File): ExcelWorkbookModel {
        val text = if (file.exists()) file.readText(Charsets.UTF_8).removePrefix("\uFEFF") else ""
        val lines = text.lines().filter { it.isNotEmpty() }
        val cells = mutableMapOf<String, ExcelCell>()
        var maxCols = 8
        lines.forEachIndexed { r, line ->
            val cols = parseCsvLine(line)
            if (cols.size > maxCols) maxCols = cols.size
            cols.forEachIndexed { c, value ->
                if (value.isNotEmpty()) {
                    cells[FormulaEvaluator.cellKey(r, c)] = ExcelCell(
                        rawInput = value,
                        displayValue = value
                    )
                }
            }
        }
        val sheet = ExcelSheet(
            name = file.nameWithoutExtension,
            rowCount = maxOf(30, lines.size + 10),
            colCount = maxOf(12, maxCols + 4),
            cells = cells
        )
        return ExcelWorkbookModel(title = file.nameWithoutExtension, sheets = listOf(sheet))
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    sb.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (ch == ',' && !inQuotes) {
                result.add(sb.toString())
                sb.clear()
            } else {
                sb.append(ch)
            }
            i++
        }
        result.add(sb.toString())
        return result
    }

    private fun writeCsv(file: File, sheet: ExcelSheet) {
        val sb = StringBuilder("\uFEFF")
        val maxRow = sheet.cells.keys.mapNotNull { it.split(",")[0].toIntOrNull() }.maxOrNull() ?: 0
        val maxCol = sheet.cells.keys.mapNotNull { it.split(",")[1].toIntOrNull() }.maxOrNull() ?: 0
        for (r in 0..maxRow) {
            val rowVals = (0..maxCol).map { c ->
                val v = sheet.cells[FormulaEvaluator.cellKey(r, c)]?.displayValue ?: ""
                if (v.contains(',') || v.contains('"') || v.contains('\n')) {
                    "\"${v.replace("\"", "\"\"")}\""
                } else v
            }
            sb.append(rowVals.joinToString(",")).append("\n")
        }
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun readLegacyXls(file: File, isBinaryXlsb: Boolean): ExcelWorkbookModel {
        val bytes = file.readBytes()
        val strings = mutableListOf<String>()
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
                if (sb.length >= 2) strings.add(sb.toString().trim())
                sb.clear()
                i++
            }
        }
        val cells = mutableMapOf<String, ExcelCell>()
        strings.take(200).forEachIndexed { idx, s ->
            cells[FormulaEvaluator.cellKey(idx / 4, idx % 4)] = ExcelCell(rawInput = s, displayValue = s)
        }
        return ExcelWorkbookModel(
            title = file.nameWithoutExtension,
            sheets = listOf(ExcelSheet(name = "ورقة 1", cells = cells)),
            isReadOnlyBinary = isBinaryXlsb
        )
    }

    private fun readOds(file: File): ExcelWorkbookModel {
        val pkg = OoxmlPackage.readFrom(file)
        val sheets = mutableListOf<ExcelSheet>()
        pkg.getEntry("content.xml")?.let { xmlBytes ->
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(xmlBytes.inputStream(), "UTF-8")
            var currentSheetName = "ورقة 1"
            var cells = mutableMapOf<String, ExcelCell>()
            var rowIdx = -1
            var colIdx = -1
            var inTable = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name?.substringAfter(':') ?: ""
                when (event) {
                    XmlPullParser.START_TAG -> when (tag) {
                        "table" -> {
                            inTable = true
                            currentSheetName = parser.getAttributeValue(null, "table:name")
                                ?: parser.getAttributeValue(null, "name")
                                ?: "ورقة ${sheets.size + 1}"
                            cells = mutableMapOf()
                            rowIdx = -1
                        }
                        "table-row" -> if (inTable) {
                            rowIdx++
                            colIdx = -1
                        }
                        "table-cell" -> if (inTable) {
                            colIdx++
                            val formula = parser.getAttributeValue(null, "table:formula")
                                ?: parser.getAttributeValue(null, "formula")
                            val valAttr = parser.getAttributeValue(null, "office:value")
                                ?: parser.getAttributeValue(null, "value")
                            if (!formula.isNullOrBlank()) {
                                val cleanF = "=" + formula.substringAfter("of:=").removePrefix("=")
                                cells[FormulaEvaluator.cellKey(rowIdx, colIdx)] = ExcelCell(rawInput = cleanF, displayValue = valAttr ?: cleanF)
                            } else if (!valAttr.isNullOrBlank()) {
                                cells[FormulaEvaluator.cellKey(rowIdx, colIdx)] = ExcelCell(rawInput = valAttr, displayValue = valAttr)
                            }
                        }
                        "p" -> if (inTable && rowIdx >= 0 && colIdx >= 0) {
                            val txt = parser.nextText()
                            val key = FormulaEvaluator.cellKey(rowIdx, colIdx)
                            val existing = cells[key]
                            if (existing == null && txt.isNotEmpty()) {
                                cells[key] = ExcelCell(rawInput = txt, displayValue = txt)
                            } else if (existing != null && existing.rawInput.startsWith("=") && txt.isNotEmpty()) {
                                cells[key] = existing.copy(displayValue = txt)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> if (tag == "table" && inTable) {
                        inTable = false
                        sheets.add(ExcelSheet(name = currentSheetName, rowCount = maxOf(30, rowIdx + 10), colCount = maxOf(12, colIdx + 5), cells = cells))
                    }
                }
                event = parser.next()
            }
        }
        if (sheets.isEmpty()) sheets.add(ExcelSheet())
        return ExcelWorkbookModel(title = file.nameWithoutExtension, sheets = sheets, sourcePackage = pkg)
    }

    private fun writeOds(file: File, workbook: ExcelWorkbookModel) {
        val pkg = workbook.sourcePackage?.copy() ?: OoxmlPackage()
        if (!pkg.hasEntry("mimetype")) {
            pkg.putEntry("mimetype", "application/vnd.oasis.opendocument.spreadsheet".toByteArray(Charsets.UTF_8))
        }
        val tablesXml = workbook.sheets.joinToString("\n") { sheet ->
            val maxR = sheet.cells.keys.mapNotNull { it.split(",")[0].toIntOrNull() }.maxOrNull() ?: 0
            val maxC = sheet.cells.keys.mapNotNull { it.split(",")[1].toIntOrNull() }.maxOrNull() ?: 0
            val rowsXml = (0..maxR).joinToString("\n") { r ->
                val cellsXml = (0..maxC).joinToString("") { c ->
                    val cell = sheet.cells[FormulaEvaluator.cellKey(r, c)]
                    if (cell == null || (cell.rawInput.isEmpty() && cell.displayValue.isEmpty())) {
                        "<table:table-cell/>"
                    } else if (cell.rawInput.startsWith("=")) {
                        "<table:table-cell table:formula=\"of:${escapeXml(cell.rawInput)}\"><text:p>${escapeXml(cell.displayValue)}</text:p></table:table-cell>"
                    } else {
                        "<table:table-cell><text:p>${escapeXml(cell.displayValue)}</text:p></table:table-cell>"
                    }
                }
                "      <table:table-row>$cellsXml</table:table-row>"
            }
            "    <table:table table:name=\"${escapeXml(sheet.name)}\">\n$rowsXml\n    </table:table>"
        }
        val contentXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2">
  <office:body>
    <office:spreadsheet>
$tablesXml
    </office:spreadsheet>
  </office:body>
</office:document-content>"""
        pkg.putEntry("content.xml", contentXml.toByteArray(Charsets.UTF_8))
        pkg.writeTo(file)
    }

    private fun readXlsx(file: File): ExcelWorkbookModel {
        val pkg = OoxmlPackage.readFrom(file)
        val sharedStrings = mutableListOf<String>()
        val sheetNames = mutableListOf<String>()
        val sheetXmlMap = mutableMapOf<Int, ByteArray>()

        pkg.getEntry("xl/sharedStrings.xml")?.let { parseSharedStrings(it, sharedStrings) }
        pkg.getEntry("xl/workbook.xml")?.let { parseWorkbookSheetNames(it, sheetNames) }

        pkg.listEntries("xl/worksheets/sheet")
            .filter { it.endsWith(".xml") }
            .forEach { entryName ->
                val num = entryName.removePrefix("xl/worksheets/sheet")
                    .removeSuffix(".xml").toIntOrNull() ?: (sheetXmlMap.size + 1)
                pkg.getEntry(entryName)?.let { sheetXmlMap[num] = it }
            }

        val sheets = mutableListOf<ExcelSheet>()
        val sortedKeys = sheetXmlMap.keys.sorted()
        sortedKeys.forEachIndexed { idx, sheetNum ->
            val xmlBytes = sheetXmlMap[sheetNum] ?: return@forEachIndexed
            val name = sheetNames.getOrElse(idx) { "ورقة ${idx + 1}" }
            sheets.add(parseWorksheetXml(name, xmlBytes, sharedStrings))
        }

        if (sheets.isEmpty()) {
            sheets.add(ExcelSheet())
        }
        return ExcelWorkbookModel(
            title = file.nameWithoutExtension,
            sheets = sheets,
            sourcePackage = pkg
        )
    }

    private fun parseSharedStrings(xmlBytes: ByteArray, out: MutableList<String>) {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xmlBytes.inputStream(), "UTF-8")
        var inSi = false
        val sb = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfter(':') ?: ""
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (tag == "si") {
                        inSi = true
                        sb.clear()
                    } else if (tag == "t" && inSi) {
                        sb.append(parser.nextText())
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (tag == "si") {
                        inSi = false
                        out.add(sb.toString())
                    }
                }
            }
            event = parser.next()
        }
    }

    private fun parseWorkbookSheetNames(xmlBytes: ByteArray, out: MutableList<String>) {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xmlBytes.inputStream(), "UTF-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name?.substringAfter(':') == "sheet") {
                val name = parser.getAttributeValue(null, "name")
                if (!name.isNullOrBlank()) out.add(name)
            }
            event = parser.next()
        }
    }

    private fun parseWorksheetXml(
        name: String,
        xmlBytes: ByteArray,
        sharedStrings: List<String>
    ): ExcelSheet {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xmlBytes.inputStream(), "UTF-8")
        val cells = mutableMapOf<String, ExcelCell>()
        val merged = mutableListOf<CellRange>()
        val colWidths = mutableMapOf<Int, Float>()
        var freezeRows = 0
        var freezeCols = 0

        var currentRef: Pair<Int, Int>? = null
        var currentType = ""
        var currentFormula: String? = null
        var currentValue: String? = null
        var maxR = 29
        var maxC = 11

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfter(':') ?: ""
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (tag) {
                        "pane" -> {
                            freezeRows = parser.getAttributeValue(null, "ySplit")?.toDoubleOrNull()?.toInt() ?: freezeRows
                            freezeCols = parser.getAttributeValue(null, "xSplit")?.toDoubleOrNull()?.toInt() ?: freezeCols
                        }
                        "col" -> {
                            val min = parser.getAttributeValue(null, "min")?.toIntOrNull()
                            val max = parser.getAttributeValue(null, "max")?.toIntOrNull()
                            val w = parser.getAttributeValue(null, "width")?.toFloatOrNull()
                            if (min != null && max != null && w != null) {
                                for (c in (min - 1)..(max - 1)) {
                                    if (c >= 0) colWidths[c] = (w * 8f).coerceIn(50f, 320f)
                                }
                            }
                        }
                        "c" -> {
                            val rStr = parser.getAttributeValue(null, "r") ?: ""
                            currentRef = FormulaEvaluator.parseCellRef(rStr)
                            currentType = parser.getAttributeValue(null, "t") ?: ""
                            currentFormula = null
                            currentValue = null
                        }
                        "f" -> currentFormula = parser.nextText()
                        "v" -> currentValue = parser.nextText()
                        "t" -> if (currentType == "inlineStr") currentValue = parser.nextText()
                        "mergeCell" -> {
                            val ref = parser.getAttributeValue(null, "ref") ?: ""
                            val parts = ref.split(":")
                            if (parts.size == 2) {
                                val s = FormulaEvaluator.parseCellRef(parts[0])
                                val e = FormulaEvaluator.parseCellRef(parts[1])
                                if (s != null && e != null) {
                                    merged.add(CellRange(s.first, s.second, e.first, e.second))
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (tag == "c" && currentRef != null) {
                        val (r, c) = currentRef
                        if (r + 5 > maxR) maxR = r + 5
                        if (c + 3 > maxC) maxC = c + 3
                        val resolvedVal = when {
                            currentType == "s" -> {
                                val idx = currentValue?.toIntOrNull() ?: -1
                                sharedStrings.getOrElse(idx) { "" }
                            }
                            else -> currentValue ?: ""
                        }
                        val rawInput = if (!currentFormula.isNullOrBlank()) {
                            "=${currentFormula}"
                        } else {
                            resolvedVal
                        }
                        if (rawInput.isNotEmpty()) {
                            cells[FormulaEvaluator.cellKey(r, c)] = ExcelCell(
                                rawInput = rawInput,
                                displayValue = resolvedVal
                            )
                        }
                        currentRef = null
                    }
                }
            }
            event = parser.next()
        }

        return ExcelSheet(
            name = name,
            rowCount = maxR + 1,
            colCount = maxC + 1,
            cells = cells,
            mergedRanges = merged,
            columnWidths = colWidths,
            freezeRows = freezeRows,
            freezeCols = freezeCols
        )
    }

    private fun writeXlsx(file: File, workbook: ExcelWorkbookModel) {
        val sharedStrings = mutableListOf<String>()
        val stringIndexMap = mutableMapOf<String, Int>()

        fun internString(s: String): Int {
            return stringIndexMap.getOrPut(s) {
                sharedStrings.add(s)
                sharedStrings.size - 1
            }
        }

        workbook.sheets.forEach { sheet ->
            sheet.cells.values.forEach { cell ->
                if (!cell.rawInput.startsWith("=") && cell.rawInput.toDoubleOrNull() == null && cell.rawInput.isNotEmpty()) {
                    internString(cell.rawInput)
                }
            }
        }

        val pkg = workbook.sourcePackage?.copy() ?: OoxmlPackage()

        if (!pkg.hasEntry("[Content_Types].xml")) {
            val sheetOverrides = workbook.sheets.indices.joinToString("\n") { i ->
                """  <Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
            }
            pkg.putEntry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.workbook+xml"/>
  <Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
$sheetOverrides
</Types>""".toByteArray(Charsets.UTF_8)
            )
        }

        if (!pkg.hasEntry("_rels/.rels")) {
            pkg.putEntry(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""".toByteArray(Charsets.UTF_8)
            )
        }

        if (!pkg.hasEntry("xl/_rels/workbook.xml.rels")) {
            val sheetRels = workbook.sheets.indices.joinToString("\n") { i ->
                """  <Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>"""
            }
            pkg.putEntry(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
$sheetRels
  <Relationship Id="rIdStrings" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
</Relationships>""".toByteArray(Charsets.UTF_8)
            )
        }

        if (!pkg.hasEntry("xl/workbook.xml")) {
            val sheetDefs = workbook.sheets.mapIndexed { i, s ->
                """    <sheet name="${escapeXml(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>"""
            }.joinToString("\n")
            pkg.putEntry(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
$sheetDefs
  </sheets>
</workbook>""".toByteArray(Charsets.UTF_8)
            )
        }

        val siItems = sharedStrings.joinToString("") { s ->
            "<si><t xml:space=\"preserve\">${escapeXml(s)}</t></si>"
        }
        pkg.putEntry(
            "xl/sharedStrings.xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${sharedStrings.size}" uniqueCount="${sharedStrings.size}">$siItems</sst>""".toByteArray(Charsets.UTF_8)
        )

        workbook.sheets.forEachIndexed { idx, sheet ->
            pkg.putEntry(
                "xl/worksheets/sheet${idx + 1}.xml",
                buildWorksheetXml(sheet, stringIndexMap).toByteArray(Charsets.UTF_8)
            )
        }

        pkg.writeTo(file)
    }

    private fun buildWorksheetXml(sheet: ExcelSheet, stringMap: Map<String, Int>): String {
        val sb = StringBuilder()
        sb.append(
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
"""
        )
        if (sheet.freezeRows > 0 || sheet.freezeCols > 0) {
            val topLeft = "${FormulaEvaluator.colIndexToName(sheet.freezeCols)}${sheet.freezeRows + 1}"
            sb.append(
                """  <sheetViews>
    <sheetView workbookViewId="0">
      <pane xSplit="${sheet.freezeCols}" ySplit="${sheet.freezeRows}" topLeftCell="$topLeft" activePane="bottomRight" state="frozen"/>
    </sheetView>
  </sheetViews>
"""
            )
        }
        if (sheet.columnWidths.isNotEmpty()) {
            sb.append("  <cols>\n")
            sheet.columnWidths.forEach { (colIdx, widthPx) ->
                val excelW = (widthPx / 8f).coerceIn(6f, 60f)
                sb.append("    <col min=\"${colIdx + 1}\" max=\"${colIdx + 1}\" width=\"$excelW\" customWidth=\"1\"/>\n")
            }
            sb.append("  </cols>\n")
        }
        sb.append("  <sheetData>\n")
        val rowsMap = sortedMapOf<Int, MutableList<Pair<Int, ExcelCell>>>()
        sheet.cells.forEach { (key, cell) ->
            val parts = key.split(",")
            val r = parts[0].toIntOrNull() ?: return@forEach
            val c = parts[1].toIntOrNull() ?: return@forEach
            rowsMap.getOrPut(r) { mutableListOf() }.add(c to cell)
        }
        rowsMap.forEach { (r, cols) ->
            sb.append("    <row r=\"${r + 1}\">\n")
            cols.sortedBy { it.first }.forEach { (c, cell) ->
                val ref = "${FormulaEvaluator.colIndexToName(c)}${r + 1}"
                if (cell.rawInput.startsWith("=")) {
                    val f = escapeXml(cell.rawInput.substring(1))
                    val v = escapeXml(cell.displayValue)
                    sb.append("      <c r=\"$ref\"><f>$f</f><v>$v</v></c>\n")
                } else if (cell.rawInput.toDoubleOrNull() != null) {
                    sb.append("      <c r=\"$ref\"><v>${cell.rawInput}</v></c>\n")
                } else if (cell.rawInput.isNotEmpty()) {
                    val sIdx = stringMap[cell.rawInput] ?: 0
                    sb.append("      <c r=\"$ref\" t=\"s\"><v>$sIdx</v></c>\n")
                }
            }
            sb.append("    </row>\n")
        }
        sb.append("  </sheetData>\n")
        if (sheet.mergedRanges.isNotEmpty()) {
            sb.append("  <mergeCells count=\"${sheet.mergedRanges.size}\">\n")
            sheet.mergedRanges.forEach { m ->
                val sRef = "${FormulaEvaluator.colIndexToName(m.startCol)}${m.startRow + 1}"
                val eRef = "${FormulaEvaluator.colIndexToName(m.endCol)}${m.endRow + 1}"
                sb.append("    <mergeCell ref=\"$sRef:$eRef\"/>\n")
            }
            sb.append("  </mergeCells>\n")
        }
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    fun exportToPdf(workbook: ExcelWorkbookModel, outPdf: File) {
        val sheet = workbook.sheets.getOrElse(workbook.activeSheetIndex) { workbook.sheets.first() }
        val pdf = PdfDocument()
        val pageWidth = 842
        val pageHeight = 595
        val margin = 36f
        val maxRow = (sheet.cells.keys.mapNotNull { it.split(",")[0].toIntOrNull() }.maxOrNull() ?: 10).coerceAtLeast(10)
        val maxCol = (sheet.cells.keys.mapNotNull { it.split(",")[1].toIntOrNull() }.maxOrNull() ?: 6).coerceIn(4, 10)
        val colW = (pageWidth - margin * 2) / (maxCol + 1)
        val rowH = 22f

        var pageNum = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        var canvas = page.canvas
        var y = margin + 24f

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            color = Color.BLACK
        }
        canvas.drawText("${workbook.title} - ${sheet.name}", margin, margin + 12f, titlePaint)

        val borderPaint = Paint().apply {
            color = Color.LTGRAY
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val cellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f
            color = Color.BLACK
        }

        for (r in 0..maxRow) {
            if (y + rowH > pageHeight - margin) {
                pdf.finishPage(page)
                pageNum++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
                canvas = page.canvas
                y = margin + 12f
            }
            for (c in 0..maxCol) {
                val x = margin + c * colW
                val cell = sheet.cells[FormulaEvaluator.cellKey(r, c)]
                if (cell?.bgColorHex != null) {
                    val bg = Paint().apply {
                        color = runCatching { Color.parseColor(cell.bgColorHex) }.getOrDefault(Color.WHITE)
                    }
                    canvas.drawRect(x, y, x + colW, y + rowH, bg)
                }
                canvas.drawRect(x, y, x + colW, y + rowH, borderPaint)
                val txt = cell?.displayValue ?: ""
                if (txt.isNotEmpty()) {
                    cellPaint.typeface = if (cell?.isBold == true) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    canvas.drawText(txt.take(16), x + 4f, y + 15f, cellPaint)
                }
            }
            y += rowH
        }

        pdf.finishPage(page)
        FileOutputStream(outPdf).use { pdf.writeTo(it) }
        pdf.close()
    }
}
