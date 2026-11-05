package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.example.core.SafeSaveManager
import com.example.core.SafeSaveResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

data class PdfTextBlock(
    val id: String = UUID.randomUUID().toString(),
    val pageIndex: Int,
    val text: String,
    val xFraction: Float,
    val yFraction: Float,
    val wFraction: Float,
    val hFraction: Float,
    val fontSizePt: Float = 12f,
    val isOcrDetected: Boolean = false
)

/**
 * Extracts positioned text blocks from text-based PDFs (or OCR for scanned PDFs)
 * and performs real PDF content-stream text editing, deletion, and insertion.
 */
object PdfTextEditor {

    fun extractPageTextBlocks(
        context: Context,
        file: File,
        pageIndex: Int,
        password: String? = null
    ): List<PdfTextBlock> {
        PdfEngine.init(context)
        return runCatching {
            PDDocument.load(file, password ?: "").use { doc ->
                if (pageIndex !in 0 until doc.numberOfPages) return emptyList()
                val page = doc.getPage(pageIndex)
                val box = page.mediaBox
                val pageW = box.width.coerceAtLeast(1f)
                val pageH = box.height.coerceAtLeast(1f)

                val blocks = mutableListOf<PdfTextBlock>()
                val stripper = object : PDFTextStripper() {
                    override fun writeString(text: String, textPositions: List<TextPosition>) {
                        val trimmed = text.trim()
                        if (trimmed.isEmpty() || textPositions.isEmpty()) return

                        var minX = Float.MAX_VALUE
                        var maxX = Float.MIN_VALUE
                        var minY = Float.MAX_VALUE
                        var maxY = Float.MIN_VALUE
                        var maxFontPt = 12f

                        textPositions.forEach { tp ->
                            val x = tp.xDirAdj
                            val y = tp.yDirAdj
                            val w = tp.widthDirAdj
                            val h = tp.heightDir.coerceAtLeast(tp.fontSizeInPt * 0.8f)
                            if (x < minX) minX = x
                            if (x + w > maxX) maxX = x + w
                            if (y - h < minY) minY = y - h
                            if (y > maxY) maxY = y
                            if (tp.fontSizeInPt > maxFontPt) maxFontPt = tp.fontSizeInPt
                        }

                        val xFrac = (minX / pageW).coerceIn(0f, 0.98f)
                        val yFrac = (minY / pageH).coerceIn(0f, 0.98f)
                        val wFrac = ((maxX - minX) / pageW).coerceIn(0.03f, 1f - xFrac)
                        val hFrac = ((maxY - minY) / pageH).coerceIn(0.015f, 0.25f)

                        blocks.add(
                            PdfTextBlock(
                                pageIndex = pageIndex,
                                text = trimmed,
                                xFraction = xFrac,
                                yFraction = yFrac,
                                wFraction = wFrac,
                                hFraction = hFrac,
                                fontSizePt = maxFontPt.coerceIn(7f, 48f)
                            )
                        )
                    }
                }
                stripper.startPage = pageIndex + 1
                stripper.endPage = pageIndex + 1
                stripper.getText(doc)
                blocks
            }
        }.getOrDefault(emptyList())
    }

