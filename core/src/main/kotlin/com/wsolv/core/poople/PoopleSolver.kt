package com.wsolv.core.poople

/**
 * Result of a word-ladder search from a start word to the target.
 *
 * Exactly one variant is returned per [PoopleSolver.solve] call.
 */
sealed interface PoopleResult {
    /**
     * A shortest ladder was found.
     *
     * @property words the chain from start to target inclusive.
     * @property steps number of transformations (`words.size - 1`).
     */
    data class Path(val words: List<String>, val steps: Int) : PoopleResult

    /** The start word was not 4 letters; [actual] is its trimmed length. */
    data class InvalidLength(val actual: Int) : PoopleResult

    /** The (normalized) start [word] is not in the dictionary. */
    data class NotInDictionary(val word: String) : PoopleResult

    /** No ladder connects the (normalized) start [word] to the target. */
    data class NoPath(val word: String) : PoopleResult
}

/**
 * Solves the "poople" word ladder: transform a 4-letter start word into
 * [target] one letter at a time, where every intermediate word is in the
 * dictionary. Breadth-first search over single-letter-substitution neighbors
 * yields a provably shortest ladder.
 *
 * @param words the 4-letter dictionary (case-insensitive; deduplicated).
 * @param target the goal word; must be 4 letters and present in [words].
 * @throws IllegalArgumentException if [target] is not a 4-letter dictionary word.
 */
class PoopleSolver(words: List<String>, val target: String = "poop") {
    private val wordSet: Set<String> = words.map { it.lowercase() }.toHashSet()

    // For each wildcard key (e.g. "*oop", "p*op"), the words matching it. Two
    // distinct words are neighbors iff they share a wildcard key.
    private val buckets: Map<String, List<String>> = buildBuckets(wordSet)

    init {
        require(target.length == WORD_LEN && target in wordSet) {
            "target must be a $WORD_LEN-letter dictionary word: '$target'"
        }
    }

    /**
     * Find the shortest ladder from [start] to [target].
     *
     * [start] is trimmed and lowercased before matching. Returns
     * [PoopleResult.InvalidLength] / [PoopleResult.NotInDictionary] for bad
     * input, [PoopleResult.Path] with `steps == 0` when start equals target,
     * and [PoopleResult.NoPath] when no ladder exists.
     */
    fun solve(start: String): PoopleResult = solve(start, emptySet())

    /**
     * Find the shortest ladder from [start] to [target], never traversing to or
     * stopping on any word in [excluded].
     *
     * [excluded] is normalized to lowercase; the [start] word is always allowed
     * as the origin even if it appears in [excluded]. If [target] is excluded the
     * result is [PoopleResult.NoPath]. All other behavior matches [solve].
     */
    fun solve(start: String, excluded: Set<String>): PoopleResult {
        val word = start.trim().lowercase()
        if (word.length != WORD_LEN) return PoopleResult.InvalidLength(word.length)
        if (word !in wordSet) return PoopleResult.NotInDictionary(word)
        if (word == target) return PoopleResult.Path(listOf(target), 0)

        val blocked = excluded.mapTo(HashSet()) { it.lowercase() }
        if (target in blocked) return PoopleResult.NoPath(word)

        val prev = HashMap<String, String>()
        val visited = HashSet<String>()
        val queue = ArrayDeque<String>()
        visited.add(word)
        queue.add(word)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (next in neighbors(current)) {
                // The start is the only excluded word allowed (as origin); never
                // traverse to a blocked word.
                if (next in blocked) continue
                if (!visited.add(next)) continue
                prev[next] = current
                if (next == target) return PoopleResult.Path(reconstruct(prev, word, target), steps(prev, target))
                queue.add(next)
            }
        }
        return PoopleResult.NoPath(word)
    }

    /** Words one substitution away from [word] (excluding [word] itself). */
    private fun neighbors(word: String): Sequence<String> =
        sequence {
            val sb = StringBuilder(word)
            for (i in 0 until WORD_LEN) {
                val original = sb[i]
                sb[i] = WILDCARD
                val key = sb.toString()
                sb[i] = original
                val bucket = buckets[key] ?: continue
                for (w in bucket) if (w != word) yield(w)
            }
        }

    private fun steps(prev: Map<String, String>, target: String): Int {
        var count = 0
        var cur: String? = target
        while (cur != null && prev.containsKey(cur)) {
            count++
            cur = prev[cur]
        }
        return count
    }

    private fun reconstruct(prev: Map<String, String>, start: String, target: String): List<String> {
        val path = ArrayList<String>()
        var cur: String? = target
        while (cur != null) {
            path.add(cur)
            if (cur == start) break
            cur = prev[cur]
        }
        path.reverse()
        return path
    }

    companion object {
        private const val WORD_LEN = 4
        private const val WILDCARD = '*'

        private fun buildBuckets(wordSet: Set<String>): Map<String, List<String>> {
            val map = HashMap<String, MutableList<String>>()
            for (word in wordSet) {
                if (word.length != WORD_LEN) continue
                val sb = StringBuilder(word)
                for (i in 0 until WORD_LEN) {
                    val original = sb[i]
                    sb[i] = WILDCARD
                    map.getOrPut(sb.toString()) { ArrayList() }.add(word)
                    sb[i] = original
                }
            }
            return map
        }
    }
}
