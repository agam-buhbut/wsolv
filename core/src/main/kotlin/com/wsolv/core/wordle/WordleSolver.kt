package com.wsolv.core.wordle

import com.wsolv.core.feedback.Feedback
import kotlin.math.ln

/** Outcome of the current solving session. */
enum class WordleStatus {
    /** Still narrowing down; [WordleSession.suggestion] holds the next guess. */
    PLAYING,

    /** The answer has been found (the last feedback was all green). */
    SOLVED,

    /**
     * No answer in the dictionary is consistent with the feedback so far —
     * the colors entered must be wrong (or the board is not a real Wordle).
     */
    CONTRADICTION,
}

/** One played guess and the 5 colors observed for it. */
class GuessFeedback(
    val guess: String,
    /** `IntArray(5)`, each entry one of [Feedback.GRAY]/[Feedback.YELLOW]/[Feedback.GREEN]. */
    val colors: IntArray,
)

/**
 * Immutable snapshot of a Wordle solving session.
 *
 * Produced by [WordleSolver.initial] and advanced by [WordleSolver.submit],
 * which returns a fresh snapshot each time. Besides the public game state it
 * carries the bookkeeping the solver needs to advance: the current decision
 * tree [node] (or `-1` once off-tree) and the [candidates] still consistent
 * with the feedback history.
 *
 * @property suggestion next word to play; `""` when [status] is not [WordleStatus.PLAYING].
 * @property status current outcome.
 * @property offTree `true` once feedback has left the precomputed tree and the
 *   solver is computing guesses live.
 * @property remainingCount number of answers still consistent with the history.
 * @property history every guess played so far, in order.
 * @property node current decision-tree node, or `-1` when off-tree.
 * @property candidates answers still consistent with the history.
 * @property hardMode `true` when NYT hard-mode constraints are enforced on every
 *   suggested guess (greens kept in place; all revealed letters reused).
 */
class WordleSession
internal constructor(
    val suggestion: String,
    val status: WordleStatus,
    val offTree: Boolean,
    val remainingCount: Int,
    val history: List<GuessFeedback>,
    val node: Int,
    val candidates: List<String>,
    val hardMode: Boolean,
)

/**
 * Drives a Wordle game toward the answer.
 *
 * While the observed feedback follows the precomputed [tree] the solver simply
 * walks it — O(1) per move. The instant feedback leaves the tree (a mistyped
 * color, or a Wordle clone whose answer set differs) it falls back to a live
 * search over the [allowed] dictionary, recomputing the candidate set from the
 * full feedback history and choosing the next guess by minimax.
 *
 * All methods are pure and synchronous; sessions are immutable. Off-tree moves
 * can be O(pool * candidates) and are intended to run off the UI thread.
 */
