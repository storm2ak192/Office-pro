package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.FileOutputStream

object PdfEngine {
    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
    }

    fun createBlankPdf(context: Context, outFile: File, title: String = "Office Pro PDF") {
        init(context)
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 16f)
                cs.newLineAtOffset(50f, 770f)
                cs.showText(title.filter { it.code in 32..126 }.ifEmpty { "Office Pro Document" })
                cs.endText()
            }
            doc.save(outFile)
        }
    }

    fun getPageCount(file: File, password: String? = null): Int {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer -> renderer.pageCount }
            }
        } catch (e: Exception) {
            try {
                PDDocument.load(file, password ?: "").use { it.numberOfPages }
            } catch (_: Exception) {
                0
            }
        }
    }

    fun renderPageBitmap(file: File, pageIndex: Int, scale: Float = 1.5f): Bitmap? {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
                    renderer.openPage(pageIndex).use { page ->
                        val w = (page.width * scale).toInt().coerceIn(100, 2400)
                        val h = (page.height * scale).toInt().coerceIn(100, 3400)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun extractAllText(context: Context, file: File, password: String? = null): String {
        init(context)
        return PDDocument.load(file, password ?: "").use { doc ->
            PDFTextStripper().getText(doc)
        }
    }

    fun searchTextPages(context: Context, file: File, query: String): List<Int> {
        init(context)
        if (query.isBlank()) return emptyList()
        val matches = mutableListOf<Int>()
        PDDocument.load(file).use { doc ->
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
        return matches
    }

    fun addTextAnnotation(
        context: Context,
        file: File,
        pageIndex: Int,
        text: String,
        xFraction: Float,
        yFraction: Float,
        fontSize: Float = 14f,
        colorInt: Int = Color.BLUE
    ) {
        init(context)
        PDDocument.load(file).use { doc ->
            val page = doc.getPage(pageIndex.coerceIn(0, doc.numberOfPages - 1))
            val mediaBox = page.mediaBox
            val x = mediaBox.lowerLeftX + xFraction * mediaBox.width
            val y = mediaBox.lowerLeftY + (1f - yFraction) * mediaBox.height
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, fontSize)
                cs.setNonStrokingColor(Color.red(colorInt), Color.green(colorInt), Color.blue(colorInt))
                cs.newLineAtOffset(x, y)
                val safeText = text.filter { it.code in 32..126 }.ifEmpty { "Note" }
                cs.showText(safeText)
                cs.endText()
            }
            doc.save(file)
        }
    }

    fun addHighlightOrShape(
        context: Context,
        file: File,
        pageIndex: Int,
        xFraction: Float,
        yFraction: Float,
        wFraction: Float,
        hFraction: Float,
        colorInt: Int = Color.YELLOW,
        isFilledHighlight: Boolean = true
    ) {
        init(context)
        PDDocument.load(file).use { doc ->
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
            doc.save(file)
        }
    }

    fun addBitmapOverlay(
        context: Context,
        file: File,
        pageIndex: Int,
        bitmap: Bitmap,
        xFraction: Float = 0.25f,
        yFraction: Float = 0.65f,
        widthFraction: Float = 0.4f
    ) {
        init(context)
        PDDocument.load(file).use { doc ->
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
            doc.save(file)
        }
    }

    fun addBlankPage(context: Context, file: File) {
        init(context)
        PDDocument.load(file).use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            doc.save(file)
        }
    }

    fun deletePage(context: Context, file: File, pageIndex: Int) {
        init(context)
        PDDocument.load(file).use { doc ->
            if (doc.numberOfPages > 1 && pageIndex in 0 until doc.numberOfPages) {
                doc.removePage(pageIndex)
                doc.save(file)
            }
        }
    }

    fun rotatePage(context: Context, file: File, pageIndex: Int, degrees: Int = 90) {
        init(context)
        PDDocument.load(file).use { doc ->
            val page = doc.getPage(pageIndex.coerceIn(0, doc.numberOfPages - 1))
            page.rotation = (page.rotation + degrees) % 360
            doc.save(file)
        }
    }

    fun cropPage(context: Context, file: File, pageIndex: Int, marginInsetPt: Float = 36f) {
        init(context)
        PDDocument.load(file).use { doc ->
            val page = doc.getPage(pageIndex.coerceIn(0, doc.numberOfPages - 1))
            val box = page.cropBox
            val newBox = PDRectangle(
                box.lowerLeftX + marginInsetPt,
                box.lowerLeftY + marginInsetPt,
                (box.width - marginInsetPt * 2).coerceAtLeast(100f),
                (box.height - marginInsetPt * 2).coerceAtLeast(100f)
            )
            page.cropBox = newBox
            doc.save(file)
        }
    }

    fun reorderPages(context: Context, file: File, newOrder: List<Int>) {
        init(context)
        PDDocument.load(file).use { src ->
            PDDocument().use { dest ->
                newOrder.forEach { idx ->
                    if (idx in 0 until src.numberOfPages) {
                        dest.importPage(src.getPage(idx))
                    }
                }
                dest.save(file)
            }
        }
    }

    fun extractPages(context: Context, srcFile: File, pageIndices: List<Int>, outFile: File) {
        init(context)
        PDDocument.load(srcFile).use { src ->
            PDDocument().use { dest ->
                pageIndices.forEach { idx ->
                    if (idx in 0 until src.numberOfPages) {
                        dest.importPage(src.getPage(idx))
                    }
                }
                dest.save(outFile)
            }
        }
    }

    fun mergePdfs(context: Context, files: List<File>, outFile: File) {
        init(context)
        val merger = PDFMergerUtility()
        merger.destinationFileName = outFile.absolutePath
        files.forEach { merger.addSource(it) }
        merger.mergeDocuments(null)
    }

    fun splitPdf(context: Context, srcFile: File, splitAfterPage: Int, outPart1: File, outPart2: File) {
        init(context)
        PDDocument.load(srcFile).use { src ->
            val total = src.numberOfPages
            val cut = splitAfterPage.coerceIn(1, (total - 1).coerceAtLeast(1))
            PDDocument().use { p1 ->
                for (i in 0 until cut) p1.importPage(src.getPage(i))
                p1.save(outPart1)
            }
            PDDocument().use { p2 ->
                for (i in cut until total) p2.importPage(src.getPage(i))
                p2.save(outPart2)
            }
        }
    }

    fun addPageNumbers(context: Context, file: File) {
        init(context)
        PDDocument.load(file).use { doc ->
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
            doc.save(file)
        }
    }

    fun addWatermark(context: Context, file: File, watermarkText: String) {
        init(context)
        val safeText = watermarkText.filter { it.code in 32..126 }.ifEmpty { "CONFIDENTIAL" }
        PDDocument.load(file).use { doc ->
            for (i in 0 until doc.numberOfPages) {
                val page = doc.getPage(i)
                val box = page.mediaBox
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 42f)
                    cs.setNonStrokingColor(203, 213, 225)
                    cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(45.0), box.width * 0.25f, box.height * 0.35f))
                    cs.showText(safeText)
                    cs.endText()
                }
            }
            doc.save(file)
        }
    }

    fun protectWithPassword(context: Context, file: File, password: String) {
        init(context)
        PDDocument.load(file).use { doc ->
            val ap = AccessPermission()
            val spp = StandardProtectionPolicy(password, password, ap)
            spp.encryptionKeyLength = 128
            doc.protect(spp)
            doc.save(file)
        }
    }

    fun removePassword(context: Context, file: File, currentPassword: String) {
        init(context)
        PDDocument.load(file, currentPassword).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(file)
        }
    }

    fun compressPdf(context: Context, srcFile: File, outFile: File) {
        // Re-render pages at optimized 150 DPI JPEG/PNG into a clean compressed PDF
        init(context)
        val count = getPageCount(srcFile)
        PDDocument().use { dest ->
            for (i in 0 until count) {
                val bmp = renderPageBitmap(srcFile, i, 1.2f) ?: continue
                val page = PDPage(PDRectangle(bmp.width.toFloat(), bmp.height.toFloat()))
                dest.addPage(page)
                val pdImg = LosslessFactory.createFromImage(dest, bmp)
                PDPageContentStream(dest, page).use { cs ->
                    cs.drawImage(pdImg, 0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
                }
                bmp.recycle()
            }
            dest.save(outFile)
        }
    }
}
