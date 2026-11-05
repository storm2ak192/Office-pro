package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OfficeDao {
    @Query("SELECT * FROM documents ORDER BY lastModified DESC")
    fun getAllDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE lastOpenedAt > 0 ORDER BY lastOpenedAt DESC LIMIT 30")
    fun getRecentDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE isFavorite = 1 ORDER BY lastModified DESC")
    fun getFavoriteDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE fileType = :type ORDER BY lastModified DESC")
    fun getDocumentsByType(type: OfficeFileType): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE folderPath = :folderPath ORDER BY lastModified DESC")
    fun getDocumentsInFolder(folderPath: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun getDocumentById(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents WHERE filePath = :path LIMIT 1")
    suspend fun getDocumentByPath(path: String): DocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(doc: DocumentEntity): Long

    @Update
    suspend fun updateDocument(doc: DocumentEntity)

    @Delete
    suspend fun deleteDocument(doc: DocumentEntity)

    @Query("DELETE FROM documents WHERE lastOpenedAt > 0")
    suspend fun clearRecentHistory()

    @Query("UPDATE documents SET lastOpenedAt = 0")
    suspend fun resetRecentTimestamps()

    @Query("SELECT * FROM folders WHERE parentPath = :parentPath ORDER BY name ASC")
    fun getFoldersIn(parentPath: String): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY name ASC")
    fun getAllFolders(): Flow<List<FolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE path = :path OR path LIKE :path || '/%'")
    suspend fun deleteFolderAndChildren(path: String)

    @Query("SELECT * FROM autosave_drafts WHERE docKey = :docKey LIMIT 1")
    suspend fun getDraft(docKey: String): AutoSaveDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDraft(draft: AutoSaveDraftEntity)

    @Query("DELETE FROM autosave_drafts WHERE docKey = :docKey")
    suspend fun deleteDraft(docKey: String)

    @Query("DELETE FROM autosave_drafts")
    suspend fun clearAllDrafts()
}
