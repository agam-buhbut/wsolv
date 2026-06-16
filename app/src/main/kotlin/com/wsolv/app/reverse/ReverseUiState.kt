package com.wsolv.app.reverse

import com.wsolv.core.feedback.Feedback
import com.wsolv.core.wordle.LosingStatus

/** One past guess in the reverse game and the colors recorded for it. */
data class ReverseGuessRow(
    val guess: String,
    val colors: List<Int>,
)

/**
 * Immutable view state for the reverse ("losing") Wordle screen.
 *
 * @property suggestion the word the solver currently recommends playing to
 *   AVOID winning (`""` once the game is over).
 * @property pendingColors the 5 colors the user is entering for [suggestion]
 *   (size 5, each in `0..2`, default all [Feedback.GRAY]).
 * @property history past guesses, in play order (oldest first).
 * @property guessCount guesses played so far.
 * @property remainingCount answers still consistent with the history.
 * @property status current solver outcome.
 * @property thinking whether the solver is computing the next suggestion.
 * @property rejectedCount how many words are on the persistent reject list.
 */
data class ReverseUiState(
    val suggestion: String = "",
    val pendingColors: List<Int> = List(Feedback.WORD_LEN) { Feedback.GRAY },
    val history: List<ReverseGuessRow> = emptyList(),
    val guessCount: Int = 0,
    val remainingCount: Int = 0,
    val status: LosingStatus = LosingStatus.PLAYING,
    val thinking: Boolean = false,
    val rejectedCount: Int = 0,
)
