package com.wsolv.core.wordle

import com.wsolv.core.common.MalformedTreeException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DecisionTreeTest {
    private fun sampleTree(): DecisionTree {
        // Root (guess 0) -> code 5 to node 1, code 100 to node 2. Both leaves.
        val builder = TreeBytesBuilder(allowedCount = 7)
        builder.addNode(guessIndex = 0, children = listOf(5 to 1, 100 to 2))
        builder.addNode(guessIndex = 3) // leaf
        builder.addNode(guessIndex = 6) // leaf
        return DecisionTree.parse(builder.build())
    }

    @Test
    fun `parses header and node fields`() {
        val tree = sampleTree()
        assertEquals(3, tree.nodeCount)
        assertEquals(7, tree.allowedCount)
        assertEquals(0, tree.guessIndexAt(0))
        assertEquals(3, tree.guessIndexAt(1))
        assertEquals(6, tree.guessIndexAt(2))
    }

    @Test
    fun `child returns target for present code and -1 for absent`() {
        val tree = sampleTree()
        assertEquals(1, tree.child(0, 5))
        assertEquals(2, tree.child(0, 100))
        assertEquals(-1, tree.child(0, 6), "code with no edge -> -1")
        assertEquals(-1, tree.child(1, 5), "leaf has no children -> -1")
    }

    @Test
    fun `child binary search works regardless of input order`() {
        // Provide children out of code order; parser must sort them.
        val builder = TreeBytesBuilder(allowedCount = 5)
        builder.addNode(guessIndex = 0, children = listOf(200 to 1, 1 to 2, 50 to 3))
        builder.addNode(guessIndex = 1)
        builder.addNode(guessIndex = 2)
        builder.addNode(guessIndex = 3)
        val tree = DecisionTree.parse(builder.build())
        assertEquals(2, tree.child(0, 1))
        assertEquals(3, tree.child(0, 50))
        assertEquals(1, tree.child(0, 200))
        assertEquals(-1, tree.child(0, 51))
    }

    @Test
    fun `parse rejects bad magic`() {
        val bytes = sampleTree().let { TreeBytesBuilder(7).apply { addNode(0) }.build() }
        bytes[0] = 'X'.code.toByte()
        assertThrows(MalformedTreeException::class.java) { DecisionTree.parse(bytes) }
    }

    @Test
    fun `parse rejects unsupported version`() {
        val bytes = TreeBytesBuilder(7).apply { addNode(0) }.build()
        bytes[4] = 2 // version byte
        assertThrows(MalformedTreeException::class.java) { DecisionTree.parse(bytes) }
    }

    @Test
    fun `parse rejects truncated body`() {
        val full = TreeBytesBuilder(7).apply { addNode(0, listOf(5 to 1)); addNode(1) }.build()
        val truncated = full.copyOf(full.size - 3)
        assertThrows(MalformedTreeException::class.java) { DecisionTree.parse(truncated) }
    }

    @Test
    fun `parse rejects header shorter than 13 bytes`() {
        assertThrows(MalformedTreeException::class.java) { DecisionTree.parse(ByteArray(5)) }
    }
}
