package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.FileUtils
import com.example.core.LocalAppStrings
import com.example.data.db.DocumentEntity
import com.example.data.db.FolderEntity
import com.example.data.db.OfficeFileType
import com.example.data.db.SortOption
import com.example.engine.ExcelEngine
import com.example.engine.PowerPointEngine
import com.example.engine.WordEngine
import com.example.ui.theme.*
import com.example.ui.viewmodel.HomeFilter
import com.example.ui.viewmodel.MainViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenDocument: (DocumentEntity) -> Unit,
    onNavigateToConverter: () -> Unit,
    onNavigateToScanner: () -> Unit,
    onNavigateToTemplates: () -> Unit,
    onNavigateToAi: () -> Unit
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val docs by viewModel.filteredDocuments.collectAsStateWithLifecycle()
    val folders by viewModel.allFolders.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val currentFolder by viewModel.currentFolderPath.collectAsStateWithLifecycle()
    val sortOption by viewModel.sortOption.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val statusMsg by viewModel.statusMessage.collectAsStateWithLifecycle()

    var showSortMenu by remember { mutableStateOf(false) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var showNewDocDialog by remember { mutableStateOf<Pair<OfficeFileType, String>?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<DocumentEntity?>(null) }
    var copyMoveTarget by remember { mutableStateOf<Pair<DocumentEntity, Boolean>?>(null) } // true = move

    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.importUris(uris) { single ->
                onOpenDocument(single)
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.importUris(uris)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusMsg) {
        statusMsg?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = strings.appName,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            openDocLauncher.launch(
                                arrayOf(
                                    "application/pdf",
                                    "application/msword",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "application/vnd.ms-excel",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    "application/vnd.ms-powerpoint",
                                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                    "text/plain",
                                    "text/csv",
                                    "application/rtf",
                                    "image/*"
                                )
                            )
                        },
                        modifier = Modifier.testTag("open_device_file_btn")
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = strings.openDeviceFile)
                    }
                    IconButton(
                        onClick = { viewModel.toggleGridView() },
                        modifier = Modifier.testTag("toggle_view_btn")
                    ) {
                        Icon(
                            imageVector = if (settings.isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = if (settings.isGridView) strings.listView else strings.gridView
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { showSortMenu = true },
                            modifier = Modifier.testTag("sort_btn")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = strings.sortBy)
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            SortOption.entries.forEach { opt ->
                                val label = when (opt) {
                                    SortOption.NAME -> strings.sortName
                                    SortOption.DATE -> strings.sortDate
                                    SortOption.SIZE -> strings.sortSize
                                    SortOption.TYPE -> strings.sortType
                                }
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    leadingIcon = {
                                        if (sortOption == opt) Icon(Icons.Default.Check, contentDescription = null)
                                    },
                                    onClick = {
                                        viewModel.setSortOption(opt)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateSheet = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(strings.createNew) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("create_new_fab")
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text(strings.searchFiles) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = strings.close)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("search_input")
            )

            // Quick Action Tools Row
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    QuickToolChip(
                        icon = Icons.Default.DocumentScanner,
                        label = strings.scanDocument,
                        tint = RoyalBluePrimary,
                        onClick = onNavigateToScanner,
                        tag = "quick_scanner"
                    )
                }
                item {
                    QuickToolChip(
                        icon = Icons.Default.SwapHoriz,
                        label = strings.convertFiles,
                        tint = ExcelBadgeColor,
                        onClick = onNavigateToConverter,
                        tag = "quick_converter"
                    )
                }
                item {
                    QuickToolChip(
                        icon = Icons.Default.DashboardCustomize,
                        label = strings.templates,
                        tint = PowerPointBadgeColor,
                        onClick = onNavigateToTemplates,
                        tag = "quick_templates"
                    )
                }
                if (settings.aiAssistantEnabled) {
                    item {
                        QuickToolChip(
                            icon = Icons.Default.AutoAwesome,
                            label = strings.aiAssistant,
                            tint = IndigoTertiary,
                            onClick = onNavigateToAi,
                            tag = "quick_ai"
                        )
                    }
                }
            }

            // Category Filter Chips
            val filterItems = listOf(
                HomeFilter.ALL to strings.allDocuments,
                HomeFilter.RECENT to strings.recentFiles,
                HomeFilter.FAVORITES to strings.favorites,
                HomeFilter.WORD to strings.wordFiles,
                HomeFilter.EXCEL to strings.excelFiles,
                HomeFilter.POWERPOINT to strings.pptFiles,
                HomeFilter.PDF to strings.pdfFiles,
                HomeFilter.IMAGE to strings.imageFiles,
                HomeFilter.FOLDERS to strings.folders
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filterItems) { (filter, label) ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text(label) }
                    )
                }
            }

            // Folder Breadcrumb & Create Folder when in FOLDERS tab
            if (selectedFilter == HomeFilter.FOLDERS) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (currentFolder.isNotEmpty()) {
                            IconButton(onClick = {
                                val parent = currentFolder.substringBeforeLast('/', "")
                                viewModel.setFolderPath(parent)
                            }) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "Up")
                            }
                        }
                        Text(
                            text = if (currentFolder.isEmpty()) "/ ${strings.folders}" else "/ $currentFolder",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    TextButton(onClick = { showNewFolderDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(strings.newFolder)
                    }
                }

                val subFolders = folders.filter { it.parentPath == currentFolder }
                if (subFolders.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(subFolders) { folder ->
                            FolderCard(
                                folder = folder,
                                onClick = { viewModel.setFolderPath(folder.path) },
                                onDelete = { viewModel.deleteFolder(folder) }
                            )
                        }
                    }
                }
            }

            // Document List / Grid
            if (docs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.FolderCopy,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = strings.noFilesYet,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = strings.noFilesHint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (settings.isGridView) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 156.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(docs, key = { it.id }) { doc ->
                        DocumentGridItem(
                            doc = doc,
                            onClick = {
                                viewModel.openDocument(doc)
                                onOpenDocument(doc)
                            },
                            onToggleFav = { viewModel.toggleFavorite(doc) },
                            onRename = { renameTarget = doc },
                            onCopy = { copyMoveTarget = doc to false },
                            onMove = { copyMoveTarget = doc to true },
                            onDelete = { viewModel.deleteDocument(doc) },
                            onShare = { FileUtils.shareFile(context, File(doc.filePath)) },
                            onPrint = { printAnyDocument(context, doc) },
                            onOpenExternal = { FileUtils.openWithExternalApp(context, File(doc.filePath)) }
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(docs, key = { it.id }) { doc ->
                        DocumentListItem(
                            doc = doc,
                            onClick = {
                                viewModel.openDocument(doc)
                                onOpenDocument(doc)
                            },
                            onToggleFav = { viewModel.toggleFavorite(doc) },
                            onRename = { renameTarget = doc },
                            onCopy = { copyMoveTarget = doc to false },
                            onMove = { copyMoveTarget = doc to true },
                            onDelete = { viewModel.deleteDocument(doc) },
                            onShare = { FileUtils.shareFile(context, File(doc.filePath)) },
                            onPrint = { printAnyDocument(context, doc) },
                            onOpenExternal = { FileUtils.openWithExternalApp(context, File(doc.filePath)) }
                        )
                    }
                }
            }
        }
    }

    // Create New Bottom Sheet
    if (showCreateSheet) {
        ModalBottomSheet(onDismissRequest = { showCreateSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = strings.createNew,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                CreateOptionRow(Icons.Default.Description, strings.newWord, WordBadgeColor) {
                    showCreateSheet = false
                    showNewDocDialog = OfficeFileType.WORD to "docx"
                }
                CreateOptionRow(Icons.Default.TableChart, strings.newExcel, ExcelBadgeColor) {
                    showCreateSheet = false
                    showNewDocDialog = OfficeFileType.EXCEL to "xlsx"
                }
                CreateOptionRow(Icons.Default.Slideshow, strings.newPpt, PowerPointBadgeColor) {
                    showCreateSheet = false
                    showNewDocDialog = OfficeFileType.POWERPOINT to "pptx"
                }
                CreateOptionRow(Icons.Default.PictureAsPdf, strings.newPdf, PdfBadgeColor) {
                    showCreateSheet = false
                    showNewDocDialog = OfficeFileType.PDF to "pdf"
                }
                CreateOptionRow(Icons.Default.CreateNewFolder, strings.newFolder, SkySecondary) {
                    showCreateSheet = false
                    showNewFolderDialog = true
                }
                CreateOptionRow(Icons.Default.PhotoLibrary, strings.imageFiles, ImageBadgeColor) {
                    showCreateSheet = false
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // New Document Name & Format Dialog
    showNewDocDialog?.let { (type, defaultExt) ->
        var name by remember { mutableStateOf("") }
        var ext by remember { mutableStateOf(defaultExt) }
        AlertDialog(
            onDismissRequest = { showNewDocDialog = null },
            title = { Text(strings.createNew) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(strings.sortName) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (type == OfficeFileType.WORD) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("docx", "rtf", "txt").forEach { f ->
                                FilterChip(
                                    selected = ext == f,
                                    onClick = { ext = f },
                                    label = { Text(f.uppercase()) }
                                )
                            }
                        }
                    } else if (type == OfficeFileType.EXCEL) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("xlsx", "csv").forEach { f ->
                                FilterChip(
                                    selected = ext == f,
                                    onClick = { ext = f },
                                    label = { Text(f.uppercase()) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showNewDocDialog = null
                    when (type) {
                        OfficeFileType.WORD -> viewModel.createNewWordDocument(name, ext, onOpenDocument)
                        OfficeFileType.EXCEL -> viewModel.createNewExcelWorkbook(name, ext, onOpenDocument)
                        OfficeFileType.POWERPOINT -> viewModel.createNewPresentation(name, onOpenDocument)
                        OfficeFileType.PDF -> viewModel.createNewPdf(name, onOpenDocument)
                        else -> {}
                    }
                }) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { showNewDocDialog = null }) { Text(strings.cancel) }
            }
        )
    }

    // New Folder Dialog
    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text(strings.newFolder) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(strings.sortName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.createFolder(folderName)
                    showNewFolderDialog = false
                }) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text(strings.cancel) }
            }
        )
    }

    // Rename Dialog
    renameTarget?.let { doc ->
        var newName by remember { mutableStateOf(doc.name.substringBeforeLast('.')) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(strings.rename) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.renameDocument(doc, newName)
                    renameTarget = null
                }) { Text(strings.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(strings.cancel) }
            }
        )
    }

    // Copy / Move Folder Picker Dialog
    copyMoveTarget?.let { (doc, isMove) ->
        AlertDialog(
            onDismissRequest = { copyMoveTarget = null },
            title = { Text(if (isMove) strings.move else strings.copy) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            if (isMove) viewModel.moveDocument(doc, "") else viewModel.copyDocument(doc, "")
                            copyMoveTarget = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("/ (${strings.allDocuments})")
                    }
                    folders.forEach { f ->
                        TextButton(
                            onClick = {
                                if (isMove) viewModel.moveDocument(doc, f.path) else viewModel.copyDocument(doc, f.path)
                                copyMoveTarget = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("/ ${f.path}")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { copyMoveTarget = null }) { Text(strings.cancel) }
            }
        )
    }
}

