package com.example.nightjar.ui.record

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.example.nightjar.audio.CaptureState
import com.example.nightjar.data.db.entity.CaptureGroupEntity
import com.example.nightjar.data.db.entity.TakeEntity
import com.example.nightjar.ui.theme.NightjarTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CaptureWorkspaceLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun show(state: RecordUiState = RecordUiState(), height: Dp = 560.dp,
        onAction: (RecordAction) -> Unit = {}) {
        compose.setContent {
            NightjarTheme {
                CaptureWritingWorkspace(state, {}, onAction, {}, {}, Modifier.requiredSize(320.dp, height))
            }
        }
    }

    @Test fun optionsAndGroupsKeepTransportReachable() {
        show()
        compose.onNodeWithText("Options").performClick()
        compose.onNode(hasText("New Idea") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("RECORD").assertIsDisplayed()
        compose.onNodeWithText("STOP").assertIsDisplayed()
        compose.onNodeWithText("CLOSE").performClick()
        compose.onNodeWithContentDescription("Change recording destination").performClick()
        compose.onNodeWithText("New Group").assertIsDisplayed()
        compose.onNodeWithText("RECORD").assertIsDisplayed()
        compose.onNodeWithText("STOP").assertIsDisplayed()
    }

    @Test fun populatedIdeaKeepsDestinationIndependentFromPlayback() {
        val backing = TakeEntity(id = 7, clipId = 1, audioFileName = "unused.wav",
            displayName = "Take 3", sortIndex = 2, durationMs = 12000)
        show(RecordUiState(capture = CaptureState(
            ideaId = 1, openGroupId = 2,
            groups = listOf(
                CaptureGroupEntity(id = 1, ideaId = 1, clipId = 1, displayName = "Guitar", sortIndex = 0, latchedTakeId = 7),
                CaptureGroupEntity(id = 2, ideaId = 1, clipId = 2, displayName = "Vocals", sortIndex = 1)),
            latchedTakes = mapOf(7L to backing))))
        compose.onNodeWithText("Vocals").assertIsDisplayed()
        compose.onNodeWithText("Guitar · Take 3").assertIsDisplayed()
        compose.onNodeWithText("SELECTED FOR PLAY").assertIsDisplayed()
        compose.onNodeWithText("PLAY").assertIsDisplayed()
    }

    @Test fun compactWritingLeavesEditorAndStopVisible() {
        show(RecordUiState(isWriting = true), height = 420.dp)
        val editor = compose.onNodeWithContentDescription("Idea words").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val stop = compose.onNodeWithText("STOP").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(editor.height > 0f)
        assertTrue(editor.bottom <= stop.top)
    }

    @Test fun freshIdeaHasSquareTransportAndNoEmptyListMessages() {
        show()
        compose.onNodeWithText("Room for an idea").assertDoesNotExist()
        compose.onNodeWithText("TAKES / 0").assertDoesNotExist()
        compose.onNodeWithContentDescription("Live recording display").assertIsDisplayed()
        for (tag in listOf("capture-play-face", "capture-stop-face")) {
            val face = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(face.width, face.height, 0.5f)
        }
    }

    @Test fun renameAnotherDestinationDoesNotOpenIt() {
        val actions = mutableListOf<RecordAction>()
        show(RecordUiState(capture = CaptureState(ideaId = 1, openGroupId = 1, groups = listOf(
            CaptureGroupEntity(id = 1, ideaId = 1, clipId = 1, displayName = "Guitar", sortIndex = 0),
            CaptureGroupEntity(id = 2, ideaId = 1, clipId = 2, displayName = "Melody", sortIndex = 1)
        ))), onAction = { actions.add(it) })
        compose.onNodeWithContentDescription("Change recording destination").performClick()
        // Opening the panel only dismisses the tempo panel; it must not open a destination.
        compose.runOnIdle { actions.clear() }
        compose.onNodeWithContentDescription("Rename Melody").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("Chorus melody")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(RecordAction.RenameGroup(2, "Chorus melody")), actions)
        }
        compose.onNodeWithText("Record into").assertIsDisplayed()
    }

    @Test fun positiveShortTakeDoesNotLookEmpty() {
        val short = TakeEntity(id = 1, clipId = 1, audioFileName = "short.wav",
            displayName = "Take 1", sortIndex = 0, durationMs = 500)
        show(RecordUiState(capture = CaptureState(takes = listOf(short))))
        compose.onNodeWithText("TAKE 1  <1s").assertIsDisplayed()
    }
}
