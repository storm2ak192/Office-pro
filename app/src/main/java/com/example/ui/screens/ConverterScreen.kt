package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.FileUtils
import com.example.core.LocalAppStrings
import com.example.data.OfficeRepository
import com.example.data.db.DocumentEntity
import com.example.engine.ConversionType
import com.example.engine.ConverterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen(
    repository: OfficeRepository,
    onOpenDocument: (DocumentEntity) -> Unit,
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingType by remember { mutableStateOf<ConversionType?>(null) }
    var isConverting by remember { mutableStateOf(false) }
    var convertedDocs by remember { mutableStateOf<List<DocumentEntity>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val type = pendingType ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            scope.launch {
                isConverting = true
                errorMessage = null
                val copied = FileUtils.copyUriToWorkspace(context, uri)
                if (copied != null) {
                    val outDir = FileUtils.getWorkspaceRoot(context)
                    val res = withContext(Dispatchers.IO) {
                        ConverterEngine.convertSingleFile(context, copied, type, outDir)
                    }
                    res.onSuccess { files ->
                        val registered = files.map { repository.registerOrUpdateFile(it, markOpened = false) }
                        convertedDocs = registered
                    }.onFailure { err ->
                        errorMessage = err.localizedMessage ?: "تعذر تحويل الملف."
                    }
                } else {
                    errorMessage = "تعذر قراءة الملف المختار."
                }
                isConverting = false
            }
        }
    }

    val multiImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                isConverting = true
                errorMessage = null
                val outDir = FileUtils.getWorkspaceRoot(context)
                val imgFiles = uris.mapNotNull { FileUtils.copyUriToWorkspace(context, it) }
                val outPdf = FileUtils.ensureUniqueFile(outDir, "صور_مدمجة.pdf")
                val res = withContext(Dispatchers.IO) {
                    runCatching {
                        ConverterEngine.imagesToPdf(imgFiles, outPdf)
                        outPdf
                    }
                }
                res.onSuccess { pdf ->
                    val doc = repository.registerOrUpdateFile(pdf, markOpened = false)
                    convertedDocs = listOf(doc)
                }.onFailure {
                    errorMessage = it.localizedMessage ?: "تعذر تحويل الصور إلى PDF."
                }
                isConverting = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(strings.convertFiles, fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isConverting) {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            errorMessage?.let { err ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(err, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            if (convertedDocs.isNotEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("تم التحويل بنجاح (${convertedDocs.size})", fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(8.dp))
                            convertedDocs.forEach { doc ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(doc.name, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { onOpenDocument(doc) }) {
                                        Text("فتح")
                                    }
                                    TextButton(onClick = { FileUtils.shareFile(context, File(doc.filePath)) }) {
                                        Text(strings.share)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            items(ConversionType.entries) { type ->
                Surface(
                    onClick = {
                        pendingType = type
                        if (type == ConversionType.IMAGES_TO_PDF) {
                            multiImagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        } else {
                            filePicker.launch(arrayOf("*/*"))
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (strings.isArabic) type.titleAr else type.titleEn,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${type.sourceExts.joinToString(", ").uppercase()} → ${type.targetExt.uppercase()}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
