package com.wsolv.core.wordle

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Test-only builder for the WSV1 binary decision-tree format. Lets tests
 * hand-assemble small trees without depending on the Python pipeline.
 */
internal class TreeBytesBuilder(private val allowedCount: Int) {
    /** A node: the guess index plus its (feedback code -> child index) edges. */
    private data class Node(val guessIndex: Int, val children: List<Pair<Int, Int>>)

    private val nodes = ArrayList<Node>()

    /** Append a node and return its index. */
    fun addNode(guessIndex: Int, children: List<Pair<Int, Int>> = emptyList()): Int {
        nodes.add(Node(guessIndex, children))
        return nodes.size - 1
    }

    /** Serialize to little-endian WSV1 bytes. */
    fun build(): ByteArray {
        val body = ByteArrayOutputStream()
        for (node in nodes) {
            body.write(u16(node.guessIndex))
            body.write(node.children.size and 0xFF)
            for ((code, child) in node.children) {
                body.write(code and 0xFF)
                body.write(u32(child))
            }
        }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('W'.code.toByte(), 'S'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte()))
        out.write(1) // version
        out.write(u32(allowedCount))
        out.write(u32(nodes.size))
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    private fun u16(v: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

    private fun u32(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
}
