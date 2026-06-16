package com.wsolv.core.wordle

import com.wsolv.core.feedback.Feedback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WordleSolverTest {
    // A tiny world. "crane" splits {apple, ample} (code 11) from {amber} (code 37);
    // within code 11, "apple" then splits apple (solved) from ample (code 188).
    private val allowed = listOf("crane", "apple", "amber", "ample", "stilt", "study", "spoil")
    private val answers = listOf("apple", "amber", "ample")

    private fun idx(word: String) = allowed.indexOf(word)

    private fun buildTree(): DecisionTree {
        val b = TreeBytesBuilder(allowedCount = allowed.size)
        // node 0 root: crane -> 11:node1, 37:node2
        val root = b.addNode(idx("crane"), listOf(11 to 1, 37 to 2))
        // node 1: apple -> 188:node3 (242 solved not stored)
        b.addNode(idx("apple"), listOf(188 to 3))
        // node 2: amber (leaf)
        b.addNode(idx("amber"))
        // node 3: ample (leaf)
        b.addNode(idx("ample"))
        check(root == 0)
        return DecisionTree.parse(b.build())
    }

    private fun solver() = WordleSolver(allowed, answers, buildTree())

    private fun driveToSolve(target: String): WordleSession {
        val solver = solver()
        var session = solver.initial()
        var guesses = 0
        while (session.status == WordleStatus.PLAYING) {
            val colors = Feedback.score(session.suggestion, target)
            session = solver.submit(session, colors)
            guesses++
            check(guesses <= 10) { "did not converge for $target" }
        }
        return session
    }

    @Test
    fun `initial offers the root guess over the full answer set`() {
        val session = solver().initial()
        assertEquals("crane", session.suggestion)
        assertEquals(WordleStatus.PLAYING, session.status)
        assertFalse(session.offTree)
        assertEquals(answers.size, session.remainingCount)
        assertTrue(session.history.isEmpty())
        assertEquals(0, session.node)
    }

    @Test
    fun `solves every answer on the tree`() {
        for (target in answers) {
            val session = driveToSolve(target)
            assertEquals(WordleStatus.SOLVED, session.status, "expected to solve $target")
            assertEquals("", session.suggestion)
            assertFalse(session.offTree, "should stay on tree for $target")
            assertTrue(session.history.isNotEmpty())
            // Last recorded guess is the answer itself (all-green).
            val last = session.history.last()
            assertTrue(last.colors.all { it == Feedback.GREEN })
            // amber solves in 1 guess from its leaf? It needs crane then amber: depth >= 1.
            assertTrue(session.history.size in 1..3, "history length sane for $target")
        }
    }

    @Test
    fun `apple solves directly after the root then the apple node`() {
        val solver = solver()
        var s = solver.initial() // crane
        s = solver.submit(s, Feedback.score("crane", "apple")) // code 11 -> node1 (apple)
        assertEquals("apple", s.suggestion)
        assertFalse(s.offTree)
        s = solver.submit(s, Feedback.score("apple", "apple")) // 242 -> solved
        assertEquals(WordleStatus.SOLVED, s.status)
        assertEquals(2, s.history.size)
    }

    @Test
    fun `unexpected feedback leaves the tree and keeps searching`() {
        val solver = solver()
        val s0 = solver.initial()
        // code 0 = crane all-gray; not an edge at root. Survivors in allowed:
        // stilt, study, spoil -> off-tree with a suggestion.
        val s1 = solver.submit(s0, Feedback.decode(0))
        assertTrue(s1.offTree, "should transition off-tree")
        assertEquals(WordleStatus.PLAYING, s1.status)
        assertEquals(-1, s1.node)
        assertEquals(3, s1.remainingCount)
        assertTrue(s1.suggestion in listOf("stilt", "study", "spoil"))
    }

    @Test
    fun `off-tree narrows to a single candidate then solves`() {
        val solver = solver()
        var s = solver.initial()
        s = solver.submit(s, Feedback.decode(0)) // off-tree, {stilt, study, spoil}
        assertTrue(s.offTree)
        // Pin the secret to "spoil" and feed real colors until solved.
        val target = "spoil"
        var guesses = 0
        while (s.status == WordleStatus.PLAYING) {
            s = solver.submit(s, Feedback.score(s.suggestion, target))
            guesses++
            check(guesses <= 10)
        }
        assertEquals(WordleStatus.SOLVED, s.status)
        assertTrue(s.offTree, "stays off-tree once it has left")
    }

    @Test
    fun `impossible feedback yields a contradiction`() {
        val solver = solver()
        val s0 = solver.initial()
        // code 1 = crane with only 'e' yellow; no allowed word matches and it is
        // not a root edge -> widened candidate set is empty -> CONTRADICTION.
        val s1 = solver.submit(s0, Feedback.decode(1))
        assertEquals(WordleStatus.CONTRADICTION, s1.status)
        assertEquals("", s1.suggestion)
        assertEquals(0, s1.remainingCount)
        assertTrue(s1.offTree)
    }

    @Test
    fun `submit rejects colors of the wrong size`() {
        val solver = solver()
        val s = solver.initial()
        assertThrows(IllegalArgumentException::class.java) {
            solver.submit(s, intArrayOf(0, 0, 0, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            solver.submit(s, intArrayOf(0, 0, 0, 0, 0, 0))
        }
    }

    @Test
    fun `submit rejects out-of-range colors`() {
        val solver = solver()
        val s = solver.initial()
        assertThrows(IllegalArgumentException::class.java) {
            solver.submit(s, intArrayOf(0, 1, 2, 3, 0))
        }
    }

    @Test
    fun `submit is pure and does not mutate the prior session`() {
        val solver = solver()
        val s0 = solver.initial()
        val before = s0.suggestion
        solver.submit(s0, Feedback.score("crane", "amber"))
        assertEquals(before, s0.suggestion, "original session unchanged")
        assertTrue(s0.history.isEmpty(), "original history unchanged")
    }

    // ----- Hard mode -----

    // A larger world so hard mode has room to narrow over several guesses.
    private val hardAllowed =
        listOf(
            "crane", "amber", "ample", "apple", "alert", "arbor", "abide",
            "amply", "amend", "ankle", "agile", "anger", "alter", "abode",
            "stilt", "study", "spoil", "shine", "slate",
        )
    private val hardAnswers =
        listOf("amber", "ample", "apple", "alert", "arbor", "abide", "amend", "anger", "alter")

    private fun hardSolver(): WordleSolver {
        val b = TreeBytesBuilder(allowedCount = hardAllowed.size)
        // The tree is used only for the opener in hard mode; a single root node
        // pointing at "crane" suffices.
        b.addNode(hardAllowed.indexOf("crane"))
        return WordleSolver(hardAllowed, hardAnswers, DecisionTree.parse(b.build()))
    }

    /** Greens (position->letter) and required letters derived from a history. */
    private fun constraints(history: List<GuessFeedback>): Pair<Map<Int, Char>, Set<Char>> {
        val greens = HashMap<Int, Char>()
        val required = HashSet<Char>()
        for (fb in history) {
            for (i in 0 until Feedback.WORD_LEN) {
                when (fb.colors[i]) {
                    Feedback.GREEN -> {
                        greens[i] = fb.guess[i]
                        required.add(fb.guess[i])
                    }
                    Feedback.YELLOW -> required.add(fb.guess[i])
                }
            }
        }
        return greens to required
    }

    private fun assertHardLegal(word: String, history: List<GuessFeedback>) {
        val (greens, required) = constraints(history)
        for ((pos, ch) in greens) {
            assertEquals(ch, word[pos], "green at $pos must persist in '$word'")
        }
        for (ch in required) {
            assertTrue(ch in word, "required letter '$ch' must appear in '$word'")
        }
    }

    @Test
    fun `hard mode initial offers the opener off-tree`() {
        val session = hardSolver().initial(hardMode = true)
        assertEquals("crane", session.suggestion)
        assertTrue(session.hardMode)
        assertEquals(-1, session.node)
        assertFalse(session.offTree)
        assertEquals(WordleStatus.PLAYING, session.status)
    }

    @Test
    fun `every hard-mode suggestion obeys green and yellow constraints`() {
        val solver = hardSolver()
        for (target in hardAnswers) {
            var s = solver.initial(hardMode = true)
            var guesses = 0
            while (s.status == WordleStatus.PLAYING) {
                // Each suggestion must respect all constraints revealed so far.
                assertHardLegal(s.suggestion, s.history)
                s = solver.submit(s, Feedback.score(s.suggestion, target))
                guesses++
                check(guesses <= 12) { "hard mode did not converge for $target" }
            }
            assertEquals(WordleStatus.SOLVED, s.status, "hard mode should solve $target")
            assertTrue(s.hardMode)
        }
    }

    @Test
    fun `hard mode reuses gray letters but keeps greens and yellows`() {
        val solver = hardSolver()
        var s = solver.initial(hardMode = true) // crane
        // Drive one real move so we have a non-trivial constraint set.
        s = solver.submit(s, Feedback.score("crane", "amber"))
        assertEquals(WordleStatus.PLAYING, s.status)
        // The new suggestion must obey everything crane revealed about amber.
        assertHardLegal(s.suggestion, s.history)
        // Sanity: at least one green or yellow was revealed (amber shares a, r, e).
        val (greens, required) = constraints(s.history)
        assertTrue(greens.isNotEmpty() || required.isNotEmpty())
    }

    @Test
    fun `hard mode solves to completion without a depth cap`() {
        val solver = hardSolver()
        for (target in hardAnswers) {
            var s = solver.initial(hardMode = true)
            var guesses = 0
            while (s.status == WordleStatus.PLAYING) {
                s = solver.submit(s, Feedback.score(s.suggestion, target))
                guesses++
                check(guesses <= 12)
            }
            assertEquals(WordleStatus.SOLVED, s.status)
            val last = s.history.last()
            assertTrue(last.colors.all { it == Feedback.GREEN })
        }
    }

    @Test
    fun `normal mode still walks the tree unchanged`() {
        val solver = solver()
        val s0 = solver.initial()
        assertFalse(s0.hardMode)
        assertEquals(0, s0.node)
        val s1 = solver.submit(s0, Feedback.score("crane", "apple"))
        assertFalse(s1.offTree, "normal mode stays on the tree")
        assertFalse(s1.hardMode)
        assertEquals("apple", s1.suggestion)
    }

    @Test
    fun `pickNextGuess prefers a split over a useless guess`() {
        val solver = solver()
        // Among answers, a guess that separates them beats one that lumps them.
        val pick = solver.pickNextGuess(answers)
        // Whatever it picks, its worst bucket over answers must be < answers.size.
        val worst = answers.groupingBy { Feedback.code(pick, it) }.eachCount().values.max()
        assertNotEquals(answers.size, worst, "chosen guess must split the set")
    }
}
