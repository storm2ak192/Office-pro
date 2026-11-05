package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class OfficeFileType {
    WORD, EXCEL, POWERPOINT, PDF, IMAGE
}

enum class SortOption {
    NAME, DATE, SIZE, TYPE
}

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val filePath: String,
    val folderPath: String = "",
    val fileType: OfficeFileType,
    val extension: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val isFavorite: Boolean = false,
    val lastOpenedAt: Long = 0L
)

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val path: String,
    val name: String,
    val parentPath: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "autosave_drafts")
data class AutoSaveDraftEntity(
    @PrimaryKey val docKey: String,
    val docType: OfficeFileType,
    val title: String,
    val serializedContent: String,
    val updatedAt: Long = System.currentTimeMillis()
)
