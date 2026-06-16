package com.wsolv.core.wordle

import com.wsolv.core.common.MalformedTreeException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * In-memory view of the precomputed Wordle decision tree.
 *
 * The tree is a flat array of nodes. Node 0 is the root. Each node stores a
 * guess (an index into the allowed-word list) and a sparse map from feedback
 * code (`0..241`) to a child node index. A node with no children is a leaf.
 *
 * The on-disk format (little-endian) is produced by the Python pipeline:
 * ```
 * Header (13 bytes):
 *   [0:4]  magic = ASCII "WSV1"
 *   [4]    version u8 = 1
 *   [5:9]  allowed_count u32
 *   [9:13] node_count u32
 * Then node_count nodes, each:
 *   guess_index u16
 *   child_count u8
 *   child_count * ( feedback_code u8 (0..241), child_index u32 )
 * ```
 * The solved sentinel (code 242) is never stored — reaching it ends the game.
 *
 * Instances are immutable and therefore safe to share across threads.
 */
class DecisionTree
internal constructor(
    private val guessIndices: IntArray,
    // For node i, children live in childCodes/childTargets over the half-open
    // slice [childOffsets[i], childOffsets[i + 1]). Codes within a node are
    // stored in ascending order so child() can binary-search.
    private val childOffsets: IntArray,
    private val childCodes: IntArray,
    private val childTargets: IntArray,
    /** Size of the allowed-word dictionary this tree indexes into. */
    val allowedCount: Int,
) {
    /** Number of nodes in the tree. */
    val nodeCount: Int
        get() = guessIndices.size

    /**
     * The guess to play at [node], as an index into the allowed-word list.
     *
     * @throws IndexOutOfBoundsException if [node] is not in `0 until nodeCount`.
     */
    fun guessIndexAt(node: Int): Int = guessIndices[node]

    /**
     * Child node reached from [node] under feedback [code], or `-1` if [node]
     * has no edge for that code (i.e. the observed feedback leaves the tree).
     *
     * @throws IndexOutOfBoundsException if [node] is not in `0 until nodeCount`.
     */
    fun child(node: Int, code: Int): Int {
        var lo = childOffsets[node]
        var hi = childOffsets[node + 1] - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = childCodes[mid]
            when {
                c < code -> lo = mid + 1
                c > code -> hi = mid - 1
                else -> return childTargets[mid]
            }
        }
        return -1
    }

    companion object {
        private const val HEADER_BYTES = 13
        private const val VERSION = 1
        private const val MAX_CODE = 241 // 242 (solved) is never stored
        private val MAGIC = byteArrayOf('W'.code.toByte(), 'S'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())

        /**
         * Insertion-sort the parallel (code, target) tail `[start, size)` by code.
         * Child counts are tiny so this is effectively constant time per node.
         *
         * @throws MalformedTreeException if two children share a feedback code.
         */
        private fun sortNodeChildren(codes: ArrayList<Int>, targets: ArrayList<Int>, start: Int) {
            for (i in start + 1 until codes.size) {
                val code = codes[i]
                val target = targets[i]
                var j = i - 1
                while (j >= start && codes[j] > code) {
                    codes[j + 1] = codes[j]
                    targets[j + 1] = targets[j]
                    j--
                }
                if (j >= start && codes[j] == code) {
                    throw MalformedTreeException("duplicate feedback code $code within a node")
                }
                codes[j + 1] = code
                targets[j + 1] = target
            }
        }

        /**
         * Parse [bytes] into a [DecisionTree].
         *
         * @throws MalformedTreeException on bad magic, an unsupported version,
         *   a truncated buffer, an out-of-range feedback code, or a child index
         *   that points outside the node array.
         */
        fun parse(bytes: ByteArray): DecisionTree {
            if (bytes.size < HEADER_BYTES) {
                throw MalformedTreeException(
                    "tree too short for header: ${bytes.size} < $HEADER_BYTES bytes",
                )
            }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            for (i in MAGIC.indices) {
                if (buf.get() != MAGIC[i]) {
                    throw MalformedTreeException("bad magic: expected ASCII \"WSV1\"")
                }
            }
            val version = buf.get().toInt() and 0xFF
            if (version != VERSION) {
                throw MalformedTreeException("unsupported tree version: $version (expected $VERSION)")
            }
            val allowedCount = buf.int.toLong() and 0xFFFF_FFFFL
            val nodeCountLong = buf.int.toLong() and 0xFFFF_FFFFL
            if (nodeCountLong > Int.MAX_VALUE || allowedCount > Int.MAX_VALUE) {
                throw MalformedTreeException("declared counts exceed addressable range")
            }
            val nodeCount = nodeCountLong.toInt()

            val guessIndices = IntArray(nodeCount)
            val childOffsets = IntArray(nodeCount + 1)
            // Two-pass would require seeking; instead grow flat lists as we read.
            // Codes are sorted per node afterwards so child() can binary-search,
            // without assuming the producer already emitted them in order.
            val codes = ArrayList<Int>()
            val targets = ArrayList<Int>()

            try {
                for (node in 0 until nodeCount) {
                    childOffsets[node] = codes.size
                    guessIndices[node] = buf.short.toInt() and 0xFFFF
                    val childCount = buf.get().toInt() and 0xFF
                    repeat(childCount) {
                        val code = buf.get().toInt() and 0xFF
                        val targetLong = buf.int.toLong() and 0xFFFF_FFFFL
                        if (code > MAX_CODE) {
                            throw MalformedTreeException(
                                "feedback code out of range at node $node: $code > $MAX_CODE",
                            )
                        }
                        if (targetLong >= nodeCount) {
                            throw MalformedTreeException(
                                "child index out of range at node $node: $targetLong >= $nodeCount",
                            )
                        }
                        codes.add(code)
                        targets.add(targetLong.toInt())
                    }
                    sortNodeChildren(codes, targets, childOffsets[node])
                }
            } catch (e: BufferUnderflowException) {
                throw MalformedTreeException("truncated tree: ran out of bytes while reading nodes").apply {
                    initCause(e)
                }
            }
            childOffsets[nodeCount] = codes.size

            return DecisionTree(
                guessIndices = guessIndices,
                childOffsets = childOffsets,
                childCodes = codes.toIntArray(),
                childTargets = targets.toIntArray(),
                allowedCount = allowedCount.toInt(),
            )
        }
    }
}
