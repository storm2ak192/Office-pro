package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.core.FileUtils
import com.example.core.LocalAppStrings
import com.example.data.OfficeRepository
import com.example.data.db.DocumentEntity
import com.example.engine.ConverterEngine
import com.example.engine.ScannerOcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    filePath: String,
    repository: OfficeRepository,
    onOpenDocument: (DocumentEntity) -> Unit,
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(filePath) { File(filePath) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(filePath) {
        bitmap = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(file.absolutePath)
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
                title = { Text(file.name, maxLines = 1) },
                actions = {
                    IconButton(onClick = {
                        bitmap?.let { bmp ->
                            scope.launch(Dispatchers.IO) {
                                val rotated = ScannerOcrEngine.rotateBitmap(bmp, 90f)
                                FileOutputStream(file).use { rotated.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                                withContext(Dispatchers.Main) { bitmap = rotated }
                            }
                        }
                    }) {
                        Icon(Icons.Default.RotateRight, contentDescription = "Rotate")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            val pdfFile = FileUtils.ensureUniqueFile(
                                file.parentFile!!,
                                "${file.nameWithoutExtension}.pdf"
                            )
                            withContext(Dispatchers.IO) {
                                ConverterEngine.imagesToPdf(listOf(file), pdfFile)
                            }
                            val doc = repository.registerOrUpdateFile(pdfFile, markOpened = true)
                            onOpenDocument(doc)
                        }
                    }) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = strings.exportPdf)
                    }
                    IconButton(onClick = { FileUtils.shareFile(context, file) }) {
                        Icon(Icons.Default.Share, contentDescription = strings.share)
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = file.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                CircularProgressIndicator()
            }
        }
    }
}
