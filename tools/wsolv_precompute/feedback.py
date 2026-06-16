"""Wordle feedback rule and base-3 codec — the shared Python/Kotlin contract.

This module is the single source of truth for how a guess is scored against an
answer. The Kotlin ``com.wsolv.core.feedback.Feedback`` object reimplements the
exact same rule, and a generated ``feedback_vectors.json`` fixture is replayed by
both test suites to guarantee they never drift.

Colors::

    GRAY (0)   letter absent, or all its occurrences already consumed
    YELLOW (1) letter present but in the wrong position
    GREEN (2)  letter correct and in the correct position

A feedback pattern is encoded as a base-3 integer in ``[0, 243)`` with position 0
as the most-significant trit, so ``22222`` (all green) is ``242`` (the solved
sentinel).
"""

from __future__ import annotations

import numpy as np
from numpy.typing import NDArray

GRAY = 0
YELLOW = 1
GREEN = 2

WORD_LEN = 5
NUM_PATTERNS = 243  # 3 ** 5
SOLVED = 242  # all green == 22222 base 3

_POW3 = (81, 27, 9, 3, 1)  # position 0 is the most-significant trit

__all__ = [
    "GRAY",
    "YELLOW",
    "GREEN",
    "WORD_LEN",
    "NUM_PATTERNS",
    "SOLVED",
    "score",
    "feedback_code",
    "encode",
    "decode",
    "words_to_array",
    "pattern_matrix",
]


def score(guess: str, answer: str) -> tuple[int, ...]:
    """Return the 5-color feedback for ``guess`` against ``answer``.

    Two passes: greens claim their letter first, then yellows consume the
    remaining occurrences left to right. This ordering is what makes duplicate
    letters correct (e.g. ``LEVEL`` vs ``LEVER`` colors the trailing ``L`` gray).

    Args:
        guess: The 5-letter guessed word.
        answer: The 5-letter target word.

    Returns:
        A tuple of 5 ints, each ``GRAY``/``YELLOW``/``GREEN``.

    Raises:
        ValueError: If either word is not exactly 5 letters.
    """
    if len(guess) != WORD_LEN or len(answer) != WORD_LEN:
        raise ValueError(f"words must be {WORD_LEN} letters: {guess!r}, {answer!r}")

    result = [GRAY] * WORD_LEN
    counts: dict[str, int] = {}
    for ch in answer:
        counts[ch] = counts.get(ch, 0) + 1

    for i in range(WORD_LEN):
        if guess[i] == answer[i]:
            result[i] = GREEN
            counts[guess[i]] -= 1

    for i in range(WORD_LEN):
        if result[i] == GREEN:
            continue
        ch = guess[i]
        if counts.get(ch, 0) > 0:
            result[i] = YELLOW
            counts[ch] -= 1

    return tuple(result)


def encode(pattern: tuple[int, ...]) -> int:
    """Encode a 5-color pattern as a base-3 code in ``[0, 243)``."""
    if len(pattern) != WORD_LEN:
        raise ValueError(f"pattern must have {WORD_LEN} entries: {pattern!r}")
    return sum(p * w for p, w in zip(pattern, _POW3))


def decode(code: int) -> tuple[int, ...]:
    """Decode a base-3 code back into a 5-color pattern."""
    if not 0 <= code < NUM_PATTERNS:
        raise ValueError(f"code out of range [0,{NUM_PATTERNS}): {code}")
    return tuple((code // w) % 3 for w in _POW3)


def feedback_code(guess: str, answer: str) -> int:
    """Scalar convenience: ``encode(score(guess, answer))``."""
    return encode(score(guess, answer))


def words_to_array(words: list[str]) -> NDArray[np.int8]:
    """Convert words to an ``(N, 5)`` int8 array of 0..25 letter indices."""
    arr = np.frombuffer("".join(words).encode("ascii"), dtype=np.uint8)
    return (arr.reshape(len(words), WORD_LEN) - ord("a")).astype(np.int8)


def pattern_matrix(
    guesses: NDArray[np.int8], answers: NDArray[np.int8]
) -> NDArray[np.int16]:
    """Vectorized feedback codes for every (guess, answer) pair.

    Built one guess-row at a time to avoid the ``(G, A, 5, 5)`` blow-up; each row
    is a fully vectorized two-pass score over all answers. The result is verified
    against the scalar :func:`score` in the test suite.

    Args:
        guesses: ``(G, 5)`` int array of letter indices.
        answers: ``(A, 5)`` int array of letter indices.

    Returns:
        ``(G, A)`` int16 matrix where entry ``[g, a]`` is the base-3 feedback
        code for guessing ``guesses[g]`` against answer ``answers[a]``.
    """
    g_count = guesses.shape[0]
    a_count = answers.shape[0]
    out = np.empty((g_count, a_count), dtype=np.int16)
    rows = np.arange(a_count)
    for g in range(g_count):
        guess = guesses[g]
        res = np.zeros((a_count, WORD_LEN), dtype=np.int8)
        green = answers == guess[None, :]  # (A, 5)
        res[green] = GREEN

        # Remaining answer-letter counts after removing green-matched positions.
        counts = np.zeros((a_count, 26), dtype=np.int8)
        for i in range(WORD_LEN):
            keep = ~green[:, i]
            np.add.at(counts, (rows[keep], answers[keep, i]), 1)

        # Yellows: left to right, consume a remaining occurrence if available.
        for i in range(WORD_LEN):
            gi = int(guess[i])
            mask = (~green[:, i]) & (counts[:, gi] > 0)
            res[mask, i] = YELLOW
            counts[mask, gi] -= 1

        res16 = res.astype(np.int16)  # avoid int8 overflow: 2*81 > 127
        out[g] = (
            res16[:, 0] * 81
            + res16[:, 1] * 27
            + res16[:, 2] * 9
            + res16[:, 3] * 3
            + res16[:, 4]
        )
    return out
