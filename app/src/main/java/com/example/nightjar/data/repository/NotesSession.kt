package com.example.nightjar.data.repository

import android.util.Log
import com.example.nightjar.data.storage.NotesDraft
import com.example.nightjar.data.storage.NotesDraftStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotesState(
    val ideaId: Long? = null,
    val text: String = "",
    val ready: Boolean = false,
    val pending: Boolean = false,
    val error: String? = null
) {
    val safeToLeaveIdea: Boolean get() = ready && !pending && error == null
}

/** One writer per Idea, independent of screen lifetimes. All commands are confined to Main. */
@Singleton
class NotesSession @Inject constructor(
    private val repo: IdeaRepository,
    private val storage: NotesDraftStorage
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val documents = mutableMapOf<Long, Document>()

    fun open(id: Long): Document = documents.getOrPut(id) { Document(id, null) }.also { it.load() }
    fun create(createIdea: suspend () -> Long): Document = Document(null, createIdea)

    inner class Document internal constructor(id: Long?, private val createIdea: (suspend () -> Long)?) {
        private val mutableState = MutableStateFlow(NotesState(ideaId = id, ready = id == null))
        val state = mutableState.asStateFlow()
        private var base = ""
        private var revision = 0L
        private var savedRevision = 0L
        private var job: Job? = null

        /** Audio can establish the Idea after an empty writing surface was opened. */
        fun attachIdea(id: Long) {
            check(state.value.ideaId == null || state.value.ideaId == id)
            documents[id] = this
            mutableState.value = state.value.copy(ideaId = id)
        }

        internal fun load() {
            if (state.value.ready || job?.isActive == true) return
            job = scope.launch {
                try {
                    val id = requireNotNull(state.value.ideaId)
                    val idea = requireNotNull(repo.getIdeaById(id)) { "This Idea no longer exists." }
                    val recovery = storage.read(id)
                    base = recovery?.base ?: idea.notes
                    val text = recovery?.text ?: idea.notes
                    mutableState.value = state.value.copy(text = text, ready = true, pending = recovery != null, error = null)
                    if (recovery != null) {
                        revision++
                        saveLoop()
                    }
                } catch (e: Exception) { fail(e) }
            }
        }

        fun edit(text: String) {
            if (!state.value.ready || text == state.value.text) return
            revision++
            mutableState.value = state.value.copy(text = text, pending = true, error = null)
            requestSave()
        }

        fun retry() {
            if (!state.value.ready) { load(); return }
            if (state.value.pending || state.value.error != null) requestSave()
        }

        private fun requestSave() {
            if (job?.isActive == true) return
            job = scope.launch(start = CoroutineStart.LAZY) { saveLoop() }.also { it.start() }
        }

        suspend fun flush(): Boolean {
            retry()
            job?.join()
            return state.value.safeToLeaveIdea
        }

        private suspend fun saveLoop() {
            try {
                while (savedRevision < revision) {
                    val snapshotRevision = revision
                    val text = state.value.text
                    val id = state.value.ideaId ?: requireNotNull(createIdea).invoke().also { created ->
                        attachIdea(created)
                    }
                    storage.write(id, NotesDraft(base, text))
                    repo.saveNotesRevision(id, base, text)
                    base = text
                    storage.clear(id)
                    savedRevision = snapshotRevision
                    mutableState.value = state.value.copy(pending = savedRevision < revision, error = null)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e) }
        }

        private fun fail(e: Exception) {
            Log.e("NotesSession", "Writing save or recovery failed", e)
            mutableState.value = state.value.copy(error = e.message ?: "Could not save your words. Please retry.")
        }
    }
}
