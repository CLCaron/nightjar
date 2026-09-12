package com.example.nightjar.ui.record

import androidx.lifecycle.ViewModelStore
import com.example.nightjar.audio.CapturePhase
import com.example.nightjar.audio.CaptureSession
import com.example.nightjar.audio.CaptureState
import com.example.nightjar.audio.MetronomePreferences
import com.example.nightjar.audio.OboeAudioEngine
import com.example.nightjar.data.repository.IdeaRepository
import com.example.nightjar.util.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val main = MainDispatcherRule(dispatcher)
    private val engine = mockk<OboeAudioEngine>(relaxed = true)
    private val repo = mockk<IdeaRepository>(relaxed = true)
    private val prefs = mockk<MetronomePreferences>(relaxed = true)
    private val session = mockk<CaptureSession>(relaxed = true)
    private val captureState = MutableStateFlow(CaptureState())

    private fun viewModel(): RecordViewModel {
        every { session.state } returns captureState
        return RecordViewModel(engine, repo, prefs, session)
    }

    @Test fun `clearing screen does not stop the app owned recording`() = runTest(dispatcher) {
        captureState.value = CaptureState(phase = CapturePhase.RECORDING)
        val store = ViewModelStore()
        val first = viewModel()
        store.put("record", first)
        runCurrent()
        assertTrue(first.state.value.isRecording)
        store.clear()
        verify(exactly = 0) { session.stop(any()) }
        verify(exactly = 0) { engine.stopRecording() }
        val restored = viewModel()
        store.put("record", restored)
        runCurrent()
        assertTrue(restored.state.value.isRecording)
        store.clear()
    }

    @Test fun `returning after notification stop shows the saved idea`() = runTest(dispatcher) {
        val file = File("saved.wav")
        captureState.value = CaptureState(phase = CapturePhase.SAVED, ideaId = 42L, file = file)
        val store = ViewModelStore()
        val vm = viewModel()
        store.put("record", vm)
        runCurrent()
        assertFalse(vm.state.value.isRecording)
        assertEquals(42L, vm.state.value.postRecording?.ideaId)
        assertEquals(file, vm.state.value.postRecording?.audioFile)
        store.clear()
    }

    @Test fun `saving status is explicit even without waveform samples`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val vm = viewModel()
        store.put("record", vm)
        captureState.value = CaptureState(phase = CapturePhase.SAVING)
        runCurrent()
        assertTrue(vm.state.value.isSaving)
        assertFalse(vm.state.value.isRecording)
        assertNull(vm.state.value.postRecording)
        store.clear()
    }
    @Test fun `opening Write while listening does not stop or restart audio`() = runTest(dispatcher) {
        captureState.value = CaptureState(phase = CapturePhase.SAVED, ideaId = 42L, playing = true)
        val document = mockk<com.example.nightjar.data.repository.NotesSession.Document>(relaxed = true)
        every { document.state } returns MutableStateFlow(com.example.nightjar.data.repository.NotesState(ideaId = 42L, text = "lyrics", ready = true))
        every { session.writingDocument() } returns document
        val store = ViewModelStore()
        val vm = viewModel(); store.put("record", vm); runCurrent()
        vm.onAction(RecordAction.CreateWriteIdea); runCurrent()
        assertTrue(vm.state.value.isWriting)
        assertEquals("lyrics", vm.state.value.words.text)
        assertTrue(vm.state.value.capture.playing)
        verify(exactly = 0) { session.stopAudition() }
        verify(exactly = 0) { engine.pause() }
        vm.onAction(RecordAction.WordsChanged("next line"))
        verify { document.edit("next line") }
        store.clear()
    }

    @Test fun `record and Write dismiss tempo drawer without changing settings`() = runTest(dispatcher) {
        every { prefs.isEnabled } returns true
        every { prefs.volume } returns 0.45f
        every { prefs.countInBars } returns 2
        val document = mockk<com.example.nightjar.data.repository.NotesSession.Document>(relaxed = true)
        every { document.state } returns MutableStateFlow(
            com.example.nightjar.data.repository.NotesState(ready = true)
        )
        every { session.writingDocument() } returns document
        val store = ViewModelStore()
        val vm = viewModel(); store.put("record", vm); runCurrent()

        vm.onAction(RecordAction.ToggleTempoDrawer)
        assertTrue(vm.state.value.isTempoDrawerOpen)
        vm.onAction(RecordAction.CreateWriteIdea)
        assertFalse(vm.state.value.isTempoDrawerOpen)
        assertTrue(vm.state.value.isMetronomeEnabled)
        assertEquals(0.45f, vm.state.value.metronomeVolume)
        assertEquals(2, vm.state.value.countInBars)

        vm.onAction(RecordAction.ShowSound)
        vm.onAction(RecordAction.ToggleTempoDrawer)
        vm.onAction(RecordAction.StartRecording)
        assertFalse(vm.state.value.isTempoDrawerOpen)
        verify { session.start(match { it.metronome && it.volume == 0.45f && it.countInBars == 2 }) }
        store.clear()
    }

    @Test fun `tempo drawer has an explicit dismiss action`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val vm = viewModel(); store.put("record", vm); runCurrent()
        vm.onAction(RecordAction.ToggleTempoDrawer)
        vm.onAction(RecordAction.DismissTempoDrawer)
        assertFalse(vm.state.value.isTempoDrawerOpen)
        store.clear()
    }

}
