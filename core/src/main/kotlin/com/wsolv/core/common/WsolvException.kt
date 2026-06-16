package com.wsolv.core.common

/**
 * Base class for every checked failure raised by the `:core` module.
 *
 * Callers can catch [WsolvException] to handle any domain error uniformly, or
 * match a concrete subclass for finer control.
 */
sealed class WsolvException(message: String) : Exception(message)

/**
 * Thrown when a bundled asset cannot be trusted: a malformed decision tree
 * (bad magic, unsupported version, truncated bytes) or a SHA-256 mismatch
 * between an asset and its manifest in [com.wsolv.core.wordle.TreeMeta].
 */
class MalformedTreeException(message: String) : WsolvException(message)
