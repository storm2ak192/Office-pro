package com.example.engine

import com.example.data.db.OfficeFileType

data class OfficeTemplate(
    val id: String,
    val titleAr: String,
    val titleEn: String,
    val categoryAr: String,
    val categoryEn: String,
    val fileType: OfficeFileType,
    val extension: String
)

object TemplatesProvider {

    val allTemplates = listOf(
        OfficeTemplate("cv", "سيرة ذاتية احترافية", "Professional CV", "السير الذاتية", "Resumes", OfficeFileType.WORD, "docx"),
        OfficeTemplate("letter", "خطاب رسمي", "Official Letter", "الخطابات الرسمية", "Official Letters", OfficeFileType.WORD, "docx"),
        OfficeTemplate("report", "تقرير عمل شامل", "Business Report", "التقارير", "Reports", OfficeFileType.WORD, "docx"),
        OfficeTemplate("research", "ورقة بحثية أكاديمية", "Academic Research", "الأبحاث", "Research", OfficeFileType.WORD, "docx"),
        OfficeTemplate("invoice", "فاتورة ضريبية", "Tax Invoice", "الفواتير", "Invoices", OfficeFileType.EXCEL, "xlsx"),
        OfficeTemplate("budget", "ميزانية شهرية", "Monthly Budget", "الميزانيات", "Budgets", OfficeFileType.EXCEL, "xlsx"),
        OfficeTemplate("ledger", "دفتر حسابات", "Accounting Ledger", "الجداول المحاسبية", "Accounting", OfficeFileType.EXCEL, "xlsx"),
        OfficeTemplate("expenses", "متتبع المصروفات", "Expense Tracker", "جداول المصروفات", "Expenses", OfficeFileType.EXCEL, "xlsx"),
        OfficeTemplate("edu_ppt", "عرض تعليمي تفاعلي", "Educational Deck", "العروض التعليمية", "Education", OfficeFileType.POWERPOINT, "pptx"),
        OfficeTemplate("biz_ppt", "عرض خطة عمل", "Business Pitch", "العروض التجارية", "Business", OfficeFileType.POWERPOINT, "pptx"),
        OfficeTemplate("mkt_ppt", "عرض حملة تسويقية", "Marketing Deck", "العروض التسويقية", "Marketing", OfficeFileType.POWERPOINT, "pptx")
    )

