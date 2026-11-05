package com.example

import com.example.engine.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfficeEnginesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun wordEngine_saveAndReloadDocxRtfTxt_preservesArabicAndTables() {
        val doc = WordDocumentModel(
            title = "اختبار مستند",
            headerText = "ترويسة",
            paragraphs = listOf(
                WordParagraph(text = "مرحباً بكم في Office Pro", style = WordStyle.HEADING_1, isBold = true, isRtl = true),
                WordParagraph(text = "فقرة نصية باللغة العربية", isItalic = true, isRtl = true)
            ),
            tables = listOf(
                WordTable(
                    afterParagraphIndex = 0,
                    rows = listOf(
                        listOf(WordTableCell("الاسم", isHeader = true), WordTableCell("القيمة", isHeader = true)),
                        listOf(WordTableCell("بند 1"), WordTableCell("250"))
                    )
                )
            )
        )

        val docxFile = File(tempFolder.root, "test.docx")
        WordEngine.saveDocument(docxFile, doc)
        assertTrue(docxFile.exists() && docxFile.length() > 0)

        val reloadedDocx = WordEngine.loadDocument(docxFile)
        assertEquals(2, reloadedDocx.paragraphs.size)
        assertEquals("مرحباً بكم في Office Pro", reloadedDocx.paragraphs[0].text)
        assertEquals(1, reloadedDocx.tables.size)
        assertEquals("250", reloadedDocx.tables[0].rows[1][1].text)

        val rtfFile = File(tempFolder.root, "test.rtf")
        WordEngine.saveDocument(rtfFile, doc)
        val reloadedRtf = WordEngine.loadDocument(rtfFile)
        assertTrue(reloadedRtf.paragraphs.any { it.text.contains("مرحباً بكم في Office Pro") })

        val txtFile = File(tempFolder.root, "test.txt")
        WordEngine.saveDocument(txtFile, doc)
        val reloadedTxt = WordEngine.loadDocument(txtFile)
        assertEquals(2, reloadedTxt.paragraphs.size)
    }

    @Test
    fun formulaEvaluator_evaluatesCoreAndAdvancedFunctions() {
        val cells = mutableMapOf(
            "0,0" to ExcelCell("10", "10"),
            "1,0" to ExcelCell("20", "20"),
            "2,0" to ExcelCell("30", "30"),
            "3,0" to ExcelCell("=SUM(A1:A3)", ""),
            "4,0" to ExcelCell("=AVERAGE(A1:A3)", ""),
            "5,0" to ExcelCell("=IF(A4>50, \"مرتفع\", \"منخفض\")", ""),
            "6,0" to ExcelCell("=COUNTIF(A1:A3, \">15\")", ""),
            "7,0" to ExcelCell("=SUMIF(A1:A3, \">=20\")", ""),
            "0,1" to ExcelCell("أحمد", "أحمد"),
            "0,2" to ExcelCell("95", "95"),
            "1,1" to ExcelCell("سارة", "سارة"),
            "1,2" to ExcelCell("88", "88"),
            "2,1" to ExcelCell("=VLOOKUP(\"سارة\", B1:C2, 2, FALSE)", ""),
            "3,1" to ExcelCell("=INDEX(C1:C2, 2, 1)", ""),
            "4,1" to ExcelCell("=MATCH(\"أحمد\", B1:B2, 0)", "")
        )

        val sheet = FormulaEvaluator.recalculateSheet(ExcelSheet(cells = cells))
        assertEquals("60", sheet.cells["3,0"]?.displayValue)
        assertEquals("20", sheet.cells["4,0"]?.displayValue)
        assertEquals("مرتفع", sheet.cells["5,0"]?.displayValue)
        assertEquals("2", sheet.cells["6,0"]?.displayValue)
        assertEquals("50", sheet.cells["7,0"]?.displayValue)
        assertEquals("88", sheet.cells["2,1"]?.displayValue)
        assertEquals("88", sheet.cells["3,1"]?.displayValue)
        assertEquals("1", sheet.cells["4,1"]?.displayValue)
    }

    @Test
    fun excelEngine_saveAndReloadXlsxAndCsv() {
        val wb = TemplatesProvider.createExcelFromTemplate("invoice")
        val xlsxFile = File(tempFolder.root, "invoice.xlsx")
        ExcelEngine.saveWorkbook(xlsxFile, wb)
        assertTrue(xlsxFile.exists())

        val reloaded = ExcelEngine.loadWorkbook(xlsxFile)
        assertEquals("2500", reloaded.sheets[0].cells["3,3"]?.displayValue)

        val csvFile = File(tempFolder.root, "invoice.csv")
        ExcelEngine.saveWorkbook(csvFile, wb)
        val reloadedCsv = ExcelEngine.loadWorkbook(csvFile)
        assertTrue(reloadedCsv.sheets[0].cells.isNotEmpty())
    }

    @Test
    fun powerPointEngine_saveAndReloadPptx() {
        val pres = TemplatesProvider.createPresentationFromTemplate("biz_ppt")
        val pptxFile = File(tempFolder.root, "deck.pptx")
        PowerPointEngine.savePresentation(pptxFile, pres)
        assertTrue(pptxFile.exists())

        val reloaded = PowerPointEngine.loadPresentation(pptxFile)
        assertEquals(2, reloaded.slides.size)
        assertTrue(reloaded.slides[0].elements[0].text.contains("خطة العمل"))
    }
}
