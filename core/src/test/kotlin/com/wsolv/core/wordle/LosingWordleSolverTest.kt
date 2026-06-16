package com.wsolv.core.wordle

import com.wsolv.core.feedback.Feedback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests the "Don't Wordle" losing solver. Suggestions must obey the hard
 * constraints accumulated from feedback (greens stay, yellows move, grays drop),
 * never repeat a played or rejected word, and the game must terminate.
 */
class LosingWordleSolverTest {
    // Answers share enough letters (-ace / -ate) that constraints bite.
    private val answers =
        listOf(
            "crane", "trace", "brace", "grace", "plate",
            "slate", "prate", "crate", "place", "space",
        )

    // Allowed = answers plus a few off-list words (the fallback pool).
    private val allowed = answers + listOf("fjord", "nymph", "gucks")

    private fun solver() = LosingWordleSolver(allowed, answers)

    /** Independently re-derives Don't Wordle legality to check suggestions. */
    private fun assertLegal(word: String, history: List<GuessFeedback>) {
        val n = Feedback.WORD_LEN
        val greenAt = CharArray(n) { ' ' }
        val required = HashSet<Char>()
        val bannedAt = Array(n) { HashSet<Char>() }
        val grayed = HashSet<Char>()
        for (fb in history) {
            for (i in 0 until n) {
                val c = fb.guess[i]
                when (fb.colors[i]) {
                    Feedback.GREEN -> { greenAt[i] = c; required.add(c) }
                    Feedback.YELLOW -> { required.add(c); bannedAt[i].add(c) }
                    else -> { bannedAt[i].add(c); grayed.add(c) }
                }
            }
        }
        val excludedChars = grayed.filter { it !in required }.toSet()
        for (i in 0 until n) {
            if (greenAt[i] != ' ') {
                assertEquals(greenAt[i], word[i], "green must stay at position $i in '$word'")
            } else {
                assertFalse(word[i] in bannedAt[i], "letter '${word[i]}' is banned at position $i in '$word'")
            }
        }
        for (c in excludedChars) assertFalse(c in word, "grayed-out letter '$c' reused in '$word'")
        for (c in required) assertTrue(c in word, "required letter '$c' missing from '$word'")
    }

    @Test
    fun `initial offers a playing session with a valid dictionary word`() {
        val s = solver().initial()
        assertEquals(LosingStatus.PLAYING, s.status)
        assertEquals(0, s.guessCount)
        assertEquals(answers.size, s.remainingCount)
        assertTrue(s.history.isEmpty())
        assertTrue(s.suggestion in allowed, "opening suggestion must be a real dictionary word")
    }

    @Test
    fun `initial avoids a blocklisted opener`() {
        val solver = solver()
        val opener = solver.initial().suggestion
        val s = solver.initial(setOf(opener))
        assertNotEquals(opener, s.suggestion, "a blocklisted opener must not be reused")
        assertTrue(opener in s.blocklist)
        assertEquals(LosingStatus.PLAYING, s.status)
    }

    @Test
    fun `every suggestion obeys the hard constraints and never repeats`() {
        val solver = solver()
        val hidden = "place"
        val played = HashSet<String>()
        var s = solver.initial()
        var guard = 0
        while (s.status == LosingStatus.PLAYING) {
            assertLegal(s.suggestion, s.history)
            assertFalse(s.suggestion in played, "suggestion '${s.suggestion}' was already played")
            played.add(s.suggestion)
            s = solver.submit(s, Feedback.score(s.suggestion, hidden))
            guard++
            check(guard <= LosingWordleSolver.MAX_GUESSES + 1) { "game ran too long" }
        }
        assertTrue(
            s.status in listOf(LosingStatus.SURVIVED, LosingStatus.CORNERED, LosingStatus.SOLVED),
            "game must end in a terminal state",
        )
    }