private fun printAnyDocument(context: android.content.Context, doc: DocumentEntity) {
    val file = File(doc.filePath)
    if (!file.exists()) return
    if (doc.fileType == OfficeFileType.PDF) {
        FileUtils.printPdfFile(context, file)
    } else {
        val tempPdf = File(context.cacheDir, "${file.nameWithoutExtension}_print.pdf")
        when (doc.fileType) {
            OfficeFileType.WORD -> WordEngine.exportToPdf(WordEngine.loadDocument(file), tempPdf)
            OfficeFileType.EXCEL -> ExcelEngine.exportToPdf(ExcelEngine.loadWorkbook(file), tempPdf)
            OfficeFileType.POWERPOINT -> PowerPointEngine.exportToPdf(PowerPointEngine.loadPresentation(file), tempPdf)
            OfficeFileType.IMAGE -> com.example.engine.ConverterEngine.imagesToPdf(listOf(file), tempPdf)
            else -> {}
        }
        if (tempPdf.exists()) {
            FileUtils.printPdfFile(context, tempPdf)
        }
    }
}

@Composable
private fun QuickToolChip(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    tag: String
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = tint.copy(alpha = 0.10f),
        modifier = Modifier
            .height(42.dp)
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = tint, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CreateOptionRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint)
        }
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun FolderCard(
    folder: FolderEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = RoyalBluePrimary)
            Spacer(Modifier.width(8.dp))
            Text(folder.name, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Delete folder", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun DocumentListItem(
    doc: DocumentEntity,
    onClick: () -> Unit,
    onToggleFav: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onPrint: () -> Unit,
    onOpenExternal: () -> Unit
) {
    val strings = LocalAppStrings.current
    var menuExpanded by remember { mutableStateOf(false) }
    val (icon, badgeColor) = fileTypeVisuals(doc.fileType)

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("doc_item_${doc.id}")
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(badgeColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = badgeColor)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = doc.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${doc.extension.uppercase()} • ${FileUtils.formatFileSize(doc.sizeBytes)} • ${FileUtils.formatDate(doc.lastModified)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onToggleFav) {
                Icon(
                    imageVector = if (doc.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = strings.favorites,
                    tint = if (doc.isFavorite) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DocumentContextMenu(
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    onRename = onRename,
                    onCopy = onCopy,
                    onMove = onMove,
                    onShare = onShare,
                    onPrint = onPrint,
                    onOpenExternal = onOpenExternal,
                    onDelete = onDelete
                )
            }
        }
    }
}

