package com.wsolv.app.reverse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wsolv.app.persist.RejectStore
import com.wsolv.core.feedback.Feedback
import com.wsolv.core.wordle.GuessFeedback
import com.wsolv.core.wordle.LosingSession
import com.wsolv.core.wordle.LosingStatus
import com.wsolv.core.wordle.LosingWordleSolver
import com.wsolv.core.wordle.WordleData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the live [LosingSession] and exposes it to the UI as an immutable
 * [ReverseUiState]. The guess search in [LosingWordleSolver] is O(pool *
 * candidates), so [submit] and the initial pick run on [Dispatchers.Default]
 * behind a brief "thinking" state.
 */
class ReverseViewModel(
    data: WordleData,
    private val store: RejectStore,
) : ViewModel() {
    private val solver = LosingWordleSolver(data.allowed, data.answers)

    // Null until the (heavy) opening guess is computed off the main thread. The
    // session carries its own blocklist of rejected words, seeded from [store]
    // so rejections persist across resets, navigation, and app restarts.
    private var session: LosingSession? = null

    private val _uiState = MutableStateFlow(ReverseUiState(thinking = true))
    val uiState: StateFlow<ReverseUiState> = _uiState.asStateFlow()

    init {
        newGame()
    }

    /** Compute a fresh opening session off the main thread, showing a spinner. */
    private fun newGame() {
        session = null
        _uiState.value = ReverseUiState(thinking = true)
        viewModelScope.launch {
            val blocked = store.load()
            val fresh = withContext(Dispatchers.Default) { solver.initial(blocked) }
            session = fresh
            _uiState.value = stateFor(fresh, freshColors(), thinking = false)
        }
    }

    /** Set the [color] (`0..2`) at position [pos] (`0..4`) for the pending guess. */
    fun setColor(pos: Int, color: Int) {
        if (pos !in 0 until Feedback.WORD_LEN || color !in Feedback.GRAY..Feedback.GREEN) return
        val updated = _uiState.value.pendingColors.toMutableList().apply { this[pos] = color }
        _uiState.value = _uiState.value.copy(pendingColors = updated)
    }

    /**
     * Record the pending colors for the current suggestion and advance the
     * session. No-op once the game is over or while a search is running.
     */
    fun submit() {
        val current = session ?: return
        if (current.status != LosingStatus.PLAYING || _uiState.value.thinking) return
        val colors = _uiState.value.pendingColors.toIntArray()
        _uiState.value = _uiState.value.copy(thinking = true)
        viewModelScope.launch {
            val next = withContext(Dispatchers.Default) { solver.submit(current, colors) }
            session = next
            _uiState.value = stateFor(next, freshColors(), thinking = false)
        }
    }

    /**
     * Drop the current suggestion (e.g. the real game rejected it) and re-pick a
     * different losing word for the same turn. No feedback is recorded.
     */
    fun skip() {
        val current = session ?: return
        if (current.status != LosingStatus.PLAYING || _uiState.value.thinking) return
        _uiState.value = _uiState.value.copy(thinking = true)
        viewModelScope.launch {
            val next = withContext(Dispatchers.Default) { solver.skip(current) }
            session = next
            store.save(next.blocklist) // persist the rejection
            _uiState.value = stateFor(next, freshColors(), thinking = false)
        }
    }

    /** Start a fresh game, keeping the persisted list of rejected words. */
    fun reset() {
        newGame()
    }

    /** Forget every rejected word, then start a fresh game. */
    fun clearRejected() {
        store.clear()
        newGame()
    }

    private fun freshColors(): List<Int> = List(Feedback.WORD_LEN) { Feedback.GRAY }

    private fun stateFor(
        session: LosingSession,
        pendingColors: List<Int>,
        thinking: Boolean,
    ): ReverseUiState =
        ReverseUiState(
            suggestion = session.suggestion,
            pendingColors = pendingColors,
            history = session.history.map { it.toRow() },
            guessCount = session.guessCount,
            remainingCount = session.remainingCount,
            status = session.status,
            thinking = thinking,
            rejectedCount = session.blocklist.size,
        )

    private fun GuessFeedback.toRow(): ReverseGuessRow =
        ReverseGuessRow(guess = guess, colors = colors.toList())
}
