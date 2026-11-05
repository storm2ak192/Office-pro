package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "office_pro_settings")

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

data class UserSettings(
    val language: String = "ar",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val defaultFont: String = "Cairo",
    val defaultFontSize: Int = 14,
    val autoSaveEnabled: Boolean = true,
    val autoSaveIntervalSec: Int = 10,
    val recordRecentFiles: Boolean = true,
    val openInEditMode: Boolean = true,
    val shareAsPdfByDefault: Boolean = false,
    val aiAssistantEnabled: Boolean = true,
    val isGridView: Boolean = false
)

class SettingsDataStore(private val context: Context) {
    private object Keys {
        val LANGUAGE = stringPreferencesKey("language")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DEFAULT_FONT = stringPreferencesKey("default_font")
        val DEFAULT_FONT_SIZE = intPreferencesKey("default_font_size")
        val AUTO_SAVE = booleanPreferencesKey("auto_save")
        val AUTO_SAVE_INTERVAL = intPreferencesKey("auto_save_interval")
        val RECORD_RECENT = booleanPreferencesKey("record_recent")
        val OPEN_EDIT_MODE = booleanPreferencesKey("open_edit_mode")
        val SHARE_AS_PDF = booleanPreferencesKey("share_as_pdf")
        val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        val IS_GRID_VIEW = booleanPreferencesKey("is_grid_view")
    }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            language = prefs[Keys.LANGUAGE] ?: "ar",
            themeMode = runCatching {
                ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name)
            }.getOrDefault(ThemeMode.SYSTEM),
            defaultFont = prefs[Keys.DEFAULT_FONT] ?: "Cairo",
            defaultFontSize = prefs[Keys.DEFAULT_FONT_SIZE] ?: 14,
            autoSaveEnabled = prefs[Keys.AUTO_SAVE] ?: true,
            autoSaveIntervalSec = prefs[Keys.AUTO_SAVE_INTERVAL] ?: 10,
            recordRecentFiles = prefs[Keys.RECORD_RECENT] ?: true,
            openInEditMode = prefs[Keys.OPEN_EDIT_MODE] ?: true,
            shareAsPdfByDefault = prefs[Keys.SHARE_AS_PDF] ?: false,
            aiAssistantEnabled = prefs[Keys.AI_ENABLED] ?: true,
            isGridView = prefs[Keys.IS_GRID_VIEW] ?: false
        )
    }

    suspend fun setLanguage(lang: String) = context.dataStore.edit { it[Keys.LANGUAGE] = lang }
    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setDefaultFont(font: String) = context.dataStore.edit { it[Keys.DEFAULT_FONT] = font }
    suspend fun setDefaultFontSize(size: Int) = context.dataStore.edit { it[Keys.DEFAULT_FONT_SIZE] = size }
    suspend fun setAutoSaveEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.AUTO_SAVE] = enabled }
    suspend fun setAutoSaveInterval(sec: Int) = context.dataStore.edit { it[Keys.AUTO_SAVE_INTERVAL] = sec }
    suspend fun setRecordRecentFiles(record: Boolean) = context.dataStore.edit { it[Keys.RECORD_RECENT] = record }
    suspend fun setOpenInEditMode(editMode: Boolean) = context.dataStore.edit { it[Keys.OPEN_EDIT_MODE] = editMode }
    suspend fun setShareAsPdfByDefault(pdf: Boolean) = context.dataStore.edit { it[Keys.SHARE_AS_PDF] = pdf }
    suspend fun setAiAssistantEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.AI_ENABLED] = enabled }
    suspend fun setGridView(isGrid: Boolean) = context.dataStore.edit { it[Keys.IS_GRID_VIEW] = isGrid }
}