    fun createWordFromTemplate(templateId: String): WordDocumentModel {
        return when (templateId) {
            "cv" -> WordDocumentModel(
                title = "السيرة الذاتية",
                paragraphs = listOf(
                    WordParagraph(text = "الاسم الكامل", style = WordStyle.HEADING_1, isBold = true, fontSizeSp = 22, textColorHex = "#1E3A8A"),
                    WordParagraph(text = "المسمى الوظيفي | البريد الإلكتروني | رقم الهاتف | المدينة", fontSizeSp = 12, textColorHex = "#475569"),
                    WordParagraph(text = "الملخص المهني", style = WordStyle.HEADING_2, isBold = true, fontSizeSp = 16, textColorHex = "#1D4ED8"),
                    WordParagraph(text = "خبير متخصص بخبرة عملية في إدارة المشاريع وتطوير الحلول التقنية وتحقيق أهداف العمل بكفاءة عالية."),
                    WordParagraph(text = "الخبرات العملية", style = WordStyle.HEADING_2, isBold = true, fontSizeSp = 16, textColorHex = "#1D4ED8"),
                    WordParagraph(text = "مدير مشاريع — شركة التقنية الحديثة (2022 - الآن)", listType = WordListType.BULLET, isBold = true),
                    WordParagraph(text = "قيادة فريق متعدد التخصصات وتسليم المشاريع في الوقت المحدد.", listType = WordListType.BULLET),
                    WordParagraph(text = "المؤهلات العلمية والمهارات", style = WordStyle.HEADING_2, isBold = true, fontSizeSp = 16, textColorHex = "#1D4ED8"),
                    WordParagraph(text = "بكالوريوس إدارة الأعمال / علوم الحاسب", listType = WordListType.BULLET)
                )
            )
            "letter" -> WordDocumentModel(
                title = "خطاب رسمي",
                paragraphs = listOf(
                    WordParagraph(text = "التاريخ: 2026/11/05", alignment = WordAlignment.LEFT),
                    WordParagraph(text = "السادة / إدارة الموارد البشرية المحترمين", style = WordStyle.HEADING_2, isBold = true),
                    WordParagraph(text = "الموضوع: طلب تعاون / خطاب رسمي", isBold = true, textColorHex = "#1D4ED8"),
                    WordParagraph(text = "السلام عليكم ورحمة الله وبركاته،"),
                    WordParagraph(text = "نهديكم أطيب التحيات، ونتقدم إليكم بهذا الخطاب بخصوص الموضوع المشار إليه أعلاه، آملين منكم التكرم بالنظر فيه والموافقة عليه."),
                    WordParagraph(text = "وتفضلوا بقبول فائق الاحترام والتقدير،"),
                    WordParagraph(text = "التوقيع: _________________", isBold = true)
                )
            )
            "report" -> WordDocumentModel(
                title = "تقرير الأداء",
                headerText = "تقرير الأداء الدوري - Office Pro",
                paragraphs = listOf(
                    WordParagraph(text = "تقرير الأداء الربع سنوي", style = WordStyle.HEADING_1, isBold = true, fontSizeSp = 22, textColorHex = "#1E3A8A"),
                    WordParagraph(text = "1. الملخص التنفيذي", style = WordStyle.HEADING_2, isBold = true, textColorHex = "#1D4ED8"),
                    WordParagraph(text = "يستعرض هذا التقرير أبرز الإنجازات ومؤشرات الأداء الرئيسية خلال الفترة الماضية."),
                    WordParagraph(text = "2. النتائج الرئيسية", style = WordStyle.HEADING_2, isBold = true, textColorHex = "#1D4ED8"),
                    WordParagraph(text = "تحقيق نمو بنسبة 24% في الكفاءة التشغيلية.", listType = WordListType.NUMBERED),
                    WordParagraph(text = "إطلاق 3 مبادرات تطويرية جديدة.", listType = WordListType.NUMBERED),
                    WordParagraph(text = "3. التوصيات القادمة", style = WordStyle.HEADING_2, isBold = true, textColorHex = "#1D4ED8"),
                    WordParagraph(text = "الاستمرار في تحسين العمليات وتوسيع نطاق الأتمتة.")
                ),
                tables = listOf(
                    WordTable(
                        afterParagraphIndex = 4,
                        rows = listOf(
                            listOf(WordTableCell("المؤشر", isHeader = true), WordTableCell("المستهدف", isHeader = true), WordTableCell("المحقق", isHeader = true)),
                            listOf(WordTableCell("الإنتاجية"), WordTableCell("90%"), WordTableCell("94%")),
                            listOf(WordTableCell("رضا العملاء"), WordTableCell("85%"), WordTableCell("91%"))
                        )
                    )
                )
            )
            else -> WordDocumentModel(
                title = "بحث علمي",
                paragraphs = listOf(
                    WordParagraph(text = "عنوان البحث العلمي", style = WordStyle.HEADING_1, alignment = WordAlignment.CENTER, isBold = true, fontSizeSp = 22),
                    WordParagraph(text = "إعداد الباحث: الاسم الكامل", alignment = WordAlignment.CENTER, textColorHex = "#475569"),
                    WordParagraph(text = "المستخلص (Abstract)", style = WordStyle.HEADING_2, isBold = true, textColorHex = "#1D4ED8"),
                    WordParagraph(text = "يتناول هذا البحث دراسة تحليلية شاملة للموضوع مع استعراض المنهجية والنتائج."),
                    WordParagraph(text = "المقدمة", style = WordStyle.HEADING_2, isBold = true, textColorHex = "#1D4ED8"),
                    WordParagraph(text = "تعد هذه الدراسة خطوة مهمة نحو فهم أعمق للمتغيرات الأساسية..."),
                    WordParagraph(text = "المراجع", style = WordStyle.HEADING_2, isBold = true, textColorHex = "#1D4ED8"),
                    WordParagraph(text = "1. المرجع الأول — دار النشر العلمية، 2025.", listType = WordListType.NUMBERED)
                )
            )
        }
    }

