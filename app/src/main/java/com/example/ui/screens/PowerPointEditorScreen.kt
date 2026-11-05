package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.DocumentMode
import com.example.core.FileUtils
import com.example.core.LocalAppStrings
import com.example.core.SafeSaveResult
import com.example.core.SaveState
import com.example.data.OfficeRepository
import com.example.engine.*
import com.example.ui.theme.ExcelBadgeColor
import com.example.ui.theme.PowerPointBadgeColor
import com.example.ui.theme.RoyalBluePrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerPointEditorScreen(
    filePath: String,
    repository: OfficeRepository,
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(filePath) { File(filePath) }

    var mode by remember { mutableStateOf(DocumentMode.VIEW) }
    var presentation by remember { mutableStateOf(PresentationModel()) }
    var selectedElementId by remember { mutableStateOf<String?>(null) }
    var isPresentationMode by remember { mutableStateOf(false) }
    var isUnsaved by remember { mutableStateOf(false) }
    var saveState by remember { mutableStateOf<SaveState>(SaveState.Saved) }
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    var showSaveAsDialog by remember { mutableStateOf(false) }
    var statusToast by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = !isPresentationMode && (isUnsaved || mode == DocumentMode.EDIT)) {
        if (isUnsaved) {
            showExitConfirmDialog = true
        } else if (mode == DocumentMode.EDIT) {
            mode = DocumentMode.VIEW
        }
    }

    LaunchedEffect(filePath) {
        val loaded = withContext(Dispatchers.IO) {
            if (file.exists()) PowerPointEngine.loadPresentation(file) else PresentationModel()
        }
        presentation = loaded
        selectedElementId = loaded.slides.firstOrNull()?.elements?.firstOrNull()?.id
    }

    val activeSlide = presentation.slides.getOrElse(presentation.activeSlideIndex) { SlideModel() }
    val selectedElement = activeSlide.elements.firstOrNull { it.id == selectedElementId }

    fun updateSlide(transform: (SlideModel) -> SlideModel) {
        val updated = presentation.slides.mapIndexed { idx, s ->
            if (idx == presentation.activeSlideIndex) transform(s) else s
        }
        presentation = presentation.copy(slides = updated)
        isUnsaved = true
        saveState = SaveState.Unsaved
    }

    fun updateSelectedElement(transform: (SlideElement) -> SlideElement) {
        val id = selectedElementId ?: return
        updateSlide { slide ->
            slide.copy(elements = slide.elements.map { if (it.id == id) transform(it) else it })
        }
    }

    fun saveNow(onSaved: (() -> Unit)? = null) {
        scope.launch {
            saveState = SaveState.Saving
            val res = withContext(Dispatchers.IO) {
                val r = PowerPointEngine.savePresentation(file, presentation, context)
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

    if (isPresentationMode) {
        // Fullscreen Slideshow Mode
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable {
                    if (presentation.activeSlideIndex + 1 < presentation.slides.size) {
                        presentation = presentation.copy(activeSlideIndex = presentation.activeSlideIndex + 1)
                    } else {
                        isPresentationMode = false
                    }
                }
        ) {
            AnimatedContent(
                targetState = activeSlide,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "slide_transition",
                modifier = Modifier.align(Alignment.Center)
            ) { slide ->
                SlideCanvasView(
                    slide = slide,
                    selectedElementId = null,
                    onSelectElement = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(
                    enabled = presentation.activeSlideIndex > 0,
                    onClick = {
                        presentation = presentation.copy(activeSlideIndex = presentation.activeSlideIndex - 1)
                    }
                ) { Icon(Icons.Default.ChevronRight, contentDescription = "Prev") }

                Text(
                    text = "${presentation.activeSlideIndex + 1} / ${presentation.slides.size}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                FilledTonalIconButton(
                    enabled = presentation.activeSlideIndex + 1 < presentation.slides.size,
                    onClick = {
                        presentation = presentation.copy(activeSlideIndex = presentation.activeSlideIndex + 1)
                    }
                ) { Icon(Icons.Default.ChevronLeft, contentDescription = "Next") }

                FilledTonalButton(onClick = { isPresentationMode = false }) {
                    Text(strings.close)
                }
            }
        }
        return
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
                            text = "$modeLabel • شريحة ${presentation.activeSlideIndex + 1}/${presentation.slides.size} • $stateLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUnsaved) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (mode == DocumentMode.VIEW) {
                        FilledTonalButton(
                            onClick = { mode = DocumentMode.EDIT },
                            modifier = Modifier.testTag("ppt_edit_mode_btn"),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("تحرير")
                        }
                    } else {
                        IconButton(
                            onClick = { saveNow() },
                            modifier = Modifier.testTag("save_ppt_btn")
                        ) {
                            Icon(Icons.Default.Save, contentDescription = strings.save)
                        }
                    }
                    IconButton(
                        onClick = { isPresentationMode = true },
                        modifier = Modifier.testTag("ppt_play_btn")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Present")
                    }
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
                                text = { Text("نسخ الشريحة الحالية") },
                                onClick = {
                                    moreMenu = false
                                    val copy = activeSlide.copy(id = java.util.UUID.randomUUID().toString())
                                    val list = presentation.slides.toMutableList()
                                    list.add(presentation.activeSlideIndex + 1, copy)
                                    presentation = presentation.copy(slides = list, activeSlideIndex = presentation.activeSlideIndex + 1)
                                    isUnsaved = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("حذف الشريحة الحالية") },
                                onClick = {
                                    moreMenu = false
                                    if (presentation.slides.size > 1) {
                                        val list = presentation.slides.toMutableList()
                                        list.removeAt(presentation.activeSlideIndex)
                                        presentation = presentation.copy(
                                            slides = list,
                                            activeSlideIndex = (presentation.activeSlideIndex - 1).coerceAtLeast(0)
                                        )
                                        isUnsaved = true
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(strings.exportPdf) },
                                onClick = {
                                    moreMenu = false
                                    scope.launch(Dispatchers.IO) {
                                        val pdfFile = File(file.parentFile, "${file.nameWithoutExtension}.pdf")
                                        PowerPointEngine.exportToPdf(presentation, pdfFile)
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
                                    PowerPointEngine.exportToPdf(presentation, tmp)
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Slide Canvas (16:9)
            SlideCanvasView(
                slide = activeSlide,
                selectedElementId = if (mode == DocumentMode.EDIT) selectedElementId else null,
                onSelectElement = {
                    selectedElementId = it
                    if (mode == DocumentMode.VIEW) mode = DocumentMode.EDIT
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            )

            if (mode == DocumentMode.VIEW) {
                if (activeSlide.speakerNotes.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("ملاحظات المحاضر", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(activeSlide.speakerNotes, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
            // Add Elements & Slide Background Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {
                        val el = SlideElement(type = SlideElementType.TEXT, text = "نص جديد", yFraction = 0.5f)
                        updateSlide { it.copy(elements = it.elements + el) }
                        selectedElementId = el.id
                    },
                    label = { Text("+ نص") }
                )
                AssistChip(
                    onClick = {
                        val el = SlideElement(
                            type = SlideElementType.SHAPE,
                            text = "شكل",
                            xFraction = 0.25f, yFraction = 0.35f, widthFraction = 0.5f, heightFraction = 0.3f
                        )
                        updateSlide { it.copy(elements = it.elements + el) }
                        selectedElementId = el.id
                    },
                    label = { Text("+ شكل") }
                )
                AssistChip(
                    onClick = {
                        val el = SlideElement(
                            type = SlideElementType.CHART,
                            xFraction = 0.15f, yFraction = 0.3f, widthFraction = 0.7f, heightFraction = 0.55f
                        )
                        updateSlide { it.copy(elements = it.elements + el) }
                        selectedElementId = el.id
                    },
                    label = { Text("+ رسم بياني") }
                )
                AssistChip(
                    onClick = {
                        val bgs = listOf("#FFFFFF", "#F0F9FF", "#FEF3C7", "#ECFDF5", "#1E293B")
                        val nextBg = bgs[(bgs.indexOf(activeSlide.backgroundColorHex) + 1) % bgs.size]
                        updateSlide { it.copy(backgroundColorHex = nextBg) }
                    },
                    label = { Text("خلفية") }
                )
            }

            // Selected Element Editor
            selectedElement?.let { el ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("تحرير العنصر المحدد", fontWeight = FontWeight.Bold)
                            IconButton(onClick = {
                                updateSlide { s -> s.copy(elements = s.elements.filterNot { it.id == el.id }) }
                                selectedElementId = null
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete element")
                            }
                        }

                        if (el.type == SlideElementType.TEXT || el.type == SlideElementType.SHAPE) {
                            OutlinedTextField(
                                value = el.text,
                                onValueChange = { txt -> updateSelectedElement { it.copy(text = txt) } },
                                label = { Text("النص") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("الحجم:")
                            IconButton(onClick = {
                                updateSelectedElement { it.copy(fontSizeSp = (it.fontSizeSp - 2).coerceAtLeast(12)) }
                            }) { Icon(Icons.Default.Remove, contentDescription = "Decrease") }
                            Text("${el.fontSizeSp}sp")
                            IconButton(onClick = {
                                updateSelectedElement { it.copy(fontSizeSp = (it.fontSizeSp + 2).coerceAtMost(44)) }
                            }) { Icon(Icons.Default.Add, contentDescription = "Increase") }

                            IconToggleButton(
                                checked = el.isBold,
                                onCheckedChange = { b -> updateSelectedElement { it.copy(isBold = b) } }
                            ) { Icon(Icons.Default.FormatBold, contentDescription = "Bold") }
                        }

                        // Position controls
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                updateSelectedElement { it.copy(yFraction = (it.yFraction - 0.08f).coerceAtLeast(0.02f)) }
                            }) { Text("↑ أعلى") }
                            OutlinedButton(onClick = {
                                updateSelectedElement { it.copy(yFraction = (it.yFraction + 0.08f).coerceAtMost(0.8f)) }
                            }) { Text("↓ أسفل") }
                        }
                    }
                }
            }

            // Speaker Notes
            OutlinedTextField(
                value = activeSlide.speakerNotes,
                onValueChange = { notes -> updateSlide { it.copy(speakerNotes = notes) } },
                label = { Text("ملاحظات المتحدث (Speaker Notes)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )

            // Slide Strip
            Text("الشرائح", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(presentation.slides) { idx, slide ->
                    val isCurrent = idx == presentation.activeSlideIndex
                    Surface(
                        onClick = {
                            presentation = presentation.copy(activeSlideIndex = idx)
                            selectedElementId = slide.elements.firstOrNull()?.id
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (isCurrent) 2.dp else 1.dp,
                            color = if (isCurrent) PowerPointBadgeColor else MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier
                            .width(120.dp)
                            .height(72.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    runCatching { Color(android.graphics.Color.parseColor(slide.backgroundColorHex)) }
                                        .getOrDefault(Color.White)
                                )
                                .padding(6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${idx + 1}. ${slide.elements.firstOrNull()?.text?.take(18) ?: "شريحة"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Black,
                                maxLines = 2
                            )
                        }
                    }
                }
                item {
                    if (mode == DocumentMode.EDIT) {
                        FilledTonalIconButton(
                            onClick = {
                                val newSlide = SlideModel()
                                presentation = presentation.copy(
                                    slides = presentation.slides + newSlide,
                                    activeSlideIndex = presentation.slides.size
                                )
                                isUnsaved = true
                                saveState = SaveState.Unsaved
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add Slide")
                        }
                    }
                }
            }
            }
        }
    }

    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            title = { Text("تغييرات غير محفوظة") },
            text = { Text("هل تريد حفظ التغييرات قبل الخروج من العرض التقديمي؟") },
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
        val ext = if (file.extension.equals("odp", true)) "odp" else "pptx"
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
                        val res = PowerPointEngine.savePresentation(dest, presentation, context)
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

@Composable
private fun SlideCanvasView(
    slide: SlideModel,
    selectedElementId: String?,
    onSelectElement: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = remember(slide.backgroundColorHex) {
        runCatching { Color(android.graphics.Color.parseColor(slide.backgroundColorHex)) }
            .getOrDefault(Color.White)
    }

    BoxWithConstraints(
        modifier = modifier.background(bgColor)
    ) {
        val totalW = maxWidth
        val totalH = maxHeight

        slide.elements.forEach { el ->
            val isSel = el.id == selectedElementId
            Box(
                modifier = Modifier
                    .offset(x = totalW * el.xFraction, y = totalH * el.yFraction)
                    .width(totalW * el.widthFraction)
                    .height(totalH * el.heightFraction)
                    .then(
                        if (el.type == SlideElementType.SHAPE) {
                            Modifier.background(
                                runCatching { Color(android.graphics.Color.parseColor(el.fillColorHex)) }
                                    .getOrDefault(Color(0xFFDBEAFE)),
                                RoundedCornerShape(12.dp)
                            )
                        } else Modifier
                    )
                    .then(
                        if (isSel) Modifier.border(2.dp, PowerPointBadgeColor, RoundedCornerShape(6.dp))
                        else Modifier
                    )
                    .clickable { onSelectElement(el.id) }
                    .padding(6.dp)
            ) {
                when (el.type) {
                    SlideElementType.CHART -> {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val maxV = (el.chartValues.maxOfOrNull { it.second } ?: 100f).coerceAtLeast(1f)
                            val barW = size.width / (el.chartValues.size * 2f).coerceAtLeast(1f)
                            val colors = listOf(RoyalBluePrimary, ExcelBadgeColor, PowerPointBadgeColor, Color(0xFF7C3AED))
                            el.chartValues.forEachIndexed { idx, (_, v) ->
                                val h = (v / maxV) * size.height
                                drawRect(
                                    color = colors[idx % colors.size],
                                    topLeft = Offset(idx * barW * 2f + barW * 0.4f, size.height - h),
                                    size = Size(barW, h)
                                )
                            }
                        }
                    }
                    else -> {
                        Text(
                            text = el.text,
                            fontSize = (el.fontSizeSp * 0.75f).sp,
                            fontWeight = if (el.isBold) FontWeight.Bold else FontWeight.Normal,
                            color = runCatching { Color(android.graphics.Color.parseColor(el.textColorHex)) }
                                .getOrDefault(Color.Black)
                        )
                    }
                }
            }
        }
    }
}
