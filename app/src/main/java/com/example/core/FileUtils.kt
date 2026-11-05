package com.example.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.db.OfficeFileType
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUtils {

    fun getWorkspaceRoot(context: Context): File {
        val dir = File(context.filesDir, "OfficePro")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getMimeType(extension: String): String {
        val ext = extension.lowercase(Locale.ROOT).removePrefix(".")
        return when (ext) {
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "dotx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.template"
            "doc", "dot" -> "application/msword"
            "rtf" -> "application/rtf"
            "txt" -> "text/plain"
            "odt" -> "application/vnd.oasis.opendocument.text"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "xlsm" -> "application/vnd.ms-excel.sheet.macroEnabled.12"
            "xlsb" -> "application/vnd.ms-excel.sheet.binary.macroEnabled.12"
            "xls" -> "application/vnd.ms-excel"
            "csv" -> "text/csv"
            "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "ppsx" -> "application/vnd.openxmlformats-officedocument.presentationml.slideshow"
            "potx" -> "application/vnd.openxmlformats-officedocument.presentationml.template"
            "ppt", "pps", "pot" -> "application/vnd.ms-powerpoint"
            "odp" -> "application/vnd.oasis.opendocument.presentation"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    fun detectFileType(extension: String): OfficeFileType {
        return DocumentDetector.fromExtension(extension).officeType
    }

    fun queryDisplayName(context: Context, uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx)
                }
            }
        }
        if (name.isNullOrBlank()) {
            name = uri.path?.substringAfterLast('/') ?: "document_${System.currentTimeMillis()}"
        }
        return name!!
    }

    fun copyUriToWorkspace(context: Context, uri: Uri, folderPath: String = ""): File? {
        return try {
            val fileName = queryDisplayName(context, uri)
            val targetDir = if (folderPath.isBlank()) {
                getWorkspaceRoot(context)
            } else {
                File(getWorkspaceRoot(context), folderPath).apply { mkdirs() }
            }
            val uniqueFile = ensureUniqueFile(targetDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(uniqueFile).use { output ->
                    input.copyTo(output)
                }
            }
            uniqueFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun ensureUniqueFile(dir: File, desiredName: String): File {
        var candidate = File(dir, desiredName)
        if (!candidate.exists()) return candidate
        val base = desiredName.substringBeforeLast('.', desiredName)
        val ext = desiredName.substringAfterLast('.', "")
        var counter = 1
        while (candidate.exists()) {
            val newName = if (ext.isNotEmpty()) "${base}_$counter.$ext" else "${base}_$counter"
            candidate = File(dir, newName)
            counter++
        }
        return candidate
    }

    fun shareFile(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val mime = getMimeType(file.extension)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, file.name))
        } catch (e: Exception) {
            Toast.makeText(context, e.localizedMessage ?: "Share failed", Toast.LENGTH_SHORT).show()
        }
    }

    fun openWithExternalApp(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val mime = getMimeType(file.extension)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, file.name))
        } catch (e: Exception) {
            Toast.makeText(context, "No external app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    fun printPdfFile(context: Context, pdfFile: File, jobName: String = pdfFile.nameWithoutExtension) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder(pdfFile.name)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback
            ) {
                try {
                    FileInputStream(pdfFile).use { input ->
                        FileOutputStream(destination.fileDescriptor).use { output ->
                            input.copyTo(output)
                        }
                    }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }
        }
        printManager.print(jobName, adapter, PrintAttributes.Builder().build())
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
    }

    fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }
}
