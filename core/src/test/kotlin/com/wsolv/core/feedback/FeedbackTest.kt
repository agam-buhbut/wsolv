package com.wsolv.core.feedback

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FeedbackTest {
    @Serializable
    private data class Vector(
        val guess: String,
        val answer: String,
        val pattern: List<Int>,
        val code: Int,
    )

    @Test
    fun `duplicate-letter rule pins down the canonical cases`() {
        // Trailing L has no remaining occurrence (the only L is green) -> GRAY.
        assertArrayEquals(intArrayOf(2, 2, 2, 2, 0), Feedback.score("level", "lever"))
        // 's' present once -> yellow; answer "erase" has two e's so both guess e's -> yellow.
        assertArrayEquals(intArrayOf(1, 0, 1, 1, 0), Feedback.score("speed", "erase"))
        // Guess has two e's but answer "abide" has one: first e yellow, second e gray.
        assertArrayEquals(intArrayOf(0, 0, 1, 0, 1), Feedback.score("speed", "abide"))
        // All correct -> the solved sentinel.
        assertArrayEquals(intArrayOf(2, 2, 2, 2, 2), Feedback.score("argue", "argue"))
        assertEquals(Feedback.SOLVED, Feedback.code("argue", "argue"))
        // Nothing in common -> all gray (code 0).
        assertEquals(0, Feedback.code("xylyl", "abate"))
    }

    @Test
    fun `encode and decode round-trip for all 243 codes`() {
        for (code in 0 until Feedback.NUM_PATTERNS) {
            assertEquals(code, Feedback.encode(Feedback.decode(code)))
        }
    }

    @Test
    fun `matches the shared Python-generated vectors`() {
        val text =
            checkNotNull(javaClass.getResourceAsStream("/feedback_vectors.json")) {
                "feedback_vectors.json missing from test resources"
            }.bufferedReader().use { it.readText() }
        val vectors = Json.decodeFromString<List<Vector>>(text)
        check(vectors.size > 1000) { "expected a large fixture, got ${vectors.size}" }
        for (v in vectors) {
            val score = Feedback.score(v.guess, v.answer)
            assertArrayEquals(
                v.pattern.toIntArray(),
                score,
                "score mismatch for ${v.guess}/${v.answer}",
            )
            assertEquals(v.code, Feedback.encode(score), "code mismatch ${v.guess}/${v.answer}")
            assertArrayEquals(v.pattern.toIntArray(), Feedback.decode(v.code))
        }
    }
}