    @Test
    fun `skip re-picks a different word and never returns a blocked one`() {
        val solver = solver()
        val s = solver.initial()
        val first = s.suggestion
        val s1 = solver.skip(s)
        val s2 = solver.skip(s1)
        assertNotEquals(first, s1.suggestion)
        assertNotEquals(first, s2.suggestion, "a rejected word must never come back")
        assertNotEquals(s1.suggestion, s2.suggestion)
        assertTrue(first in s2.blocklist && s1.suggestion in s2.blocklist)
    }

    @Test
    fun `the blocklist persists across a submitted turn`() {
        val solver = solver()
        val s0 = solver.initial()
        val rejected = s0.suggestion
        val s1 = solver.skip(s0) // rejected joins the blocklist
        val s2 = solver.submit(s1, Feedback.score(s1.suggestion, "place"))
        if (s2.status == LosingStatus.PLAYING) {
            assertTrue(rejected in s2.blocklist, "blocklist must survive a turn")
            assertNotEquals(rejected, s2.suggestion)
        }
    }

    @Test
    fun `submitting all green yields SOLVED`() {
        val solver = solver()
        val s = solver.initial()
        val next = solver.submit(s, IntArray(Feedback.WORD_LEN) { Feedback.GREEN })
        assertEquals(LosingStatus.SOLVED, next.status)
        assertEquals("", next.suggestion)
        assertEquals(1, next.guessCount)
    }

    @Test
    fun `narrowing to one answer yields CORNERED`() {
        val solver = solver()
        val twoCandidates = listOf("crane", "place")
        val guess = "trace"
        assertNotEquals(
            Feedback.code(guess, "crane"),
            Feedback.code(guess, "place"),
            "guess must distinguish the two candidates",
        )
        val session =
            LosingSession(
                suggestion = guess,
                status = LosingStatus.PLAYING,
                guessCount = 1,
                remainingCount = twoCandidates.size,
                history = emptyList(),
                candidates = twoCandidates,
                blocklist = emptySet(),
            )
        val next = solver.submit(session, Feedback.score(guess, "crane"))
        assertEquals(LosingStatus.CORNERED, next.status)
        assertEquals(1, next.remainingCount)
        assertEquals("", next.suggestion)
    }

    @Test
    fun `surviving the final guess without solving yields SURVIVED`() {
        val solver = solver()
        val session =
            LosingSession(
                suggestion = "fjord",
                status = LosingStatus.PLAYING,
                guessCount = LosingWordleSolver.MAX_GUESSES - 1,
                remainingCount = answers.size,
                history = emptyList(),
                candidates = answers,
                blocklist = emptySet(),
            )
        // "fjord" leaves several r-bearing answers consistent: a non-green,
        // still-ambiguous result on the last guess is a survival.
        val colors = Feedback.score("fjord", "crane")
        assertNotEquals(Feedback.SOLVED, Feedback.encode(colors))
        val next = solver.submit(session, colors)
        assertEquals(LosingStatus.SURVIVED, next.status)
        assertEquals(LosingWordleSolver.MAX_GUESSES, next.guessCount)
        assertEquals("", next.suggestion)
        assertTrue(next.remainingCount >= 2, "survived only if still ambiguous")
    }

    @Test
    fun `skip on a finished game is a no-op`() {
        val solver = solver()
        val solved = solver.submit(solver.initial(), IntArray(Feedback.WORD_LEN) { Feedback.GREEN })
        assertEquals(solved, solver.skip(solved))
    }

    @Test
    fun `submit rejects malformed colors`() {
        val solver = solver()
        val s = solver.initial()
        assertThrows(IllegalArgumentException::class.java) {
            solver.submit(s, intArrayOf(0, 0, 0, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            solver.submit(s, intArrayOf(0, 1, 2, 3, 0))
        }
    }

    @Test
    fun `submit is pure and does not mutate the prior session`() {
        val solver = solver()
        val s0 = solver.initial()
        val before = s0.suggestion
        solver.submit(s0, Feedback.score(s0.suggestion, "place"))
        assertEquals(before, s0.suggestion, "original session unchanged")
        assertTrue(s0.history.isEmpty(), "original history unchanged")
        assertEquals(0, s0.guessCount, "original guess count unchanged")
    }
}
