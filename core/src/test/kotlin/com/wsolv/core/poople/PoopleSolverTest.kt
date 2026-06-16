package com.wsolv.core.poople

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PoopleSolverTest {
    @Test
    fun `finds the shortest ladder to poop`() {
        val solver = PoopleSolver(listOf("boar", "boor", "poor", "poop"), target = "poop")
        val result = solver.solve("boar")
        assertTrue(result is PoopleResult.Path)
        result as PoopleResult.Path
        assertEquals(listOf("boar", "boor", "poor", "poop"), result.words)
        assertEquals(3, result.steps)
    }

    @Test
    fun `target is configurable to any 4-letter word`() {
        val solver = PoopleSolver(listOf("cold", "cord", "word", "ward"), target = "ward")
        val r = solver.solve("cold")
        assertTrue(r is PoopleResult.Path)
        r as PoopleResult.Path
        assertEquals(listOf("cold", "cord", "word", "ward"), r.words)
        assertEquals(3, r.steps)
    }

    @Test
    fun `start equal to target is zero steps`() {
        val solver = PoopleSolver(listOf("poop", "poor"), target = "poop")
        val r = solver.solve("poop")
        assertEquals(PoopleResult.Path(listOf("poop"), 0), r)
    }

    @Test
    fun `wrong length reports invalid length`() {
        val solver = PoopleSolver(listOf("poop", "poor"), target = "poop")
        val r = solver.solve("po")
        assertEquals(PoopleResult.InvalidLength(2), r)
    }

    @Test
    fun `unknown word reports not in dictionary`() {
        val solver = PoopleSolver(listOf("poop", "poor"), target = "poop")
        val r = solver.solve("quiz")
        assertEquals(PoopleResult.NotInDictionary("quiz"), r)
    }

    @Test
    fun `no path reports the normalized start word`() {
        // "isol" shares no single-substitution neighbor with the poop component.
        val solver = PoopleSolver(listOf("poop", "poor", "boor", "isol"), target = "poop")
        val r = solver.solve("isol")
        assertEquals(PoopleResult.NoPath("isol"), r)
    }

    @Test
    fun `input is normalized to lowercase and trimmed`() {
        val solver = PoopleSolver(listOf("boar", "boor", "poor", "poop"), target = "poop")
        val r = solver.solve("  BOAR ")
        assertTrue(r is PoopleResult.Path)
        r as PoopleResult.Path
        assertEquals("boar", r.words.first())
        assertEquals(3, r.steps)
    }

    @Test
    fun `constructor rejects a target absent from the dictionary`() {
        assertThrows(IllegalArgumentException::class.java) {
            PoopleSolver(listOf("boar", "boor"), target = "poop")
        }
    }

    @Test
    fun `constructor rejects a target of the wrong length`() {
        assertThrows(IllegalArgumentException::class.java) {
            PoopleSolver(listOf("poop"), target = "pop")
        }
    }

    @Test
    fun `excluding a word on the shortest path reroutes via a valid alternative`() {
        // Two equal-length ladders boar -> poop: via "boor" or via "boar->poar".
        val solver =
            PoopleSolver(
                listOf("boar", "boor", "poor", "poop", "poar"),
                target = "poop",
            )
        val r = solver.solve("boar", setOf("boor"))
        assertTrue(r is PoopleResult.Path)
        r as PoopleResult.Path
        assertFalse(r.words.contains("boor"), "rerouted path must avoid the excluded word")
        assertEquals("boar", r.words.first())
        assertEquals("poop", r.words.last())
        // Each step is a single-letter substitution.
        for (i in 1 until r.words.size) {
            assertEquals(1, hammingDistance(r.words[i - 1], r.words[i]))
        }
    }

    @Test
    fun `excluding a bridge word forces a longer path`() {
        // boar -> boor -> poor -> poop is the only 3-step ladder; removing "boor"
        // forces the detour boar -> poar -> poor -> poop (still 3 here), so make
        // "boor" the sole bridge and verify exclusion lengthens or breaks it.
        val solver =
            PoopleSolver(
                listOf("boar", "boor", "poor", "poop"),
                target = "poop",
            )
        val baseline = solver.solve("boar")
        baseline as PoopleResult.Path
        assertEquals(3, baseline.steps)
        // "boor" is the only neighbor of "boar" reaching the component -> NoPath.
        val r = solver.solve("boar", setOf("boor"))
        assertEquals(PoopleResult.NoPath("boar"), r)
    }

    @Test
    fun `excluding the target yields no path`() {
        val solver = PoopleSolver(listOf("boar", "boor", "poor", "poop"), target = "poop")
        val r = solver.solve("boar", setOf("poop"))
        assertEquals(PoopleResult.NoPath("boar"), r)
    }

    @Test
    fun `excluded normalization is case-insensitive`() {
        val solver = PoopleSolver(listOf("boar", "boor", "poor", "poop"), target = "poop")
        val r = solver.solve("boar", setOf("BOOR"))
        assertEquals(PoopleResult.NoPath("boar"), r)
    }

    @Test
    fun `start excluded is still a valid origin`() {
        val solver = PoopleSolver(listOf("boar", "boor", "poor", "poop"), target = "poop")
        val r = solver.solve("boar", setOf("boar"))
        assertTrue(r is PoopleResult.Path)
        r as PoopleResult.Path
        assertEquals("boar", r.words.first())
        assertEquals(3, r.steps)
    }

    @Test
    fun `default solve is unchanged with empty exclusions`() {
        val solver = PoopleSolver(listOf("boar", "boor", "poor", "poop"), target = "poop")
        assertEquals(solver.solve("boar"), solver.solve("boar", emptySet()))
    }

    private fun hammingDistance(a: String, b: String): Int {
        var d = 0
        for (i in a.indices) if (a[i] != b[i]) d++
        return d
    }
}
