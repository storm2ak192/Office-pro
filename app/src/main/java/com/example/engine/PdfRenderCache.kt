package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Thread-safe, memory-bounded PDF page renderer & LRU bitmap cache.
 * - Guards PdfRenderer with a Mutex (PdfRenderer is not thread-safe).
 * - Uses LruCache<String, Bitmap> sized to 1/6 of heap (capped at 48MB) to prevent OOM.
 * - Never calls Bitmap.recycle() on cached bitmaps while Compose may still be drawing them.
 * - Supports password-protected PDFs by decrypting to a temporary cache file.
 */
object PdfRenderCache {

    private val renderMutex = Mutex()

    private val maxCacheBytes: Int = run {
        val maxMem = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        (maxMem / 6).coerceIn(8 * 1024, 48 * 1024) // in KB
    }

    private val bitmapCache = object : LruCache<String, Bitmap>(maxCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private fun cacheKey(filePath: String, lastModified: Long, pageIndex: Int, scaleBucket: Int): String =
        "${filePath}_${lastModified}_p${pageIndex}_s${scaleBucket}"

    fun invalidateFile(filePath: String) {
        val snapshot = bitmapCache.snapshot()
        snapshot.keys.filter { it.startsWith("${filePath}_") }.forEach {
            bitmapCache.remove(it)
        }
    }

    fun clearAll() {
        bitmapCache.evictAll()
    }

    suspend fun renderPage(
        context: Context,
        file: File,
        pageIndex: Int,
        scale: Float = 1.5f,
        password: String? = null
    ): Bitmap? {
        if (!file.exists() || pageIndex < 0) return null
        val scaleBucket = (scale * 10).toInt().coerceIn(5, 30)
        val key = cacheKey(file.absolutePath, file.lastModified(), pageIndex, scaleBucket)
        bitmapCache.get(key)?.let { cached ->
            if (!cached.isRecycled) return cached
        }

        return renderMutex.withLock {
            // Double-check after acquiring lock
            bitmapCache.get(key)?.let { cached ->
                if (!cached.isRecycled) return@withLock cached
            }

            val targetFile = resolveRenderableFile(context, file, password) ?: return@withLock null
            val bmp = renderWithPdfRenderer(targetFile, pageIndex, scale)
            if (bmp != null) {
                bitmapCache.put(key, bmp)
            }
            bmp
        }
    }

    private fun resolveRenderableFile(context: Context, file: File, password: String?): File? {
        if (password.isNullOrEmpty()) return file
        return runCatching {
            PdfEngine.init(context)
            val decryptedDir = File(context.cacheDir, "pdf_decrypted").apply { mkdirs() }
            val out = File(decryptedDir, "${file.absolutePath.hashCode().toUInt().toString(16)}.pdf")
            if (!out.exists() || out.lastModified() < file.lastModified()) {
                PDDocument.load(file, password).use { doc ->
                    doc.isAllSecurityToBeRemoved = true
                    doc.save(out)
                }
            }
            out
        }.getOrNull()
    }

    private fun renderWithPdfRenderer(file: File, pageIndex: Int, scale: Float): Bitmap? {
        return runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
                    renderer.openPage(pageIndex).use { page ->
                        val w = (page.width * scale).toInt().coerceIn(120, 2200)
                        val h = (page.height * scale).toInt().coerceIn(120, 3200)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        }.getOrNull()
    }
}
