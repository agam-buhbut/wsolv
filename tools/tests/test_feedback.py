"""Tests for the shared feedback rule and base-3 codec."""

from __future__ import annotations

import random

import numpy as np
import pytest

from wsolv_precompute import feedback


@pytest.mark.parametrize(
    ("guess", "answer", "expected"),
    [
        ("level", "lever", (2, 2, 2, 2, 0)),  # trailing duplicate L -> gray
        ("eerie", "there", (1, 0, 1, 0, 2)),  # one E yellow, one E gray
        ("speed", "erase", (1, 0, 1, 1, 0)),  # leading S yellow (erase has an S)
        ("argue", "argue", (2, 2, 2, 2, 2)),  # all green
        ("xylyl", "abate", (0, 0, 0, 0, 0)),  # all gray
        ("crane", "slate", (0, 0, 2, 0, 2)),  # shared A and E land in place
    ],
)
def test_score_vectors(guess: str, answer: str, expected: tuple[int, ...]) -> None:
    assert feedback.score(guess, answer) == expected


def test_argue_is_solved_sentinel() -> None:
    assert feedback.feedback_code("argue", "argue") == feedback.SOLVED
    assert feedback.encode((2, 2, 2, 2, 2)) == feedback.SOLVED == 242


def test_all_gray_code_is_zero() -> None:
    assert feedback.feedback_code("xylyl", "abate") == 0


def test_encode_decode_roundtrip() -> None:
    for code in range(feedback.NUM_PATTERNS):
        assert feedback.encode(feedback.decode(code)) == code


def test_encode_position_weights() -> None:
    # Position 0 is the most-significant trit.
    assert feedback.encode((1, 0, 0, 0, 0)) == 81
    assert feedback.encode((0, 0, 0, 0, 1)) == 1


def test_score_rejects_wrong_length() -> None:
    with pytest.raises(ValueError):
        feedback.score("abcd", "abcde")
    with pytest.raises(ValueError):
        feedback.score("abcde", "abc")


def test_decode_rejects_out_of_range() -> None:
    with pytest.raises(ValueError):
        feedback.decode(feedback.NUM_PATTERNS)
    with pytest.raises(ValueError):
        feedback.decode(-1)


def test_words_to_array() -> None:
    arr = feedback.words_to_array(["abcde", "zzzzz"])
    assert arr.shape == (2, 5)
    assert arr.dtype == np.int8
    assert list(arr[0]) == [0, 1, 2, 3, 4]
    assert list(arr[1]) == [25, 25, 25, 25, 25]


def test_pattern_matrix_matches_scalar() -> None:
    """Property: the vectorized matrix agrees with the scalar rule everywhere."""
    rng = random.Random(20240607)
    pool = [
        "".join(rng.choice("abcde") for _ in range(5))  # tiny alphabet -> many dups
        for _ in range(40)
    ]
    guesses = pool[:20]
    answers = pool[20:]
    matrix = feedback.pattern_matrix(
        feedback.words_to_array(guesses), feedback.words_to_array(answers)
    )
    assert matrix.shape == (len(guesses), len(answers))
    for gi, guess in enumerate(guesses):
        for ai, answer in enumerate(answers):
            assert int(matrix[gi, ai]) == feedback.feedback_code(guess, answer)