    fun createExcelFromTemplate(templateId: String): ExcelWorkbookModel {
        val cells = mutableMapOf<String, ExcelCell>()
        when (templateId) {
            "invoice" -> {
                cells["0,0"] = ExcelCell("البند", "البند", isBold = true, bgColorHex = "#DBEAFE")
                cells["0,1"] = ExcelCell("الكمية", "الكمية", isBold = true, bgColorHex = "#DBEAFE")
                cells["0,2"] = ExcelCell("السعر", "السعر", isBold = true, bgColorHex = "#DBEAFE")
                cells["0,3"] = ExcelCell("الإجمالي", "الإجمالي", isBold = true, bgColorHex = "#DBEAFE")
                cells["1,0"] = ExcelCell("تصميم واجهات", "تصميم واجهات")
                cells["1,1"] = ExcelCell("2", "2")
                cells["1,2"] = ExcelCell("500", "500", numberFormat = CellNumberFormat.CURRENCY)
                cells["1,3"] = ExcelCell("=B2*C2", "", numberFormat = CellNumberFormat.CURRENCY)
                cells["2,0"] = ExcelCell("تطوير تطبيق", "تطوير تطبيق")
                cells["2,1"] = ExcelCell("1", "1")
                cells["2,2"] = ExcelCell("1500", "1500", numberFormat = CellNumberFormat.CURRENCY)
                cells["2,3"] = ExcelCell("=B3*C3", "", numberFormat = CellNumberFormat.CURRENCY)
                cells["3,2"] = ExcelCell("المجموع الكلي", "المجموع الكلي", isBold = true)
                cells["3,3"] = ExcelCell("=SUM(D2:D3)", "", isBold = true, numberFormat = CellNumberFormat.CURRENCY, bgColorHex = "#DCFCE7")
            }
            "budget" -> {
                cells["0,0"] = ExcelCell("الفئة", "الفئة", isBold = true, bgColorHex = "#DBEAFE")
                cells["0,1"] = ExcelCell("المخطط", "المخطط", isBold = true, bgColorHex = "#DBEAFE")
                cells["0,2"] = ExcelCell("الفعلي", "الفعلي", isBold = true, bgColorHex = "#DBEAFE")
                cells["0,3"] = ExcelCell("الفرق", "الفرق", isBold = true, bgColorHex = "#DBEAFE")
                val rows = listOf(
                    Triple("السكن", "2000", "2000"),
                    Triple("الطعام", "800", "720"),
                    Triple("المواصلات", "400", "350"),
                    Triple("الادخار", "1000", "1100")
                )
                rows.forEachIndexed { idx, (cat, plan, act) ->
                    val r = idx + 1
                    cells["$r,0"] = ExcelCell(cat, cat)
                    cells["$r,1"] = ExcelCell(plan, plan, numberFormat = CellNumberFormat.CURRENCY)
                    cells["$r,2"] = ExcelCell(act, act, numberFormat = CellNumberFormat.CURRENCY)
                    cells["$r,3"] = ExcelCell("=B${r + 1}-C${r + 1}", "", numberFormat = CellNumberFormat.CURRENCY)
                }
                cells["5,0"] = ExcelCell("الإجمالي", "الإجمالي", isBold = true)
                cells["5,1"] = ExcelCell("=SUM(B2:B5)", "", isBold = true, numberFormat = CellNumberFormat.CURRENCY)
                cells["5,2"] = ExcelCell("=SUM(C2:C5)", "", isBold = true, numberFormat = CellNumberFormat.CURRENCY)
                cells["5,3"] = ExcelCell("=SUM(D2:D5)", "", isBold = true, numberFormat = CellNumberFormat.CURRENCY)
            }
            "ledger" -> {
                cells["0,0"] = ExcelCell("التاريخ", "التاريخ", isBold = true, bgColorHex = "#DBEAFE")
                cells["0,1"] = ExcelCell("البيان", "البيان", isBold = true, bgColorHex = "#DBEAFE")
                cells["0,2"] = ExcelCell("مدين", "مدين", isBold = true, bgColorHex = "#DBEAFE")
                cells["0,3"] = ExcelCell("دائن", "دائن", isBold = true, bgColorHex = "#DBEAFE")
                cells["1,0"] = ExcelCell("2026-11-01", "2026-11-01")
                cells["1,1"] = ExcelCell("رصيد افتتاحي", "رصيد افتتاحي")
                cells["1,2"] = ExcelCell("10000", "10000", numberFormat = CellNumberFormat.CURRENCY)
                cells["1,3"] = ExcelCell("0", "0", numberFormat = CellNumberFormat.CURRENCY)
                cells["2,0"] = ExcelCell("2026-11-03", "2026-11-03")
                cells["2,1"] = ExcelCell("مشتريات مكتبية", "مشتريات مكتبية")
                cells["2,2"] = ExcelCell("0", "0", numberFormat = CellNumberFormat.CURRENCY)
                cells["2,3"] = ExcelCell("1250", "1250", numberFormat = CellNumberFormat.CURRENCY)
                cells["3,1"] = ExcelCell("صافي الرصيد", "صافي الرصيد", isBold = true)
                cells["3,2"] = ExcelCell("=SUM(C2:C3)-SUM(D2:D3)", "", isBold = true, numberFormat = CellNumberFormat.CURRENCY, bgColorHex = "#DCFCE7")
            }
            else -> {
                cells["0,0"] = ExcelCell("البند", "البند", isBold = true, bgColorHex = "#DBEAFE")
                cells["0,1"] = ExcelCell("التكلفة", "التكلفة", isBold = true, bgColorHex = "#DBEAFE")
                cells["1,0"] = ExcelCell("فواتير الكهرباء والإنترنت", "فواتير الكهرباء والإنترنت")
                cells["1,1"] = ExcelCell("320", "320", numberFormat = CellNumberFormat.CURRENCY)
                cells["2,0"] = ExcelCell("مستلزمات", "مستلزمات")
                cells["2,1"] = ExcelCell("450", "450", numberFormat = CellNumberFormat.CURRENCY)
                cells["3,0"] = ExcelCell("المجموع", "المجموع", isBold = true)
                cells["3,1"] = ExcelCell("=SUM(B2:B3)", "", isBold = true, numberFormat = CellNumberFormat.CURRENCY)
            }
        }
        val sheet = FormulaEvaluator.recalculateSheet(
            ExcelSheet(
                name = "البيانات",
                cells = cells,
                chartConfig = ExcelChartConfig(title = "ملخص بياني", startRow = 1, endRow = 3)
            )
        )
        return ExcelWorkbookModel(title = "جدول من قالب", sheets = listOf(sheet))
    }

