package com.example.nightjar

import com.example.nightjar.ui.library.LibraryScreen
import com.example.nightjar.ui.overview.OverviewScreen
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.nightjar.ui.studio.StudioScreen
import com.example.nightjar.ui.record.RecordScreen
import dagger.hilt.android.AndroidEntryPoint

/** Single-activity host. Sets up the Compose theme and [NightjarApp] navigation graph. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            com.example.nightjar.ui.theme.NightjarTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NightjarApp()
                }
            }
        }

    }
}

/** Navigation route constants. */
private object Routes {
    const val RECORD = "record"
    const val LIBRARY = "library"
    const val OVERVIEW = "overview"
    const val STUDIO = "studio"
}

/** Top-level navigation graph: Record → Library → Overview → Studio. */
@Composable
fun NightjarApp() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.RECORD) {
        composable(Routes.RECORD) {
            RecordScreen(
                onOpenLibrary = { navController.navigate(Routes.LIBRARY) },
                onOpenOverview = { ideaId ->
                    navController.navigate("${Routes.OVERVIEW}/$ideaId")
                },
                onOpenStudio = { ideaId ->
                    navController.navigate("${Routes.STUDIO}/$ideaId")
                }
            )
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onBack = { navController.popBackStack() },
                onOpenOverview = { ideaId ->
                    navController.navigate("${Routes.OVERVIEW}/$ideaId")
                }
            )
        }
        composable(
            route = "${Routes.OVERVIEW}/{ideaId}",
            arguments = listOf(navArgument("ideaId") { type = NavType.LongType })
        ) { entry ->
            val ideaId = entry.arguments?.getLong("ideaId") ?: -1L
            OverviewScreen(
                ideaId = ideaId,
                onBack = { navController.popBackStack() },
                onOpenStudio = { id ->
                    navController.navigate("${Routes.STUDIO}/$id")
                }
            )
        }
        composable(
            route = "${Routes.STUDIO}/{ideaId}",
            arguments = listOf(navArgument("ideaId") { type = NavType.LongType })
        ) { entry ->
            val ideaId = entry.arguments?.getLong("ideaId") ?: -1L
            StudioScreen(
                ideaId = ideaId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
