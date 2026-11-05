package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.DocumentMode
import com.example.core.FileUtils
import com.example.core.LocalAppStrings
import com.example.core.SafeSaveResult
import com.example.core.SaveState
import com.example.data.OfficeRepository
import com.example.engine.*
import com.example.ui.theme.CairoFontFamily
import com.example.ui.theme.ExcelBadgeColor
import com.example.ui.theme.RobotoMonoFontFamily
import com.example.ui.theme.RoyalBluePrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExcelEditorScreen(
    filePath: String,
    repository: OfficeRepository,
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(filePath) { File(filePath) }

    var mode by remember { mutableStateOf(DocumentMode.VIEW) }
    var workbook by remember { mutableStateOf(ExcelWorkbookModel()) }
    var selectedRow by remember { mutableIntStateOf(0) }
    var selectedCol by remember { mutableIntStateOf(0) }
    var undoStack by remember { mutableStateOf<List<ExcelWorkbookModel>>(emptyList()) }
    var redoStack by remember { mutableStateOf<List<ExcelWorkbookModel>>(emptyList()) }
    var isUnsaved by remember { mutableStateOf(false) }
    var saveState by remember { mutableStateOf<SaveState>(SaveState.Saved) }
    var showChartSheet by remember { mutableStateOf(false) }
    var showFindReplace by remember { mutableStateOf(false) }
    var showConditionalDialog by remember { mutableStateOf(false) }
    var showValidationDialog by remember { mutableStateOf(false) }
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    var showSaveAsDialog by remember { mutableStateOf(false) }
    var statusToast by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = isUnsaved || mode == DocumentMode.EDIT) {
        if (isUnsaved) {
            showExitConfirmDialog = true
        } else if (mode == DocumentMode.EDIT) {
            mode = DocumentMode.VIEW
        }
    }

    LaunchedEffect(filePath) {
        val loaded = withContext(Dispatchers.IO) {
            if (file.exists()) ExcelEngine.loadWorkbook(file) else ExcelWorkbookModel()
        }
        workbook = loaded
    }

    val activeSheet = workbook.sheets.getOrElse(workbook.activeSheetIndex) { ExcelSheet() }
    val selectedKey = FormulaEvaluator.cellKey(selectedRow, selectedCol)
    val selectedCell = activeSheet.cells[selectedKey] ?: ExcelCell()

    fun updateWorkbook(newWb: ExcelWorkbookModel, recalculate: Boolean = true) {
        undoStack = (undoStack + workbook).takeLast(20)
        redoStack = emptyList()
        workbook = if (recalculate) {
            newWb.copy(sheets = newWb.sheets.mapIndexed { idx, s ->
                if (idx == newWb.activeSheetIndex) FormulaEvaluator.recalculateSheet(s) else s
            })
        } else newWb
        isUnsaved = true
        saveState = SaveState.Unsaved
    }

    fun updateActiveSheet(transform: (ExcelSheet) -> ExcelSheet) {
        val updatedSheets = workbook.sheets.mapIndexed { idx, s ->
            if (idx == workbook.activeSheetIndex) transform(s) else s
        }
        updateWorkbook(workbook.copy(sheets = updatedSheets))
    }

    fun updateSelectedCell(transform: (ExcelCell) -> ExcelCell) {
        updateActiveSheet { sheet ->
            val map = sheet.cells.toMutableMap()
            val cur = map[selectedKey] ?: ExcelCell()
            map[selectedKey] = transform(cur)
            sheet.copy(cells = map)
        }
    }

    fun saveNow(onSaved: (() -> Unit)? = null) {
        scope.launch {
            saveState = SaveState.Saving
            val res = withContext(Dispatchers.IO) {
                val r = ExcelEngine.saveWorkbook(file, workbook, context)
                if (r is SafeSaveResult.Success) {
                    repository.registerOrUpdateFile(file, markOpened = true)
                }
                r
            }
            when (res) {
                is SafeSaveResult.Success -> {
                    isUnsaved = false
                    saveState = SaveState.Saved
                    statusToast = strings.saved
                    onSaved?.invoke()
                }
                is SafeSaveResult.Failure -> {
                    saveState = SaveState.Failed(res.message)
                    statusToast = res.message
                }
            }
        }
    }

    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(statusToast) {
        statusToast?.let {
            snackbarHost.showSnackbar(it)
            statusToast = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        if (isUnsaved) showExitConfirmDialog = true else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        val modeLabel = if (mode == DocumentMode.VIEW) "وضع العرض" else "وضع التحرير"
                        val stateLabel = when (saveState) {
                            is SaveState.Saved -> strings.saved
                            is SaveState.Unsaved -> strings.unsaved
                            is SaveState.Saving -> strings.saving
                            is SaveState.Failed -> "فشل الحفظ"
                        }
                        Text(
                            text = "$modeLabel • $stateLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUnsaved) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (mode == DocumentMode.VIEW) {
                        if (!workbook.isReadOnlyBinary) {
                            FilledTonalButton(
                                onClick = { mode = DocumentMode.EDIT },
                                modifier = Modifier.testTag("excel_edit_mode_btn"),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("تحرير")
                            }
                        }
                    } else {
                        IconButton(
                            enabled = undoStack.isNotEmpty(),
                            onClick = {
                                val prev = undoStack.last()
                                undoStack = undoStack.dropLast(1)
                                redoStack = redoStack + workbook
                                workbook = prev
                                isUnsaved = true
                                saveState = SaveState.Unsaved
                            }
                        ) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = strings.undo) }

                        IconButton(
                            enabled = redoStack.isNotEmpty(),
                            onClick = {
                                val next = redoStack.last()
                                redoStack = redoStack.dropLast(1)
                                undoStack = undoStack + workbook
                                workbook = next
                                isUnsaved = true
                                saveState = SaveState.Unsaved
                            }
                        ) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = strings.redo) }

                        IconButton(
                            onClick = { saveNow() },
                            modifier = Modifier.testTag("save_excel_btn")
                        ) { Icon(Icons.Default.Save, contentDescription = strings.save) }
                    }

                    IconButton(
                        onClick = { showChartSheet = true },
                        modifier = Modifier.testTag("excel_chart_btn")
                    ) { Icon(Icons.Default.BarChart, contentDescription = "Charts") }

                    var moreMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { moreMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            if (mode == DocumentMode.EDIT) {
                                DropdownMenuItem(
                                    text = { Text("معاينة (وضع العرض)") },
                                    onClick = { moreMenu = false; mode = DocumentMode.VIEW }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("حفظ باسم (نسخة جديدة)") },
                                onClick = { moreMenu = false; showSaveAsDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text("+ إضافة صف") },
                                onClick = {
                                    moreMenu = false
                                    updateActiveSheet { it.copy(rowCount = it.rowCount + 1) }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("+ إضافة عمود") },
                                onClick = {
                                    moreMenu = false
                                    updateActiveSheet { it.copy(colCount = it.colCount + 1) }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("فرز العمود تصاعدياً (A-Z)") },
                                onClick = {
                                    moreMenu = false
                                    sortSheetByColumn(activeSheet, selectedCol, ascending = true) { sorted ->
                                        updateActiveSheet { sorted }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("فرز العمود تنازلياً (Z-A)") },
                                onClick = {
                                    moreMenu = false
                                    sortSheetByColumn(activeSheet, selectedCol, ascending = false) { sorted ->
                                        updateActiveSheet { sorted }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (activeSheet.freezeRows > 0) "إلغاء تثبيت الصف الأول" else "تثبيت الصف الأول") },
                                onClick = {
                                    moreMenu = false
                                    updateActiveSheet { it.copy(freezeRows = if (it.freezeRows > 0) 0 else 1) }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("تنسيق شرطي") },
                                onClick = { moreMenu = false; showConditionalDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text("قائمة منسدلة (التحقق من البيانات)") },
                                onClick = { moreMenu = false; showValidationDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text(strings.findReplace) },
                                onClick = { moreMenu = false; showFindReplace = true }
                            )
                            DropdownMenuItem(
                                text = { Text(strings.exportPdf) },
                                onClick = {
                                    moreMenu = false
                                    scope.launch(Dispatchers.IO) {
                                        val pdfFile = File(file.parentFile, "${file.nameWithoutExtension}.pdf")
                                        ExcelEngine.exportToPdf(workbook, pdfFile)
                                        repository.registerOrUpdateFile(pdfFile, markOpened = false)
                                        statusToast = "تم التصدير إلى ${pdfFile.name}"
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(strings.print) },
                                onClick = {
                                    moreMenu = false
                                    val tmp = File(context.cacheDir, "${file.nameWithoutExtension}_print.pdf")
                                    ExcelEngine.exportToPdf(workbook, tmp)
                                    FileUtils.printPdfFile(context, tmp)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(strings.share) },
                                onClick = {
                                    moreMenu = false
                                    saveNow()
                                    FileUtils.shareFile(context, file)
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (workbook.isReadOnlyBinary) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "ملف XLSB ثنائي (وضع القراءة فقط)",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                val out = FileUtils.ensureUniqueFile(file.parentFile!!, "${file.nameWithoutExtension}.xlsx")
                                val res = ExcelEngine.saveWorkbook(out, workbook.copy(isReadOnlyBinary = false), context)
                                if (res is SafeSaveResult.Success) {
                                    repository.registerOrUpdateFile(out, markOpened = false)
                                    statusToast = "تم الحفظ كـ ${out.name} للتحرير الكامل"
                                }
                            }
                        }) { Text("حفظ كـ XLSX") }
                    }
                }
            }

            if (mode == DocumentMode.EDIT) {
            // Formula Bar
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${FormulaEvaluator.colIndexToName(selectedCol)}${selectedRow + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = RobotoMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("fx", fontWeight = FontWeight.Bold, color = ExcelBadgeColor)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = selectedCell.rawInput,
                        onValueChange = { newRaw ->
                            updateSelectedCell { it.copy(rawInput = newRaw) }
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontFamily = RobotoMonoFontFamily,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("excel_formula_input")
                    )
                }
            }

            // Quick Formula & Format Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("=SUM(", "=AVERAGE(", "=IF(", "=COUNTIF(", "=VLOOKUP(").forEach { fn ->
                    AssistChip(
                        onClick = {
                            updateSelectedCell { it.copy(rawInput = it.rawInput + fn) }
                        },
                        label = { Text(fn.removePrefix("="), fontFamily = RobotoMonoFontFamily, fontSize = 11.sp) }
                    )
                }
                IconToggleButton(
                    checked = selectedCell.isBold,
                    onCheckedChange = { b -> updateSelectedCell { it.copy(isBold = b) } }
                ) { Icon(Icons.Default.FormatBold, contentDescription = "Bold") }

                IconToggleButton(
                    checked = selectedCell.isItalic,
                    onCheckedChange = { i -> updateSelectedCell { it.copy(isItalic = i) } }
                ) { Icon(Icons.Default.FormatItalic, contentDescription = "Italic") }

                IconButton(onClick = {
                    val colors = listOf(null, "#DBEAFE", "#DCFCE7", "#FEF08A", "#FEE2E2")
                    val next = colors[(colors.indexOf(selectedCell.bgColorHex) + 1) % colors.size]
                    updateSelectedCell { it.copy(bgColorHex = next) }
                }) { Icon(Icons.Default.FormatColorFill, contentDescription = "Fill Color") }

                FilterChip(
                    selected = selectedCell.numberFormat != CellNumberFormat.GENERAL,
                    onClick = {
                        val next = CellNumberFormat.entries[(selectedCell.numberFormat.ordinal + 1) % CellNumberFormat.entries.size]
                        updateSelectedCell { it.copy(numberFormat = next) }
                    },
                    label = {
                        Text(
                            when (selectedCell.numberFormat) {
                                CellNumberFormat.GENERAL -> "عام"
                                CellNumberFormat.CURRENCY -> "عملة $"
                                CellNumberFormat.PERCENT -> "نسبة %"
                                CellNumberFormat.DATE -> "تاريخ"
                            }
                        )
                    }
                )
            }
            }

            // Dropdown validation quick selector if cell has validation
            val cellDropdown = activeSheet.dropdownValidations[selectedKey]
            if (!cellDropdown.isNullOrEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(cellDropdown) { _, opt ->
                        SuggestionChip(
                            onClick = { updateSelectedCell { it.copy(rawInput = opt) } },
                            label = { Text(opt) }
                        )
                    }
                }
            }

            // Spreadsheet Grid
            val hScroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(hScroll)
            ) {
                // Column Headers
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(0.5.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Box(
                        modifier = Modifier
                            .width(44.dp)
                            .height(32.dp)
                            .border(0.5.dp, MaterialTheme.colorScheme.outline),
                        contentAlignment = Alignment.Center
                    ) { Text("#", style = MaterialTheme.typography.labelSmall) }

                    for (c in 0 until activeSheet.colCount) {
                        val colW = activeSheet.columnWidths[c]?.dp ?: 96.dp
                        Box(
                            modifier = Modifier
                                .width(colW)
                                .height(32.dp)
                                .background(
                                    if (c == selectedCol) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .border(0.5.dp, MaterialTheme.colorScheme.outline),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = FormulaEvaluator.colIndexToName(c),
                                style = MaterialTheme.typography.labelLarge,
                                fontFamily = RobotoMonoFontFamily,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Rows
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(activeSheet.rowCount) { r ->
                        Row {
                            // Row header
                            Box(
                                modifier = Modifier
                                    .width(44.dp)
                                    .height(36.dp)
                                    .background(
                                        if (r == selectedRow) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .border(0.5.dp, MaterialTheme.colorScheme.outline),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${r + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = RobotoMonoFontFamily
                                )
                            }

                            for (c in 0 until activeSheet.colCount) {
                                val colW = activeSheet.columnWidths[c]?.dp ?: 96.dp
                                val key = FormulaEvaluator.cellKey(r, c)
                                val cell = activeSheet.cells[key]
                                val isSel = r == selectedRow && c == selectedCol

                                // Check conditional formatting
                                val condBg = evaluateConditionalBg(cell?.displayValue, activeSheet.conditionalRules)
                                val bgColor = condBg
                                    ?: cell?.bgColorHex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
                                    ?: MaterialTheme.colorScheme.surface

                                Box(
                                    modifier = Modifier
                                        .width(colW)
                                        .height(36.dp)
                                        .background(bgColor)
                                        .border(
                                            width = if (isSel) 2.dp else 0.5.dp,
                                            color = if (isSel) ExcelBadgeColor else MaterialTheme.colorScheme.outlineVariant
                                        )
                                        .clickable {
                                            selectedRow = r
                                            selectedCol = c
                                        }
                                        .padding(horizontal = 6.dp)
                                        .testTag("cell_${r}_${c}"),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = cell?.displayValue ?: "",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (cell?.isBold == true) FontWeight.Bold else FontWeight.Normal,
                                            fontStyle = if (cell?.isItalic == true) FontStyle.Italic else FontStyle.Normal
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Sheet Tabs
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    workbook.sheets.forEachIndexed { idx, s ->
                        FilterChip(
                            selected = idx == workbook.activeSheetIndex,
                            onClick = {
                                workbook = workbook.copy(activeSheetIndex = idx)
                            },
                            label = { Text(s.name) }
                        )
                    }
                    IconButton(onClick = {
                        val newSheet = ExcelSheet(name = "ورقة ${workbook.sheets.size + 1}")
                        updateWorkbook(
                            workbook.copy(
                                sheets = workbook.sheets + newSheet,
                                activeSheetIndex = workbook.sheets.size
                            ),
                            recalculate = false
                        )
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Sheet")
                    }
                }
            }
        }
    }

    // Interactive Chart Modal Sheet
    if (showChartSheet) {
        ModalBottomSheet(onDismissRequest = { showChartSheet = false }) {
            var chartType by remember { mutableStateOf(activeSheet.chartConfig?.type ?: ExcelChartType.BAR) }
            val chartData = remember(activeSheet, chartType) {
                (1..10).mapNotNull { r ->
                    val lbl = activeSheet.cells[FormulaEvaluator.cellKey(r, 0)]?.displayValue
                    val valNum = activeSheet.cells[FormulaEvaluator.cellKey(r, 1)]?.displayValue
                        ?.replace("$", "")?.replace(",", "")?.replace("%", "")?.toFloatOrNull()
                    if (!lbl.isNullOrBlank() && valNum != null) lbl to valNum else null
                }.ifEmpty {
                    listOf("A" to 35f, "B" to 70f, "C" to 50f, "D" to 90f)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text("الرسوم البيانية التفاعلية", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExcelChartType.entries.forEach { t ->
                        FilterChip(
                            selected = chartType == t,
                            onClick = { chartType = t },
                            label = {
                                Text(
                                    when (t) {
                                        ExcelChartType.BAR -> "أعمدة"
                                        ExcelChartType.LINE -> "خطي"
                                        ExcelChartType.PIE -> "دائري"
                                    }
                                )
                            }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    val maxVal = (chartData.maxOfOrNull { it.second } ?: 100f).coerceAtLeast(1f)
                    val colors = listOf(RoyalBluePrimary, ExcelBadgeColor, Color(0xFFEA580C), Color(0xFF7C3AED), Color(0xFF0284C7))
                    when (chartType) {
                        ExcelChartType.BAR -> {
                            val barW = size.width / (chartData.size * 2f)
                            chartData.forEachIndexed { idx, (_, v) ->
                                val barH = (v / maxVal) * size.height
                                val x = idx * (barW * 2f) + barW * 0.5f
                                drawRect(
                                    color = colors[idx % colors.size],
                                    topLeft = Offset(x, size.height - barH),
                                    size = Size(barW, barH)
                                )
                            }
                        }
                        ExcelChartType.LINE -> {
                            val stepX = size.width / (chartData.size - 1).coerceAtLeast(1)
                            for (i in 0 until chartData.size - 1) {
                                val x1 = i * stepX
                                val y1 = size.height - (chartData[i].second / maxVal) * size.height
                                val x2 = (i + 1) * stepX
                                val y2 = size.height - (chartData[i + 1].second / maxVal) * size.height
                                drawLine(RoyalBluePrimary, Offset(x1, y1), Offset(x2, y2), strokeWidth = 6f)
                            }
                        }
                        ExcelChartType.PIE -> {
                            val total = chartData.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(1f)
                            var startAngle = -90f
                            val diameter = minOf(size.width, size.height)
                            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                            chartData.forEachIndexed { idx, (_, v) ->
                                val sweep = (v / total) * 360f
                                drawArc(
                                    color = colors[idx % colors.size],
                                    startAngle = startAngle,
                                    sweepAngle = sweep,
                                    useCenter = true,
                                    topLeft = topLeft,
                                    size = Size(diameter, diameter)
                                )
                                startAngle += sweep
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    chartData.forEach { (lbl, v) ->
                        Text("$lbl: ${v.toInt()}", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // Find & Replace
    if (showFindReplace) {
        var findTxt by remember { mutableStateOf("") }
        var replaceTxt by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showFindReplace = false },
            title = { Text(strings.findReplace) },
            text = {
                Column {
                    OutlinedTextField(value = findTxt, onValueChange = { findTxt = it }, label = { Text(strings.find) })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = replaceTxt, onValueChange = { replaceTxt = it }, label = { Text(strings.replace) })
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (findTxt.isNotEmpty()) {
                        updateActiveSheet { sheet ->
                            val newMap = sheet.cells.mapValues { (_, cell) ->
                                cell.copy(rawInput = cell.rawInput.replace(findTxt, replaceTxt, ignoreCase = true))
                            }
                            sheet.copy(cells = newMap)
                        }
                    }
                    showFindReplace = false
                }) { Text(strings.replaceAll) }
            },
            dismissButton = { TextButton(onClick = { showFindReplace = false }) { Text(strings.cancel) } }
        )
    }

    // Conditional Formatting Dialog
    if (showConditionalDialog) {
        var targetVal by remember { mutableStateOf("100") }
        AlertDialog(
            onDismissRequest = { showConditionalDialog = false },
            title = { Text("تنسيق شرطي (أكبر من)") },
            text = {
                OutlinedTextField(
                    value = targetVal,
                    onValueChange = { targetVal = it },
                    label = { Text("تظليل الخلايا الأكبر من:") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    updateActiveSheet { sheet ->
                        sheet.copy(
                            conditionalRules = sheet.conditionalRules + ConditionalRule(
                                ConditionalOperator.GREATER_THAN,
                                targetVal,
                                "#FEF08A"
                            )
                        )
                    }
                    showConditionalDialog = false
                }) { Text(strings.confirm) }
            },
            dismissButton = { TextButton(onClick = { showConditionalDialog = false }) { Text(strings.cancel) } }
        )
    }

    // Data Validation Dropdown Dialog
    if (showValidationDialog) {
        var optionsStr by remember { mutableStateOf("مكتمل,قيد التنفيذ,مؤجل") }
        AlertDialog(
            onDismissRequest = { showValidationDialog = false },
            title = { Text("قائمة منسدلة للخلية المحددة") },
            text = {
                OutlinedTextField(
                    value = optionsStr,
                    onValueChange = { optionsStr = it },
                    label = { Text("الخيارات مفصولة بفاصلة (,)") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    val list = optionsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    updateActiveSheet { sheet ->
                        val map = sheet.dropdownValidations.toMutableMap()
                        map[selectedKey] = list
                        sheet.copy(dropdownValidations = map)
                    }
                    showValidationDialog = false
                }) { Text(strings.confirm) }
            },
            dismissButton = { TextButton(onClick = { showValidationDialog = false }) { Text(strings.cancel) } }
        )
    }

    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            title = { Text("تغييرات غير محفوظة") },
            text = { Text("هل تريد حفظ التغييرات قبل الخروج من جدول البيانات؟") },
            confirmButton = {
                Button(onClick = {
                    showExitConfirmDialog = false
                    saveNow { onBack() }
                }) { Text("حفظ التغييرات") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            showExitConfirmDialog = false
                            onBack()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("تجاهل التغييرات") }
                    TextButton(onClick = { showExitConfirmDialog = false }) { Text(strings.cancel) }
                }
            }
        )
    }

    if (showSaveAsDialog) {
        val ext = if (file.extension.equals("csv", true)) "csv" else "xlsx"
        var newName by remember { mutableStateOf("${file.nameWithoutExtension}_copy.$ext") }
        AlertDialog(
            onDismissRequest = { showSaveAsDialog = false },
            title = { Text("حفظ باسم") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("اسم الملف الجديد") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    val targetName = if (newName.contains('.')) newName else "$newName.$ext"
                    showSaveAsDialog = false
                    scope.launch(Dispatchers.IO) {
                        val dest = FileUtils.ensureUniqueFile(file.parentFile!!, targetName)
                        val res = ExcelEngine.saveWorkbook(dest, workbook.copy(isReadOnlyBinary = false), context)
                        withContext(Dispatchers.Main) {
                            if (res is SafeSaveResult.Success) {
                                repository.registerOrUpdateFile(res.savedFile, markOpened = false)
                                statusToast = "تم الحفظ باسم ${res.savedFile.name}"
                            } else if (res is SafeSaveResult.Failure) {
                                statusToast = res.message
                            }
                        }
                    }
                }) { Text(strings.save) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveAsDialog = false }) { Text(strings.cancel) }
            }
        )
    }
}

private fun evaluateConditionalBg(displayVal: String?, rules: List<ConditionalRule>): Color? {
    if (displayVal.isNullOrBlank() || rules.isEmpty()) return null
    val num = displayVal.replace("$", "").replace(",", "").replace("%", "").toDoubleOrNull()
    for (rule in rules) {
        val targetNum = rule.targetValue.toDoubleOrNull()
        val matched = when (rule.operator) {
            ConditionalOperator.GREATER_THAN -> num != null && targetNum != null && num > targetNum
            ConditionalOperator.LESS_THAN -> num != null && targetNum != null && num < targetNum
            ConditionalOperator.EQUALS -> displayVal.equals(rule.targetValue, ignoreCase = true)
            ConditionalOperator.CONTAINS -> displayVal.contains(rule.targetValue, ignoreCase = true)
        }
        if (matched) {
            return runCatching { Color(android.graphics.Color.parseColor(rule.highlightHex)) }.getOrNull()
        }
    }
    return null
}

private fun sortSheetByColumn(
    sheet: ExcelSheet,
    col: Int,
    ascending: Boolean,
    onSorted: (ExcelSheet) -> Unit
) {
    val maxRow = sheet.cells.keys.mapNotNull { it.split(",")[0].toIntOrNull() }.maxOrNull() ?: 0
    if (maxRow <= 1) return
    val rowIndices = (1..maxRow).toList()
    val sortedRows = if (ascending) {
        rowIndices.sortedBy { r -> sheet.cells[FormulaEvaluator.cellKey(r, col)]?.displayValue ?: "" }
    } else {
        rowIndices.sortedByDescending { r -> sheet.cells[FormulaEvaluator.cellKey(r, col)]?.displayValue ?: "" }
    }
    val newCells = sheet.cells.filterKeys { it.startsWith("0,") }.toMutableMap()
    sortedRows.forEachIndexed { newIdx, oldRow ->
        val targetRow = newIdx + 1
        for (c in 0 until sheet.colCount) {
            val cell = sheet.cells[FormulaEvaluator.cellKey(oldRow, c)]
            if (cell != null) {
                newCells[FormulaEvaluator.cellKey(targetRow, c)] = cell
            }
        }
    }
    onSorted(sheet.copy(cells = newCells))
}
