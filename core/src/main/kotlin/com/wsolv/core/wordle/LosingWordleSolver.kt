package com.wsolv.core.wordle

import com.wsolv.core.feedback.Feedback

/**
 * Outcome of the current reverse ("Don't Wordle") session.
 *
 * The reverse game is won by playing [LosingWordleSolver.MAX_GUESSES] guesses
 * without ever turning the board all-green. It is lost either by accidentally
 * solving the puzzle ([SOLVED]) or by being [CORNERED] — narrowed to a single
 * remaining answer, at which point the next guess would be forced to solve it.
 */
enum class LosingStatus {
    /** Still surviving; [LosingSession.suggestion] holds the next word to play. */
    PLAYING,

    /** Survived [LosingWordleSolver.MAX_GUESSES] guesses without solving — a win. */
    SURVIVED,

    /** Only one consistent answer remains — forced to solve next turn (a loss). */
    CORNERED,

    /** The last feedback was all green — accidentally solved the puzzle (a loss). */
    SOLVED,
}

/**
 * Immutable snapshot of a reverse ("Don't Wordle") session.
 *
 * Produced by [LosingWordleSolver.initial] and advanced by
 * [LosingWordleSolver.submit] / [LosingWordleSolver.skip], each of which returns
 * a fresh snapshot. Besides the public game state it carries the [candidates]
 * still consistent with the feedback history and the [blocklist] of words that
 * must never be suggested again.
 *
 * @property suggestion next word to play; `""` when [status] is not
 *   [LosingStatus.PLAYING]. Always obeys the Don't Wordle hard constraints
 *   (greens kept, yellows moved, grays dropped) and is never a previously
 *   played or rejected word.
 * @property status current outcome.
 * @property guessCount number of guesses played so far.
 * @property remainingCount number of answers still consistent with the history.
 * @property history every guess played so far, in order.
 * @property candidates answers still consistent with the history.
 * @property blocklist words that must never be suggested again (rejected via
 *   [LosingWordleSolver.skip]); persists for the whole game.
 */
class LosingSession
internal constructor(
    val suggestion: String,
    val status: LosingStatus,
    val guessCount: Int,
    val remainingCount: Int,
    val history: List<GuessFeedback>,
    internal val candidates: List<String>,
    val blocklist: Set<String>,
)

/**
 * Drives a "Don't Wordle" game — the inverse of [WordleSolver]: the player tries
 * to AVOID solving for [MAX_GUESSES] guesses.
 *
 * Don't Wordle is played in strict hard mode, so every suggestion obeys the
 * accumulated hints: a green letter stays in its position, a yellow letter must
 * appear but in a different position than where it was yellow, and a grayed-out
 * letter may not be used again. Within those legal words the solver prefers one
 * that is **not** a current candidate (so it can't come back all-green) and that
 * leaks the least information (keeps the candidate set as large as possible).
 *
 * Suggestions are drawn from the full [allowed] dictionary (~13k words) for
 * maximum strategic breadth; the common [answers] list is used only as a
 * tie-break, gently favoring more familiar words. Words rejected by the player
 * ([skip]) and words already played are never suggested again. All methods are
 * pure and synchronous; sessions are immutable. The guess search is
 * O(allowed * candidates) and is intended to run off the UI thread (the opening
 * guess is cached so resets are instant).
 */
