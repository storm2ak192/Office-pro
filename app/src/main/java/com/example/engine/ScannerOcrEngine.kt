package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.pdf.PdfDocument
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ScanFilterMode { ORIGINAL, ENHANCED, GRAYSCALE, BLACK_AND_WHITE }

object ScannerOcrEngine {

    fun applyPerspectiveCrop(
        source: Bitmap,
        topLeft: PointF,
        topRight: PointF,
        bottomRight: PointF,
        bottomLeft: PointF
    ): Bitmap {
        val w = source.width.toFloat()
        val h = source.height.toFloat()
        val srcPts = floatArrayOf(
            topLeft.x * w, topLeft.y * h,
            topRight.x * w, topRight.y * h,
            bottomRight.x * w, bottomRight.y * h,
            bottomLeft.x * w, bottomLeft.y * h
        )
        val outW = (maxOf(
            kotlin.math.hypot((topRight.x - topLeft.x) * w, (topRight.y - topLeft.y) * h),
            kotlin.math.hypot((bottomRight.x - bottomLeft.x) * w, (bottomRight.y - bottomLeft.y) * h)
        )).toInt().coerceIn(100, 2400)
        val outH = (maxOf(
            kotlin.math.hypot((bottomLeft.x - topLeft.x) * w, (bottomLeft.y - topLeft.y) * h),
            kotlin.math.hypot((bottomRight.x - topRight.x) * w, (bottomRight.y - topRight.y) * h)
        )).toInt().coerceIn(100, 3200)

        val dstPts = floatArrayOf(
            0f, 0f,
            outW.toFloat(), 0f,
            outW.toFloat(), outH.toFloat(),
            0f, outH.toFloat()
        )

        val matrix = Matrix()
        matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return result
    }

    fun applyFilterAndAdjustments(
        source: Bitmap,
        filterMode: ScanFilterMode,
        brightness: Float = 0f, // -100..100
        contrast: Float = 1f    // 0.5..2.0
    ): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val cm = ColorMatrix()

        when (filterMode) {
            ScanFilterMode.ORIGINAL -> {}
            ScanFilterMode.ENHANCED -> {
                val c = contrast * 1.25f
                val t = (-0.5f * c + 0.5f) * 255f + brightness + 10f
                cm.set(
                    floatArrayOf(
                        c, 0f, 0f, 0f, t,
                        0f, c, 0f, 0f, t,
                        0f, 0f, c, 0f, t,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            }
            ScanFilterMode.GRAYSCALE -> {
                cm.setSaturation(0f)
            }
            ScanFilterMode.BLACK_AND_WHITE -> {
                cm.setSaturation(0f)
                val c = 2.2f
                val t = (-0.5f * c + 0.5f) * 255f + brightness
                val bw = ColorMatrix(
                    floatArrayOf(
                        c, 0f, 0f, 0f, t,
                        0f, c, 0f, 0f, t,
                        0f, 0f, c, 0f, t,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                cm.postConcat(bw)
            }
        }

        if (filterMode == ScanFilterMode.ORIGINAL || filterMode == ScanFilterMode.GRAYSCALE) {
            val t = (-0.5f * contrast + 0.5f) * 255f + brightness
            val bc = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, t,
                    0f, contrast, 0f, 0f, t,
                    0f, 0f, contrast, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(bc)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
    }

    fun rotateBitmap(source: Bitmap, degrees: Float = 90f): Bitmap {
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
    }

    fun saveBitmapsAsPdf(bitmaps: List<Bitmap>, outPdf: File) {
        val pdf = PdfDocument()
        bitmaps.forEachIndexed { idx, bmp ->
            val info = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, idx + 1).create()
            val page = pdf.startPage(info)
            page.canvas.drawBitmap(bmp, 0f, 0f, null)
            pdf.finishPage(page)
        }
        FileOutputStream(outPdf).use { pdf.writeTo(it) }
        pdf.close()
    }

    suspend fun recognizeTextOfflineMlKit(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    cont.resume(visionText.text)
                }
                .addOnFailureListener { e ->
                    cont.resumeWithException(e)
                }
        }

    suspend fun recognizeArabicAndEnglishWithGemini(bitmap: Bitmap): String {
        val model = Firebase.ai.generativeModel("gemini-3.8-flash")
        val response = withTimeout(60_000L) {
            model.generateContent(
                content {
                    image(bitmap)
                    text("استخرج جميع النصوص العربية والإنجليزية من صورة المستند بدقة تامة وحافظ على ترتيب الفقرات دون إضافة أي مقدمات.")
                }
            )
        }
        return response.text.orEmpty()
    }
}