    suspend fun detectScannedPageTextWithOcr(
        bitmap: Bitmap,
        pageIndex: Int
    ): List<PdfTextBlock> = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        val bmpW = bitmap.width.toFloat().coerceAtLeast(1f)
        val bmpH = bitmap.height.toFloat().coerceAtLeast(1f)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val list = mutableListOf<PdfTextBlock>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val rect = line.boundingBox ?: continue
                        val xFrac = (rect.left / bmpW).coerceIn(0f, 0.98f)
                        val yFrac = (rect.top / bmpH).coerceIn(0f, 0.98f)
                        val wFrac = (rect.width() / bmpW).coerceIn(0.03f, 1f - xFrac)
                        val hFrac = (rect.height() / bmpH).coerceIn(0.015f, 0.25f)
                        list.add(
                            PdfTextBlock(
                                pageIndex = pageIndex,
                                text = line.text,
                                xFraction = xFrac,
                                yFraction = yFrac,
                                wFraction = wFrac,
                                hFraction = hFrac,
                                fontSizePt = (hFrac * 792f * 0.75f).coerceIn(8f, 36f),
                                isOcrDetected = true
                            )
                        )
                    }
                }
                recognizer.close()
                if (cont.isActive) cont.resume(list)
            }
            .addOnFailureListener {
                recognizer.close()
                if (cont.isActive) cont.resume(emptyList())
            }
    }

    fun editTextBlock(
        context: Context,
        file: File,
        block: PdfTextBlock,
        newText: String,
        newFontSizePt: Float = block.fontSizePt,
        newColorInt: Int = Color.BLACK,
        password: String? = null
    ): SafeSaveResult {
        PdfEngine.init(context)
        val result = SafeSaveManager.saveAtomically(
            targetFile = file,
            context = context,
            validator = { PdfEngine.validatePdfIntegrity(it, password) }
        ) { tempOut ->
            PDDocument.load(file, password ?: "").use { doc ->
                val page = doc.getPage(block.pageIndex.coerceIn(0, doc.numberOfPages - 1))
                val box = page.mediaBox
                val padX = 2f
                val padY = 2f
                val rectX = (box.lowerLeftX + block.xFraction * box.width - padX).coerceAtLeast(box.lowerLeftX)
                val rectW = (block.wFraction * box.width + padX * 2).coerceAtLeast(16f)
                val rectH = (block.hFraction * box.height + padY * 2).coerceAtLeast(12f)
                val rectY = box.lowerLeftY + (1f - block.yFraction) * box.height - rectH + padY

                val font = ArabicPdfTextHelper.loadUnicodeFont(context, doc)
                val prepared = ArabicPdfTextHelper.prepareForPdfShowText(newText, font)

                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    // 1. Mask original text region with clean page background
                    cs.setNonStrokingColor(255, 255, 255)
                    cs.addRect(rectX, rectY, rectW, rectH)
                    cs.fill()

                    // 2. Draw updated text if non-empty
                    if (newText.isNotEmpty()) {
                        cs.beginText()
                        cs.setFont(font, newFontSizePt)
                        cs.setNonStrokingColor(
                            Color.red(newColorInt),
                            Color.green(newColorInt),
                            Color.blue(newColorInt)
                        )
                        val baselineY = rectY + (rectH - newFontSizePt) * 0.35f + 2f
                        cs.newLineAtOffset(rectX + padX, baselineY)
                        cs.showText(prepared)
                        cs.endText()
                    }
                }
                doc.save(tempOut)
            }
        }
        if (result is SafeSaveResult.Success) {
            PdfRenderCache.invalidateFile(file.absolutePath)
        }
        return result
    }

    fun deleteTextBlock(
        context: Context,
        file: File,
        block: PdfTextBlock,
        password: String? = null
    ): SafeSaveResult {
        return editTextBlock(
            context = context,
            file = file,
            block = block,
            newText = "",
            password = password
        )
    }

    fun addText(
        context: Context,
        file: File,
        pageIndex: Int,
        text: String,
        xFraction: Float,
        yFraction: Float,
        fontSizePt: Float = 14f,
        colorInt: Int = Color.BLACK,
        password: String? = null
    ): SafeSaveResult {
        PdfEngine.init(context)
        val result = SafeSaveManager.saveAtomically(
            targetFile = file,
            context = context,
            validator = { PdfEngine.validatePdfIntegrity(it, password) }
        ) { tempOut ->
            PDDocument.load(file, password ?: "").use { doc ->
                val page = doc.getPage(pageIndex.coerceIn(0, doc.numberOfPages - 1))
                val box = page.mediaBox
                val x = box.lowerLeftX + xFraction.coerceIn(0.02f, 0.92f) * box.width
                val y = box.lowerLeftY + (1f - yFraction.coerceIn(0.03f, 0.95f)) * box.height
                val font = ArabicPdfTextHelper.loadUnicodeFont(context, doc)
                val prepared = ArabicPdfTextHelper.prepareForPdfShowText(text, font)

                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.beginText()
                    cs.setFont(font, fontSizePt)
                    cs.setNonStrokingColor(
                        Color.red(colorInt),
                        Color.green(colorInt),
                        Color.blue(colorInt)
                    )
                    cs.newLineAtOffset(x, y)
                    cs.showText(prepared)
                    cs.endText()
                }
                doc.save(tempOut)
            }
        }
        if (result is SafeSaveResult.Success) {
            PdfRenderCache.invalidateFile(file.absolutePath)
        }
        return result
    }
}
