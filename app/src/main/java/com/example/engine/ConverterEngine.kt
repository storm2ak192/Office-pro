package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

enum class ConversionType(val titleAr: String, val titleEn: String, val sourceExts: List<String>, val targetExt: String) {
    WORD_TO_PDF("Word إلى PDF", "Word to PDF", listOf("docx", "dotx", "rtf", "doc", "dot", "odt"), "pdf"),
    PDF_TO_WORD("PDF إلى Word", "PDF to Word", listOf("pdf"), "docx"),
    EXCEL_TO_PDF("Excel إلى PDF", "Excel to PDF", listOf("xlsx", "xlsm", "xlsb", "csv", "xls", "ods"), "pdf"),
    PDF_TO_EXCEL("PDF إلى Excel", "PDF to Excel", listOf("pdf"), "xlsx"),
    PPT_TO_PDF("PowerPoint إلى PDF", "PowerPoint to PDF", listOf("pptx", "ppsx", "potx", "ppt", "pps", "pot", "odp"), "pdf"),
    PDF_TO_PPT("PDF إلى PowerPoint", "PDF to PowerPoint", listOf("pdf"), "pptx"),
    IMAGES_TO_PDF("صور إلى PDF واحد", "Images to Single PDF", listOf("jpg", "jpeg", "png", "webp", "bmp"), "pdf"),
    PDF_TO_IMAGES("PDF إلى صور", "PDF to Images", listOf("pdf"), "png"),
    TXT_TO_PDF("TXT إلى PDF", "TXT to PDF", listOf("txt"), "pdf"),
    LEGACY_TO_MODERN("ترقية الصيغ القديمة (DOC/XLS/PPT)", "Upgrade Legacy Formats", listOf("doc", "xls", "ppt"), "modern")
}

object ConverterEngine {

