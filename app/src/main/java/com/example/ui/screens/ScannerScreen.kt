package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.core.FileUtils
import com.example.core.LocalAppStrings
import com.example.data.OfficeRepository
import com.example.data.db.DocumentEntity
import com.example.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    repository: OfficeRepository,
    onOpenDocument: (DocumentEntity) -> Unit,
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val scannedPages = remember { mutableStateListOf<Bitmap>() }
    var activePageIndex by remember { mutableIntStateOf(0) }
    var isCameraMode by remember { mutableStateOf(true) }
    var filterMode by remember { mutableStateOf(ScanFilterMode.ENHANCED) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1.1f) }
    var showCropMode by remember { mutableStateOf(false) }

    // 4 normalized corners for perspective crop
    var tl by remember { mutableStateOf(PointF(0.08f, 0.08f)) }
    var tr by remember { mutableStateOf(PointF(0.92f, 0.08f)) }
    var br by remember { mutableStateOf(PointF(0.92f, 0.92f)) }
    var bl by remember { mutableStateOf(PointF(0.08f, 0.92f)) }

    var extractedOcrText by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var statusToast by remember { mutableStateOf<String?>(null) }

    val imageCapture = remember { ImageCapture.Builder().build() }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { inp ->
                    val bmp = BitmapFactory.decodeStream(inp)
                    if (bmp != null) {
                        withContext(Dispatchers.Main) {
                            scannedPages.add(bmp)
                            activePageIndex = scannedPages.size - 1
                            isCameraMode = false
                        }
                    }
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(strings.scanDocument, fontWeight = FontWeight.Bold) },
                actions = {
                    if (scannedPages.isNotEmpty()) {
                        TextButton(onClick = { isCameraMode = !isCameraMode }) {
                            Text(if (isCameraMode) "معاينة (${scannedPages.size})" else "+ صفحة")
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
            if (isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (isCameraMode) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (hasCameraPermission) {
                        AndroidView(
                            factory = { ctx ->
                                val previewView = PreviewView(ctx)
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                cameraProviderFuture.addListener({
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.surfaceProvider = previewView.surfaceProvider
                                    }
                                    runCatching {
                                        cameraProvider.unbindAll()
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            imageCapture
                                        )
                                    }
                                }, ContextCompat.getMainExecutor(ctx))
                                previewView
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("يرجى منح صلاحية الكاميرا لتصوير المستندات، أو استيراد صورة من المعرض.")
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                                Text("منح صلاحية الكاميرا")
                            }
                        }
                    }

                    // Bottom Camera Controls
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalIconButton(
                            onClick = {
                                galleryPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = "Gallery")
                        }

                        if (hasCameraPermission) {
                            FloatingActionButton(
                                onClick = {
                                    val tmpFile = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
                                    val opts = ImageCapture.OutputFileOptions.Builder(tmpFile).build()
                                    imageCapture.takePicture(
                                        opts,
                                        ContextCompat.getMainExecutor(context),
                                        object : ImageCapture.OnImageSavedCallback {
                                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                                val bmp = BitmapFactory.decodeFile(tmpFile.absolutePath)
                                                if (bmp != null) {
                                                    scannedPages.add(bmp)
                                                    activePageIndex = scannedPages.size - 1
                                                    isCameraMode = false
                                                }
                                            }
                                            override fun onError(exception: ImageCaptureException) {
                                                statusToast = "فشل التصوير: ${exception.message}"
                                            }
                                        }
                                    )
                                },
                                modifier = Modifier.size(68.dp)
                            ) {
                                Icon(Icons.Default.Camera, contentDescription = "Capture", modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
            } else {
                // Scanned Page Editor & Multi-page Queue
                val currentBmp = scannedPages.getOrNull(activePageIndex)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (currentBmp != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black)
                        ) {
                            Image(
                                bitmap = currentBmp.asImageBitmap(),
                                contentDescription = "Scanned Page",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )

                            if (showCropMode) {
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(Unit) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                val nx = (change.position.x / size.width).coerceIn(0.02f, 0.98f)
                                                val ny = (change.position.y / size.height).coerceIn(0.02f, 0.98f)
                                                val corners = listOf(tl, tr, br, bl)
                                                val nearestIdx = corners.indices.minByOrNull { i ->
                                                    kotlin.math.hypot(corners[i].x - nx, corners[i].y - ny)
                                                } ?: 0
                                                when (nearestIdx) {
                                                    0 -> tl = PointF(nx, ny)
                                                    1 -> tr = PointF(nx, ny)
                                                    2 -> br = PointF(nx, ny)
                                                    3 -> bl = PointF(nx, ny)
                                                }
                                            }
                                        }
                                ) {
                                    val p1 = Offset(tl.x * size.width, tl.y * size.height)
                                    val p2 = Offset(tr.x * size.width, tr.y * size.height)
                                    val p3 = Offset(br.x * size.width, br.y * size.height)
                                    val p4 = Offset(bl.x * size.width, bl.y * size.height)
                                    val path = Path().apply {
                                        moveTo(p1.x, p1.y)
                                        lineTo(p2.x, p2.y)
                                        lineTo(p3.x, p3.y)
                                        lineTo(p4.x, p4.y)
                                        close()
                                    }
                                    drawPath(path, Color(0xFF38BDF8), style = Stroke(width = 4f))
                                    listOf(p1, p2, p3, p4).forEach { pt ->
                                        drawCircle(Color(0xFF1D4ED8), radius = 16f, center = pt)
                                    }
                                }
                            }
                        }

                        // Enhancement & Crop Controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = showCropMode,
                                onClick = {
                                    if (showCropMode) {
                                        val cropped = ScannerOcrEngine.applyPerspectiveCrop(currentBmp, tl, tr, br, bl)
                                        scannedPages[activePageIndex] = cropped
                                    }
                                    showCropMode = !showCropMode
                                },
                                label = { Text(if (showCropMode) "تطبيق القص" else "قص المنظور") }
                            )
                            AssistChip(
                                onClick = {
                                    scannedPages[activePageIndex] = ScannerOcrEngine.rotateBitmap(currentBmp, 90f)
                                },
                                label = { Text("تدوير 90°") }
                            )
                            AssistChip(
                                onClick = {
                                    val enhanced = ScannerOcrEngine.applyFilterAndAdjustments(
                                        currentBmp, filterMode, brightness, contrast
                                    )
                                    scannedPages[activePageIndex] = enhanced
                                    statusToast = "تم تحسين الصورة"
                                },
                                label = { Text("تحسين الوضوح") }
                            )
                        }

                        // Filter Modes
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                ScanFilterMode.ORIGINAL to "أصلي",
                                ScanFilterMode.ENHANCED to "محسّن",
                                ScanFilterMode.GRAYSCALE to "رمادي",
                                ScanFilterMode.BLACK_AND_WHITE to "أبيض وأسود"
                            ).forEach { (mode, lbl) ->
                                FilterChip(
                                    selected = filterMode == mode,
                                    onClick = { filterMode = mode },
                                    label = { Text(lbl) }
                                )
                            }
                        }

                        // Brightness & Contrast Sliders
                        Text("السطوع: ${brightness.toInt()}", style = MaterialTheme.typography.labelSmall)
                        Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -60f..60f)
                        Text("التباين: ${String.format("%.1f", contrast)}", style = MaterialTheme.typography.labelSmall)
                        Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0.6f..1.8f)

                        // OCR Actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        isBusy = true
                                        runCatching {
                                            ScannerOcrEngine.recognizeTextOfflineMlKit(currentBmp)
                                        }.onSuccess { txt ->
                                            extractedOcrText = txt.ifBlank { "لم يتم اكتشاف نص إنجليزي." }
                                        }.onFailure {
                                            statusToast = it.localizedMessage
                                        }
                                        isBusy = false
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("OCR محلي (EN)") }

                            Button(
                                onClick = {
                                    scope.launch {
                                        isBusy = true
                                        runCatching {
                                            ScannerOcrEngine.recognizeArabicAndEnglishWithGemini(currentBmp)
                                        }.onSuccess { txt ->
                                            extractedOcrText = txt.ifBlank { "لم يتم اكتشاف نص." }
                                        }.onFailure {
                                            statusToast = "تعذر OCR الذكي (تأكد من الإنترنت)، جرب OCR المحلي."
                                        }
                                        isBusy = false
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("OCR عربي/إنجليزي") }
                        }

                        // Save All Scanned Pages as PDF
                        Button(
                            onClick = {
                                scope.launch {
                                    isBusy = true
                                    val outPdf = FileUtils.ensureUniqueFile(
                                        FileUtils.getWorkspaceRoot(context),
                                        "مسح_ضوئي_${System.currentTimeMillis() / 1000}.pdf"
                                    )
                                    withContext(Dispatchers.IO) {
                                        ScannerOcrEngine.saveBitmapsAsPdf(scannedPages.toList(), outPdf)
                                    }
                                    val doc = repository.registerOrUpdateFile(outPdf, markOpened = true)
                                    isBusy = false
                                    onOpenDocument(doc)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("حفظ كملف PDF (${scannedPages.size} صفحات)")
                        }
                    }

                    // Multi-page thumbnails
                    if (scannedPages.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(scannedPages) { idx, bmp ->
                                Box(modifier = Modifier.size(72.dp)) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "Page ${idx + 1}",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { activePageIndex = idx }
                                    )
                                    IconButton(
                                        onClick = {
                                            scannedPages.removeAt(idx)
                                            if (activePageIndex >= scannedPages.size) {
                                                activePageIndex = (scannedPages.size - 1).coerceAtLeast(0)
                                            }
                                        },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(22.dp)
                                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(11.dp))
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Extracted OCR Text Dialog
    extractedOcrText?.let { ocrText ->
        AlertDialog(
            onDismissRequest = { extractedOcrText = null },
            title = { Text("النص المستخرج (OCR)") },
            text = {
                OutlinedTextField(
                    value = ocrText,
                    onValueChange = { extractedOcrText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 280.dp)
                )
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val wordFile = FileUtils.ensureUniqueFile(
                            FileUtils.getWorkspaceRoot(context),
                            "نص_مستخرج_${System.currentTimeMillis() / 1000}.docx"
                        )
                        val paragraphs = ocrText.lines().map { line ->
                            val isAr = line.any { it in '\u0600'..'\u06FF' }
                            WordParagraph(
                                text = line,
                                isRtl = isAr,
                                alignment = if (isAr) WordAlignment.RIGHT else WordAlignment.LEFT
                            )
                        }
                        withContext(Dispatchers.IO) {
                            WordEngine.saveDocument(wordFile, WordDocumentModel(title = wordFile.nameWithoutExtension, paragraphs = paragraphs))
                        }
                        val doc = repository.registerOrUpdateFile(wordFile, markOpened = true)
                        extractedOcrText = null
                        onOpenDocument(doc)
                    }
                }) { Text("حفظ كملف Word") }
            },
            dismissButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(ocrText))
                    extractedOcrText = null
                    statusToast = "تم نسخ النص"
                }) { Text("نسخ") }
            }
        )
    }
}
