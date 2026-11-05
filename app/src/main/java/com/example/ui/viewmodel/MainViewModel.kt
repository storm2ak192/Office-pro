package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.FileUtils
import com.example.data.OfficeRepository
import com.example.data.ThemeMode
import com.example.data.UserSettings
import com.example.data.db.DocumentEntity
import com.example.data.db.FolderEntity
import com.example.data.db.OfficeFileType
import com.example.data.db.SortOption
import com.example.engine.ExcelEngine
import com.example.engine.ExcelWorkbookModel
import com.example.engine.OfficeTemplate
import com.example.engine.PdfEngine
import com.example.engine.PowerPointEngine
import com.example.engine.PresentationModel
import com.example.engine.TemplatesProvider
import com.example.engine.WordDocumentModel
import com.example.engine.WordEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class HomeFilter {
    ALL, RECENT, FAVORITES, WORD, EXCEL, POWERPOINT, PDF, IMAGE, FOLDERS
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val repository = OfficeRepository(application)

    val settings: StateFlow<UserSettings> = repository.settingsDataStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedFilter = MutableStateFlow(HomeFilter.ALL)
    val selectedFilter: StateFlow<HomeFilter> = _selectedFilter.asStateFlow()

    private val _currentFolderPath = MutableStateFlow("")
    val currentFolderPath: StateFlow<String> = _currentFolderPath.asStateFlow()

    private val _sortOption = MutableStateFlow(SortOption.DATE)
    val sortOption: StateFlow<SortOption> = _sortOption.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    val allDocuments: StateFlow<List<DocumentEntity>> = repository.allDocuments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentDocuments: StateFlow<List<DocumentEntity>> = repository.recentDocuments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allFolders: StateFlow<List<FolderEntity>> = repository.allFolders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredDocuments: StateFlow<List<DocumentEntity>> = combine(
        repository.allDocuments,
        repository.recentDocuments,
        _selectedFilter,
        _currentFolderPath,
        _searchQuery,
        _sortOption
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        val all = arr[0] as List<DocumentEntity>
        @Suppress("UNCHECKED_CAST")
        val recent = arr[1] as List<DocumentEntity>
        val filter = arr[2] as HomeFilter
        val folder = arr[3] as String
        val query = (arr[4] as String).trim()
        val sort = arr[5] as SortOption

        val base = when (filter) {
            HomeFilter.ALL -> all
            HomeFilter.RECENT -> recent
            HomeFilter.FAVORITES -> all.filter { it.isFavorite }
            HomeFilter.WORD -> all.filter { it.fileType == OfficeFileType.WORD }
            HomeFilter.EXCEL -> all.filter { it.fileType == OfficeFileType.EXCEL }
            HomeFilter.POWERPOINT -> all.filter { it.fileType == OfficeFileType.POWERPOINT }
            HomeFilter.PDF -> all.filter { it.fileType == OfficeFileType.PDF }
            HomeFilter.IMAGE -> all.filter { it.fileType == OfficeFileType.IMAGE }
            HomeFilter.FOLDERS -> all.filter { it.folderPath == folder }
        }

        val searched = if (query.isEmpty()) base else {
            base.filter { it.name.contains(query, ignoreCase = true) }
        }

        when (sort) {
            SortOption.NAME -> searched.sortedBy { it.name.lowercase() }
            SortOption.DATE -> searched.sortedByDescending { it.lastModified }
            SortOption.SIZE -> searched.sortedByDescending { it.sizeBytes }
            SortOption.TYPE -> searched.sortedBy { it.extension }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            repository.syncWorkspaceFiles()
        }
    }

    fun setSearchQuery(q: String) { _searchQuery.value = q }
    fun setFilter(f: HomeFilter) { _selectedFilter.value = f }
    fun setFolderPath(path: String) { _currentFolderPath.value = path }
    fun setSortOption(s: SortOption) { _sortOption.value = s }
    fun clearStatusMessage() { _statusMessage.value = null }

    fun toggleGridView() {
        viewModelScope.launch {
            repository.settingsDataStore.setGridView(!settings.value.isGridView)
        }
    }

    fun createNewWordDocument(name: String, ext: String = "docx", onCreated: (DocumentEntity) -> Unit) {
        viewModelScope.launch {
            val doc = withContext(Dispatchers.IO) {
                val dir = targetDir()
                val clean = name.ifBlank { "مستند" }.removeSuffix(".$ext") + ".$ext"
                val file = FileUtils.ensureUniqueFile(dir, clean)
                val model = WordDocumentModel(
                    title = file.nameWithoutExtension,
                    isLandscape = false
                )
                WordEngine.saveDocument(file, model)
                repository.registerOrUpdateFile(file, markOpened = true)
            }
            onCreated(doc)
        }
    }

    fun createNewExcelWorkbook(name: String, ext: String = "xlsx", onCreated: (DocumentEntity) -> Unit) {
        viewModelScope.launch {
            val doc = withContext(Dispatchers.IO) {
                val dir = targetDir()
                val clean = name.ifBlank { "جدول" }.removeSuffix(".$ext") + ".$ext"
                val file = FileUtils.ensureUniqueFile(dir, clean)
                val model = ExcelWorkbookModel(title = file.nameWithoutExtension)
                ExcelEngine.saveWorkbook(file, model)
                repository.registerOrUpdateFile(file, markOpened = true)
            }
            onCreated(doc)
        }
    }

    fun createNewPresentation(name: String, onCreated: (DocumentEntity) -> Unit) {
        viewModelScope.launch {
            val doc = withContext(Dispatchers.IO) {
                val dir = targetDir()
                val clean = name.ifBlank { "عرض_تقديمي" }.removeSuffix(".pptx") + ".pptx"
                val file = FileUtils.ensureUniqueFile(dir, clean)
                val model = PresentationModel(title = file.nameWithoutExtension)
                PowerPointEngine.savePresentation(file, model)
                repository.registerOrUpdateFile(file, markOpened = true)
            }
            onCreated(doc)
        }
    }

    fun createNewPdf(name: String, onCreated: (DocumentEntity) -> Unit) {
        viewModelScope.launch {
            val doc = withContext(Dispatchers.IO) {
                val dir = targetDir()
                val clean = name.ifBlank { "ملف_PDF" }.removeSuffix(".pdf") + ".pdf"
                val file = FileUtils.ensureUniqueFile(dir, clean)
                PdfEngine.createBlankPdf(getApplication(), file, file.nameWithoutExtension)
                repository.registerOrUpdateFile(file, markOpened = true)
            }
            onCreated(doc)
        }
    }

    fun createFromTemplate(template: OfficeTemplate, onCreated: (DocumentEntity) -> Unit) {
        viewModelScope.launch {
            val doc = withContext(Dispatchers.IO) {
                val dir = targetDir()
                val file = FileUtils.ensureUniqueFile(dir, "${template.titleAr}.${template.extension}")
                when (template.fileType) {
                    OfficeFileType.WORD -> {
                        val model = TemplatesProvider.createWordFromTemplate(template.id)
                        WordEngine.saveDocument(file, model)
                    }
                    OfficeFileType.EXCEL -> {
                        val model = TemplatesProvider.createExcelFromTemplate(template.id)
                        ExcelEngine.saveWorkbook(file, model)
                    }
                    OfficeFileType.POWERPOINT -> {
                        val model = TemplatesProvider.createPresentationFromTemplate(template.id)
                        PowerPointEngine.savePresentation(file, model)
                    }
                    else -> {}
                }
                repository.registerOrUpdateFile(file, markOpened = true)
            }
            onCreated(doc)
        }
    }

    private fun targetDir(): File {
        val root = FileUtils.getWorkspaceRoot(getApplication())
        val folder = _currentFolderPath.value
        return if (_selectedFilter.value == HomeFilter.FOLDERS && folder.isNotBlank()) {
            File(root, folder).apply { mkdirs() }
        } else root
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val parent = if (_selectedFilter.value == HomeFilter.FOLDERS) _currentFolderPath.value else ""
            repository.createFolder(name, parent)
            _statusMessage.value = "تم إنشاء المجلد: $name"
        }
    }

    fun importUris(uris: List<Uri>, onSingleImported: ((DocumentEntity) -> Unit)? = null) {
        viewModelScope.launch {
            val folder = if (_selectedFilter.value == HomeFilter.FOLDERS) _currentFolderPath.value else ""
            var lastDoc: DocumentEntity? = null
            uris.forEach { uri ->
                val imported = repository.importUri(uri, folder)
                if (imported != null) lastDoc = imported
            }
            if (uris.size == 1 && lastDoc != null) {
                onSingleImported?.invoke(lastDoc!!)
            } else if (uris.isNotEmpty()) {
                _statusMessage.value = "تم استيراد ${uris.size} ملفات"
            }
        }
    }

    fun openDocument(doc: DocumentEntity) {
        viewModelScope.launch {
            repository.markDocumentOpened(doc, settings.value.recordRecentFiles)
        }
    }

    fun toggleFavorite(doc: DocumentEntity) {
        viewModelScope.launch { repository.toggleFavorite(doc) }
    }

    fun renameDocument(doc: DocumentEntity, newName: String) {
        viewModelScope.launch {
            val res = repository.renameDocument(doc, newName)
            _statusMessage.value = if (res.isSuccess) "تمت إعادة التسمية" else res.exceptionOrNull()?.message
        }
    }

    fun copyDocument(doc: DocumentEntity, targetFolder: String) {
        viewModelScope.launch {
            val res = repository.copyDocument(doc, targetFolder)
            _statusMessage.value = if (res.isSuccess) "تم نسخ الملف" else res.exceptionOrNull()?.message
        }
    }

    fun moveDocument(doc: DocumentEntity, targetFolder: String) {
        viewModelScope.launch {
            val res = repository.moveDocument(doc, targetFolder)
            _statusMessage.value = if (res.isSuccess) "تم نقل الملف" else res.exceptionOrNull()?.message
        }
    }

    fun deleteDocument(doc: DocumentEntity) {
        viewModelScope.launch {
            repository.deleteDocument(doc)
            _statusMessage.value = "تم حذف ${doc.name}"
        }
    }

    fun deleteFolder(folder: FolderEntity) {
        viewModelScope.launch {
            repository.deleteFolder(folder)
            if (_currentFolderPath.value == folder.path) {
                _currentFolderPath.value = folder.parentPath
            }
            _statusMessage.value = "تم حذف المجلد"
        }
    }

    // Settings updates
    fun setLanguage(lang: String) = viewModelScope.launch { repository.settingsDataStore.setLanguage(lang) }
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { repository.settingsDataStore.setThemeMode(mode) }
    fun setDefaultFont(font: String) = viewModelScope.launch { repository.settingsDataStore.setDefaultFont(font) }
    fun setDefaultFontSize(size: Int) = viewModelScope.launch { repository.settingsDataStore.setDefaultFontSize(size) }
    fun setAutoSave(enabled: Boolean) = viewModelScope.launch { repository.settingsDataStore.setAutoSaveEnabled(enabled) }
    fun setRecordRecent(record: Boolean) = viewModelScope.launch { repository.settingsDataStore.setRecordRecentFiles(record) }
    fun setOpenEditMode(edit: Boolean) = viewModelScope.launch { repository.settingsDataStore.setOpenInEditMode(edit) }
    fun setShareAsPdf(pdf: Boolean) = viewModelScope.launch { repository.settingsDataStore.setShareAsPdfByDefault(pdf) }
    fun setAiEnabled(enabled: Boolean) = viewModelScope.launch { repository.settingsDataStore.setAiAssistantEnabled(enabled) }
    fun clearRecentHistory() = viewModelScope.launch {
        repository.clearRecentHistory()
        _statusMessage.value = "تم مسح سجل الملفات الأخيرة"
    }
    fun clearAutoSaveDrafts() = viewModelScope.launch {
        repository.clearAllDrafts()
        _statusMessage.value = "تم مسح مسودات الحفظ التلقائي"
    }
}
