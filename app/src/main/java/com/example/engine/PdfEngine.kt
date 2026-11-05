package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.example.core.SafeSaveManager
import com.example.core.SafeSaveResult
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

object PdfEngine {
    @Volatile
    private var initialized = false

    fun init(context: Context?) {
        if (!initialized && context != null) {
            runCatching {
                PDFBoxResourceLoader.init(context.applicationContext)
            }
            initialized = true
        }
    }

    fun validatePdfIntegrity(file: File, password: String? = null): Boolean {
        if (!file.exists() || file.length() < 8L) return false
        return runCatching {
            PDDocument.load(file, password ?: "").use { it.numberOfPages > 0 }
        }.getOrDefault(false)
    }

    fun isPasswordProtected(context: Context?, file: File): Boolean {
        init(context)
        return try {
            PDDocument.load(file, "").use { false }
        } catch (_: InvalidPasswordException) {
            true
        } catch (_: Exception) {
            false
        }
    }

    fun createBlankPdf(context: Context?, outFile: File, title: String = "Office Pro PDF") {
        init(context)
        SafeSaveManager.saveAtomically(outFile, context) { tempOut ->
            PDDocument().use { doc ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                val font = ArabicPdfTextHelper.loadUnicodeFont(context, doc)
                val prepared = ArabicPdfTextHelper.prepareForPdfShowText(title, font)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(font, 16f)
                    cs.newLineAtOffset(50f, 770f)
                    cs.showText(prepared)
                    cs.endText()
                }
                doc.save(tempOut)
            }
        }
    }

    fun getPageCount(file: File, password: String? = null): Int {
        if (!file.exists() || file.length() == 0L) return 0
        if (password.isNullOrEmpty()) {
            val nativeCount = runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer -> renderer.pageCount }
                }
            }.getOrNull()
            if (nativeCount != null && nativeCount > 0) return nativeCount
        }
        return runCatching {
            PDDocument.load(file, password ?: "").use { it.numberOfPages }
        }.getOrDefault(0)
    }

    suspend fun renderPageBitmap(
        context: Context,
        file: File,
        pageIndex: Int,
        scale: Float = 1.5f,
        password: String? = null
    ): Bitmap? {
        return PdfRenderCache.renderPage(context, file, pageIndex, scale, password)
    }

    fun extractAllText(context: Context?, file: File, password: String? = null): String {
        init(context)
        return PDDocument.load(file, password ?: "").use { doc ->
            PDFTextStripper().getText(doc)
        }
    }

    fun extractPageText(context: Context?, file: File, pageIndex: Int, password: String? = null): String {
        init(context)
        return runCatching {
            PDDocument.load(file, password ?: "").use { doc ->
                if (pageIndex !in 0 until doc.numberOfPages) return ""
                val stripper = PDFTextStripper()
                stripper.startPage = pageIndex + 1
                stripper.endPage = pageIndex + 1
                stripper.getText(doc)
            }
        }.getOrDefault("")
    }

    fun searchTextPages(context: Context?, file: File, query: String, password: String? = null): List<Int> {
        init(context)
        if (query.isBlank()) return emptyList()
        val matches = mutableListOf<Int>()
        runCatching {
            PDDocument.load(file, password ?: "").use { doc ->
                val stripper = PDFTextStripper()
                for (p in 1..doc.numberOfPages) {
                    stripper.startPage = p
                    stripper.endPage = p
                    val text = stripper.getText(doc)
                    if (text.contains(query, ignoreCase = true)) {
                        matches.add(p - 1)
                    }
                }
            }
        }
        return matches
    }

    fun addTextAnnotation(
        context: Context?,
        file: File,
        pageIndex: Int,
        text: String,
        xFraction: Float,
        yFraction: Float,
        fontSize: Float = 14f,
        colorInt: Int = Color.BLUE,
        password: String? = null
    ): SafeSaveResult {
        init(context)
        val res = SafeSaveManager.saveAtomically(
            targetFile = file,
            context = context,
            validator = { validatePdfIntegrity(it, password) }
        ) { tempOut ->
            PDDocument.load(file, password ?: "").use { doc ->
                val page = doc.getPage(pageIndex.coerceIn(0, doc.numberOfPages - 1))
                val mediaBox = page.mediaBox
                val x = mediaBox.lowerLeftX + xFraction * mediaBox.width
                val y = mediaBox.lowerLeftY + (1f - yFraction) * mediaBox.height
                val font = ArabicPdfTextHelper.loadUnicodeFont(context, doc)
                val prepared = ArabicPdfTextHelper.prepareForPdfShowText(text, font)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.beginText()
                    cs.setFont(font, fontSize)
                    cs.setNonStrokingColor(Color.red(colorInt), Color.green(colorInt), Color.blue(colorInt))
                    cs.newLineAtOffset(x, y)
                    cs.showText(prepared)
                    cs.endText()
                }
                doc.save(tempOut)
            }
        }
        if (res is SafeSaveResult.Success) PdfRenderCache.invalidateFile(file.absolutePath)
        return res
    }

    fun addHighlightOrShape(
        context: Context?,
        file: File,
        pageIndex: Int,
        xFraction: Float,
        yFraction: Float,
        wFraction: Float,
        hFraction: Float,
        colorInt: Int = Color.YELLOW,
        isFilledHighlight: Boolean = true,
        password: String? = null
    ): SafeSaveResult {
        init(context)
        val res = SafeSaveManager.saveAtomically(
            targetFile = file,
            context = context,
            validator = { validatePdfIntegrity(it, password) }
        ) { tempOut ->
            PDDocument.load(file, password ?: "").use { doc ->
                val page = doc.getPage(pageIndex.coerceIn(0, doc.numberOfPages - 1))
                val box = page.mediaBox
                val x = box.lowerLeftX + xFraction * box.width
                val w = wFraction * box.width
                val h = hFraction * box.height
                val y = box.lowerLeftY + (1f - yFraction) * box.height - h
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    if (isFilledHighlight) {
                        cs.setNonStrokingColor(Color.red(colorInt), Color.green(colorInt), Color.blue(colorInt))
                        cs.addRect(x, y, w, h)
                        cs.fill()
                    } else {
                        cs.setStrokingColor(Color.red(colorInt), Color.green(colorInt), Color.blue(colorInt))
                        cs.setLineWidth(2f)
                        cs.addRect(x, y, w, h)
                        cs.stroke()
                    }
                }
                doc.save(tempOut)
            }
        }
        if (res is SafeSaveResult.Success) PdfRenderCache.invalidateFile(file.absolutePath)
        return res
    }

    fun addBitmapOverlay(
        context: Context?,
        file: File,
        pageIndex: Int,
        bitmap: Bitmap,
        xFraction: Float = 0.25f,
        yFraction: Float = 0.65f,
        widthFraction: Float = 0.4f,
        password: String? = null
    ): SafeSaveResult {
        init(context)
        val res = SafeSaveManager.saveAtomically(
            targetFile = file,
            context = context,
            validator = { validatePdfIntegrity(it, password) }
        ) { tempOut ->
            PDDocument.load(file, password ?: "").use { doc ->
                val page = doc.getPage(pageIndex.coerceIn(0, doc.numberOfPages - 1))
                val box = page.mediaBox
                val w = widthFraction * box.width
                val aspect = bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1)
                val h = w * aspect
                val x = box.lowerLeftX + xFraction * box.width
                val y = box.lowerLeftY + (1f - yFraction) * box.height - h
                val pdImage = LosslessFactory.createFromImage(doc, bitmap)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.drawImage(pdImage, x, y, w, h)
                }
                doc.save(tempOut)
            }
        }
        if (res is SafeSaveResult.Success) PdfRenderCache.invalidateFile(file.absolutePath)
        return res
    }

    fun addBlankPage(context: Context?, file: File, password: String? = null): SafeSaveResult {
        init(context)
        val res = SafeSaveManager.saveAtomically(
            targetFile = file,
            context = context,
            validator = { validatePdfIntegrity(it, password) }
        ) { tempOut ->
            PDDocument.load(file, password ?: "").use { doc ->
                doc.addPage(PDPage(PDRectangle.A4))
                doc.save(tempOut)
            }
        }
        if (res is SafeSaveResult.Success) PdfRenderCache.invalidateFile(file.absolutePath)
        return res
    }

    fun deletePage(context: Context?, file: File, pageIndex: Int, password: String? = null): SafeSaveResult {
        init(context)
        val res = SafeSaveManager.saveAtomically(
            targetFile = file,
            context = context,
            validator = { validatePdfIntegrity(it, password) }
        ) { tempOut ->
            PDDocument.load(file, password ?: "").use { doc ->
                if (doc.numberOfPages > 1 && pageIndex in 0 until doc.numberOfPages) {
                    doc.removePage(pageIndex)
                }
                doc.save(tempOut)
            }
        }
        if (res is SafeSaveResult.Success) PdfRenderCache.invalidateFile(file.absolutePath)
        return res
    }

    fun rotatePage(context: Context?, file: File, pageIndex: Int, degrees: Int = 90, password: String? = null): SafeSaveResult {
        init(context)
        val res = SafeSaveManager.saveAtomically(
            targetFile = file,
            context = context,
            validator = { validatePdfIntegrity(it, password) }
        ) { tempOut ->
            PDDocument.load(file, password ?: "").use { doc ->
                val page = doc.getPage(pageIndex.coerceIn(0, doc.numberOfPages - 1))
                page.rotation = (page.rotation + degrees) % 360
                doc.save(tempOut)
            }
        }
        if (res is SafeSaveResult.Success) PdfRenderCache.invalidateFile(file.absolutePath)
        return res
    }

    fun cropPage(context: Context?, file: File, pageIndex: Int, marginInsetPt: Float = 36f, password: String? = null): SafeSaveResult {
        init(context)
        val res = SafeSaveManager.saveAtomically(
            targetFile = file,
            context = context,
            validator = { validatePdfIntegrity(it, password) }
        ) { tempOut ->
            PDDocument.load(file, password ?: "").use { doc ->
                val page = doc.getPage(pageIndex.coerceIn(0, doc.numberOfPages - 1))
                val box = page.cropBox
                val newBox = PDRectangle(
                    box.lowerLeftX + marginInsetPt,
                    box.lowerLeftY + marginInsetPt,
                    (box.width - marginInsetPt * 2).coerceAtLeast(100f),
                    (box.height - marginInsetPt * 2).coerceAtLeast(100f)
                )
                page.cropBox = newBox
                doc.save(tempOut)
            }
        }
        if (res is SafeSaveResult.Success) PdfRenderCache.invalidateFile(file.absolutePath)
        return res
    }

    fun reorderPages(context: Context?, file: File, newOrder: List<Int>, password: String? = null): SafeSaveResult {
        init(context)
        val res = SafeSaveManager.saveAtomically(
            targetFile = file,
            context = context,
            validator = { validatePdfIntegrity(it, password) }
        ) { tempOut ->
            PDDocument.load(file, password ?: "").use { src ->
                PDDocument().use { dest ->
                    newOrder.forEach { idx ->
                        if (idx in 0 until src.numberOfPages) {
                            dest.importPage(src.getPage(idx))
                        }
                    }
                    dest.save(tempOut)
                }
            }
        }
        if (res is SafeSaveResult.Success) PdfRenderCache.invalidateFile(file.absolutePath)
        return res
    }

    fun extractPages(context: Context?, srcFile: File, pageIndices: List<Int>, outFile: File, password: String? = null) {
        init(context)
        SafeSaveManager.saveAtomically(outFile, context) { tempOut ->
            PDDocument.load(srcFile, password ?: "").use { src ->
                PDDocument().use { dest ->
                    pageIndices.forEach { idx ->
                        if (idx in 0 until src.numberOfPages) {
                            dest.importPage(src.getPage(idx))
                        }
                    }
                    dest.save(tempOut)
                }
            }
        }
    }

    fun mergePdfs(context: Context?, files: List<File>, outFile: File) {
        init(context)
        SafeSaveManager.saveAtomically(outFile, context) { tempOut ->
            val merger = PDFMergerUtility()
            merger.destinationFileName = tempOut.absolutePath
            files.forEach { merger.addSource(it) }
            merger.mergeDocuments(null)
        }
    }

    fun splitPdf(context: Context?, srcFile: File, splitAfterPage: Int, outPart1: File, outPart2: File, password: String? = null) {
        init(context)
        PDDocument.load(srcFile, password ?: "").use { src ->
            val total = src.numberOfPages
            val cut = splitAfterPage.coerceIn(1, (total - 1).coerceAtLeast(1))
            SafeSaveManager.saveAtomically(outPart1, context) { temp1 ->
                PDDocument().use { p1 ->
                    for (i in 0 until cut) p1.importPage(src.getPage(i))
                    p1.save(temp1)
                }
            }
            SafeSaveManager.saveAtomically(outPart2, context) { temp2 ->
                PDDocument().use { p2 ->
                    for (i in cut until total) p2.importPage(src.getPage(i))
                    p2.save(temp2)
                }
            }
        }
    }

    fun addPageNumbers(context: Context?, file: File, password: String? = null): SafeSaveResult {
        init(context)
        val res = SafeSaveManager.saveAtomically(
            targetFile = file,
            context = context,
            validator = { validatePdfIntegrity(it, password) }
        ) { tempOut ->
            PDDocument.load(file, password ?: "").use { doc ->
                val total = doc.numberOfPages
                for (i in 0 until total) {
                    val page = doc.getPage(i)
                    val box = page.mediaBox
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA, 10f)
                        cs.setNonStrokingColor(100, 116, 139)
                        cs.newLineAtOffset(box.width / 2f - 15f, 24f)
                        cs.showText("${i + 1} / $total")
                        cs.endText()
                    }
                }
                doc.save(tempOut)
            }
        }
        if (res is SafeSaveResult.Success) PdfRenderCache.invalidateFile(file.absolutePath)
        return res
    }

    fun addWatermark(context: Context?, file: File, watermarkText: String, password: String? = null): SafeSaveResult {
        init(context)
        val res = SafeSaveManager.saveAtomically(
            targetFile = file,
            context = context,
            validator = { validatePdfIntegrity(it, password) }
        ) { tempOut ->
            PDDocument.load(file, password ?: "").use { doc ->
                val font = ArabicPdfTextHelper.loadUnicodeFont(context, doc)
                val prepared = ArabicPdfTextHelper.prepareForPdfShowText(watermarkText, font)
                for (i in 0 until doc.numberOfPages) {
                    val page = doc.getPage(i)
                    val box = page.mediaBox
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.beginText()
                        cs.setFont(font, 38f)
                        cs.setNonStrokingColor(203, 213, 225)
                        cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(45.0), box.width * 0.25f, box.height * 0.35f))
                        cs.showText(prepared)
                        cs.endText()
                    }
                }
                doc.save(tempOut)
            }
        }
        if (res is SafeSaveResult.Success) PdfRenderCache.invalidateFile(file.absolutePath)
        return res
    }

    fun protectWithPassword(context: Context?, file: File, password: String): SafeSaveResult {
        init(context)
        val res = SafeSaveManager.saveAtomically(
            targetFile = file,
            context = context,
            validator = { validatePdfIntegrity(it, password) }
        ) { tempOut ->
            PDDocument.load(file).use { doc ->
                val ap = AccessPermission()
                val spp = StandardProtectionPolicy(password, password, ap)
                spp.encryptionKeyLength = 128
                doc.protect(spp)
                doc.save(tempOut)
            }
        }
        if (res is SafeSaveResult.Success) PdfRenderCache.invalidateFile(file.absolutePath)
        return res
    }

    fun removePassword(context: Context?, file: File, currentPassword: String): SafeSaveResult {
        init(context)
        val res = SafeSaveManager.saveAtomically(
            targetFile = file,
            context = context,
            validator = { validatePdfIntegrity(it, null) }
        ) { tempOut ->
            PDDocument.load(file, currentPassword).use { doc ->
                doc.isAllSecurityToBeRemoved = true
                doc.save(tempOut)
            }
        }
        if (res is SafeSaveResult.Success) PdfRenderCache.invalidateFile(file.absolutePath)
        return res
    }

    /**
     * Compresses a PDF file:
     * - Default (imageBased = false): Lossless structural compression (re-compresses streams &
     *   strips redundant document info/thumbnails while preserving 100% of selectable text & vectors).
     * - Optional (imageBased = true): Re-renders pages as optimized JPEG for scanned documents.
     */
    suspend fun compressPdf(
        context: Context,
        srcFile: File,
        outFile: File,
        imageBased: Boolean = false,
        password: String? = null
    ): SafeSaveResult {
        init(context)
        return if (!imageBased) {
            SafeSaveManager.saveAtomically(
                targetFile = outFile,
                context = context,
                validator = { validatePdfIntegrity(it, password) }
            ) { tempOut ->
                PDDocument.load(srcFile, password ?: "").use { doc ->
                    doc.documentCatalog.metadata = null
                    doc.save(tempOut)
                }
            }
        } else {
            val count = getPageCount(srcFile, password)
            val bitmaps = (0 until count).mapNotNull { i ->
                PdfRenderCache.renderPage(context, srcFile, i, 1.25f, password)
            }
            SafeSaveManager.saveAtomically(
                targetFile = outFile,
                context = context,
                validator = { validatePdfIntegrity(it, null) }
            ) { tempOut ->
                PDDocument().use { dest ->
                    bitmaps.forEach { bmp ->
                        val page = PDPage(PDRectangle(bmp.width.toFloat(), bmp.height.toFloat()))
                        dest.addPage(page)
                        val pdImg = JPEGFactory.createFromImage(dest, bmp, 0.75f)
                        PDPageContentStream(dest, page).use { cs ->
                            cs.drawImage(pdImg, 0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
                        }
                    }
                    dest.save(tempOut)
                }
            }
        }
    }
}
