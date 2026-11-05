package com.example.core

import com.example.data.db.OfficeFileType
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.zip.ZipInputStream

enum class DocumentMode {
    VIEW,
    EDIT
}

sealed class SaveState {
    data object Saved : SaveState()
    data object Unsaved : SaveState()
    data object Saving : SaveState()
    data class Failed(val reason: String) : SaveState()
}

enum class DetectedFormat(
    val extension: String,
    val officeType: OfficeFileType,
    val isEditable: Boolean,
    val supportNoteAr: String
) {
    DOCX("docx", OfficeFileType.WORD, true, "دعم كامل مع الحفاظ على التنسيقات والوسائط"),
    DOTX("dotx", OfficeFileType.WORD, true, "قالب Word مع الحفاظ على البنية الأصلية"),
    DOC("doc", OfficeFileType.WORD, true, "مستند Word ثنائي قديم (استخراج النصوص وحفظ بصيغة DOCX/RTF)"),
    DOT("dot", OfficeFileType.WORD, true, "قالب Word ثنائي قديم"),
    RTF("rtf", OfficeFileType.WORD, true, "نص منسق RTF"),
    TXT("txt", OfficeFileType.WORD, true, "نص عادي UTF-8"),
    ODT("odt", OfficeFileType.WORD, true, "مستند OpenDocument Text"),

    XLSX("xlsx", OfficeFileType.EXCEL, true, "دعم كامل مع الحفاظ على الصيغ والأنماط والرسوم"),
    XLSM("xlsm", OfficeFileType.EXCEL, true, "جدول بيانات مع الحفاظ على وحدات الماكرو vbaProject.bin"),
    XLSB("xlsb", OfficeFileType.EXCEL, false, "ملف Excel ثنائي (عرض النصوص والبيانات المستخرجة؛ يُنصح بالحفظ كـ XLSX للتحرير الكامل)"),
    XLS("xls", OfficeFileType.EXCEL, true, "جدول Excel ثنائي قديم"),
    CSV("csv", OfficeFileType.EXCEL, true, "قيم مفصولة بفواصل CSV"),
    ODS("ods", OfficeFileType.EXCEL, true, "جدول OpenDocument Spreadsheet"),

    PPTX("pptx", OfficeFileType.POWERPOINT, true, "دعم كامل مع الحفاظ على الشرائح الرئيسية والوسائط"),
    PPSX("ppsx", OfficeFileType.POWERPOINT, true, "عرض شرائح PowerPoint"),
    POTX("potx", OfficeFileType.POWERPOINT, true, "قالب عرض تقديمي PowerPoint"),
    PPT("ppt", OfficeFileType.POWERPOINT, true, "عرض PowerPoint ثنائي قديم"),
    PPS("pps", OfficeFileType.POWERPOINT, true, "عرض شرائح ثنائي قديم"),
    POT("pot", OfficeFileType.POWERPOINT, true, "قالب عرض ثنائي قديم"),
    ODP("odp", OfficeFileType.POWERPOINT, true, "عرض OpenDocument Presentation"),

    PDF("pdf", OfficeFileType.PDF, true, "عرض أصلي عالي الدقة مع تحرير النصوص والتعليقات والصفحات"),

    PNG("png", OfficeFileType.IMAGE, false, "صورة PNG"),
    JPG("jpg", OfficeFileType.IMAGE, false, "صورة JPEG"),
    WEBP("webp", OfficeFileType.IMAGE, false, "صورة WebP"),
    BMP("bmp", OfficeFileType.IMAGE, false, "صورة BMP")
}

data class DocumentDetectionResult(
    val format: DetectedFormat,
    val isValidIntegrity: Boolean,
    val errorMessage: String? = null
)

object DocumentDetector {

