package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.core.FileUtils
import com.example.core.LocalAppStrings
import com.example.data.OfficeRepository
import com.example.data.db.DocumentEntity
import com.example.engine.*
import com.example.ui.viewmodel.AiAssistantViewModel
import com.example.ui.viewmodel.AiTaskPreset
import com.example.ui.viewmodel.AiUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantScreen(
    repository: OfficeRepository,
    onOpenDocument: (DocumentEntity) -> Unit,
    onBack: () -> Unit,
    aiViewModel: AiAssistantViewModel = viewModel()
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val uiState by aiViewModel.uiState.collectAsStateWithLifecycle()

    var selectedPreset by remember { mutableStateOf<AiTaskPreset?>(AiTaskPreset.WRITE_ARTICLE) }
    var promptInput by remember { mutableStateOf("") }
    var attachedFileName by remember { mutableStateOf<String?>(null) }
    var attachedContext by remember { mutableStateOf("") }
    var statusToast by remember { mutableStateOf<String?>(null) }

    val attachPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val copied = FileUtils.copyUriToWorkspace(context, uri)
                if (copied != null) {
                    attachedFileName = copied.name
                    attachedContext = withContext(Dispatchers.IO) {
                        when (copied.extension.lowercase()) {
                            "pdf" -> PdfEngine.extractAllText(context, copied)
                            "docx", "rtf", "txt", "doc" -> WordEngine.loadDocument(copied).paragraphs.joinToString("\n") { it.text }
                            "xlsx", "csv", "xls" -> {
                                val wb = ExcelEngine.loadWorkbook(copied)
                                wb.sheets.firstOrNull()?.cells?.values?.joinToString(" | ") { it.displayValue } ?: ""
                            }
                            else -> copied.readText(Charsets.UTF_8)
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
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(strings.aiAssistant, fontWeight = FontWeight.Bold)
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
            // Task Presets
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AiTaskPreset.entries) { preset ->
                    FilterChip(
                        selected = selectedPreset == preset,
                        onClick = { selectedPreset = if (selectedPreset == preset) null else preset },
                        label = { Text(if (strings.isArabic) preset.titleAr else preset.titleEn) }
                    )
                }
            }

            // Attach Document for Analysis (PDF / Word / Excel)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = { attachPicker.launch(arrayOf("*/*")) }
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(attachedFileName ?: "إرفاق ملف (PDF / Word / Excel) للتحليل")
                }
                if (attachedFileName != null) {
                    IconButton(onClick = {
                        attachedFileName = null
                        attachedContext = ""
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear attachment")
                    }
                }
            }

            // Prompt Input
            OutlinedTextField(
                value = promptInput,
                onValueChange = { promptInput = it },
                label = { Text("اكتب طلبك أو سؤالك هنا...") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_prompt_input")
            )

            Button(
                onClick = { aiViewModel.runPrompt(selectedPreset, promptInput, attachedContext) },
                enabled = uiState !is AiUiState.Loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("ai_send_btn")
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("إنشاء بالذكاء الاصطناعي")
            }

            // Output Area
            when (val state = uiState) {
                is AiUiState.Idle -> {}
                is AiUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is AiUiState.Streaming -> {
                    AiResultCard(
                        text = state.text,
                        isStreaming = true,
                        onCopy = {},
                        onSaveAsWord = {},
                        onSaveAsPpt = {}
                    )
                }
                is AiUiState.Success -> {
                    AiResultCard(
                        text = state.text,
                        isStreaming = false,
                        onCopy = {
                            clipboard.setText(AnnotatedString(state.text))
                            statusToast = "تم النسخ"
                        },
                        onSaveAsWord = {
                            scope.launch {
                                val file = FileUtils.ensureUniqueFile(
                                    FileUtils.getWorkspaceRoot(context),
                                    "مستند_ذكي_${System.currentTimeMillis() / 1000}.docx"
                                )
                                val paragraphs = state.text.lines().map { line ->
                                    val isAr = line.any { it in '\u0600'..'\u06FF' }
                                    WordParagraph(
                                        text = line,
                                        isRtl = isAr,
                                        alignment = if (isAr) WordAlignment.RIGHT else WordAlignment.LEFT
                                    )
                                }
                                withContext(Dispatchers.IO) {
                                    WordEngine.saveDocument(file, WordDocumentModel(title = file.nameWithoutExtension, paragraphs = paragraphs))
                                }
                                val doc = repository.registerOrUpdateFile(file, markOpened = true)
                                onOpenDocument(doc)
                            }
                        },
                        onSaveAsPpt = {
                            scope.launch {
                                val file = FileUtils.ensureUniqueFile(
                                    FileUtils.getWorkspaceRoot(context),
                                    "عرض_ذكي_${System.currentTimeMillis() / 1000}.pptx"
                                )
                                val chunks = state.text.lines().filter { it.isNotBlank() }.chunked(5)
                                val slides = chunks.mapIndexed { idx, lines ->
                                    SlideModel(
                                        elements = listOf(
                                            SlideElement(
                                                type = SlideElementType.TEXT,
                                                xFraction = 0.08f, yFraction = 0.10f, widthFraction = 0.84f, heightFraction = 0.18f,
                                                text = "شريحة ${idx + 1}", fontSizeSp = 24, isBold = true
                                            ),
                                            SlideElement(
                                                type = SlideElementType.TEXT,
                                                xFraction = 0.08f, yFraction = 0.32f, widthFraction = 0.84f, heightFraction = 0.55f,
                                                text = lines.joinToString("\n"), fontSizeSp = 16
                                            )
                                        )
                                    )
                                }.ifEmpty { listOf(SlideModel()) }
                                withContext(Dispatchers.IO) {
                                    PowerPointEngine.savePresentation(file, PresentationModel(title = file.nameWithoutExtension, slides = slides))
                                }
                                val doc = repository.registerOrUpdateFile(file, markOpened = true)
                                onOpenDocument(doc)
                            }
                        }
                    )
                }
                is AiUiState.Error -> {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiResultCard(
    text: String,
    isStreaming: Boolean,
    onCopy: () -> Unit,
    onSaveAsWord: () -> Unit,
    onSaveAsPpt: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = text, style = MaterialTheme.typography.bodyLarge)
            if (!isStreaming) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onCopy) { Text("نسخ") }
                    OutlinedButton(onClick = onSaveAsWord) { Text("حفظ كـ Word") }
                    OutlinedButton(onClick = onSaveAsPpt) { Text("حفظ كـ PPT") }
                }
            }
        }
    }
}
