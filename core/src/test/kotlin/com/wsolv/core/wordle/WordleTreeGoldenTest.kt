package com.wsolv.core.wordle

import com.wsolv.core.feedback.Feedback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Replays the real bundled tree against every answer. The assets are synced in
 * a later pipeline step, so when they are absent this test skips cleanly
 * (only `feedback_vectors.json` ships today).
 */
class WordleTreeGoldenTest {
    private fun resource(name: String): ByteArray? =
        javaClass.getResourceAsStream(name)?.use { it.readBytes() }

    @Test
    fun `every answer is solved and metrics match the manifest`() {
        val treeBytes = resource("/wordle_tree.bin")
        val answersBytes = resource("/wordle_answers.txt")
        val allowedBytes = resource("/wordle_allowed.txt")
        val metaBytes = resource("/tree_meta.json")
        assumeTrue(
            treeBytes != null && answersBytes != null && allowedBytes != null && metaBytes != null,
            "real wordle assets not present yet; skipping golden replay",
        )

        val data = WordleLoader.load(allowedBytes!!, answersBytes!!, treeBytes!!, metaBytes!!)
        val tree = data.tree
        val meta = data.meta

        // The manifest's tree hash must equal the hash of the bytes we loaded.
        assertEquals(meta.treeSha256, WordleLoader.sha256Hex(treeBytes), "tree sha256 mismatch")
        assertEquals(meta.answerCount, data.answers.size, "answer count mismatch")
        assertEquals(meta.allowedCount, data.allowed.size, "allowed count mismatch")
        assertEquals(meta.nodeCount, tree.nodeCount, "node count mismatch")
        assertEquals(meta.firstWord, data.allowed[tree.guessIndexAt(0)], "root guess mismatch")

        var maxDepth = 0
        var totalGuesses = 0L
        for (answer in data.answers) {
            var node = 0
            var depth = 0
            while (true) {
                depth++
                check(depth <= Feedback.WORD_LEN * 5) { "runaway path for $answer" }
                val guess = data.allowed[tree.guessIndexAt(node)]
                val code = Feedback.code(guess, answer)
                if (code == Feedback.SOLVED) break
                val child = tree.child(node, code)
                assertTrue(child >= 0, "answer $answer fell off the tree at depth $depth")
                node = child
            }
            totalGuesses += depth
            if (depth > maxDepth) maxDepth = depth
        }

        assertEquals(meta.maxDepth, maxDepth, "recomputed max depth mismatch")
        assertEquals(meta.totalGuesses, totalGuesses, "recomputed total guesses mismatch")
        val mean = totalGuesses.toDouble() / data.answers.size
        assertTrue(abs(mean - meta.mean) < 1e-9, "recomputed mean $mean vs ${meta.mean}")
    }
}
