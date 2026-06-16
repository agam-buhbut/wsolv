package com.wsolv.app.poople

/**
 * Immutable view state for the Poople solver screen.
 *
 * @property input the current (normalized) start word being typed.
 * @property path the words of the last successful ladder, in play order
 *   (index 0 is the start word). Empty before any successful solve.
 * @property steps the step count for [path] (edges, i.e. `path.size - 1`).
 * @property excluded words the user has rejected; persisted across rerolls so
 *   each reroute avoids every previously rejected word.
 * @property solving whether a solve or reroute is in progress.
 * @property message a friendly message for non-path outcomes (bad input, no
 *   ladder, reroute failure), or `null` when the path is the current display.
 */
data class PoopleUiState(
    val input: String = "",
    val path: List<String> = emptyList(),
    val steps: Int = 0,
    val excluded: Set<String> = emptySet(),
    val solving: Boolean = false,
    val message: String? = null,
)