    fun createPresentationFromTemplate(templateId: String): PresentationModel {
        val (title, subtitle, bg) = when (templateId) {
            "edu_ppt" -> Triple("الدرس التعليمي: أساسيات الذكاء الاصطناعي", "مقدمة مبسطة وتطبيقات عملية", "#F0F9FF")
            "biz_ppt" -> Triple("خطة العمل الاستراتيجية 2026", "الرؤية، الأهداف، والنمو المتوقع", "#FFFFFF")
            else -> Triple("خطة الحملة التسويقية الرقمية", "الوصول للجمهور المستهدف وزيادة المبيعات", "#FFF7ED")
        }
        return PresentationModel(
            title = title,
            slides = listOf(
                SlideModel(
                    backgroundColorHex = bg,
                    elements = listOf(
                        SlideElement(
                            type = SlideElementType.TEXT,
                            xFraction = 0.08f, yFraction = 0.22f, widthFraction = 0.84f, heightFraction = 0.25f,
                            text = title, fontSizeSp = 28, isBold = true, textColorHex = "#1E3A8A"
                        ),
                        SlideElement(
                            type = SlideElementType.TEXT,
                            xFraction = 0.08f, yFraction = 0.52f, widthFraction = 0.84f, heightFraction = 0.2f,
                            text = subtitle, fontSizeSp = 18, textColorHex = "#475569"
                        )
                    )
                ),
                SlideModel(
                    backgroundColorHex = "#FFFFFF",
                    elements = listOf(
                        SlideElement(
                            type = SlideElementType.TEXT,
                            xFraction = 0.08f, yFraction = 0.10f, widthFraction = 0.84f, heightFraction = 0.18f,
                            text = "مؤشرات الأداء والنمو", fontSizeSp = 24, isBold = true, textColorHex = "#1D4ED8"
                        ),
                        SlideElement(
                            type = SlideElementType.CHART,
                            xFraction = 0.10f, yFraction = 0.32f, widthFraction = 0.80f, heightFraction = 0.55f,
                            chartValues = listOf("الربع 1" to 45f, "الربع 2" to 68f, "الربع 3" to 82f, "الربع 4" to 96f)
                        )
                    )
                )
            )
        )
    }
}