class WordleSolver(
    private val allowed: List<String>,
    private val answers: List<String>,
    private val tree: DecisionTree,
) {
    /** Threshold above which the off-tree guess pool is the candidates, not [allowed]. */
    private val liveScanThreshold = 1000

    /**
     * Fresh session: root suggestion, full answer set, [WordleStatus.PLAYING].
     *
     * In normal mode ([hardMode] `false`) the session starts on the precomputed
     * tree (`node = 0`). In hard mode it starts off-tree (`node = -1`) — the
     * opener is the same root guess (legal when nothing is known yet) but every
     * later guess is computed live under the hard-mode constraints.
     */
    fun initial(hardMode: Boolean = false): WordleSession {
        val rootGuess = allowed[tree.guessIndexAt(0)]
        return WordleSession(
            suggestion = rootGuess,
            status = WordleStatus.PLAYING,
            offTree = false,
            remainingCount = answers.size,
            history = emptyList(),
            node = if (hardMode) -1 else 0,
            candidates = answers,
            hardMode = hardMode,
        )
    }

    /**
     * Advance [session] by recording [colors] for its current suggestion and
     * returning the next session.
     *
     * @param colors the 5 feedback colors observed for `session.suggestion`.
     * @throws IllegalArgumentException if [colors] is not length 5 or contains
     *   a value outside `0..2`.
     */
    fun submit(session: WordleSession, colors: IntArray): WordleSession {
        require(colors.size == Feedback.WORD_LEN) {
            "colors must have ${Feedback.WORD_LEN} entries, got ${colors.size}"
        }
        require(colors.all { it in Feedback.GRAY..Feedback.GREEN }) {
            "each color must be in ${Feedback.GRAY}..${Feedback.GREEN}"
        }

        val code = Feedback.encode(colors)
        val guess = session.suggestion
        val newHistory = session.history + GuessFeedback(guess, colors.copyOf())

        if (session.hardMode) {
            return submitHardMode(session, guess, code, newHistory)
        }

        if (code == Feedback.SOLVED) {
            // Keep the answer(s) matching the all-green code: normally exactly one.
            val solvedCandidates = session.candidates.filter { Feedback.code(guess, it) == code }
            return WordleSession(
                suggestion = "",
                status = WordleStatus.SOLVED,
                offTree = session.offTree,
                remainingCount = solvedCandidates.size,
                history = newHistory,
                node = session.node,
                candidates = solvedCandidates,
                hardMode = false,
            )
        }

        if (!session.offTree) {
            val child = tree.child(session.node, code)
            if (child >= 0) {
                val narrowed = session.candidates.filter { Feedback.code(guess, it) == code }
                return WordleSession(
                    suggestion = allowed[tree.guessIndexAt(child)],
                    status = WordleStatus.PLAYING,
                    offTree = false,
                    remainingCount = narrowed.size,
                    history = newHistory,
                    node = child,
                    candidates = narrowed,
                    hardMode = false,
                )
            }
            // Edge absent: we have left the tree. Recompute candidates over the
            // FULL allowed list against the entire history (the tree's answer
            // set may not cover this board) and continue off-tree.
            val widened = allowed.filter { word -> consistentWithHistory(word, newHistory) }
            return offTreeSession(widened, newHistory)
        }

        // Already off-tree: keep narrowing the live candidate set.
        val narrowed = session.candidates.filter { Feedback.code(guess, it) == code }
        return offTreeSession(narrowed, newHistory)
    }

    /**
     * Advance a hard-mode session. Candidates are narrowed against the latest
     * feedback as usual, but the live guess pool is restricted to words that
     * satisfy every revealed constraint ([hardModeLegal]).
     */
    private fun submitHardMode(
        session: WordleSession,
        guess: String,
        code: Int,
        newHistory: List<GuessFeedback>,
    ): WordleSession {
        if (code == Feedback.SOLVED) {
            val solvedCandidates = session.candidates.filter { Feedback.code(guess, it) == code }
            return WordleSession(
                suggestion = "",
                status = WordleStatus.SOLVED,
                offTree = false,
                remainingCount = solvedCandidates.size,
                history = newHistory,
                node = -1,
                candidates = solvedCandidates,
                hardMode = true,
            )
        }

        val narrowed = session.candidates.filter { Feedback.code(guess, it) == code }
        val legalGuesses = allowed.filter { hardModeLegal(it, newHistory) }
        val (status, suggestion) =
            when (narrowed.size) {
                0 -> WordleStatus.CONTRADICTION to ""
                // A still-consistent answer is always hard-mode legal, so a
                // size-1 candidate is safe to suggest directly.
                1 -> WordleStatus.PLAYING to narrowed[0]
                else -> WordleStatus.PLAYING to pickNextGuess(narrowed, legalGuesses)
            }
        return WordleSession(
            suggestion = suggestion,
            status = status,
            offTree = false,
            remainingCount = narrowed.size,
            history = newHistory,
            node = -1,
            candidates = narrowed,
            hardMode = true,
        )
    }

    /**
     * Hard-mode legality (standard NYT rules): every position revealed green in
     * the [history] must hold that letter in [word], and every letter ever shown
     * green or yellow must appear somewhere in [word]. Gray letters may be reused.
     */
    private fun hardModeLegal(word: String, history: List<GuessFeedback>): Boolean {
        val greenAt = CharArray(Feedback.WORD_LEN) { ' ' } // ' ' = no green known yet
        val required = HashSet<Char>()
        for (fb in history) {
            for (i in 0 until Feedback.WORD_LEN) {
                when (fb.colors[i]) {
                    Feedback.GREEN -> {
                        greenAt[i] = fb.guess[i]
                        required.add(fb.guess[i])
                    }
                    Feedback.YELLOW -> required.add(fb.guess[i])
                    else -> {} // gray: no constraint
                }
            }
        }
        for (i in 0 until Feedback.WORD_LEN) {
            if (greenAt[i] != ' ' && word[i] != greenAt[i]) return false
        }
        return required.all { it in word }
    }

    /** Build the next off-tree session from an already-filtered [candidates] set. */
    private fun offTreeSession(
        candidates: List<String>,
        history: List<GuessFeedback>,
    ): WordleSession {
        val (status, suggestion) =
            when (candidates.size) {
                0 -> WordleStatus.CONTRADICTION to ""
                1 -> WordleStatus.PLAYING to candidates[0]
                else -> WordleStatus.PLAYING to pickNextGuess(candidates)
            }
        return WordleSession(
            suggestion = suggestion,
            status = status,
            offTree = true,
            remainingCount = candidates.size,
            history = history,
            node = -1,
            candidates = candidates,
            hardMode = false,
        )
    }

    /** True iff [word] is consistent with every (guess, colors) pair in [history]. */
    private fun consistentWithHistory(word: String, history: List<GuessFeedback>): Boolean =
        history.all { fb -> Feedback.code(fb.guess, word) == Feedback.encode(fb.colors) }

    /** A predicate keeping words whose feedback against [guess] equals [colors]. */
    fun consistent(guess: String, colors: IntArray): (String) -> Boolean {
        val code = Feedback.encode(colors)
        return { word -> Feedback.code(guess, word) == code }
    }

    /**
     * Pick the next guess by minimax: minimize the largest feedback bucket over
     * [candidates], breaking ties by maximum entropy, then preferring a guess
     * that is itself a candidate.
     *
     * The pool scanned is [candidates] when it is large (so the cost stays
     * `O(candidates^2)`) and the full [allowed] list otherwise (a wider pool
     * finds sharper splits when only a few answers remain). O(pool * candidates).
     */
    fun pickNextGuess(candidates: List<String>): String {
        val pool = if (candidates.size > liveScanThreshold) candidates else allowed
        return pickNextGuess(candidates, pool)
    }

    /**
     * Pick the next guess by minimax over an explicit guess [pool] (e.g. the
     * hard-mode-legal subset of the dictionary). Same selection logic as
     * [pickNextGuess]: minimize the largest feedback bucket over [candidates],
     * tie-break on maximum entropy, then prefer a guess that is itself a
     * candidate. O(pool * candidates).
     *
     * @throws IllegalArgumentException if [candidates] or [pool] is empty.
     */
    fun pickNextGuess(candidates: List<String>, pool: List<String>): String {
        require(candidates.isNotEmpty()) { "no candidates to choose from" }
        require(pool.isNotEmpty()) { "no guesses in the pool" }
        val candidateSet = candidates.toHashSet()

        var best: String? = null
        var bestWorst = Int.MAX_VALUE
        var bestEntropy = Double.NEGATIVE_INFINITY
        var bestIsCandidate = false

        val buckets = IntArray(Feedback.NUM_PATTERNS)
        for (guess in pool) {
            java.util.Arrays.fill(buckets, 0)
            var worst = 0
            for (answer in candidates) {
                val c = Feedback.code(guess, answer)
                val n = ++buckets[c]
                if (n > worst) worst = n
            }
            // Entropy of the bucket-size distribution (higher = more even split).
            var entropy = 0.0
            val total = candidates.size.toDouble()
            for (n in buckets) {
                if (n == 0) continue
                val p = n / total
                entropy -= p * ln(p)
            }
            val isCandidate = guess in candidateSet

            val better =
                when {
                    worst != bestWorst -> worst < bestWorst
                    entropy != bestEntropy -> entropy > bestEntropy
                    isCandidate != bestIsCandidate -> isCandidate
                    else -> false
                }
            if (best == null || better) {
                best = guess
                bestWorst = worst
                bestEntropy = entropy
                bestIsCandidate = isCandidate
            }
        }
        return best!!
    }
}
