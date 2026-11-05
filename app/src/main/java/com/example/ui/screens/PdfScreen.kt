package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.DocumentMode
import com.example.core.FileUtils
import com.example.core.LocalAppStrings
import com.example.core.SafeSaveManager
import com.example.core.SafeSaveResult
import com.example.data.OfficeRepository
import com.example.engine.PdfEngine
import com.example.engine.PdfRenderCache
import com.example.engine.PdfTextBlock
import com.example.engine.PdfTextEditor
import com.example.ui.theme.RoyalBluePrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfScreen(
    filePath: String,
    repository: OfficeRepository,
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val file = remember(filePath) { File(filePath) }

    var mode by remember { mutableStateOf(DocumentMode.VIEW) }
    var pageCount by remember { mutableIntStateOf(0) }
    var currentPage by remember { mutableIntStateOf(0) }
    var zoomScale by remember { mutableFloatStateOf(1.25f) }
    var isContinuousScroll by remember { mutableStateOf(false) }
    var isNightMode by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    var pdfPassword by remember { mutableStateOf<String?>(null) }
    var showUnlockDialog by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var pageTextBlocks by remember { mutableStateOf<List<PdfTextBlock>>(emptyList()) }
    var showTextOverlayBoxes by remember { mutableStateOf(true) }
    var editingBlock by remember { mutableStateOf<PdfTextBlock?>(null) }
    var showCopyTextDialog by remember { mutableStateOf<String?>(null) }

    var showSearchDialog by remember { mutableStateOf(false) }
    var searchMatches by remember { mutableStateOf<List<Int>>(emptyList()) }
    var showAddTextDialog by remember { mutableStateOf(false) }
    var showSignatureDialog by remember { mutableStateOf(false) }
    var showWatermarkDialog by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var showSaveAsDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf<Boolean?>(null) }
    var showJumpPageDialog by remember { mutableStateOf(false) }
    var statusToast by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = mode == DocumentMode.EDIT) {
        mode = DocumentMode.VIEW
    }

    LaunchedEffect(filePath, refreshTrigger, pdfPassword) {
        withContext(Dispatchers.IO) {
            if (!file.exists()) {
                loadError = "الملف غير موجود"
                return@withContext
            }
            if (pdfPassword == null && PdfEngine.isPasswordProtected(context, file)) {
                showUnlockDialog = true
                return@withContext
            }
            val count = PdfEngine.getPageCount(file, pdfPassword)
            if (count <= 0) {
                loadError = "تعذر فتح ملف PDF أو أن الملف تالف"
            } else {
                loadError = null
                pageCount = count
                if (currentPage >= count) currentPage = count - 1
            }
        }
    }

    LaunchedEffect(filePath, currentPage, mode, refreshTrigger, pdfPassword) {
        if (mode == DocumentMode.EDIT && pageCount > 0) {
            pageTextBlocks = withContext(Dispatchers.IO) {
                PdfTextEditor.extractPageTextBlocks(context, file, currentPage, pdfPassword)
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { inp ->
                    val bmp = BitmapFactory.decodeStream(inp)
                    if (bmp != null) {
                        PdfEngine.addBitmapOverlay(context, file, currentPage, bmp, password = pdfPassword)
                        bmp.recycle()
                        refreshTrigger++
                        statusToast = "تمت إضافة الصورة إلى الصفحة"
                    }
                }
            }
        }
    }

    val mergePdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val other = FileUtils.copyUriToWorkspace(context, uri)
                if (other != null) {
                    val out = FileUtils.ensureUniqueFile(file.parentFile!!, "${file.nameWithoutExtension}_merged.pdf")
                    PdfEngine.mergePdfs(context, listOf(file, other), out)
                    repository.registerOrUpdateFile(out, markOpened = false)
                    statusToast = "تم الدمج في ${out.name}"
                }
            }
        }
    }

    val invertMatrix = remember {
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
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
                        if (mode == DocumentMode.EDIT) mode = DocumentMode.VIEW else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column(modifier = Modifier.clickable { showJumpPageDialog = true }) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        val modeLabel = if (mode == DocumentMode.VIEW) "وضع العرض" else "وضع التحرير"
                        Text(
                            text = if (pageCount > 0) "$modeLabel • صفحة ${currentPage + 1} من $pageCount" else modeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (mode == DocumentMode.EDIT) RoyalBluePrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (mode == DocumentMode.VIEW) {
                        FilledTonalButton(
                            onClick = { mode = DocumentMode.EDIT },
                            modifier = Modifier.testTag("pdf_edit_mode_btn"),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("تحرير")
                        }
                    } else {
                        TextButton(onClick = { mode = DocumentMode.VIEW }) {
                            Text("معاينة")
                        }
                    }

                    IconButton(onClick = { showSearchDialog = true }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }

                    var moreMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { moreMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("تحديد ونسخ نص الصفحة") },
                                onClick = {
                                    moreMenu = false
                                    scope.launch(Dispatchers.IO) {
                                        val txt = PdfEngine.extractPageText(context, file, currentPage, pdfPassword)
                                        withContext(Dispatchers.Main) {
                                            showCopyTextDialog = txt.ifBlank { "لا يوجد نص قابل للنسخ في هذه الصفحة (قد تكون صورة ممسوحة)." }
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isNightMode) "الوضع النهاري" else "الوضع الليلي") },
                                onClick = { moreMenu = false; isNightMode = !isNightMode }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isContinuousScroll) "عرض صفحة واحدة" else "تمرير مستمر") },
                                onClick = { moreMenu = false; isContinuousScroll = !isContinuousScroll }
                            )
                            DropdownMenuItem(
                                text = { Text("حفظ باسم (نسخة جديدة)") },
                                onClick = { moreMenu = false; showSaveAsDialog = true }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("إضافة صفحة فارغة") },
                                onClick = {
                                    moreMenu = false
                                    scope.launch(Dispatchers.IO) {
                                        PdfEngine.addBlankPage(context, file, pdfPassword)
                                        refreshTrigger++
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("حذف الصفحة الحالية") },
                                onClick = {
                                    moreMenu = false
                                    scope.launch(Dispatchers.IO) {
                                        PdfEngine.deletePage(context, file, currentPage, pdfPassword)
                                        refreshTrigger++
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("تدوير الصفحة 90°") },
                                onClick = {
                                    moreMenu = false
                                    scope.launch(Dispatchers.IO) {
                                        PdfEngine.rotatePage(context, file, currentPage, 90, pdfPassword)
                                        refreshTrigger++
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("قص هوامش الصفحة") },
                                onClick = {
                                    moreMenu = false
                                    scope.launch(Dispatchers.IO) {
                                        PdfEngine.cropPage(context, file, currentPage, password = pdfPassword)
                                        refreshTrigger++
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("استخراج الصفحة الحالية") },
                                onClick = {
                                    moreMenu = false
                                    scope.launch(Dispatchers.IO) {
                                        val out = FileUtils.ensureUniqueFile(
                                            file.parentFile!!,
                                            "${file.nameWithoutExtension}_p${currentPage + 1}.pdf"
                                        )
                                        PdfEngine.extractPages(context, file, listOf(currentPage), out, pdfPassword)
                                        repository.registerOrUpdateFile(out, markOpened = false)
                                        statusToast = "تم استخراج الصفحة إلى ${out.name}"
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("دمج مع ملف PDF آخر") },
                                onClick = {
                                    moreMenu = false
                                    mergePdfPicker.launch(arrayOf("application/pdf"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("تقسيم الملف") },
                                onClick = {
                                    moreMenu = false
                                    scope.launch(Dispatchers.IO) {
                                        val p1 = FileUtils.ensureUniqueFile(file.parentFile!!, "${file.nameWithoutExtension}_part1.pdf")
                                        val p2 = FileUtils.ensureUniqueFile(file.parentFile!!, "${file.nameWithoutExtension}_part2.pdf")
                                        PdfEngine.splitPdf(context, file, currentPage + 1, p1, p2, pdfPassword)
                                        repository.registerOrUpdateFile(p1, markOpened = false)
                                        repository.registerOrUpdateFile(p2, markOpened = false)
                                        statusToast = "تم تقسيم الملف إلى جزأين"
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("إضافة أرقام الصفحات") },
                                onClick = {
                                    moreMenu = false
                                    scope.launch(Dispatchers.IO) {
                                        PdfEngine.addPageNumbers(context, file, pdfPassword)
                                        refreshTrigger++
                                        statusToast = "تمت إضافة أرقام الصفحات"
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("إضافة علامة مائية") },
                                onClick = { moreMenu = false; showWatermarkDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text("ضغط حجم PDF") },
                                onClick = { moreMenu = false; showCompressDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text("حماية بكلمة مرور") },
                                onClick = { moreMenu = false; showPasswordDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text("إزالة كلمة المرور") },
                                onClick = { moreMenu = false; showPasswordDialog = false }
                            )
                            DropdownMenuItem(
                                text = { Text(strings.print) },
                                onClick = {
                                    moreMenu = false
                                    FileUtils.printPdfFile(context, file)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(strings.share) },
                                onClick = {
                                    moreMenu = false
                                    FileUtils.shareFile(context, file)
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        enabled = currentPage > 0,
                        onClick = { currentPage-- }
                    ) { Icon(Icons.Default.ChevronRight, contentDescription = "Prev Page") }

                    Text("${currentPage + 1}/${pageCount.coerceAtLeast(1)}", fontWeight = FontWeight.Bold)

                    IconButton(
                        enabled = currentPage + 1 < pageCount,
                        onClick = { currentPage++ }
                    ) { Icon(Icons.Default.ChevronLeft, contentDescription = "Next Page") }

                    IconButton(onClick = { zoomScale = (zoomScale - 0.25f).coerceAtLeast(0.75f) }) {
                        Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out")
                    }
                    Text("${(zoomScale * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { zoomScale = (zoomScale + 0.25f).coerceAtMost(2.5f) }) {
                        Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In")
                    }

                    if (mode == DocumentMode.EDIT) {
                        FilterChip(
                            selected = showTextOverlayBoxes,
                            onClick = { showTextOverlayBoxes = !showTextOverlayBoxes },
                            label = { Text("تحديد النصوص") }
                        )
                        AssistChip(
                            onClick = { showAddTextDialog = true },
                            label = { Text("+ نص") },
                            modifier = Modifier.testTag("pdf_add_text_btn")
                        )
                        AssistChip(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    PdfEngine.addHighlightOrShape(
                                        context, file, currentPage,
                                        0.15f, 0.25f, 0.5f, 0.04f,
                                        android.graphics.Color.YELLOW, true, pdfPassword
                                    )
                                    refreshTrigger++
                                }
                            },
                            label = { Text("تظليل") }
                        )
                        AssistChip(
                            onClick = { showSignatureDialog = true },
                            label = { Text("توقيع / قلم") }
                        )
                        AssistChip(
                            onClick = {
                                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            label = { Text("+ صورة") }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(if (isNightMode) Color(0xFF0F172A) else MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (loadError != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text(loadError!!, color = MaterialTheme.colorScheme.error)
                }
            } else if (pageCount == 0) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (isContinuousScroll && mode == DocumentMode.VIEW) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(pageCount) { idx ->
                        PdfPageBitmapView(
                            file = file,
                            pageIndex = idx,
                            zoomScale = zoomScale,
                            refreshKey = refreshTrigger,
                            password = pdfPassword,
                            colorFilter = if (isNightMode) ColorFilter.colorMatrix(invertMatrix) else null
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (mode == DocumentMode.EDIT && pageTextBlocks.isEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "صفحة ممسوحة ضوئيًا (بدون طبقة نصية)",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val bmp = PdfRenderCache.renderPage(context, file, currentPage, 1.5f, pdfPassword)
                                        if (bmp != null) {
                                            val ocrBlocks = PdfTextEditor.detectScannedPageTextWithOcr(bmp, currentPage)
                                            withContext(Dispatchers.Main) {
                                                pageTextBlocks = ocrBlocks
                                                statusToast = "تم التعرف على ${ocrBlocks.size} كتلة نصية بـ OCR"
                                            }
                                        }
                                    }
                                }) {
                                    Text("تحليل OCR")
                                }
                            }
                        }
                    }

                    PdfPageBitmapView(
                        file = file,
                        pageIndex = currentPage,
                        zoomScale = zoomScale,
                        refreshKey = refreshTrigger,
                        password = pdfPassword,
                        colorFilter = if (isNightMode) ColorFilter.colorMatrix(invertMatrix) else null,
                        editableBlocks = if (mode == DocumentMode.EDIT && showTextOverlayBoxes) pageTextBlocks else emptyList(),
                        onBlockClick = { blk -> editingBlock = blk }
                    )
                }
            }

            if (searchMatches.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("نتائج البحث: ${searchMatches.size} صفحات")
                        searchMatches.forEach { pIdx ->
                            TextButton(onClick = { currentPage = pIdx }) {
                                Text("ص ${pIdx + 1}")
                            }
                        }
                        IconButton(onClick = { searchMatches = emptyList() }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close search")
                        }
                    }
                }
            }
        }
    }

    // Edit or Delete Existing PDF Text Block Dialog
    editingBlock?.let { blk ->
        var updatedText by remember(blk.id) { mutableStateOf(blk.text) }
        var fontSize by remember(blk.id) { mutableFloatStateOf(blk.fontSizePt) }
        var selectedColor by remember(blk.id) { mutableIntStateOf(android.graphics.Color.BLACK) }

        AlertDialog(
            onDismissRequest = { editingBlock = null },
            title = {
                Text(if (blk.isOcrDetected) "تحرير نص ممسوح (OCR)" else "تحرير نص PDF")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = updatedText,
                        onValueChange = { updatedText = it },
                        label = { Text("النص") },
                        modifier = Modifier.fillMaxWidth().testTag("pdf_edit_block_input")
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("الحجم: ${fontSize.toInt()}pt", style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = fontSize,
                            onValueChange = { fontSize = it },
                            valueRange = 8f..36f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            android.graphics.Color.BLACK to Color.Black,
                            android.graphics.Color.rgb(30, 58, 138) to Color(0xFF1E3A8A),
                            android.graphics.Color.rgb(185, 28, 28) to Color(0xFFB91C1C),
                            android.graphics.Color.rgb(21, 128, 61) to Color(0xFF15803D)
                        ).forEach { (androidCol, composeCol) ->
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(composeCol)
                                    .border(
                                        width = if (selectedColor == androidCol) 2.5.dp else 1.dp,
                                        color = if (selectedColor == androidCol) RoyalBluePrimary else Color.Gray,
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable { selectedColor = androidCol }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val target = blk
                    val txt = updatedText
                    val sz = fontSize
                    val col = selectedColor
                    editingBlock = null
                    scope.launch(Dispatchers.IO) {
                        val res = PdfTextEditor.editTextBlock(context, file, target, txt, sz, col, pdfPassword)
                        withContext(Dispatchers.Main) {
                            when (res) {
                                is SafeSaveResult.Success -> {
                                    refreshTrigger++
                                    statusToast = "تم تحديث النص وحفظ الملف بأمان"
                                }
                                is SafeSaveResult.Failure -> statusToast = res.message
                            }
                        }
                    }
                }) { Text("حفظ التعديل") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            val target = blk
                            editingBlock = null
                            scope.launch(Dispatchers.IO) {
                                val res = PdfTextEditor.deleteTextBlock(context, file, target, pdfPassword)
                                withContext(Dispatchers.Main) {
                                    if (res is SafeSaveResult.Success) {
                                        refreshTrigger++
                                        statusToast = "تم حذف النص"
                                    } else if (res is SafeSaveResult.Failure) {
                                        statusToast = res.message
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("حذف النص") }
                    TextButton(onClick = { editingBlock = null }) { Text(strings.cancel) }
                }
            }
        )
    }

    // Add New Text Dialog
    if (showAddTextDialog) {
        var noteText by remember { mutableStateOf("") }
        var fontSize by remember { mutableFloatStateOf(14f) }
        AlertDialog(
            onDismissRequest = { showAddTextDialog = false },
            title = { Text("إضافة نص جديد إلى الصفحة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("النص (يدعم العربية والإنجليزية)") },
                        modifier = Modifier.fillMaxWidth().testTag("pdf_new_text_input")
                    )
                    Text("حجم الخط: ${fontSize.toInt()}pt", style = MaterialTheme.typography.labelSmall)
                    Slider(value = fontSize, onValueChange = { fontSize = it }, valueRange = 10f..32f)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val txt = noteText.trim()
                    val sz = fontSize
                    showAddTextDialog = false
                    if (txt.isNotEmpty()) {
                        scope.launch(Dispatchers.IO) {
                            val res = PdfTextEditor.addText(
                                context, file, currentPage, txt,
                                0.12f, 0.22f, sz, android.graphics.Color.rgb(30, 58, 138), pdfPassword
                            )
                            withContext(Dispatchers.Main) {
                                if (res is SafeSaveResult.Success) {
                                    refreshTrigger++
                                    statusToast = "تمت إضافة النص"
                                } else if (res is SafeSaveResult.Failure) {
                                    statusToast = res.message
                                }
                            }
                        }
                    }
                }) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { showAddTextDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    // Copy Page Text Dialog
    showCopyTextDialog?.let { pageTxt ->
        AlertDialog(
            onDismissRequest = { showCopyTextDialog = null },
            title = { Text("نص الصفحة ${currentPage + 1}") },
            text = {
                SelectionContainer {
                    Text(
                        text = pageTxt,
                        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(pageTxt))
                    showCopyTextDialog = null
                    statusToast = "تم نسخ نص الصفحة"
                }) { Text("نسخ الكل") }
            },
            dismissButton = {
                TextButton(onClick = { showCopyTextDialog = null }) { Text(strings.close) }
            }
        )
    }

    // Compress PDF Dialog (Lossless vs Image-based)
    if (showCompressDialog) {
        AlertDialog(
            onDismissRequest = { showCompressDialog = false },
            title = { Text("ضغط حجم PDF") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("اختر طريقة الضغط المناسبة:")
                    OutlinedButton(
                        onClick = {
                            showCompressDialog = false
                            scope.launch(Dispatchers.IO) {
                                val out = FileUtils.ensureUniqueFile(file.parentFile!!, "${file.nameWithoutExtension}_compressed.pdf")
                                PdfEngine.compressPdf(context, file, out, imageBased = false, password = pdfPassword)
                                repository.registerOrUpdateFile(out, markOpened = false)
                                statusToast = "تم الضغط القياسي مع الحفاظ على النصوص: ${out.name}"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ضغط قياسي (يحافظ على النصوص والروابط)")
                    }
                    OutlinedButton(
                        onClick = {
                            showCompressDialog = false
                            scope.launch(Dispatchers.IO) {
                                val out = FileUtils.ensureUniqueFile(file.parentFile!!, "${file.nameWithoutExtension}_img_compressed.pdf")
                                PdfEngine.compressPdf(context, file, out, imageBased = true, password = pdfPassword)
                                repository.registerOrUpdateFile(out, markOpened = false)
                                statusToast = "تم ضغط الصور: ${out.name}"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ضغط الصور (للملفات الممسوحة ضوئيًا)")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCompressDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    // Save As Dialog
    if (showSaveAsDialog) {
        var newName by remember { mutableStateOf("${file.nameWithoutExtension}_copy.pdf") }
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
                    val targetName = if (newName.endsWith(".pdf", true)) newName else "$newName.pdf"
                    showSaveAsDialog = false
                    scope.launch(Dispatchers.IO) {
                        val res = SafeSaveManager.saveAs(file.parentFile!!, targetName, context) { tempOut ->
                            file.copyTo(tempOut, overwrite = true)
                        }
                        if (res is SafeSaveResult.Success) {
                            repository.registerOrUpdateFile(res.savedFile, markOpened = false)
                            statusToast = "تم الحفظ باسم ${res.savedFile.name}"
                        }
                    }
                }) { Text(strings.save) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveAsDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    // Unlock Password-Protected PDF Dialog
    if (showUnlockDialog) {
        var pwd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showUnlockDialog = false; onBack() },
            title = { Text("ملف PDF محمي بكلمة مرور") },
            text = {
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    label = { Text("أدخل كلمة المرور لفتح الملف") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    showUnlockDialog = false
                    pdfPassword = pwd
                }) { Text("فتح") }
            },
            dismissButton = {
                TextButton(onClick = { showUnlockDialog = false; onBack() }) { Text(strings.cancel) }
            }
        )
    }

    // Freehand Signature / Drawing Dialog
    if (showSignatureDialog) {
        val strokes = remember { mutableStateListOf<List<Offset>>() }
        var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
        AlertDialog(
            onDismissRequest = { showSignatureDialog = false },
            title = { Text("رسم توقيع أو ملاحظة") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.dp, Color.Gray, RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset -> currentStroke = listOf(offset) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    currentStroke = currentStroke + change.position
                                },
                                onDragEnd = {
                                    if (currentStroke.isNotEmpty()) {
                                        strokes.add(currentStroke)
                                        currentStroke = emptyList()
                                    }
                                }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val all = strokes + listOf(currentStroke)
                        all.forEach { pts ->
                            for (i in 0 until pts.size - 1) {
                                drawLine(
                                    color = Color(0xFF1E3A8A),
                                    start = pts[i],
                                    end = pts[i + 1],
                                    strokeWidth = 6f
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showSignatureDialog = false
                    if (strokes.isNotEmpty()) {
                        scope.launch(Dispatchers.IO) {
                            val bmp = Bitmap.createBitmap(600, 260, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bmp)
                            val paint = Paint().apply {
                                color = android.graphics.Color.rgb(30, 58, 138)
                                strokeWidth = 6f
                                style = Paint.Style.STROKE
                                isAntiAlias = true
                            }
                            strokes.forEach { pts ->
                                if (pts.size > 1) {
                                    val path = Path()
                                    path.moveTo(pts.first().x, pts.first().y)
                                    pts.drop(1).forEach { path.lineTo(it.x, it.y) }
                                    canvas.drawPath(path, paint)
                                }
                            }
                            PdfEngine.addBitmapOverlay(context, file, currentPage, bmp, 0.55f, 0.78f, 0.35f, pdfPassword)
                            bmp.recycle()
                            refreshTrigger++
                            statusToast = "تم إدراج التوقيع"
                        }
                    }
                }) { Text("إدراج") }
            },
            dismissButton = {
                TextButton(onClick = { showSignatureDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    // Search Dialog
    if (showSearchDialog) {
        var query by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { Text("بحث في PDF") },
            text = {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("كلمة البحث") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    showSearchDialog = false
                    scope.launch(Dispatchers.IO) {
                        val found = PdfEngine.searchTextPages(context, file, query, pdfPassword)
                        searchMatches = found
                        if (found.isNotEmpty()) {
                            currentPage = found.first()
                        } else {
                            statusToast = "لم يتم العثور على نتائج"
                        }
                    }
                }) { Text("بحث") }
            },
            dismissButton = {
                TextButton(onClick = { showSearchDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    // Watermark Dialog
    if (showWatermarkDialog) {
        var wmText by remember { mutableStateOf("سري - Office Pro") }
        AlertDialog(
            onDismissRequest = { showWatermarkDialog = false },
            title = { Text("إضافة علامة مائية") },
            text = {
                OutlinedTextField(value = wmText, onValueChange = { wmText = it }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    showWatermarkDialog = false
                    scope.launch(Dispatchers.IO) {
                        PdfEngine.addWatermark(context, file, wmText, pdfPassword)
                        refreshTrigger++
                        statusToast = "تمت إضافة العلامة المائية"
                    }
                }) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { showWatermarkDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    // Password Protect / Remove Dialog
    showPasswordDialog?.let { isProtect ->
        var pwd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasswordDialog = null },
            title = { Text(if (isProtect) "حماية PDF بكلمة مرور" else "إزالة كلمة مرور PDF") },
            text = {
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    label = { Text("كلمة المرور") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    showPasswordDialog = null
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            if (isProtect) {
                                PdfEngine.protectWithPassword(context, file, pwd)
                                pdfPassword = pwd
                            } else {
                                PdfEngine.removePassword(context, file, pwd)
                                pdfPassword = null
                            }
                        }.onSuccess {
                            refreshTrigger++
                            statusToast = if (isProtect) "تمت حماية الملف" else "تمت إزالة الحماية"
                        }.onFailure {
                            statusToast = "كلمة المرور غير صحيحة"
                        }
                    }
                }) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = null }) { Text(strings.cancel) }
            }
        )
    }

    // Jump to Page Dialog
    if (showJumpPageDialog && pageCount > 0) {
        var pageInput by remember { mutableStateOf("${currentPage + 1}") }
        AlertDialog(
            onDismissRequest = { showJumpPageDialog = false },
            title = { Text("الانتقال إلى صفحة (1 - $pageCount)") },
            text = {
                OutlinedTextField(value = pageInput, onValueChange = { pageInput = it }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    val p = pageInput.toIntOrNull()
                    if (p != null && p in 1..pageCount) {
                        currentPage = p - 1
                    }
                    showJumpPageDialog = false
                }) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { showJumpPageDialog = false }) { Text(strings.cancel) }
            }
        )
    }
}

@Composable
private fun PdfPageBitmapView(
    file: File,
    pageIndex: Int,
    zoomScale: Float,
    refreshKey: Int,
    password: String? = null,
    colorFilter: ColorFilter? = null,
    editableBlocks: List<PdfTextBlock> = emptyList(),
    onBlockClick: (PdfTextBlock) -> Unit = {}
) {
    val context = LocalContext.current
    var bitmap by remember(file.absolutePath, pageIndex, zoomScale, refreshKey, password) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(file.absolutePath, pageIndex, zoomScale, refreshKey, password) {
        val bmp = withContext(Dispatchers.IO) {
            PdfRenderCache.renderPage(context, file, pageIndex, zoomScale, password)
        }
        bitmap = bmp
    }

    val currentBmp = bitmap
    if (currentBmp == null || currentBmp.isRecycled) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else {
        val aspect = currentBmp.width.toFloat() / currentBmp.height.coerceAtLeast(1).toFloat()
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(8.dp))
        ) {
            val boxW = maxWidth
            val boxH = maxHeight
            Image(
                bitmap = currentBmp.asImageBitmap(),
                contentDescription = "PDF Page ${pageIndex + 1}",
                contentScale = ContentScale.FillBounds,
                colorFilter = colorFilter,
                modifier = Modifier.fillMaxSize()
            )
            editableBlocks.forEach { blk ->
                Box(
                    modifier = Modifier
                        .offset(x = boxW * blk.xFraction, y = boxH * blk.yFraction)
                        .width((boxW * blk.wFraction).coerceAtLeast(24.dp))
                        .height((boxH * blk.hFraction).coerceAtLeast(18.dp))
                        .background(Color(0x221D4ED8), RoundedCornerShape(3.dp))
                        .border(1.dp, Color(0x881D4ED8), RoundedCornerShape(3.dp))
                        .clickable { onBlockClick(blk) }
                )
            }
        }
    }
}
