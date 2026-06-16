package com.wsolv.app.poople

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wsolv.app.persist.RejectStore
import com.wsolv.core.poople.PoopleResult
import com.wsolv.core.poople.PoopleSolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val WORD_LEN = 4

/**
 * Exposes the Poople word-ladder solver as an immutable [PoopleUiState].
 * The BFS in [PoopleSolver.solve] runs on [Dispatchers.Default].
 */
class PoopleViewModel(
    private val solver: PoopleSolver,
    private val store: RejectStore,
) : ViewModel() {
    // Seed exclusions from disk so rejected words persist across navigation and
    // app restarts.
    private val _uiState = MutableStateFlow(PoopleUiState(excluded = store.load()))
    val uiState: StateFlow<PoopleUiState> = _uiState.asStateFlow()

    /** Normalize raw [s] to lowercase letters, capped at the 4-letter word length. */
    fun onInput(s: String) {
        val cleaned = s.filter { it.isLetter() }.lowercase().take(WORD_LEN)
        _uiState.value = _uiState.value.copy(input = cleaned)
    }

    /**
     * Solve the ladder from the current input, avoiding every word rejected so
     * far. Exclusions persist across solves (use [clearRejected] to reset them),
     * so a word the game rejected is never suggested again. No-op while solving.
     */
    fun solve() {
        if (_uiState.value.solving) return
        val word = _uiState.value.input
        val excluded = _uiState.value.excluded
        _uiState.value = _uiState.value.copy(solving = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { solver.solve(word, excluded) }
            _uiState.value =
                when (result) {
                    is PoopleResult.Path ->
                        _uiState.value.copy(
                            path = result.words,
                            steps = result.steps,
                            solving = false,
                            message = null,
                        )
                    else ->
                        _uiState.value.copy(
                            path = emptyList(),
                            steps = 0,
                            solving = false,
                            message = result.toMessage(),
                        )
                }
        }
    }

    /**
     * Forget every rejected word and re-solve the current input from scratch.
     * No-op while a solve is running.
     */
    fun clearRejected() {
        if (_uiState.value.solving) return
        store.clear()
        _uiState.value = _uiState.value.copy(excluded = emptySet())
        if (_uiState.value.input.isNotEmpty()) solve()
    }

    /**
     * Reject the word at [index] in the current path and reroute around it.
     *
     * Index 0 is the given start word and is never rejected. The rejected word
     * is added to the persistent exclusion set, then the solver reroutes from
     * the previous valid word ([path][index - 1]); on success the already-played
     * prefix is kept and only the suffix is replaced. On failure the prior path
     * stays visible and a message is shown. No-op while solving.
     */
    fun rejectWord(index: Int) {
        val current = _uiState.value
        if (current.solving) return
        val path = current.path
        if (index < 1 || index > path.lastIndex) return

        val from = path[index - 1]
        val newExcluded = current.excluded + path[index]
        store.save(newExcluded) // persist the rejection
        _uiState.value = current.copy(excluded = newExcluded, solving = true)
        viewModelScope.launch {
            val sub = withContext(Dispatchers.Default) { solver.solve(from, newExcluded) }
            _uiState.value =
                when (sub) {
                    is PoopleResult.Path -> {
                        // sub.words[0] == from == path[index - 1]; splice the new
                        // suffix onto the already-played prefix path[0 until index-1].
                        val newPath = path.subList(0, index - 1) + sub.words
                        _uiState.value.copy(
                            path = newPath,
                            steps = newPath.size - 1,
                            solving = false,
                            message = null,
                        )
                    }
                    is PoopleResult.NoPath ->
                        _uiState.value.copy(
                            solving = false,
                            message = "No valid ladder avoiding the rejected word(s).",
                        )
                    else ->
                        _uiState.value.copy(
                            solving = false,
                            message = sub.toMessage(),
                        )
                }
        }
    }

    private fun PoopleResult.toMessage(): String =
        when (this) {
            is PoopleResult.Path -> "" // unreachable: callers handle Path separately
            is PoopleResult.InvalidLength ->
                "Enter a 4-letter word (got $actual letters)."
            is PoopleResult.NotInDictionary ->
                "\"$word\" is not in the dictionary."
            is PoopleResult.NoPath ->
                "No ladder connects \"$word\" to \"poop\"."
        }
}
