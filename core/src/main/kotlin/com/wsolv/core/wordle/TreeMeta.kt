package com.wsolv.core.wordle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Manifest describing a precomputed Wordle decision tree and its companion
 * word lists, emitted as JSON by the Python pipeline.
 *
 * The SHA-256 fields let [WordleLoader] verify that the bundled binary tree and
 * word lists are exactly the ones the metrics below were computed from.
 *
 * @property maxDepth deepest path length (number of guesses) in the tree.
 * @property totalGuesses summed guess count over every answer.
 * @property mean average guesses per answer (`totalGuesses / answerCount`).
 * @property answerCount number of secret answers the tree resolves.
 * @property allowedCount size of the allowed-guess dictionary.
 * @property firstWord the root suggestion (the optimal opening guess).
 * @property nodeCount number of nodes in the binary tree.
 * @property treeSha256 lowercase hex SHA-256 of the raw tree bytes.
 * @property answersSha256 lowercase hex SHA-256 of the raw answers file bytes.
 * @property allowedSha256 lowercase hex SHA-256 of the raw allowed file bytes.
 */
@Serializable
data class TreeMeta(
    @SerialName("max_depth") val maxDepth: Int,
    @SerialName("total_guesses") val totalGuesses: Long,
    @SerialName("mean") val mean: Double,
    @SerialName("answer_count") val answerCount: Int,
    @SerialName("allowed_count") val allowedCount: Int,
    @SerialName("first_word") val firstWord: String,
    @SerialName("node_count") val nodeCount: Int,
    @SerialName("tree_sha256") val treeSha256: String,
    @SerialName("answers_sha256") val answersSha256: String,
    @SerialName("allowed_sha256") val allowedSha256: String,
) {
    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Parse [json] (UTF-8 text) into a [TreeMeta], tolerating extra keys. */
        fun parse(json: String): TreeMeta = JSON.decodeFromString(json)
    }
}