    fun detect(file: File): DocumentDetectionResult {
        if (!file.exists()) {
            return DocumentDetectionResult(
                format = fromExtension(file.extension),
                isValidIntegrity = false,
                errorMessage = "الملف غير موجود"
            )
        }
        if (file.length() == 0L) {
            // Empty newly created txt/csv is valid
            val extFmt = fromExtension(file.extension)
            val emptyAllowed = extFmt == DetectedFormat.TXT || extFmt == DetectedFormat.CSV
            return DocumentDetectionResult(
                format = extFmt,
                isValidIntegrity = emptyAllowed,
                errorMessage = if (emptyAllowed) null else "الملف فارغ أو تالف (0 بايت)"
            )
        }

        val header = ByteArray(16)
        val readCount = runCatching {
            FileInputStream(file).use { it.read(header) }
        }.getOrDefault(0)

        val extFormat = fromExtension(file.extension)

        // 1. Check PDF magic bytes "%PDF-"
        if (readCount >= 5 &&
            header[0] == 0x25.toByte() &&
            header[1] == 0x50.toByte() &&
            header[2] == 0x44.toByte() &&
            header[3] == 0x46.toByte() &&
            header[4] == 0x2D.toByte()
        ) {
            return DocumentDetectionResult(DetectedFormat.PDF, true)
        }

        // 2. Check RTF magic bytes "{\rtf"
        if (readCount >= 5 &&
            header[0] == '{'.code.toByte() &&
            header[1] == '\\'.code.toByte() &&
            header[2] == 'r'.code.toByte() &&
            header[3] == 't'.code.toByte() &&
            header[4] == 'f'.code.toByte()
        ) {
            return DocumentDetectionResult(DetectedFormat.RTF, true)
        }

        // 3. Check Image magic bytes
        if (readCount >= 8 &&
            header[0] == 0x89.toByte() &&
            header[1] == 0x50.toByte() &&
            header[2] == 0x4E.toByte() &&
            header[3] == 0x47.toByte()
        ) {
            return DocumentDetectionResult(DetectedFormat.PNG, true)
        }
        if (readCount >= 3 &&
            header[0] == 0xFF.toByte() &&
            header[1] == 0xD8.toByte() &&
            header[2] == 0xFF.toByte()
        ) {
            return DocumentDetectionResult(DetectedFormat.JPG, true)
        }
        if (readCount >= 12 &&
            header[0] == 'R'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() &&
            header[9] == 'E'.code.toByte()
        ) {
            return DocumentDetectionResult(DetectedFormat.WEBP, true)
        }
        if (readCount >= 2 && header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()) {
            return DocumentDetectionResult(DetectedFormat.BMP, true)
        }

        // 4. Check ZIP container (OOXML or OpenDocument)
        if (readCount >= 4 &&
            header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            (header[2] == 0x03.toByte() || header[2] == 0x05.toByte())
        ) {
            val zipFormat = inspectZipContainer(file, extFormat)
            return if (zipFormat != null) {
                DocumentDetectionResult(zipFormat, true)
            } else {
                DocumentDetectionResult(extFormat, false, "أرشيف المستند غير مكتمل أو تالف")
            }
        }

        // 5. Check OLE2 Compound Binary (DOC, XLS, PPT, DOT, PPS, POT)
        if (readCount >= 8 &&
            header[0] == 0xD0.toByte() &&
            header[1] == 0xCF.toByte() &&
            header[2] == 0x11.toByte() &&
            header[3] == 0xE0.toByte()
        ) {
            val oleFmt = when (extFormat.officeType) {
                OfficeFileType.EXCEL -> DetectedFormat.XLS
                OfficeFileType.POWERPOINT -> if (extFormat == DetectedFormat.PPS) DetectedFormat.PPS else if (extFormat == DetectedFormat.POT) DetectedFormat.POT else DetectedFormat.PPT
                else -> if (extFormat == DetectedFormat.DOT) DetectedFormat.DOT else DetectedFormat.DOC
            }
            return DocumentDetectionResult(oleFmt, true)
        }

        // 6. Fallback to extension (TXT, CSV, etc.)
        return DocumentDetectionResult(extFormat, true)
    }

    private fun inspectZipContainer(file: File, fallback: DetectedFormat): DetectedFormat? {
        return runCatching {
            var hasWord = false
            var hasExcel = false
            var hasPpt = false
            var hasMacros = false
            var hasContentXml = false
            var mimeText = ""
            var entryCount = 0

            ZipInputStream(FileInputStream(file)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    entryCount++
                    val name = entry.name
                    when {
                        name == "mimetype" -> {
                            mimeText = zip.readBytes().toString(Charsets.UTF_8).trim()
                        }
                        name == "content.xml" -> hasContentXml = true
                        name.startsWith("word/") -> hasWord = true
                        name.startsWith("xl/") -> {
                            hasExcel = true
                            if (name.endsWith("vbaProject.bin")) hasMacros = true
                        }
                        name.startsWith("ppt/") -> hasPpt = true
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            if (entryCount == 0) return null

            when {
                mimeText.contains("opendocument.text") -> DetectedFormat.ODT
                mimeText.contains("opendocument.spreadsheet") -> DetectedFormat.ODS
                mimeText.contains("opendocument.presentation") -> DetectedFormat.ODP
                hasContentXml && fallback == DetectedFormat.ODT -> DetectedFormat.ODT
                hasContentXml && fallback == DetectedFormat.ODS -> DetectedFormat.ODS
                hasContentXml && fallback == DetectedFormat.ODP -> DetectedFormat.ODP
                hasWord -> if (fallback == DetectedFormat.DOTX) DetectedFormat.DOTX else DetectedFormat.DOCX
                hasExcel -> when {
                    hasMacros || fallback == DetectedFormat.XLSM -> DetectedFormat.XLSM
                    fallback == DetectedFormat.XLSB -> DetectedFormat.XLSB
                    else -> DetectedFormat.XLSX
                }
                hasPpt -> when (fallback) {
                    DetectedFormat.PPSX -> DetectedFormat.PPSX
                    DetectedFormat.POTX -> DetectedFormat.POTX
                    else -> DetectedFormat.PPTX
                }
                else -> fallback
            }
        }.getOrNull()
    }

    fun fromExtension(extension: String): DetectedFormat {
        return when (extension.lowercase(Locale.ROOT).removePrefix(".")) {
            "docx" -> DetectedFormat.DOCX
            "dotx" -> DetectedFormat.DOTX
            "doc" -> DetectedFormat.DOC
            "dot" -> DetectedFormat.DOT
            "rtf" -> DetectedFormat.RTF
            "txt" -> DetectedFormat.TXT
            "odt" -> DetectedFormat.ODT
            "xlsx" -> DetectedFormat.XLSX
            "xlsm" -> DetectedFormat.XLSM
            "xlsb" -> DetectedFormat.XLSB
            "xls" -> DetectedFormat.XLS
            "csv" -> DetectedFormat.CSV
            "ods" -> DetectedFormat.ODS
            "pptx" -> DetectedFormat.PPTX
            "ppsx" -> DetectedFormat.PPSX
            "potx" -> DetectedFormat.POTX
            "ppt" -> DetectedFormat.PPT
            "pps" -> DetectedFormat.PPS
            "pot" -> DetectedFormat.POT
            "odp" -> DetectedFormat.ODP
            "pdf" -> DetectedFormat.PDF
            "png" -> DetectedFormat.PNG
            "jpg", "jpeg" -> DetectedFormat.JPG
            "webp" -> DetectedFormat.WEBP
            "bmp" -> DetectedFormat.BMP
            else -> DetectedFormat.TXT
        }
    }
}
