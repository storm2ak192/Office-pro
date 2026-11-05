package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.FileUtils
import com.example.core.LocalAppStrings
import com.example.data.ThemeMode
import com.example.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val statusMsg by viewModel.statusMessage.collectAsStateWithLifecycle()

    val storageUsed = remember {
        FileUtils.formatFileSize(FileUtils.calculateDirectorySize(FileUtils.getWorkspaceRoot(context)))
    }

    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(statusMsg) {
        statusMsg?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearStatusMessage()
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
                title = { Text(strings.settings, fontWeight = FontWeight.Bold) }
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
            // Language
            SettingsCard("اللغة / Language") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.language == "ar",
                        onClick = { viewModel.setLanguage("ar") },
                        label = { Text("العربية (RTL)") }
                    )
                    FilterChip(
                        selected = settings.language == "en",
                        onClick = { viewModel.setLanguage("en") },
                        label = { Text("English (LTR)") }
                    )
                }
            }

            // Theme
            SettingsCard("المظهر (الوضع الداكن والفاتح)") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.themeMode == ThemeMode.SYSTEM,
                        onClick = { viewModel.setThemeMode(ThemeMode.SYSTEM) },
                        label = { Text("تلقائي") }
                    )
                    FilterChip(
                        selected = settings.themeMode == ThemeMode.LIGHT,
                        onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) },
                        label = { Text("فاتح") }
                    )
                    FilterChip(
                        selected = settings.themeMode == ThemeMode.DARK,
                        onClick = { viewModel.setThemeMode(ThemeMode.DARK) },
                        label = { Text("داكن") }
                    )
                }
            }

            // Fonts
            SettingsCard("إعدادات الخطوط الافتراضية") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Cairo", "Roboto Mono").forEach { f ->
                        FilterChip(
                            selected = settings.defaultFont == f,
                            onClick = { viewModel.setDefaultFont(f) },
                            label = { Text(f) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("حجم الخط: ${settings.defaultFontSize}sp")
                    OutlinedButton(onClick = { viewModel.setDefaultFontSize((settings.defaultFontSize - 2).coerceAtLeast(10)) }) { Text("-") }
                    OutlinedButton(onClick = { viewModel.setDefaultFontSize((settings.defaultFontSize + 2).coerceAtMost(24)) }) { Text("+") }
                }
            }

            // Auto-save & Recent Files
            SettingsCard("الحفظ التلقائي والملفات الأخيرة") {
                SettingsSwitchRow("تفعيل الحفظ التلقائي للاستعادة", settings.autoSaveEnabled) {
                    viewModel.setAutoSave(it)
                }
                SettingsSwitchRow("تسجيل الملفات الأخيرة", settings.recordRecentFiles) {
                    viewModel.setRecordRecent(it)
                }
                OutlinedButton(onClick = { viewModel.clearRecentHistory() }) {
                    Text("مسح سجل الملفات الأخيرة")
                }
            }

            // Opening, Sharing & Privacy
            SettingsCard("الفتح والمشاركة والخصوصية") {
                SettingsSwitchRow("فتح المستندات في وضع التحرير مباشرة", settings.openInEditMode) {
                    viewModel.setOpenEditMode(it)
                }
                SettingsSwitchRow("تفضيل المشاركة بصيغة PDF", settings.shareAsPdfByDefault) {
                    viewModel.setShareAsPdf(it)
                }
            }

            // AI Assistant
            SettingsCard("إعدادات الذكاء الاصطناعي") {
                SettingsSwitchRow("تفعيل مساعد الذكاء الاصطناعي (Gemini)", settings.aiAssistantEnabled) {
                    viewModel.setAiEnabled(it)
                }
            }

            // Storage Management
            SettingsCard("إدارة مساحة التخزين") {
                Text("المساحة المستخدمة لمستندات التطبيق: $storageUsed")
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { viewModel.clearAutoSaveDrafts() }) {
                    Text("مسح مسودات الحفظ التلقائي المؤقتة")
                }
            }

            // App Info
            SettingsCard("حول التطبيق") {
                Text("Office Pro — الإصدار 1.0.0", fontWeight = FontWeight.Bold)
                Text(
                    "تطبيق مكتبي متكامل لتحرير المستندات والجداول والعروض التقديمية وملفات PDF محلياً بخصوصية تامة.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
