package com.wsolv.app.assets

import android.content.Context
import com.wsolv.core.common.MalformedTreeException
import com.wsolv.core.poople.PoopleSolver
import com.wsolv.core.wordle.WordleData
import com.wsolv.core.wordle.WordleLoader
import java.io.IOException

/** Outcome of loading the bundled solver assets. */
sealed interface AppLoadState {
    /** Assets are still being read and verified. */
    data object Loading : AppLoadState

    /** Everything loaded and verified; the solvers are ready to use. */
    data class Ready(val wordle: WordleData, val poople: PoopleSolver) : AppLoadState

    /** Loading failed; [message] is a user-readable explanation. */
    data class Error(val message: String) : AppLoadState
}

/**
 * Reads and verifies the bundled Wordle and Poople assets from `assets/`.
 *
 * The work is blocking I/O plus SHA-256 verification and must be called off the
 * main thread (e.g. on [kotlinx.coroutines.Dispatchers.IO]).
 */
object AssetLoader {
    private const val WORDLE_ALLOWED = "wordle_allowed.txt"
    private const val WORDLE_ANSWERS = "wordle_answers.txt"
    private const val WORDLE_TREE = "wordle_tree.bin"
    private const val TREE_META = "tree_meta.json"
    private const val POOPLE_WORDS = "poople_words.txt"

    /**
     * Load all assets and assemble the solver inputs.
     *
     * Never throws: any [MalformedTreeException], [IOException], or malformed
     * bundle ([IllegalArgumentException], e.g. a Poople target missing from its
     * dictionary) is reported as [AppLoadState.Error].
     */
    fun load(context: Context): AppLoadState {
        val assets = context.assets
        return try {
            val allowedBytes = assets.open(WORDLE_ALLOWED).use { it.readBytes() }
            val answersBytes = assets.open(WORDLE_ANSWERS).use { it.readBytes() }
            val treeBytes = assets.open(WORDLE_TREE).use { it.readBytes() }
            val metaBytes = assets.open(TREE_META).use { it.readBytes() }
            val poopleBytes = assets.open(POOPLE_WORDS).use { it.readBytes() }

            val wordle = WordleLoader.load(allowedBytes, answersBytes, treeBytes, metaBytes)
            val poople = PoopleSolver(WordleLoader.parseWordList(poopleBytes))

            AppLoadState.Ready(wordle = wordle, poople = poople)
        } catch (e: MalformedTreeException) {
            AppLoadState.Error("Bundled puzzle data is corrupt or out of date: ${e.message}")
        } catch (e: IOException) {
            AppLoadState.Error("Could not read bundled puzzle data: ${e.message}")
        } catch (e: IllegalArgumentException) {
            AppLoadState.Error("Bundled puzzle data is invalid: ${e.message}")
        }
    }
}
