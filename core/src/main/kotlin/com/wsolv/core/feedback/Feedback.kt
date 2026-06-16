package com.wsolv.core.feedback

/**
 * Wordle feedback rule and base-3 codec — the Kotlin half of the shared contract.
 *
 * This must stay byte-for-byte equivalent to `wsolv_precompute.feedback` in the
 * Python pipeline. The generated `feedback_vectors.json` fixture is replayed by
 * both test suites to guarantee they never drift.
 *
 * Colors: [GRAY] (0) absent or already consumed, [YELLOW] (1) present but wrong
 * position, [GREEN] (2) correct position. A pattern is encoded base-3 with
 * position 0 as the most-significant trit, so all-green is [SOLVED] (242).
 */
object Feedback {
    const val GRAY = 0
    const val YELLOW = 1
    const val GREEN = 2

    const val WORD_LEN = 5
    const val NUM_PATTERNS = 243 // 3^5
    const val SOLVED = 242 // 22222 base 3

    private val POW3 = intArrayOf(81, 27, 9, 3, 1) // position 0 = most significant

    /**
     * Score [guess] against [answer], returning 5 colors.
     *
     * Two passes: greens claim their letter first, then yellows consume the
     * remaining occurrences left to right — the ordering that makes duplicate
     * letters correct (e.g. `LEVEL` vs `LEVER` colors the trailing `L` gray).
     *
     * Both words must be 5 lowercase `a`–`z` letters.
     */
    fun score(guess: String, answer: String): IntArray {
        require(guess.length == WORD_LEN && answer.length == WORD_LEN) {
            "words must be $WORD_LEN letters: '$guess', '$answer'"
        }
        val result = IntArray(WORD_LEN) // defaults to GRAY (0)
        val counts = IntArray(26)
        for (c in answer) counts[c - 'a']++

        for (i in 0 until WORD_LEN) {
            if (guess[i] == answer[i]) {
                result[i] = GREEN
                counts[guess[i] - 'a']--
            }
        }
        for (i in 0 until WORD_LEN) {
            if (result[i] == GREEN) continue
            val ci = guess[i] - 'a'
            if (counts[ci] > 0) {
                result[i] = YELLOW
                counts[ci]--
            }
        }
        return result
    }

    /** Encode a 5-color pattern as a base-3 code in `[0, 243)`. */
    fun encode(pattern: IntArray): Int {
        require(pattern.size == WORD_LEN) { "pattern must have $WORD_LEN entries" }
        var code = 0
        for (i in 0 until WORD_LEN) code += pattern[i] * POW3[i]
        return code
    }

    /** Decode a base-3 code back into a 5-color pattern. */
    fun decode(code: Int): IntArray {
        require(code in 0 until NUM_PATTERNS) { "code out of range: $code" }
        return IntArray(WORD_LEN) { (code / POW3[it]) % 3 }
    }

    /** Convenience: `encode(score(guess, answer))`. */
    fun code(guess: String, answer: String): Int = encode(score(guess, answer))
}
