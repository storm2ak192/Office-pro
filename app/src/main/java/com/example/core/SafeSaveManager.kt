package com.example.core

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

sealed class SafeSaveResult {
    data class Success(val savedFile: File) : SafeSaveResult()
    data class Failure(val message: String, val cause: Throwable? = null) : SafeSaveResult()
}

/**
 * Atomic, backup-protected file saver:
 * 1. Never modifies the original file in-place during serialization.
 * 2. Writes to a temporary sibling file first.
 * 3. Validates the written file (non-empty + optional format validator).
 * 4. Creates a backup of the existing file before replacing it.
 * 5. Atomically renames (or copies+verifies) temp -> target, restoring backup if anything fails.
 */
object SafeSaveManager {

    fun saveAtomically(
        targetFile: File,
        context: Context? = null,
        validator: ((File) -> Boolean)? = null,
        writeAction: (File) -> Unit
    ): SafeSaveResult {
        val parent = targetFile.parentFile ?: return SafeSaveResult.Failure("مسار الملف غير صالح")
        if (!parent.exists()) parent.mkdirs()

        val tempFile = File(parent, ".${targetFile.name}.${System.currentTimeMillis()}.tmp")
        val backupFile = createBackupFile(targetFile, context)

        return try {
            // 1. Backup existing file if present
            if (targetFile.exists() && targetFile.length() > 0L && backupFile != null) {
                copyFile(targetFile, backupFile)
            }

            // 2. Write to temporary file
            writeAction(tempFile)

            // 3. Validate temporary file
            if (!tempFile.exists() || tempFile.length() == 0L) {
                tempFile.delete()
                return SafeSaveResult.Failure("فشل التحقق من الملف المؤقت: الملف الناتج فارغ")
            }
            if (validator != null && !validator(tempFile)) {
                tempFile.delete()
                return SafeSaveResult.Failure("فشل التحقق من سلامة بنية الملف قبل الاستبدال")
            }

            // 4. Replace target file atomically
            if (targetFile.exists() && !targetFile.delete()) {
                // Fallback: overwrite via stream if delete is blocked
                copyFile(tempFile, targetFile)
                tempFile.delete()
            } else if (!tempFile.renameTo(targetFile)) {
                copyFile(tempFile, targetFile)
                tempFile.delete()
            }

            // 5. Final verification
            if (!targetFile.exists() || targetFile.length() == 0L) {
                if (backupFile != null && backupFile.exists()) {
                    copyFile(backupFile, targetFile)
                }
                return SafeSaveResult.Failure("تعذر التحقق من الملف النهائي بعد الحفظ؛ تم استعادة النسخة الاحتياطية")
            }

            SafeSaveResult.Success(targetFile)
        } catch (e: Exception) {
            tempFile.delete()
            if (backupFile != null && backupFile.exists() && (!targetFile.exists() || targetFile.length() == 0L)) {
                runCatching { copyFile(backupFile, targetFile) }
            }
            SafeSaveResult.Failure(e.localizedMessage ?: "حدث خطأ أثناء الحفظ الآمن", e)
        }
    }

    fun saveAs(
        targetDir: File,
        desiredName: String,
        context: Context? = null,
        validator: ((File) -> Boolean)? = null,
        writeAction: (File) -> Unit
    ): SafeSaveResult {
        if (!targetDir.exists()) targetDir.mkdirs()
        val dest = FileUtils.ensureUniqueFile(targetDir, desiredName)
        return saveAtomically(dest, context, validator, writeAction)
    }

    private fun createBackupFile(targetFile: File, context: Context?): File? {
        return runCatching {
            val backupDir = if (context != null) {
                File(context.cacheDir, "office_backups").apply { mkdirs() }
            } else {
                targetFile.parentFile
            }
            val safeKey = targetFile.absolutePath.hashCode().toUInt().toString(16)
            File(backupDir, "${targetFile.nameWithoutExtension}_$safeKey.bak")
        }.getOrNull()
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { inp ->
            FileOutputStream(dst).use { out ->
                inp.copyTo(out)
                out.fd.sync()
            }
        }
    }
}
