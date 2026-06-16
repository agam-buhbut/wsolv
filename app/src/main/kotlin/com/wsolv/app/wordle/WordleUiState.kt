package com.wsolv.app.wordle

import com.wsolv.core.feedback.Feedback
import com.wsolv.core.wordle.WordleStatus

/** One past guess and the colors that were recorded for it. */
data class GuessRow(
    val guess: String,
    val colors: List<Int>,
)

/**
 * Immutable view state for the Wordle solver screen.
 *
 * @property suggestion the word the solver currently recommends playing.
 * @property pendingColors the 5 colors the user is entering for [suggestion]
 *   (size 5, each in `0..2`, default all [Feedback.GRAY]).
 * @property history past guesses, in play order (oldest first).
 * @property remainingCount answers still consistent with the history.
 * @property status current solver outcome.
 * @property offTree whether the solver has left the precomputed tree.
 * @property hardMode whether the session is solving in hard mode (revealed
 *   letters must be reused, greens kept in place).
 */
data class WordleUiState(
    val suggestion: String = "",
    val pendingColors: List<Int> = List(Feedback.WORD_LEN) { Feedback.GRAY },
    val history: List<GuessRow> = emptyList(),
    val remainingCount: Int = 0,
    val status: WordleStatus = WordleStatus.PLAYING,
    val offTree: Boolean = false,
    val hardMode: Boolean = false,
)
