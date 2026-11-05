package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.FileUtils
import com.example.core.LocalAppStrings
import com.example.data.OfficeRepository
import com.example.engine.PdfEngine
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
    val scope = rememberCoroutineScope()
    val file = remember(filePath) { File(filePath) }

    var pageCount by remember { mutableIntStateOf(0) }
    var currentPage by remember { mutableIntStateOf(0) }
    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var isContinuousScroll by remember { mutableStateOf(false) }
    var isNightMode by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    var showSearchDialog by remember { mutableStateOf(false) }
    var searchMatches by remember { mutableStateOf<List<Int>>(emptyList()) }
    var showAddTextDialog by remember { mutableStateOf(false) }
    var showSignatureDialog by remember { mutableStateOf(false) }
    var showWatermarkDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf<Boolean?>(null) } // true=protect, false=remove
    var showJumpPageDialog by remember { mutableStateOf(false) }
    var statusToast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(filePath, refreshTrigger) {
        pageCount = withContext(Dispatchers.IO) {
            if (file.exists()) PdfEngine.getPageCount(file) else 0
        }
        if (currentPage >= pageCount && pageCount > 0) {
            currentPage = pageCount - 1
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
                        PdfEngine.addBitmapOverlay(context, file, currentPage, bmp)
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column(modifier = Modifier.clickable { showJumpPageDialog = true }) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            text = if (pageCount > 0) "صفحة ${currentPage + 1} من $pageCount" else "PDF",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSearchDialog = true }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { isNightMode = !isNightMode }) {
                        Icon(
                            if (isNightMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Night Mode"
                        )
                    }
                    IconButton(onClick = { isContinuousScroll = !isContinuousScroll }) {
                        Icon(
                            if (isContinuousScroll) Icons.Default.ViewDay else Icons.Default.ViewAgenda,
                            contentDescription = "Scroll Mode"
                        )
                    }
                    var moreMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { moreMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("إضافة صفحة فارغة") },
                                onClick = {
                                    moreMenu = false
                                    scope.launch(Dispatchers.IO) {
                                        PdfEngine.addBlankPage(context, file)
                                        refreshTrigger++
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("حذف الصفحة الحالية") },
                                onClick = {
                                    moreMenu = false
                                    scope.launch(Dispatchers.IO) {
                                        PdfEngine.deletePage(context, file, currentPage)
                                        refreshTrigger++
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("تدوير الصفحة 90°") },
                                onClick = {
                                    moreMenu = false
                                    scope.launch(Dispatchers.IO) {
                                        PdfEngine.rotatePage(context, file, currentPage, 90)
                                        refreshTrigger++
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("قص هوامش الصفحة") },
                                onClick = {
                                    moreMenu = false
                                    scope.launch(Dispatchers.IO) {
                                        PdfEngine.cropPage(context, file, currentPage)
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
                                        PdfEngine.extractPages(context, file, listOf(currentPage), out)
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
                                        PdfEngine.splitPdf(context, file, currentPage + 1, p1, p2)
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
                                        PdfEngine.addPageNumbers(context, file)
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
                                onClick = {
                                    moreMenu = false
                                    scope.launch(Dispatchers.IO) {
                                        val out = FileUtils.ensureUniqueFile(file.parentFile!!, "${file.nameWithoutExtension}_compressed.pdf")
                                        PdfEngine.compressPdf(context, file, out)
                                        repository.registerOrUpdateFile(out, markOpened = false)
                                        statusToast = "تم الضغط: ${out.name}"
                                    }
                                }
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
                    IconButton(onClick = { zoomScale = (zoomScale + 0.25f).coerceAtMost(2.5f) }) {
                        Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In")
                    }

                    AssistChip(
                        onClick = { showAddTextDialog = true },
                        label = { Text("+ نص/تعليق") }
                    )
                    AssistChip(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                PdfEngine.addHighlightOrShape(
                                    context, file, currentPage,
                                    0.15f, 0.25f, 0.5f, 0.04f,
                                    android.graphics.Color.YELLOW, true
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
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(if (isNightMode) Color(0xFF0F172A) else MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (pageCount == 0) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (isContinuousScroll) {
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
                            colorFilter = if (isNightMode) ColorFilter.colorMatrix(invertMatrix) else null
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    PdfPageBitmapView(
                        file = file,
                        pageIndex = currentPage,
                        zoomScale = zoomScale,
                        refreshKey = refreshTrigger,
                        colorFilter = if (isNightMode) ColorFilter.colorMatrix(invertMatrix) else null
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

    // Add Text Annotation Dialog
    if (showAddTextDialog) {
        var noteText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddTextDialog = false },
            title = { Text("إضافة نص أو تعليق") },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("النص (بالإنجليزية للأحرف اللاتينية)") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (noteText.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            PdfEngine.addTextAnnotation(context, file, currentPage, noteText, 0.15f, 0.20f)
                            refreshTrigger++
                        }
                    }
                    showAddTextDialog = false
                }) { Text(strings.confirm) }
            },
            dismissButton = { TextButton(onClick = { showAddTextDialog = false }) { Text(strings.cancel) } }
        )
    }

    // Signature / Freehand Pen Pad Dialog
    if (showSignatureDialog) {
        val strokes = remember { mutableStateListOf<List<Offset>>() }
        var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
        AlertDialog(
            onDismissRequest = { showSignatureDialog = false },
            title = { Text("التوقيع الإلكتروني / الرسم بالقلم") },
            text = {
                Column {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(Color.White, RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
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
                        (strokes + listOf(currentStroke)).forEach { pts ->
                            for (i in 0 until pts.size - 1) {
                                drawLine(Color(0xFF1E3A8A), pts[i], pts[i + 1], strokeWidth = 5f)
                            }
                        }
                    }
                    TextButton(onClick = { strokes.clear() }) { Text("مسح") }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (strokes.isNotEmpty()) {
                        scope.launch(Dispatchers.IO) {
                            val bmp = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
                            val c = Canvas(bmp)
                            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = android.graphics.Color.rgb(30, 58, 138)
                                strokeWidth = 6f
                                style = Paint.Style.STROKE
                            }
                            strokes.forEach { pts ->
                                val path = Path()
                                pts.forEachIndexed { idx, pt ->
                                    if (idx == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                                }
                                c.drawPath(path, p)
                            }
                            PdfEngine.addBitmapOverlay(context, file, currentPage, bmp)
                            bmp.recycle()
                            refreshTrigger++
                        }
                    }
                    showSignatureDialog = false
                }) { Text("إدراج التوقيع") }
            },
            dismissButton = { TextButton(onClick = { showSignatureDialog = false }) { Text(strings.cancel) } }
        )
    }

    // Search Dialog
    if (showSearchDialog) {
        var q by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { Text("بحث في PDF") },
            text = {
                OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text(strings.find) })
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        searchMatches = PdfEngine.searchTextPages(context, file, q)
                        if (searchMatches.isNotEmpty()) {
                            currentPage = searchMatches.first()
                        } else {
                            statusToast = "لم يتم العثور على نتائج"
                        }
                    }
                    showSearchDialog = false
                }) { Text(strings.confirm) }
            },
            dismissButton = { TextButton(onClick = { showSearchDialog = false }) { Text(strings.cancel) } }
        )
    }

    // Watermark Dialog
    if (showWatermarkDialog) {
        var wm by remember { mutableStateOf("CONFIDENTIAL") }
        AlertDialog(
            onDismissRequest = { showWatermarkDialog = false },
            title = { Text("إضافة علامة مائية") },
            text = { OutlinedTextField(value = wm, onValueChange = { wm = it }, label = { Text("النص") }) },
            confirmButton = {
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        PdfEngine.addWatermark(context, file, wm)
                        refreshTrigger++
                    }
                    showWatermarkDialog = false
                }) { Text(strings.confirm) }
            },
            dismissButton = { TextButton(onClick = { showWatermarkDialog = false }) { Text(strings.cancel) } }
        )
    }

    // Password Protect / Remove Dialog
    showPasswordDialog?.let { isProtect ->
        var pwd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasswordDialog = null },
            title = { Text(if (isProtect) "حماية بكلمة مرور" else "إزالة كلمة المرور") },
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
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            if (isProtect) PdfEngine.protectWithPassword(context, file, pwd)
                            else PdfEngine.removePassword(context, file, pwd)
                        }.onSuccess {
                            statusToast = if (isProtect) "تمت حماية الملف" else "تمت إزالة كلمة المرور"
                            refreshTrigger++
                        }.onFailure {
                            statusToast = "كلمة المرور غير صحيحة"
                        }
                    }
                    showPasswordDialog = null
                }) { Text(strings.confirm) }
            },
            dismissButton = { TextButton(onClick = { showPasswordDialog = null }) { Text(strings.cancel) } }
        )
    }

    // Jump to Page Dialog
    if (showJumpPageDialog) {
        var pStr by remember { mutableStateOf("${currentPage + 1}") }
        AlertDialog(
            onDismissRequest = { showJumpPageDialog = false },
            title = { Text("الانتقال إلى صفحة") },
            text = {
                OutlinedTextField(
                    value = pStr,
                    onValueChange = { pStr = it },
                    label = { Text("رقم الصفحة (1 - $pageCount)") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    val num = pStr.toIntOrNull()
                    if (num != null && num in 1..pageCount) {
                        currentPage = num - 1
                    }
                    showJumpPageDialog = false
                }) { Text(strings.confirm) }
            },
            dismissButton = { TextButton(onClick = { showJumpPageDialog = false }) { Text(strings.cancel) } }
        )
    }
}

@Composable
private fun PdfPageBitmapView(
    file: File,
    pageIndex: Int,
    zoomScale: Float,
    refreshKey: Int,
    colorFilter: ColorFilter?
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(file.absolutePath, pageIndex, refreshKey) {
        bitmap = withContext(Dispatchers.IO) {
            PdfEngine.renderPageBitmap(file, pageIndex, 1.5f)
        }
    }

    DisposableEffect(bitmap) {
        onDispose { bitmap?.recycle() }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "PDF Page ${pageIndex + 1}",
            contentScale = ContentScale.FillWidth,
            colorFilter = colorFilter,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(scaleX = zoomScale, scaleY = zoomScale)
                .clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}
