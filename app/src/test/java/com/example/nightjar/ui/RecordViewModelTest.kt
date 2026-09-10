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
}
