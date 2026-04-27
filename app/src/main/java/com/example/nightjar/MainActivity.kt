package com.example.nightjar

import com.example.nightjar.audio.ThemePreferences
import com.example.nightjar.ui.library.LibraryScreen
import com.example.nightjar.ui.overview.OverviewScreen
import com.example.nightjar.ui.settings.SettingsScreen
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
import com.example.nightjar.ui.studio.DrumEditorScreen
import com.example.nightjar.ui.studio.InstrumentPickerKeys
import com.example.nightjar.ui.studio.InstrumentPickerScreen
import com.example.nightjar.ui.studio.PianoRollScreen
import com.example.nightjar.ui.studio.StudioScreen
import com.example.nightjar.ui.record.RecordScreen
import com.example.nightjar.ui.theme.IndigoPalette
import com.example.nightjar.ui.theme.LemonCakePalette
import com.example.nightjar.ui.theme.WarmPlumPalette
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Single-activity host. Sets up the Compose theme and [NightjarApp] navigation graph. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var themePrefs: ThemePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialDark = themePrefs.themeKey != ThemePreferences.LEMON_CAKE
        enableEdgeToEdge(
            statusBarStyle = if (initialDark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = if (initialDark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        setContent {
            var themeKey by remember { mutableStateOf(themePrefs.themeKey) }
            val palette = when (themeKey) {
                ThemePreferences.WARM_PLUM -> WarmPlumPalette
                ThemePreferences.LEMON_CAKE -> LemonCakePalette
                else -> IndigoPalette
            }

            DisposableEffect(palette.isDark) {
                enableEdgeToEdge(
                    statusBarStyle = if (palette.isDark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = if (palette.isDark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                )
                onDispose {}
            }

            com.example.nightjar.ui.theme.NightjarTheme(palette = palette) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NightjarApp(
                        onThemeChanged = { themeKey = it }
                    )
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
    const val PIANO_ROLL = "piano_roll"
    const val DRUM_EDITOR = "drum_editor"
    const val INSTRUMENT_PICKER = "instrument_picker"
    const val SETTINGS = "settings"
}

/** Top-level navigation graph: Record -> Library -> Overview -> Studio. */
@Composable
fun NightjarApp(onThemeChanged: (String) -> Unit) {
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
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
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
            val savedStateHandle = entry.savedStateHandle
            val pendingTrackId by savedStateHandle
                .getStateFlow<Long?>(InstrumentPickerKeys.TRACK_ID, null)
                .collectAsState()
            val pendingProgram by savedStateHandle
                .getStateFlow<Int?>(InstrumentPickerKeys.PROGRAM, null)
                .collectAsState()
            StudioScreen(
                ideaId = ideaId,
                onBack = { navController.popBackStack() },
                onOpenPianoRoll = { trackId, clipId ->
                    navController.navigate("${Routes.PIANO_ROLL}/$trackId/$ideaId/$clipId")
                },
                onOpenDrumEditor = { trackId, clipId ->
                    navController.navigate("${Routes.DRUM_EDITOR}/$trackId/$ideaId/$clipId")
                },
                onOpenInstrumentPicker = { trackId ->
                    navController.navigate("${Routes.INSTRUMENT_PICKER}/$trackId/$ideaId")
                },
                pendingInstrumentSelectionTrackId = pendingTrackId,
                pendingInstrumentSelectionProgram = pendingProgram,
                onPendingInstrumentSelectionConsumed = {
                    savedStateHandle[InstrumentPickerKeys.TRACK_ID] = null
                    savedStateHandle[InstrumentPickerKeys.PROGRAM] = null
                }
            )
        }
        composable(
            route = "${Routes.PIANO_ROLL}/{trackId}/{ideaId}/{clipId}",
            arguments = listOf(
                navArgument("trackId") { type = NavType.LongType },
                navArgument("ideaId") { type = NavType.LongType },
                navArgument("clipId") { type = NavType.LongType }
            )
        ) {
            PianoRollScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = "${Routes.DRUM_EDITOR}/{trackId}/{ideaId}/{clipId}",
            arguments = listOf(
                navArgument("trackId") { type = NavType.LongType },
                navArgument("ideaId") { type = NavType.LongType },
                navArgument("clipId") { type = NavType.LongType }
            )
        ) {
            DrumEditorScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = "${Routes.INSTRUMENT_PICKER}/{trackId}/{ideaId}",
            arguments = listOf(
                navArgument("trackId") { type = NavType.LongType },
                navArgument("ideaId") { type = NavType.LongType }
            )
        ) {
            InstrumentPickerScreen(
                onBack = { navController.popBackStack() },
                onProgramSelected = { trackId, program ->
                    val parent = navController.previousBackStackEntry?.savedStateHandle
                    parent?.set(InstrumentPickerKeys.TRACK_ID, trackId)
                    parent?.set(InstrumentPickerKeys.PROGRAM, program)
                }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onThemeChanged = onThemeChanged
            )
        }
    }
}
