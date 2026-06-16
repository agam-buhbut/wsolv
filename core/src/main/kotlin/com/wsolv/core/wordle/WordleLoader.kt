package com.wsolv.core.wordle

import com.wsolv.core.common.MalformedTreeException
import java.security.MessageDigest

/**
 * The fully loaded, verified Wordle data set: word lists plus the decision tree
 * and its manifest.
 *
 * @property allowed every word the game accepts as a guess.
 * @property answers the possible secret answers.
 * @property tree the precomputed optimal-play decision tree.
 * @property meta the manifest the tree and lists were verified against.
 */
class WordleData(
    val allowed: List<String>,
    val answers: List<String>,
    val tree: DecisionTree,
    val meta: TreeMeta,
)

/**
 * Loads and integrity-checks the bundled Wordle assets.
 *
 * The decision tree references words by their index in [WordleData.allowed], so
 * the lists must be exactly the ones used to build the tree. [load] enforces
 * this by comparing the SHA-256 of each raw asset against [TreeMeta].
 */
object WordleLoader {
    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * Parse a newline-delimited word list from UTF-8 [bytes].
     *
     * Each line is trimmed, blank lines are dropped, and every word is
     * lowercased. Hashing for integrity verification is done on the raw bytes,
     * not on this parsed result.
     */
    fun parseWordList(bytes: ByteArray): List<String> =
        bytes.toString(Charsets.UTF_8)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }
            .toList()

    /** Lowercase hex SHA-256 of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val b = digest[i].toInt() and 0xFF
            out[i * 2] = HEX_DIGITS[b ushr 4]
            out[i * 2 + 1] = HEX_DIGITS[b and 0x0F]
        }
        return String(out)
    }

    /**
     * Verify and assemble a [WordleData] from the raw asset bytes.
     *
     * [metaBytes] is parsed as UTF-8 JSON. The SHA-256 of [allowedBytes],
     * [answersBytes], and [treeBytes] must each match the corresponding field
     * in the manifest. The byte arrays passed here must be the exact file
     * contents (including any trailing newline) that the Python side hashed.
     *
     * @throws MalformedTreeException on any SHA-256 mismatch or if the tree
     *   bytes do not parse.
     */
    fun load(
        allowedBytes: ByteArray,
        answersBytes: ByteArray,
        treeBytes: ByteArray,
        metaBytes: ByteArray,
    ): WordleData {
        val meta = TreeMeta.parse(metaBytes.toString(Charsets.UTF_8))

        verify("allowed", allowedBytes, meta.allowedSha256)
        verify("answers", answersBytes, meta.answersSha256)
        verify("tree", treeBytes, meta.treeSha256)

        val allowed = parseWordList(allowedBytes)
        val answers = parseWordList(answersBytes)
        val tree = DecisionTree.parse(treeBytes)

        return WordleData(allowed = allowed, answers = answers, tree = tree, meta = meta)
    }

    private fun verify(name: String, bytes: ByteArray, expected: String) {
        val actual = sha256Hex(bytes)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw MalformedTreeException("$name sha256 mismatch: expected $expected, got $actual")
        }
    }
}