    suspend fun convertSingleFile(context: Context, srcFile: File, type: ConversionType, outDir: File): Result<List<File>> {
        return runCatching {
            val baseName = srcFile.nameWithoutExtension
            when (type) {
                ConversionType.WORD_TO_PDF, ConversionType.TXT_TO_PDF -> {
                    val doc = WordEngine.loadDocument(srcFile)
                    val out = File(outDir, "$baseName.pdf")
                    WordEngine.exportToPdf(doc, out)
                    listOf(out)
                }
                ConversionType.PDF_TO_WORD -> {
                    val text = PdfEngine.extractAllText(context, srcFile)
                    if (text.isBlank()) {
                        throw IllegalStateException("ملف PDF لا يحتوي على نص قابل للاستخراج (قد يكون ممسوحاً ضوئياً كصور).")
                    }
                    val paragraphs = text.lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { line ->
                            val isAr = line.any { it in '\u0600'..'\u06FF' }
                            WordParagraph(
                                text = line,
                                isRtl = isAr,
                                alignment = if (isAr) WordAlignment.RIGHT else WordAlignment.LEFT
                            )
                        }
                    val doc = WordDocumentModel(title = baseName, paragraphs = paragraphs)
                    val out = File(outDir, "$baseName.docx")
                    WordEngine.saveDocument(out, doc)
                    listOf(out)
                }
                ConversionType.EXCEL_TO_PDF -> {
                    val wb = ExcelEngine.loadWorkbook(srcFile)
                    val out = File(outDir, "$baseName.pdf")
                    ExcelEngine.exportToPdf(wb, out)
                    listOf(out)
                }
                ConversionType.PDF_TO_EXCEL -> {
                    val text = PdfEngine.extractAllText(context, srcFile)
                    if (text.isBlank()) {
                        throw IllegalStateException("تعذر استخراج بيانات جدولية من ملف PDF هذا.")
                    }
                    val cells = mutableMapOf<String, ExcelCell>()
                    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    lines.forEachIndexed { r, line ->
                        val cols = line.split(Regex("\\s{2,}|\\t|\\|")).map { it.trim() }.filter { it.isNotEmpty() }
                        cols.forEachIndexed { c, v ->
                            cells[FormulaEvaluator.cellKey(r, c)] = ExcelCell(rawInput = v, displayValue = v)
                        }
                    }
                    val wb = ExcelWorkbookModel(
                        title = baseName,
                        sheets = listOf(ExcelSheet(name = "بيانات PDF", rowCount = maxOf(30, lines.size + 5), cells = cells))
                    )
                    val out = File(outDir, "$baseName.xlsx")
                    ExcelEngine.saveWorkbook(out, wb)
                    listOf(out)
                }
                ConversionType.PPT_TO_PDF -> {
                    val pres = PowerPointEngine.loadPresentation(srcFile)
                    val out = File(outDir, "$baseName.pdf")
                    PowerPointEngine.exportToPdf(pres, out)
                    listOf(out)
                }
                ConversionType.PDF_TO_PPT -> {
                    val count = PdfEngine.getPageCount(srcFile)
                    if (count <= 0) throw IllegalStateException("ملف PDF فارغ أو محمي بكلمة مرور.")
                    val fullText = PdfEngine.extractAllText(context, srcFile)
                    val chunks = fullText.lines().filter { it.isNotBlank() }.chunked(6)
                    val slides = if (chunks.isEmpty()) {
                        listOf(SlideModel())
                    } else {
                        chunks.mapIndexed { idx, lines ->
                            SlideModel(
                                elements = listOf(
                                    SlideElement(
                                        type = SlideElementType.TEXT,
                                        xFraction = 0.08f,
                                        yFraction = 0.10f,
                                        widthFraction = 0.84f,
                                        heightFraction = 0.18f,
                                        text = "شريحة ${idx + 1}",
                                        fontSizeSp = 24,
                                        isBold = true
                                    ),
                                    SlideElement(
                                        type = SlideElementType.TEXT,
                                        xFraction = 0.08f,
                                        yFraction = 0.32f,
                                        widthFraction = 0.84f,
                                        heightFraction = 0.55f,
                                        text = lines.joinToString("\n"),
                                        fontSizeSp = 16
                                    )
                                )
                            )
                        }
                    }
                    val pres = PresentationModel(title = baseName, slides = slides)
                    val out = File(outDir, "$baseName.pptx")
                    PowerPointEngine.savePresentation(out, pres)
                    listOf(out)
                }
                ConversionType.IMAGES_TO_PDF -> {
                    val out = File(outDir, "$baseName.pdf")
                    imagesToPdf(listOf(srcFile), out)
                    listOf(out)
                }
                ConversionType.PDF_TO_IMAGES -> {
                    val count = PdfEngine.getPageCount(srcFile)
                    if (count <= 0) throw IllegalStateException("تعذر قراءة صفحات PDF.")
                    val outFiles = mutableListOf<File>()
                    for (i in 0 until count) {
                        val bmp = PdfEngine.renderPageBitmap(context, srcFile, i, 2.0f) ?: continue
                        val imgFile = File(outDir, "${baseName}_page_${i + 1}.png")
                        FileOutputStream(imgFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
                        outFiles.add(imgFile)
                    }
                    outFiles
                }
                ConversionType.LEGACY_TO_MODERN -> {
                    when (srcFile.extension.lowercase()) {
                        "doc" -> {
                            val doc = WordEngine.loadDocument(srcFile)
                            val out = File(outDir, "$baseName.docx")
                            WordEngine.saveDocument(out, doc)
                            listOf(out)
                        }
                        "xls" -> {
                            val wb = ExcelEngine.loadWorkbook(srcFile)
                            val out = File(outDir, "$baseName.xlsx")
                            ExcelEngine.saveWorkbook(out, wb)
                            listOf(out)
                        }
                        "ppt" -> {
                            val pres = PowerPointEngine.loadPresentation(srcFile)
                            val out = File(outDir, "$baseName.pptx")
                            PowerPointEngine.savePresentation(out, pres)
                            listOf(out)
                        }
                        else -> throw IllegalArgumentException("الصيغة غير مدعومة للترقية: ${srcFile.extension}")
                    }
                }
            }
        }
    }

    fun imagesToPdf(imageFiles: List<File>, outPdf: File) {
        val pdf = PdfDocument()
        imageFiles.forEachIndexed { idx, imgFile ->
            val bmp = BitmapFactory.decodeFile(imgFile.absolutePath) ?: return@forEachIndexed
            val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, idx + 1).create()
            val page = pdf.startPage(pageInfo)
            page.canvas.drawBitmap(bmp, 0f, 0f, null)
            pdf.finishPage(page)
            bmp.recycle()
        }
        FileOutputStream(outPdf).use { pdf.writeTo(it) }
        pdf.close()
    }
}