@Composable
private fun DocumentGridItem(
    doc: DocumentEntity,
    onClick: () -> Unit,
    onToggleFav: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onPrint: () -> Unit,
    onOpenExternal: () -> Unit
) {
    val strings = LocalAppStrings.current
    var menuExpanded by remember { mutableStateOf(false) }
    val (icon, badgeColor) = fileTypeVisuals(doc.fileType)

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(badgeColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = badgeColor)
                }
                Row {
                    IconButton(onClick = onToggleFav, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (doc.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = strings.favorites,
                            tint = if (doc.isFavorite) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", modifier = Modifier.size(18.dp))
                        }
                        DocumentContextMenu(
                            expanded = menuExpanded,
                            onDismiss = { menuExpanded = false },
                            onRename = onRename,
                            onCopy = onCopy,
                            onMove = onMove,
                            onShare = onShare,
                            onPrint = onPrint,
                            onOpenExternal = onOpenExternal,
                            onDelete = onDelete
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = doc.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${doc.extension.uppercase()} • ${FileUtils.formatFileSize(doc.sizeBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DocumentContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onPrint: () -> Unit,
    onOpenExternal: () -> Unit,
    onDelete: () -> Unit
) {
    val strings = LocalAppStrings.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(strings.rename) }, onClick = { onDismiss(); onRename() })
        DropdownMenuItem(text = { Text(strings.copy) }, onClick = { onDismiss(); onCopy() })
        DropdownMenuItem(text = { Text(strings.move) }, onClick = { onDismiss(); onMove() })
        DropdownMenuItem(text = { Text(strings.share) }, onClick = { onDismiss(); onShare() })
        DropdownMenuItem(text = { Text(strings.print) }, onClick = { onDismiss(); onPrint() })
        DropdownMenuItem(text = { Text(strings.openWith) }, onClick = { onDismiss(); onOpenExternal() })
        DropdownMenuItem(
            text = { Text(strings.delete, color = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); onDelete() }
        )
    }
}

fun fileTypeVisuals(type: OfficeFileType): Pair<ImageVector, Color> {
    return when (type) {
        OfficeFileType.WORD -> Icons.Default.Description to WordBadgeColor
        OfficeFileType.EXCEL -> Icons.Default.TableChart to ExcelBadgeColor
        OfficeFileType.POWERPOINT -> Icons.Default.Slideshow to PowerPointBadgeColor
        OfficeFileType.PDF -> Icons.Default.PictureAsPdf to PdfBadgeColor
        OfficeFileType.IMAGE -> Icons.Default.Image to ImageBadgeColor
    }
}
