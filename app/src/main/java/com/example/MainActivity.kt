package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.core.ArabicStrings
import com.example.core.EnglishStrings
import com.example.core.LocalAppStrings
import com.example.core.configureAppCheck
import com.example.data.ThemeMode
import com.example.ui.*
import com.example.ui.theme.OfficeProTheme
import com.example.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure Firebase App Check with launch intent before UI or ViewModels
        configureAppCheck(this, intent)

        enableEdgeToEdge()

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val mainViewModel: MainViewModel = viewModel()
            val settings by mainViewModel.settings.collectAsStateWithLifecycle()

            val strings = if (settings.language == "en") EnglishStrings else ArabicStrings
            val isDark = when (settings.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            CompositionLocalProvider(
                LocalAppStrings provides strings,
                LocalLayoutDirection provides strings.layoutDirection
            ) {
                OfficeProTheme(darkTheme = isDark) {
                    val navController = rememberNavController()
                    val backStackEntry by navController.currentBackStackEntryAsState()
                    val dest = backStackEntry?.destination

                    val showTopLevelNav = dest?.let {
                        it.hasRoute<HomeRoute>() ||
                            it.hasRoute<TemplatesRoute>() ||
                            it.hasRoute<ConverterRoute>() ||
                            it.hasRoute<AiRoute>() ||
                            it.hasRoute<SettingsRoute>()
                    } ?: true

                    val useRail = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

                    Scaffold(
                        bottomBar = {
                            if (showTopLevelNav && !useRail) {
                                NavigationBar {
                                    NavigationBarItem(
                                        selected = dest?.hasRoute<HomeRoute>() == true,
                                        onClick = {
                                            navController.navigate(HomeRoute) {
                                                popUpTo<HomeRoute> { inclusive = true }
                                            }
                                        },
                                        icon = { Icon(Icons.Default.Home, contentDescription = strings.home) },
                                        label = { Text(strings.home) },
                                        modifier = Modifier.testTag("nav_home")
                                    )
                                    NavigationBarItem(
                                        selected = dest?.hasRoute<TemplatesRoute>() == true,
                                        onClick = { navController.navigate(TemplatesRoute) },
                                        icon = { Icon(Icons.Default.DashboardCustomize, contentDescription = strings.templates) },
                                        label = { Text(strings.templates) },
                                        modifier = Modifier.testTag("nav_templates")
                                    )
                                    NavigationBarItem(
                                        selected = dest?.hasRoute<ConverterRoute>() == true,
                                        onClick = { navController.navigate(ConverterRoute) },
                                        icon = { Icon(Icons.Default.SwapHoriz, contentDescription = strings.convertFiles) },
                                        label = { Text(strings.tools) },
                                        modifier = Modifier.testTag("nav_tools")
                                    )
                                    if (settings.aiAssistantEnabled) {
                                        NavigationBarItem(
                                            selected = dest?.hasRoute<AiRoute>() == true,
                                            onClick = { navController.navigate(AiRoute) },
                                            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = strings.aiAssistant) },
                                            label = { Text(strings.aiAssistant) },
                                            modifier = Modifier.testTag("nav_ai")
                                        )
                                    }
                                    NavigationBarItem(
                                        selected = dest?.hasRoute<SettingsRoute>() == true,
                                        onClick = { navController.navigate(SettingsRoute) },
                                        icon = { Icon(Icons.Default.Settings, contentDescription = strings.settings) },
                                        label = { Text(strings.settings) },
                                        modifier = Modifier.testTag("nav_settings")
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            if (showTopLevelNav && useRail) {
                                NavigationRail {
                                    NavigationRailItem(
                                        selected = dest?.hasRoute<HomeRoute>() == true,
                                        onClick = {
                                            navController.navigate(HomeRoute) {
                                                popUpTo<HomeRoute> { inclusive = true }
                                            }
                                        },
                                        icon = { Icon(Icons.Default.Home, contentDescription = strings.home) },
                                        label = { Text(strings.home) }
                                    )
                                    NavigationRailItem(
                                        selected = dest?.hasRoute<TemplatesRoute>() == true,
                                        onClick = { navController.navigate(TemplatesRoute) },
                                        icon = { Icon(Icons.Default.DashboardCustomize, contentDescription = strings.templates) },
                                        label = { Text(strings.templates) }
                                    )
                                    NavigationRailItem(
                                        selected = dest?.hasRoute<ConverterRoute>() == true,
                                        onClick = { navController.navigate(ConverterRoute) },
                                        icon = { Icon(Icons.Default.SwapHoriz, contentDescription = strings.convertFiles) },
                                        label = { Text(strings.tools) }
                                    )
                                    if (settings.aiAssistantEnabled) {
                                        NavigationRailItem(
                                            selected = dest?.hasRoute<AiRoute>() == true,
                                            onClick = { navController.navigate(AiRoute) },
                                            icon = { Icon(Icons.Default.AutoAwesome, contentDescription = strings.aiAssistant) },
                                            label = { Text(strings.aiAssistant) }
                                        )
                                    }
                                    NavigationRailItem(
                                        selected = dest?.hasRoute<SettingsRoute>() == true,
                                        onClick = { navController.navigate(SettingsRoute) },
                                        icon = { Icon(Icons.Default.Settings, contentDescription = strings.settings) },
                                        label = { Text(strings.settings) }
                                    )
                                }
                            }

                            OfficeNavHost(
                                navController = navController,
                                mainViewModel = mainViewModel,
                                autoSaveEnabled = settings.autoSaveEnabled,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}
