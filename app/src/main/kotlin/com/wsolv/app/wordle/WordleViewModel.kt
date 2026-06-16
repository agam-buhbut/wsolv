package com.wsolv.app.wordle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wsolv.core.feedback.Feedback
import com.wsolv.core.wordle.GuessFeedback
import com.wsolv.core.wordle.WordleData
import com.wsolv.core.wordle.WordleSession
import com.wsolv.core.wordle.WordleSolver
import com.wsolv.core.wordle.WordleStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the live [WordleSession] and exposes it to the UI as an immutable
 * [WordleUiState]. All solver work delegates to [WordleSolver]; the off-tree
 * branch can be expensive, so [submit] runs it on [Dispatchers.Default].
 */
class WordleViewModel(data: WordleData) : ViewModel() {
    private val solver = WordleSolver(data.allowed, data.answers, data.tree)

    private var hardMode: Boolean = false

    private var session: WordleSession = solver.initial(hardMode)

    private val _uiState =
        MutableStateFlow(stateFor(session, List(Feedback.WORD_LEN) { Feedback.GRAY }))
    val uiState: StateFlow<WordleUiState> = _uiState.asStateFlow()

    /** Set the [color] (`0..2`) at position [pos] (`0..4`) for the pending guess. */
    fun setColor(pos: Int, color: Int) {
        if (pos !in 0 until Feedback.WORD_LEN || color !in Feedback.GRAY..Feedback.GREEN) return
        val updated = _uiState.value.pendingColors.toMutableList().apply { this[pos] = color }
        _uiState.value = _uiState.value.copy(pendingColors = updated)
    }

    /**
     * Record the pending colors for the current suggestion and advance the
     * session. No-op once the game is not [com.wsolv.core.wordle.WordleStatus.PLAYING].
     */
    fun submit() {
        if (session.status != WordleStatus.PLAYING) return
        val colors = _uiState.value.pendingColors.toIntArray()
        viewModelScope.launch {
            val next = withContext(Dispatchers.Default) { solver.submit(session, colors) }
            session = next
            _uiState.value = stateFor(next, freshColors())
        }
    }

    /**
     * Additive V2 entry point: advance the session with externally supplied
     * [colors] (e.g. read off the screen by OCR) instead of the manual pickers.
     * Same semantics as [submit], but the colors come from the caller.
     *
     * @param colors `IntArray(5)`, each entry in `0..2`.
     */
    fun submit(colors: IntArray) {
        if (session.status != WordleStatus.PLAYING) return
        if (colors.size != Feedback.WORD_LEN ||
            colors.any { it !in Feedback.GRAY..Feedback.GREEN }
        ) {
            return
        }
        val snapshot = colors.copyOf()
        viewModelScope.launch {
            val next = withContext(Dispatchers.Default) { solver.submit(session, snapshot) }
            session = next
            _uiState.value = stateFor(next, freshColors())
        }
    }

    /** The word the solver currently recommends; `""` once the game is over. */
    fun currentSuggestion(): String = session.suggestion

    /**
     * Switch hard mode [on] (or off) and start a fresh session in that mode.
     * Clears history and pending colors.
     */
    fun setHardMode(on: Boolean) {
        hardMode = on
        session = solver.initial(on)
        _uiState.value = stateFor(session, freshColors())
    }

    /** Discard all progress and start a fresh session, preserving hard mode. */
    fun reset() {
        session = solver.initial(hardMode)
        _uiState.value = stateFor(session, freshColors())
    }

    private fun freshColors(): List<Int> = List(Feedback.WORD_LEN) { Feedback.GRAY }

    private fun stateFor(session: WordleSession, pendingColors: List<Int>): WordleUiState =
        WordleUiState(
            suggestion = session.suggestion,
            pendingColors = pendingColors,
            history = session.history.map { it.toRow() },
            remainingCount = session.remainingCount,
            status = session.status,
            offTree = session.offTree,
            hardMode = session.hardMode,
        )

    private fun GuessFeedback.toRow(): GuessRow = GuessRow(guess = guess, colors = colors.toList())
}
