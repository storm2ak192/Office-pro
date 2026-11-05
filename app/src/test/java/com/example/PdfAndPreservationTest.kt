package com.example

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.core.DetectedFormat
import com.example.core.DocumentDetector
import com.example.core.OoxmlPackage
import com.example.core.SafeSaveManager
import com.example.core.SafeSaveResult
import com.example.engine.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PdfAndPreservationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun pdf_create_extract_edit_delete_compress_and_password_roundtrip() = runTest {
        val pdfFile = File(tempFolder.root, "sample.pdf")
        PdfEngine.createBlankPdf(context, pdfFile, "Office Pro Report")

        assertTrue(pdfFile.exists())
        assertEquals(DetectedFormat.PDF, DocumentDetector.detect(pdfFile).format)
        assertTrue(PdfEngine.validatePdfIntegrity(pdfFile))
        assertEquals(1, PdfEngine.getPageCount(pdfFile))

        // Add text
        val addRes = PdfTextEditor.addText(
            context = context,
            file = pdfFile,
            pageIndex = 0,
            text = "Hello World 2026",
            xFraction = 0.15f,
            yFraction = 0.30f,
            fontSizePt = 14f,
            colorInt = Color.BLACK
        )
        assertTrue(addRes is SafeSaveResult.Success)

        val extracted = PdfEngine.extractAllText(context, pdfFile)
        assertTrue(extracted.contains("Hello World 2026"))

        val searchHits = PdfEngine.searchTextPages(context, pdfFile, "Hello World")
        assertEquals(listOf(0), searchHits)

        // Extract positioned blocks & edit one
        val blocks = PdfTextEditor.extractPageTextBlocks(context, pdfFile, 0)
        assertTrue(blocks.isNotEmpty())
        val targetBlock = blocks.firstOrNull { it.text.contains("Hello") } ?: blocks.first()
        val editRes = PdfTextEditor.editTextBlock(
            context = context,
            file = pdfFile,
            block = targetBlock,
            newText = "Updated Line",
            newFontSizePt = 14f,
            newColorInt = Color.BLUE
        )
        assertTrue(editRes is SafeSaveResult.Success)
        val afterEditText = PdfEngine.extractAllText(context, pdfFile)
        assertTrue(afterEditText.contains("Updated Line"))

        // Lossless compression preserves selectable text
        val compressed = File(tempFolder.root, "compressed.pdf")
        val compRes = PdfEngine.compressPdf(context, pdfFile, compressed, imageBased = false)
        assertTrue(compRes is SafeSaveResult.Success)
        assertTrue(PdfEngine.extractAllText(context, compressed).contains("Updated Line"))

        // Password protect & remove
        assertFalse(PdfEngine.isPasswordProtected(context, pdfFile))
        assertTrue(PdfEngine.protectWithPassword(context, pdfFile, "1234") is SafeSaveResult.Success)
        assertTrue(PdfEngine.isPasswordProtected(context, pdfFile))
        assertTrue(PdfEngine.removePassword(context, pdfFile, "1234") is SafeSaveResult.Success)
        assertFalse(PdfEngine.isPasswordProtected(context, pdfFile))
    }

    @Test
    fun arabic_pdf_shaping_produces_presentation_forms() {
        val shaped = ArabicPdfTextHelper.shapeArabic("مرحبا بكم في أوفيس برو")
        assertTrue(shaped.isNotEmpty())
        assertNotEquals("مرحبا بكم في أوفيس برو", shaped)
    }

    @Test
    fun word_docx_preserves_unmodified_styles_and_media_on_save() {
        val docxFile = File(tempFolder.root, "preserve.docx")
        val initialDoc = WordDocumentModel(
            title = "Test",
            paragraphs = listOf(WordParagraph(text = "الفقرة الأولى"), WordParagraph(text = "الفقرة الثانية"))
        )
        assertTrue(WordEngine.saveDocument(docxFile, initialDoc, context) is SafeSaveResult.Success)

        // Inject custom untouched parts (styles.xml, theme1.xml, media/logo.png)
        val pkg = OoxmlPackage.readFrom(docxFile)
        val customStyles = "<w:styles/>".toByteArray()
        val fakePng = byteArrayOf(1, 2, 3, 4, 5, 6)
        pkg.putEntry("word/styles.xml", customStyles)
        pkg.putEntry("word/media/logo.png", fakePng)
        pkg.writeTo(docxFile)

        // Load, edit only paragraph 0, and save
        val loaded = WordEngine.loadDocument(docxFile)
        val edited = loaded.copy(
            paragraphs = loaded.paragraphs.mapIndexed { idx, p ->
                if (idx == 0) p.copy(text = "الفقرة المعدلة") else p
            }
        )
        assertTrue(WordEngine.saveDocument(docxFile, edited, context) is SafeSaveResult.Success)

        // Verify untouched parts are still intact byte-for-byte
        val afterPkg = OoxmlPackage.readFrom(docxFile)
        assertArrayEquals(customStyles, afterPkg.getEntry("word/styles.xml"))
        assertArrayEquals(fakePng, afterPkg.getEntry("word/media/logo.png"))

        val reloaded = WordEngine.loadDocument(docxFile)
        assertEquals("الفقرة المعدلة", reloaded.paragraphs[0].text)
        assertEquals("الفقرة الثانية", reloaded.paragraphs[1].text)
    }

    @Test
    fun word_odt_roundtrip() {
        val odtFile = File(tempFolder.root, "doc.odt")
        val doc = WordDocumentModel(paragraphs = listOf(WordParagraph(text = "مستند ODT عربي")))
        assertTrue(WordEngine.saveDocument(odtFile, doc, context) is SafeSaveResult.Success)
        assertEquals(DetectedFormat.ODT, DocumentDetector.detect(odtFile).format)
        val loaded = WordEngine.loadDocument(odtFile)
        assertEquals("مستند ODT عربي", loaded.paragraphs.first().text)
    }

    @Test
    fun excel_xlsm_preserves_macros_charts_and_formulas_on_save() {
        val xlsmFile = File(tempFolder.root, "macro_book.xlsm")
        val sheet = ExcelSheet(
            name = "المبيعات",
            cells = mapOf(
                "0,0" to ExcelCell(rawInput = "100", displayValue = "100"),
                "1,0" to ExcelCell(rawInput = "200", displayValue = "200"),
                "2,0" to ExcelCell(rawInput = "=SUM(A1:A2)", displayValue = "300")
            )
        )
        val wb = ExcelWorkbookModel(sheets = listOf(sheet))
        assertTrue(ExcelEngine.saveWorkbook(xlsmFile, wb, context) is SafeSaveResult.Success)

        // Inject vbaProject.bin and chart1.xml
        val pkg = OoxmlPackage.readFrom(xlsmFile)
        val vbaBytes = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 99)
        val chartBytes = "<c:chartSpace/>".toByteArray()
        pkg.putEntry("xl/vbaProject.bin", vbaBytes)
        pkg.putEntry("xl/charts/chart1.xml", chartBytes)
        pkg.writeTo(xlsmFile)

        assertEquals(DetectedFormat.XLSM, DocumentDetector.detect(xlsmFile).format)

        // Load, edit A1 to 150, save
        val loaded = ExcelEngine.loadWorkbook(xlsmFile)
        val updatedCells = loaded.sheets[0].cells.toMutableMap()
        updatedCells["0,0"] = ExcelCell(rawInput = "150", displayValue = "150")
        val updatedWb = loaded.copy(
            sheets = listOf(FormulaEvaluator.recalculateSheet(loaded.sheets[0].copy(cells = updatedCells)))
        )
        assertTrue(ExcelEngine.saveWorkbook(xlsmFile, updatedWb, context) is SafeSaveResult.Success)

        // Verify macros & charts preserved + formula recalculated
        val afterPkg = OoxmlPackage.readFrom(xlsmFile)
        assertArrayEquals(vbaBytes, afterPkg.getEntry("xl/vbaProject.bin"))
        assertArrayEquals(chartBytes, afterPkg.getEntry("xl/charts/chart1.xml"))

        val reloaded = ExcelEngine.loadWorkbook(xlsmFile)
        assertEquals("=SUM(A1:A2)", reloaded.sheets[0].cells["2,0"]?.rawInput)
        assertEquals("350", reloaded.sheets[0].cells["2,0"]?.displayValue)
    }

    @Test
    fun powerpoint_pptx_preserves_masters_and_media_on_save() {
        val pptxFile = File(tempFolder.root, "deck.pptx")
        val pres = PresentationModel(
            slides = listOf(
                SlideModel(elements = listOf(SlideElement(text = "العنوان الأصلي")))
            )
        )
        assertTrue(PowerPointEngine.savePresentation(pptxFile, pres, context) is SafeSaveResult.Success)

        val pkg = OoxmlPackage.readFrom(pptxFile)
        val masterBytes = "<p:sldMaster/>".toByteArray()
        pkg.putEntry("ppt/slideMasters/slideMaster1.xml", masterBytes)
        pkg.writeTo(pptxFile)

        val loaded = PowerPointEngine.loadPresentation(pptxFile)
        val edited = loaded.copy(
            slides = listOf(
                loaded.slides[0].copy(
                    elements = listOf(loaded.slides[0].elements[0].copy(text = "العنوان المحدث"))
                )
            )
        )
        assertTrue(PowerPointEngine.savePresentation(pptxFile, edited, context) is SafeSaveResult.Success)

        val afterPkg = OoxmlPackage.readFrom(pptxFile)
        assertArrayEquals(masterBytes, afterPkg.getEntry("ppt/slideMasters/slideMaster1.xml"))
        val reloaded = PowerPointEngine.loadPresentation(pptxFile)
        assertEquals("العنوان المحدث", reloaded.slides[0].elements[0].text)
    }

    @Test
    fun safe_save_manager_preserves_original_on_failure() {
        val file = File(tempFolder.root, "important.txt")
        file.writeText("Original Content", Charsets.UTF_8)

        val res = SafeSaveManager.saveAtomically(file, context) { _ ->
            throw RuntimeException("Simulated disk error")
        }
        assertTrue(res is SafeSaveResult.Failure)
        assertEquals("Original Content", file.readText(Charsets.UTF_8))
    }
}
