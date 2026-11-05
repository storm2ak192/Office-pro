package com.example.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.DocumentMode
import com.example.core.FileUtils
import com.example.core.LocalAppStrings
import com.example.core.SafeSaveManager
import com.example.core.SafeSaveResult
import com.example.core.SaveState
import com.example.data.OfficeRepository
import com.example.data.db.OfficeFileType
import com.example.engine.*
import com.example.ui.theme.CairoFontFamily
import com.example.ui.theme.RobotoMonoFontFamily
import com.example.ui.theme.RoyalBluePrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordEditorScreen(
    filePath: String,
    repository: OfficeRepository,
    autoSaveEnabled: Boolean,
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val file = remember(filePath) { File(filePath) }

    var mode by remember { mutableStateOf(DocumentMode.VIEW) }
    var doc by remember { mutableStateOf(WordDocumentModel()) }
    var activeParagraphIndex by remember { mutableIntStateOf(0) }
    var undoStack by remember { mutableStateOf<List<WordDocumentModel>>(emptyList()) }
    var redoStack by remember { mutableStateOf<List<WordDocumentModel>>(emptyList()) }
    var isUnsaved by remember { mutableStateOf(false) }
    var saveState by remember { mutableStateOf<SaveState>(SaveState.Saved) }
    var showFindReplace by remember { mutableStateOf(false) }
    var showPageSetup by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    var showSaveAsDialog by remember { mutableStateOf(false) }
    var showDraftRecovery by remember { mutableStateOf<WordDocumentModel?>(null) }
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
            if (file.exists()) WordEngine.loadDocument(file) else WordDocumentModel()
        }
        doc = loaded
        val draft = repository.getAutoSaveDraft(filePath)
        if (draft != null && draft.updatedAt > file.lastModified()) {
            runCatching { Json.decodeFromString<WordDocumentModel>(draft.serializedContent) }
                .getOrNull()?.let { showDraftRecovery = it }
        }
    }

    // Auto-save draft periodically
    LaunchedEffect(doc, isUnsaved, autoSaveEnabled) {
        if (isUnsaved && autoSaveEnabled) {
            delay(4000)
            repository.saveAutoSaveDraft(
                docKey = filePath,
                type = OfficeFileType.WORD,
                title = doc.title,
                content = Json.encodeToString(doc)
            )
        }
    }

    fun updateDoc(newDoc: WordDocumentModel) {
        undoStack = (undoStack + doc).takeLast(25)
        redoStack = emptyList()
        doc = newDoc
        isUnsaved = true
        saveState = SaveState.Unsaved
    }

    fun saveNow(onSaved: (() -> Unit)? = null) {
        scope.launch {
            saveState = SaveState.Saving
            val res = withContext(Dispatchers.IO) {
                val r = WordEngine.saveDocument(file, doc, context)
                if (r is SafeSaveResult.Success) {
                    repository.registerOrUpdateFile(file, markOpened = true)
                    repository.deleteAutoSaveDraft(filePath)
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

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { inp ->
                    val bmp = BitmapFactory.decodeStream(inp)
                    if (bmp != null) {
                        val baos = ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, baos)
                        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                        withContext(Dispatchers.Main) {
                            val list = doc.paragraphs.toMutableList()
                            list.add(
                                (activeParagraphIndex + 1).coerceAtMost(list.size),
                                WordParagraph(text = "", imageBase64 = b64)
                            )
                            updateDoc(doc.copy(paragraphs = list))
                        }
                    }
                }
            }
        }
    }

    val activeP = doc.paragraphs.getOrElse(activeParagraphIndex) { WordParagraph() }
    val wordCount = remember(doc.paragraphs) {
        doc.paragraphs.sumOf { p ->
            p.text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        }
    }
    val charCount = remember(doc.paragraphs) {
        doc.paragraphs.sumOf { it.text.length }
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
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        val modeLabel = if (mode == DocumentMode.VIEW) "وضع العرض" else "وضع التحرير"
                        val stateLabel = when (saveState) {
                            is SaveState.Saved -> strings.saved
                            is SaveState.Unsaved -> strings.unsaved
                            is SaveState.Saving -> strings.saving
                            is SaveState.Failed -> "فشل الحفظ"
                        }
                        Text(
                            text = "$modeLabel • $stateLabel • $wordCount كلمة",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUnsaved) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (mode == DocumentMode.VIEW) {
                        FilledTonalButton(
                            onClick = { mode = DocumentMode.EDIT },
                            modifier = Modifier.testTag("word_edit_mode_btn"),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("تحرير")
                        }
                    } else {
                        IconButton(
                            enabled = undoStack.isNotEmpty(),
                            onClick = {
                                val prev = undoStack.last()
                                undoStack = undoStack.dropLast(1)
                                redoStack = redoStack + doc
                                doc = prev
                                isUnsaved = true
                                saveState = SaveState.Unsaved
                            },
                            modifier = Modifier.testTag("undo_btn")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = strings.undo)
                        }
                        IconButton(
                            enabled = redoStack.isNotEmpty(),
                            onClick = {
                                val next = redoStack.last()
                                redoStack = redoStack.dropLast(1)
                                undoStack = undoStack + doc
                                doc = next
                                isUnsaved = true
                                saveState = SaveState.Unsaved
                            },
                            modifier = Modifier.testTag("redo_btn")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = strings.redo)
                        }
                        IconButton(
                            onClick = { saveNow() },
                            modifier = Modifier.testTag("save_word_btn")
                        ) {
                            Icon(Icons.Default.Save, contentDescription = strings.save)
                        }
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
                                DropdownMenuItem(
                                    text = { Text(strings.findReplace) },
                                    onClick = { moreMenu = false; showFindReplace = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("إعدادات الصفحة والهوامش") },
                                    onClick = { moreMenu = false; showPageSetup = true }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("حفظ باسم (نسخة جديدة)") },
                                onClick = { moreMenu = false; showSaveAsDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text(strings.exportPdf) },
                                onClick = {
                                    moreMenu = false
                                    scope.launch(Dispatchers.IO) {
                                        val pdfFile = File(file.parentFile, "${file.nameWithoutExtension}.pdf")
                                        WordEngine.exportToPdf(doc, pdfFile)
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
                                    WordEngine.exportToPdf(doc, tmp)
                                    FileUtils.printPdfFile(context, tmp)
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
            if (mode == DocumentMode.EDIT) {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Style cycle
                    FilterChip(
                        selected = activeP.style != WordStyle.NORMAL,
                        onClick = {
                            val nextStyle = when (activeP.style) {
                                WordStyle.NORMAL -> WordStyle.HEADING_1
                                WordStyle.HEADING_1 -> WordStyle.HEADING_2
                                WordStyle.HEADING_2 -> WordStyle.HEADING_3
                                WordStyle.HEADING_3 -> WordStyle.QUOTE
                                WordStyle.QUOTE -> WordStyle.NORMAL
                            }
                            updateParagraph(doc, activeParagraphIndex, activeP.copy(style = nextStyle), ::updateDoc)
                        },
                        label = {
                            Text(
                                when (activeP.style) {
                                    WordStyle.NORMAL -> "عادي"
                                    WordStyle.HEADING_1 -> "عنوان 1"
                                    WordStyle.HEADING_2 -> "عنوان 2"
                                    WordStyle.HEADING_3 -> "عنوان 3"
                                    WordStyle.QUOTE -> "اقتباس"
                                }
                            )
                        }
                    )
                    // Font size
                    IconButton(onClick = {
                        updateParagraph(
                            doc, activeParagraphIndex,
                            activeP.copy(fontSizeSp = (activeP.fontSizeSp - 2).coerceAtLeast(10)),
                            ::updateDoc
                        )
                    }) { Icon(Icons.Default.TextDecrease, contentDescription = "Smaller") }
                    Text("${activeP.fontSizeSp}", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = {
                        updateParagraph(
                            doc, activeParagraphIndex,
                            activeP.copy(fontSizeSp = (activeP.fontSizeSp + 2).coerceAtMost(36)),
                            ::updateDoc
                        )
                    }) { Icon(Icons.Default.TextIncrease, contentDescription = "Larger") }

                    // Bold / Italic / Underline / Strike
                    IconToggleButton(
                        checked = activeP.isBold,
                        onCheckedChange = { updateParagraph(doc, activeParagraphIndex, activeP.copy(isBold = it), ::updateDoc) },
                        modifier = Modifier.testTag("word_bold_btn")
                    ) { Icon(Icons.Default.FormatBold, contentDescription = "Bold") }

                    IconToggleButton(
                        checked = activeP.isItalic,
                        onCheckedChange = { updateParagraph(doc, activeParagraphIndex, activeP.copy(isItalic = it), ::updateDoc) }
                    ) { Icon(Icons.Default.FormatItalic, contentDescription = "Italic") }

                    IconToggleButton(
                        checked = activeP.isUnderline,
                        onCheckedChange = { updateParagraph(doc, activeParagraphIndex, activeP.copy(isUnderline = it), ::updateDoc) }
                    ) { Icon(Icons.Default.FormatUnderlined, contentDescription = "Underline") }

                    IconToggleButton(
                        checked = activeP.isStrikethrough,
                        onCheckedChange = { updateParagraph(doc, activeParagraphIndex, activeP.copy(isStrikethrough = it), ::updateDoc) }
                    ) { Icon(Icons.Default.StrikethroughS, contentDescription = "Strikethrough") }

                    // Text Color cycle
                    IconButton(onClick = {
                        val colors = listOf("#0F172A", "#1D4ED8", "#DC2626", "#16A34A", "#9333EA")
                        val next = colors[(colors.indexOf(activeP.textColorHex) + 1) % colors.size]
                        updateParagraph(doc, activeParagraphIndex, activeP.copy(textColorHex = next), ::updateDoc)
                    }) { Icon(Icons.Default.FormatColorText, contentDescription = "Text Color") }

                    // Highlight toggle
                    IconButton(onClick = {
                        val nextBg = if (activeP.highlightColorHex == null) "#FEF08A" else null
                        updateParagraph(doc, activeParagraphIndex, activeP.copy(highlightColorHex = nextBg), ::updateDoc)
                    }) { Icon(Icons.Default.Highlight, contentDescription = "Highlight") }

                    // Alignment
                    IconButton(onClick = {
                        updateParagraph(doc, activeParagraphIndex, activeP.copy(alignment = WordAlignment.RIGHT, isRtl = true), ::updateDoc)
                    }) { Icon(Icons.AutoMirrored.Filled.FormatAlignRight, contentDescription = "Align Right") }

                    IconButton(onClick = {
                        updateParagraph(doc, activeParagraphIndex, activeP.copy(alignment = WordAlignment.CENTER), ::updateDoc)
                    }) { Icon(Icons.Default.FormatAlignCenter, contentDescription = "Align Center") }

                    IconButton(onClick = {
                        updateParagraph(doc, activeParagraphIndex, activeP.copy(alignment = WordAlignment.LEFT, isRtl = false), ::updateDoc)
                    }) { Icon(Icons.AutoMirrored.Filled.FormatAlignLeft, contentDescription = "Align Left") }

                    // Lists
                    IconButton(onClick = {
                        val next = if (activeP.listType == WordListType.BULLET) WordListType.NONE else WordListType.BULLET
                        updateParagraph(doc, activeParagraphIndex, activeP.copy(listType = next), ::updateDoc)
                    }) { Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = "Bullet List") }

                    IconButton(onClick = {
                        val next = if (activeP.listType == WordListType.NUMBERED) WordListType.NONE else WordListType.NUMBERED
                        updateParagraph(doc, activeParagraphIndex, activeP.copy(listType = next), ::updateDoc)
                    }) { Icon(Icons.Default.FormatListNumbered, contentDescription = "Numbered List") }

                    // Insert Table
                    IconButton(onClick = {
                        val newTbl = WordTable(afterParagraphIndex = activeParagraphIndex)
                        updateDoc(doc.copy(tables = doc.tables + newTbl))
                    }) { Icon(Icons.Default.TableChart, contentDescription = "Insert Table") }

                    // Insert Image
                    IconButton(onClick = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Icon(Icons.Default.Image, contentDescription = "Insert Image") }

                    // Insert Shape
                    IconButton(onClick = {
                        updateDoc(doc.copy(shapes = doc.shapes + WordShape()))
                    }) { Icon(Icons.Default.Category, contentDescription = "Insert Shape") }

                    // Insert Hyperlink
                    IconButton(onClick = { showLinkDialog = true }) {
                        Icon(Icons.Default.Link, contentDescription = "Insert Link")
                    }

                    // Copy / Cut / Paste paragraph
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(activeP.text))
                        statusToast = "تم النسخ"
                    }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy") }

                    IconButton(onClick = {
                        val clipText = clipboard.getText()?.text
                        if (!clipText.isNullOrEmpty()) {
                            updateParagraph(doc, activeParagraphIndex, activeP.copy(text = activeP.text + clipText), ::updateDoc)
                        }
                    }) { Icon(Icons.Default.ContentPaste, contentDescription = "Paste") }
                }
            }
            }
        }
    ) { padding ->
        if (mode == DocumentMode.VIEW) {
            SelectionContainer {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 2.dp,
                            shadowElevation = 2.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (doc.headerText.isNotBlank()) {
                                    Text(
                                        text = doc.headerText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Center
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }

                                doc.paragraphs.forEachIndexed { idx, p ->
                                    if (p.imageBase64 != null) {
                                        val bmp = remember(p.imageBase64) {
                                            runCatching {
                                                val bytes = Base64.decode(p.imageBase64, Base64.DEFAULT)
                                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                            }.getOrNull()
                                        }
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp.asImageBitmap(),
                                                contentDescription = "Document image",
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 240.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                            )
                                        }
                                    }
                                    val prefix = when (p.listType) {
                                        WordListType.BULLET -> "• "
                                        WordListType.NUMBERED -> "${idx + 1}. "
                                        WordListType.NONE -> ""
                                    }
                                    val computedSize = when (p.style) {
                                        WordStyle.HEADING_1 -> (p.fontSizeSp + 8).sp
                                        WordStyle.HEADING_2 -> (p.fontSizeSp + 5).sp
                                        WordStyle.HEADING_3 -> (p.fontSizeSp + 2).sp
                                        else -> p.fontSizeSp.sp
                                    }
                                    val textAlign = when (p.alignment) {
                                        WordAlignment.RIGHT -> TextAlign.Right
                                        WordAlignment.CENTER -> TextAlign.Center
                                        WordAlignment.LEFT -> TextAlign.Left
                                        WordAlignment.JUSTIFY -> TextAlign.Justify
                                    }
                                    val decoration = when {
                                        p.isUnderline && p.isStrikethrough -> TextDecoration.Underline + TextDecoration.LineThrough
                                        p.isUnderline -> TextDecoration.Underline
                                        p.isStrikethrough -> TextDecoration.LineThrough
                                        else -> TextDecoration.None
                                    }
                                    if (p.text.isNotEmpty() || prefix.isNotEmpty()) {
                                        Text(
                                            text = prefix + p.text,
                                            style = TextStyle(
                                                fontFamily = if (p.fontFamily == "RobotoMono") RobotoMonoFontFamily else CairoFontFamily,
                                                fontSize = computedSize,
                                                fontWeight = if (p.isBold || p.style != WordStyle.NORMAL) FontWeight.Bold else FontWeight.Normal,
                                                fontStyle = if (p.isItalic || p.style == WordStyle.QUOTE) FontStyle.Italic else FontStyle.Normal,
                                                textDecoration = decoration,
                                                textAlign = textAlign,
                                                color = runCatching { Color(android.graphics.Color.parseColor(p.textColorHex)) }
                                                    .getOrDefault(MaterialTheme.colorScheme.onSurface)
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    activeParagraphIndex = idx
                                                    mode = DocumentMode.EDIT
                                                }
                                        )
                                    }

                                    doc.tables.filter { it.afterParagraphIndex == idx }.forEach { tbl ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 6.dp)
                                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                                        ) {
                                            tbl.rows.forEach { row ->
                                                Row(modifier = Modifier.fillMaxWidth()) {
                                                    row.forEach { cell ->
                                                        Box(
                                                            modifier = Modifier
                                                                .weight(cell.colSpan.toFloat().coerceAtLeast(1f))
                                                                .background(
                                                                    if (cell.isHeader) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                                                    else Color.Transparent
                                                                )
                                                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                                                .padding(8.dp)
                                                        ) {
                                                            Text(
                                                                text = cell.text,
                                                                fontWeight = if (cell.isHeader) FontWeight.Bold else FontWeight.Normal,
                                                                style = MaterialTheme.typography.bodySmall
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                if (doc.footerText.isNotBlank()) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Text(
                                        text = doc.footerText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (doc.headerText.isNotBlank()) {
                item {
                    Text(
                        text = doc.headerText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }

            itemsIndexed(doc.paragraphs, key = { _, p -> p.id }) { idx, p ->
                val isSelected = idx == activeParagraphIndex
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = p.highlightColorHex?.let { Color(android.graphics.Color.parseColor(it)) }
                        ?: MaterialTheme.colorScheme.surface,
                    border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { activeParagraphIndex = idx }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (p.imageBase64 != null) {
                            val bmp = remember(p.imageBase64) {
                                runCatching {
                                    val bytes = Base64.decode(p.imageBase64, Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                }.getOrNull()
                            }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Embedded image",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                        }

                        Row(verticalAlignment = Alignment.Top) {
                            if (p.listType == WordListType.BULLET) {
                                Text("• ", fontWeight = FontWeight.Bold, fontSize = p.fontSizeSp.sp)
                            } else if (p.listType == WordListType.NUMBERED) {
                                Text("${idx + 1}. ", fontWeight = FontWeight.Bold, fontSize = p.fontSizeSp.sp)
                            }

                            val computedSize = when (p.style) {
                                WordStyle.HEADING_1 -> (p.fontSizeSp + 8).sp
                                WordStyle.HEADING_2 -> (p.fontSizeSp + 5).sp
                                WordStyle.HEADING_3 -> (p.fontSizeSp + 2).sp
                                else -> p.fontSizeSp.sp
                            }
                            val textAlign = when (p.alignment) {
                                WordAlignment.RIGHT -> TextAlign.Right
                                WordAlignment.CENTER -> TextAlign.Center
                                WordAlignment.LEFT -> TextAlign.Left
                                WordAlignment.JUSTIFY -> TextAlign.Justify
                            }
                            val decoration = when {
                                p.isUnderline && p.isStrikethrough -> TextDecoration.Underline + TextDecoration.LineThrough
                                p.isUnderline -> TextDecoration.Underline
                                p.isStrikethrough -> TextDecoration.LineThrough
                                else -> TextDecoration.None
                            }

                            BasicTextField(
                                value = p.text,
                                onValueChange = { newText ->
                                    activeParagraphIndex = idx
                                    if (newText.contains('\n')) {
                                        val parts = newText.split('\n')
                                        val list = doc.paragraphs.toMutableList()
                                        list[idx] = p.copy(text = parts[0])
                                        parts.drop(1).forEachIndexed { offset, line ->
                                            list.add(
                                                idx + 1 + offset,
                                                WordParagraph(
                                                    text = line,
                                                    alignment = p.alignment,
                                                    isRtl = p.isRtl,
                                                    listType = p.listType
                                                )
                                            )
                                        }
                                        updateDoc(doc.copy(paragraphs = list))
                                        activeParagraphIndex = idx + parts.size - 1
                                    } else {
                                        updateParagraph(doc, idx, p.copy(text = newText), ::updateDoc)
                                    }
                                },
                                textStyle = TextStyle(
                                    fontFamily = if (p.fontFamily == "Roboto Mono") RobotoMonoFontFamily else CairoFontFamily,
                                    fontSize = computedSize,
                                    fontWeight = if (p.isBold || p.style != WordStyle.NORMAL) FontWeight.Bold else FontWeight.Normal,
                                    fontStyle = if (p.isItalic || p.style == WordStyle.QUOTE) FontStyle.Italic else FontStyle.Normal,
                                    color = runCatching { Color(android.graphics.Color.parseColor(p.textColorHex)) }
                                        .getOrDefault(MaterialTheme.colorScheme.onSurface),
                                    textAlign = textAlign,
                                    textDecoration = decoration
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 28.dp)
                                    .testTag("word_paragraph_$idx")
                            )
                        }

                        if (!p.hyperlinkUrl.isNullOrBlank()) {
                            Text(
                                text = "🔗 ${p.hyperlinkUrl}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Render tables anchored after this paragraph
                doc.tables.filter { it.afterParagraphIndex == idx }.forEach { tbl ->
                    WordTableEditorCard(
                        table = tbl,
                        onUpdateTable = { updatedTbl ->
                            updateDoc(doc.copy(tables = doc.tables.map { if (it.id == tbl.id) updatedTbl else it }))
                        },
                        onDeleteTable = {
                            updateDoc(doc.copy(tables = doc.tables.filterNot { it.id == tbl.id }))
                        }
                    )
                }
            }

            // Render shapes
            itemsIndexed(doc.shapes) { sIdx, shape ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = runCatching { Color(android.graphics.Color.parseColor(shape.fillColorHex)) }
                        .getOrDefault(MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = shape.label,
                            onValueChange = { newLbl ->
                                val list = doc.shapes.toMutableList()
                                list[sIdx] = shape.copy(label = newLbl)
                                updateDoc(doc.copy(shapes = list))
                            },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        IconButton(onClick = {
                            updateDoc(doc.copy(shapes = doc.shapes.filterIndexed { i, _ -> i != sIdx }))
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete shape")
                        }
                    }
                }
            }
        }
        }
    }

    // Find & Replace Dialog
    if (showFindReplace) {
        var findText by remember { mutableStateOf("") }
        var replaceText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showFindReplace = false },
            title = { Text(strings.findReplace) },
            text = {
                Column {
                    OutlinedTextField(
                        value = findText,
                        onValueChange = { findText = it },
                        label = { Text(strings.find) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = replaceText,
                        onValueChange = { replaceText = it },
                        label = { Text(strings.replace) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (findText.isNotEmpty()) {
                        val updated = doc.paragraphs.map { p ->
                            p.copy(text = p.text.replace(findText, replaceText, ignoreCase = true))
                        }
                        updateDoc(doc.copy(paragraphs = updated))
                    }
                    showFindReplace = false
                }) { Text(strings.replaceAll) }
            },
            dismissButton = {
                TextButton(onClick = { showFindReplace = false }) { Text(strings.cancel) }
            }
        )
    }

    // Page Setup Dialog
    if (showPageSetup) {
        var header by remember { mutableStateOf(doc.headerText) }
        var footer by remember { mutableStateOf(doc.footerText) }
        var landscape by remember { mutableStateOf(doc.isLandscape) }
        var showNums by remember { mutableStateOf(doc.showPageNumbers) }
        AlertDialog(
            onDismissRequest = { showPageSetup = false },
            title = { Text("إعدادات الصفحة") },
            text = {
                Column {
                    OutlinedTextField(
                        value = header,
                        onValueChange = { header = it },
                        label = { Text("رأس الصفحة (Header)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = footer,
                        onValueChange = { footer = it },
                        label = { Text("تذييل الصفحة (Footer)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = landscape, onCheckedChange = { landscape = it })
                        Text("اتجاه أفقي (Landscape)")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = showNums, onCheckedChange = { showNums = it })
                        Text("إظهار أرقام الصفحات")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    updateDoc(
                        doc.copy(
                            headerText = header,
                            footerText = footer,
                            isLandscape = landscape,
                            showPageNumbers = showNums
                        )
                    )
                    showPageSetup = false
                }) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { showPageSetup = false }) { Text(strings.cancel) }
            }
        )
    }

    // Insert Hyperlink Dialog
    if (showLinkDialog) {
        var url by remember { mutableStateOf(activeP.hyperlinkUrl ?: "https://") }
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            title = { Text("إدراج رابط") },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    updateParagraph(doc, activeParagraphIndex, activeP.copy(hyperlinkUrl = url), ::updateDoc)
                    showLinkDialog = false
                }) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { showLinkDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    // Auto-Save Draft Recovery Dialog
    showDraftRecovery?.let { draftDoc ->
        AlertDialog(
            onDismissRequest = { showDraftRecovery = null },
            title = { Text(strings.recoverDraftTitle) },
            text = { Text(strings.recoverDraftMessage) },
            confirmButton = {
                Button(onClick = {
                    doc = draftDoc
                    isUnsaved = true
                    showDraftRecovery = null
                }) { Text(strings.recover) }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch { repository.deleteAutoSaveDraft(filePath) }
                    showDraftRecovery = null
                }) { Text(strings.discard) }
            }
        )
    }

    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            title = { Text("تغييرات غير محفوظة") },
            text = { Text("هل تريد حفظ التغييرات قبل الخروج من المستند؟") },
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
                            scope.launch { repository.deleteAutoSaveDraft(filePath) }
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
        val ext = file.extension.ifEmpty { "docx" }
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
                        val res = WordEngine.saveDocument(dest, doc, context)
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

private fun updateParagraph(
    doc: WordDocumentModel,
    index: Int,
    updated: WordParagraph,
    onUpdate: (WordDocumentModel) -> Unit
) {
    val list = doc.paragraphs.toMutableList()
    if (index in list.indices) {
        list[index] = updated
        onUpdate(doc.copy(paragraphs = list))
    }
}

@Composable
private fun WordTableEditorCard(
    table: WordTable,
    onUpdateTable: (WordTable) -> Unit,
    onDeleteTable: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val cols = table.rows.firstOrNull()?.size ?: 2
                        val newRow = List(cols) { WordTableCell("") }
                        onUpdateTable(table.copy(rows = table.rows + listOf(newRow)))
                    }) { Text("+ صف") }
                    TextButton(onClick = {
                        val updatedRows = table.rows.map { it + WordTableCell("") }
                        onUpdateTable(table.copy(rows = updatedRows))
                    }) { Text("+ عمود") }
                    if (table.rows.size > 1) {
                        TextButton(onClick = {
                            onUpdateTable(table.copy(rows = table.rows.dropLast(1)))
                        }) { Text("- صف") }
                    }
                }
                IconButton(onClick = onDeleteTable) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete table")
                }
            }

            table.rows.forEachIndexed { rIdx, row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEachIndexed { cIdx, cell ->
                        BasicTextField(
                            value = cell.text,
                            onValueChange = { newVal ->
                                val newRows = table.rows.mapIndexed { r, rList ->
                                    if (r != rIdx) rList else rList.mapIndexed { c, cItem ->
                                        if (c != cIdx) cItem else cItem.copy(text = newVal)
                                    }
                                }
                                onUpdateTable(table.copy(rows = newRows))
                            },
                            textStyle = TextStyle(
                                fontFamily = CairoFontFamily,
                                fontSize = 13.sp,
                                fontWeight = if (cell.isHeader) FontWeight.Bold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier
                                .weight(cell.colSpan.toFloat())
                                .border(0.5.dp, MaterialTheme.colorScheme.outline)
                                .background(
                                    if (cell.isHeader) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                    else Color.Transparent
                                )
                                .padding(6.dp)
                        )
                    }
                }
            }
        }
    }
}
