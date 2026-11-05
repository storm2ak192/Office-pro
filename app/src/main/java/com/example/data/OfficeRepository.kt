package com.example.data

import android.content.Context
import android.net.Uri
import com.example.core.FileUtils
import com.example.data.db.AutoSaveDraftEntity
import com.example.data.db.DocumentEntity
import com.example.data.db.FolderEntity
import com.example.data.db.OfficeDao
import com.example.data.db.OfficeDatabase
import com.example.data.db.OfficeFileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class OfficeRepository(private val context: Context) {
    private val db = OfficeDatabase.getInstance(context)
    private val dao: OfficeDao = db.officeDao()
    val settingsDataStore = SettingsDataStore(context)

    val allDocuments: Flow<List<DocumentEntity>> = dao.getAllDocuments()
    val recentDocuments: Flow<List<DocumentEntity>> = dao.getRecentDocuments()
    val favoriteDocuments: Flow<List<DocumentEntity>> = dao.getFavoriteDocuments()
    val allFolders: Flow<List<FolderEntity>> = dao.getAllFolders()

    fun getDocumentsByType(type: OfficeFileType): Flow<List<DocumentEntity>> =
        dao.getDocumentsByType(type)

    fun getDocumentsInFolder(folderPath: String): Flow<List<DocumentEntity>> =
        dao.getDocumentsInFolder(folderPath)

    fun getFoldersIn(parentPath: String): Flow<List<FolderEntity>> =
        dao.getFoldersIn(parentPath)

    suspend fun syncWorkspaceFiles() = withContext(Dispatchers.IO) {
        val root = FileUtils.getWorkspaceRoot(context)
        if (!root.exists()) root.mkdirs()

        val existingDocs = mutableMapOf<String, DocumentEntity>()
        root.walkTopDown().forEach { file ->
            if (file.isDirectory && file != root) {
                val relPath = file.relativeTo(root).path
                val parentPath = file.parentFile?.takeIf { it != root }?.relativeTo(root)?.path ?: ""
                dao.insertFolder(
                    FolderEntity(
                        path = relPath,
                        name = file.name,
                        parentPath = parentPath,
                        createdAt = file.lastModified()
                    )
                )
            } else if (file.isFile) {
                val relFolder = file.parentFile?.takeIf { it != root }?.relativeTo(root)?.path ?: ""
                val existing = dao.getDocumentByPath(file.absolutePath)
                if (existing != null) {
                    if (existing.sizeBytes != file.length() || existing.lastModified != file.lastModified()) {
                        dao.updateDocument(
                            existing.copy(
                                sizeBytes = file.length(),
                                lastModified = file.lastModified(),
                                folderPath = relFolder
                            )
                        )
                    }
                    existingDocs[file.absolutePath] = existing
                } else {
                    val ext = file.extension.lowercase()
                    val doc = DocumentEntity(
                        name = file.name,
                        filePath = file.absolutePath,
                        folderPath = relFolder,
                        fileType = FileUtils.detectFileType(ext),
                        extension = ext,
                        sizeBytes = file.length(),
                        lastModified = file.lastModified()
                    )
                    dao.insertDocument(doc)
                }
            }
        }
    }

    suspend fun registerOrUpdateFile(file: File, markOpened: Boolean = true): DocumentEntity =
        withContext(Dispatchers.IO) {
            val root = FileUtils.getWorkspaceRoot(context)
            val relFolder = file.parentFile?.takeIf { it != root }?.relativeTo(root)?.path ?: ""
            val existing = dao.getDocumentByPath(file.absolutePath)
            val now = System.currentTimeMillis()
            if (existing != null) {
                val updated = existing.copy(
                    name = file.name,
                    folderPath = relFolder,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    lastOpenedAt = if (markOpened) now else existing.lastOpenedAt
                )
                dao.updateDocument(updated)
                updated
            } else {
                val ext = file.extension.lowercase()
                val doc = DocumentEntity(
                    name = file.name,
                    filePath = file.absolutePath,
                    folderPath = relFolder,
                    fileType = FileUtils.detectFileType(ext),
                    extension = ext,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    lastOpenedAt = if (markOpened) now else 0L
                )
                val id = dao.insertDocument(doc)
                doc.copy(id = id)
            }
        }

    suspend fun markDocumentOpened(doc: DocumentEntity, recordRecent: Boolean) =
        withContext(Dispatchers.IO) {
            if (recordRecent) {
                dao.updateDocument(doc.copy(lastOpenedAt = System.currentTimeMillis()))
            }
        }

    suspend fun toggleFavorite(doc: DocumentEntity) = withContext(Dispatchers.IO) {
        dao.updateDocument(doc.copy(isFavorite = !doc.isFavorite))
    }

    suspend fun createFolder(name: String, parentPath: String = ""): FolderEntity =
        withContext(Dispatchers.IO) {
            val cleanName = name.trim().replace("/", "_")
            val relPath = if (parentPath.isBlank()) cleanName else "$parentPath/$cleanName"
            val dir = File(FileUtils.getWorkspaceRoot(context), relPath)
            if (!dir.exists()) dir.mkdirs()
            val folder = FolderEntity(path = relPath, name = cleanName, parentPath = parentPath)
            dao.insertFolder(folder)
            folder
        }

    suspend fun renameDocument(doc: DocumentEntity, newName: String): Result<DocumentEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val oldFile = File(doc.filePath)
                val finalName = if (newName.contains('.')) newName else "$newName.${doc.extension}"
                val newFile = File(oldFile.parentFile, finalName)
                if (newFile.exists() && newFile.absolutePath != oldFile.absolutePath) {
                    throw IllegalStateException("File with this name already exists")
                }
                if (oldFile.exists()) {
                    oldFile.renameTo(newFile)
                }
                val updated = doc.copy(
                    name = newFile.name,
                    filePath = newFile.absolutePath,
                    extension = newFile.extension.lowercase(),
                    lastModified = System.currentTimeMillis()
                )
                dao.updateDocument(updated)
                updated
            }
        }

    suspend fun copyDocument(doc: DocumentEntity, targetFolderPath: String): Result<DocumentEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val src = File(doc.filePath)
                val targetDir = if (targetFolderPath.isBlank()) {
                    FileUtils.getWorkspaceRoot(context)
                } else {
                    File(FileUtils.getWorkspaceRoot(context), targetFolderPath).apply { mkdirs() }
                }
                val dest = FileUtils.ensureUniqueFile(targetDir, src.name)
                src.copyTo(dest, overwrite = false)
                registerOrUpdateFile(dest, markOpened = false)
            }
        }

    suspend fun moveDocument(doc: DocumentEntity, targetFolderPath: String): Result<DocumentEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val src = File(doc.filePath)
                val targetDir = if (targetFolderPath.isBlank()) {
                    FileUtils.getWorkspaceRoot(context)
                } else {
                    File(FileUtils.getWorkspaceRoot(context), targetFolderPath).apply { mkdirs() }
                }
                val dest = FileUtils.ensureUniqueFile(targetDir, src.name)
                src.copyTo(dest, overwrite = true)
                src.delete()
                val updated = doc.copy(
                    name = dest.name,
                    filePath = dest.absolutePath,
                    folderPath = targetFolderPath,
                    lastModified = System.currentTimeMillis()
                )
                dao.updateDocument(updated)
                updated
            }
        }

    suspend fun deleteDocument(doc: DocumentEntity) = withContext(Dispatchers.IO) {
        val file = File(doc.filePath)
        if (file.exists()) file.delete()
        dao.deleteDocument(doc)
        dao.deleteDraft(doc.filePath)
    }

    suspend fun deleteFolder(folder: FolderEntity) = withContext(Dispatchers.IO) {
        val dir = File(FileUtils.getWorkspaceRoot(context), folder.path)
        if (dir.exists()) dir.deleteRecursively()
        dao.deleteFolderAndChildren(folder.path)
        syncWorkspaceFiles()
    }

    suspend fun importUri(uri: Uri, folderPath: String = ""): DocumentEntity? =
        withContext(Dispatchers.IO) {
            val copied = FileUtils.copyUriToWorkspace(context, uri, folderPath) ?: return@withContext null
            registerOrUpdateFile(copied, markOpened = true)
        }

    suspend fun clearRecentHistory() = withContext(Dispatchers.IO) {
        dao.resetRecentTimestamps()
    }

    suspend fun saveAutoSaveDraft(docKey: String, type: OfficeFileType, title: String, content: String) =
        withContext(Dispatchers.IO) {
            dao.saveDraft(AutoSaveDraftEntity(docKey, type, title, content, System.currentTimeMillis()))
        }

    suspend fun getAutoSaveDraft(docKey: String): AutoSaveDraftEntity? =
        withContext(Dispatchers.IO) {
            dao.getDraft(docKey)
        }

    suspend fun deleteAutoSaveDraft(docKey: String) = withContext(Dispatchers.IO) {
        dao.deleteDraft(docKey)
    }

    suspend fun clearAllDrafts() = withContext(Dispatchers.IO) {
        dao.clearAllDrafts()
    }
}