class LosingWordleSolver(
    private val allowed: List<String>,
    private val answers: List<String>,
) {
    // Common, valid words: used only as a tie-break so that, among equally good
    // guesses, a familiar word is preferred over an obscure one.
    private val answerSet: Set<String> = answers.toHashSet()

    // The opening guess never changes (no constraints, no history), so compute it
    // once. Lazy + thread-safe: the first session pays the cost off the UI thread.
    private val opener: String by lazy { pickGuess(answers, emptyList(), emptySet()) }

    /**
     * Fresh session carrying a starting [blocklist] of words to never suggest
     * (e.g. words the player rejected in a previous game, restored from disk).
     *
     * Uses the cached opener unless it is itself blocklisted, in which case a
     * fresh opening guess is computed that avoids the whole blocklist. The
     * [blocklist] persists for the life of the session.
     */
    fun initial(blocklist: Set<String> = emptySet()): LosingSession {
        val suggestion =
            if (opener !in blocklist) opener else pickGuess(answers, emptyList(), blocklist)
        return LosingSession(
            suggestion = suggestion,
            status = LosingStatus.PLAYING,
            guessCount = 0,
            remainingCount = answers.size,
            history = emptyList(),
            candidates = answers,
            blocklist = blocklist,
        )
    }

    /**
     * Advance [session] by recording [colors] for its current suggestion and
     * returning the next session. The [blocklist] is preserved across turns.
     *
     * @param colors the 5 feedback colors observed for `session.suggestion`.
     * @throws IllegalArgumentException if [colors] is not length 5 or contains
     *   a value outside `0..2`.
     */
    fun submit(session: LosingSession, colors: IntArray): LosingSession {
        require(colors.size == Feedback.WORD_LEN) {
            "colors must have ${Feedback.WORD_LEN} entries, got ${colors.size}"
        }
        require(colors.all { it in Feedback.GRAY..Feedback.GREEN }) {
            "each color must be in ${Feedback.GRAY}..${Feedback.GREEN}"
        }

        val code = Feedback.encode(colors)
        val guess = session.suggestion
        val newCount = session.guessCount + 1
        val newHistory = session.history + GuessFeedback(guess, colors.copyOf())
        val newCandidates = session.candidates.filter { Feedback.code(guess, it) == code }

        // Accidentally solved: all green is an immediate loss.
        if (code == Feedback.SOLVED) {
            return terminal(LosingStatus.SOLVED, newCount, newCandidates, newHistory, session.blocklist)
        }
        // Cornered: a single (or zero) remaining answer forces a solve next turn.
        if (newCandidates.size <= 1) {
            return terminal(LosingStatus.CORNERED, newCount, newCandidates, newHistory, session.blocklist)
        }
        // Survived the full run without solving — a win.
        if (newCount >= MAX_GUESSES) {
            return terminal(LosingStatus.SURVIVED, newCount, newCandidates, newHistory, session.blocklist)
        }

        // Still playing: pick the next legal losing guess over the narrowed set.
        return LosingSession(
            suggestion = pickGuess(newCandidates, newHistory, session.blocklist),
            status = LosingStatus.PLAYING,
            guessCount = newCount,
            remainingCount = newCandidates.size,
            history = newHistory,
            candidates = newCandidates,
            blocklist = session.blocklist,
        )
    }

    /**
     * Reject the current suggestion (e.g. the real game would not accept it) and
     * re-pick a different legal losing word for the same turn. No feedback is
     * recorded; the rejected word joins the [blocklist] so it is never suggested
     * again. A no-op (returns [session] unchanged) when the game is over.
     */
    fun skip(session: LosingSession): LosingSession {
        if (session.status != LosingStatus.PLAYING || session.candidates.isEmpty()) {
            return session
        }
        val newBlocklist = session.blocklist + session.suggestion
        return LosingSession(
            suggestion = pickGuess(session.candidates, session.history, newBlocklist),
            status = session.status,
            guessCount = session.guessCount,
            remainingCount = session.remainingCount,
            history = session.history,
            candidates = session.candidates,
            blocklist = newBlocklist,
        )
    }

    private fun terminal(
        status: LosingStatus,
        guessCount: Int,
        candidates: List<String>,
        history: List<GuessFeedback>,
        blocklist: Set<String>,
    ): LosingSession =
        LosingSession(
            suggestion = "",
            status = status,
            guessCount = guessCount,
            remainingCount = candidates.size,
            history = history,
            candidates = candidates,
            blocklist = blocklist,
        )

    /**
     * Pick a Don't-Wordle-legal guess that narrows [candidates] as little as
     * possible.
     *
     * The pool is built in priority order, each step keeping only words that obey
     * the hard constraints of [history] (see [legal]) and are neither in
     * [blocklist] nor already played:
     *  1. dictionary words that are NOT current candidates (provably not the
     *     answer, so they can never come back all-green — the ideal losing guess);
     *  2. any legal dictionary word (used when every legal word is still a
     *     candidate, e.g. the opening guess);
     *  3. the remaining candidates (the player is cornered).
     *
     * Among the pool the guess whose LARGEST feedback bucket is biggest (the most
     * lopsided split, leaking the least) wins, tie-broken by FEWEST non-empty
     * buckets, then by preferring a common answer-list word, then
     * lexicographically.
     *
     * @throws IllegalArgumentException if [candidates] is empty.
     */
    private fun pickGuess(
        candidates: List<String>,
        history: List<GuessFeedback>,
        blocklist: Set<String>,
    ): String {
        require(candidates.isNotEmpty()) { "no candidates to choose from" }
        val candidateSet = candidates.toHashSet()
        val excluded = blocklist + history.map { it.guess }

        val legalGuesses = allowed.filter { it !in excluded && legal(it, history) }
        val pool =
            legalGuesses.filter { it !in candidateSet }
                .ifEmpty { legalGuesses }
                .ifEmpty { candidates.filter { it !in blocklist } }
                .ifEmpty { candidates }

        var best: String? = null
        var bestLargest = -1
        var bestNonEmpty = Int.MAX_VALUE

        val buckets = IntArray(Feedback.NUM_PATTERNS)
        for (guess in pool) {
            java.util.Arrays.fill(buckets, 0)
            var largest = 0
            var nonEmpty = 0
            for (answer in candidates) {
                val c = Feedback.code(guess, answer)
                val n = ++buckets[c]
                if (n == 1) nonEmpty++
                if (n > largest) largest = n
            }

            val better =
                when {
                    best == null -> true
                    largest != bestLargest -> largest > bestLargest
                    nonEmpty != bestNonEmpty -> nonEmpty < bestNonEmpty
                    (guess in answerSet) != (best in answerSet) -> guess in answerSet
                    else -> guess < best
                }
            if (better) {
                best = guess
                bestLargest = largest
                bestNonEmpty = nonEmpty
            }
        }
        return best!!
    }

    /**
     * Don't Wordle hard-mode legality for [word] given the feedback [history]:
     *  - every green position must hold its revealed letter;
     *  - every yellow letter must appear, but NOT at the position it was yellow;
     *  - every grayed-out letter may not be used again (unless that same letter
     *    was also revealed green/yellow, in which case only its grayed position
     *    is forbidden — the duplicate-letter case).
     */
    private fun legal(word: String, history: List<GuessFeedback>): Boolean {
        val n = Feedback.WORD_LEN
        val greenAt = CharArray(n) { ' ' }
        val required = HashSet<Char>()
        val bannedAt = Array(n) { HashSet<Char>() }
        val grayed = HashSet<Char>()

        for (fb in history) {
            for (i in 0 until n) {
                val c = fb.guess[i]
                when (fb.colors[i]) {
                    Feedback.GREEN -> {
                        greenAt[i] = c
                        required.add(c)
                    }
                    Feedback.YELLOW -> {
                        required.add(c)
                        bannedAt[i].add(c) // must move off this position
                    }
                    else -> {
                        bannedAt[i].add(c)
                        grayed.add(c)
                    }
                }
            }
        }
        // A grayed letter is fully excluded only if it was never green/yellow.
        val excludedChars = grayed.filter { it !in required }.toHashSet()

        for (i in 0 until n) {
            val ch = word[i]
            if (greenAt[i] != ' ') {
                if (ch != greenAt[i]) return false
            } else if (ch in bannedAt[i]) {
                return false
            }
        }
        if (word.any { it in excludedChars }) return false
        return required.all { it in word }
    }

    companion object {
        /** Guesses to survive for a reverse-game win. */
        const val MAX_GUESSES = 6
    }
}
