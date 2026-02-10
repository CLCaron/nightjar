package com.example.songseed

import LibraryScreen
import WorkspaceScreen
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.songseed.ui.record.RecordScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            com.example.songseed.ui.theme.SongSeedTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SongSeedApp()
                }
            }
        }

    }
}

private object Routes {
    const val RECORD = "record"
    const val LIBRARY = "library"
    const val WORKSPACE = "workspace"
}

@Composable
fun SongSeedApp() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.RECORD) {
        composable(Routes.RECORD) {
            RecordScreen(
                onOpenLibrary = { navController.navigate(Routes.LIBRARY) },
                onOpenWorkspace = { ideaId ->
                    navController.navigate("${Routes.WORKSPACE}/$ideaId")
                }
            )
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onBack = { navController.popBackStack() },
                onOpenWorkspace = { ideaId ->
                    navController.navigate("${Routes.WORKSPACE}/$ideaId")
                }
            )
        }
        composable(
            route = "${Routes.WORKSPACE}/{ideaId}",
            arguments = listOf(navArgument("ideaId") { type = NavType.LongType })
        ) { entry ->
            val ideaId = entry.arguments?.getLong("ideaId") ?: -1L
            WorkspaceScreen(
                ideaId = ideaId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
