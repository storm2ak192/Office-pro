package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.example.data.db.DocumentEntity
import com.example.data.db.OfficeFileType
import com.example.ui.screens.*
import com.example.ui.viewmodel.MainViewModel
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable object TemplatesRoute
@Serializable object AiRoute
@Serializable object SettingsRoute
@Serializable object ConverterRoute
@Serializable object ScannerRoute
@Serializable data class WordEditorRoute(val filePath: String)
@Serializable data class ExcelEditorRoute(val filePath: String)
@Serializable data class PptEditorRoute(val filePath: String)
@Serializable data class PdfEditorRoute(val filePath: String)
@Serializable data class ImageViewerRoute(val filePath: String)

fun NavHostController.navigateToDocument(doc: DocumentEntity) {
    when (doc.fileType) {
        OfficeFileType.WORD -> navigate(WordEditorRoute(doc.filePath))
        OfficeFileType.EXCEL -> navigate(ExcelEditorRoute(doc.filePath))
        OfficeFileType.POWERPOINT -> navigate(PptEditorRoute(doc.filePath))
        OfficeFileType.PDF -> navigate(PdfEditorRoute(doc.filePath))
        OfficeFileType.IMAGE -> navigate(ImageViewerRoute(doc.filePath))
    }
}

@Composable
fun OfficeNavHost(
    navController: NavHostController,
    mainViewModel: MainViewModel,
    autoSaveEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier
    ) {
        composable<HomeRoute> {
            HomeScreen(
                viewModel = mainViewModel,
                onOpenDocument = { doc -> navController.navigateToDocument(doc) },
                onNavigateToConverter = { navController.navigate(ConverterRoute) },
                onNavigateToScanner = { navController.navigate(ScannerRoute) },
                onNavigateToTemplates = { navController.navigate(TemplatesRoute) },
                onNavigateToAi = { navController.navigate(AiRoute) }
            )
        }
        composable<TemplatesRoute> {
            TemplatesScreen(
                viewModel = mainViewModel,
                onOpenDocument = { doc -> navController.navigateToDocument(doc) },
                onBack = { navController.popBackStack() }
            )
        }
        composable<AiRoute> {
            AiAssistantScreen(
                repository = mainViewModel.repository,
                onOpenDocument = { doc -> navController.navigateToDocument(doc) },
                onBack = { navController.popBackStack() }
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                viewModel = mainViewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable<ConverterRoute> {
            ConverterScreen(
                repository = mainViewModel.repository,
                onOpenDocument = { doc -> navController.navigateToDocument(doc) },
                onBack = { navController.popBackStack() }
            )
        }
        composable<ScannerRoute> {
            ScannerScreen(
                repository = mainViewModel.repository,
                onOpenDocument = { doc -> navController.navigateToDocument(doc) },
                onBack = { navController.popBackStack() }
            )
        }
        composable<WordEditorRoute> { backStackEntry ->
            val route: WordEditorRoute = backStackEntry.toRoute()
            WordEditorScreen(
                filePath = route.filePath,
                repository = mainViewModel.repository,
                autoSaveEnabled = autoSaveEnabled,
                onBack = { navController.popBackStack() }
            )
        }
        composable<ExcelEditorRoute> { backStackEntry ->
            val route: ExcelEditorRoute = backStackEntry.toRoute()
            ExcelEditorScreen(
                filePath = route.filePath,
                repository = mainViewModel.repository,
                onBack = { navController.popBackStack() }
            )
        }
        composable<PptEditorRoute> { backStackEntry ->
            val route: PptEditorRoute = backStackEntry.toRoute()
            PowerPointEditorScreen(
                filePath = route.filePath,
                repository = mainViewModel.repository,
                onBack = { navController.popBackStack() }
            )
        }
        composable<PdfEditorRoute> { backStackEntry ->
            val route: PdfEditorRoute = backStackEntry.toRoute()
            PdfScreen(
                filePath = route.filePath,
                repository = mainViewModel.repository,
                onBack = { navController.popBackStack() }
            )
        }
        composable<ImageViewerRoute> { backStackEntry ->
            val route: ImageViewerRoute = backStackEntry.toRoute()
            ImageViewerScreen(
                filePath = route.filePath,
                repository = mainViewModel.repository,
                onOpenDocument = { doc -> navController.navigateToDocument(doc) },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
