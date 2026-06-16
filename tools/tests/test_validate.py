"""Tests for tree validation (replay every answer)."""

from __future__ import annotations

import pytest

from wsolv_precompute import feedback
from wsolv_precompute.build_tree import build_tree
from wsolv_precompute.serialize import (
    ParsedNode,
    ParsedTree,
    parse_tree,
    serialize_tree,
)
from wsolv_precompute.validate import (
    ValidationError,
    validate_tree,
)

SMALL = ["crane", "slate", "trace", "blimp", "ghost", "vodka"]


def test_validate_accepts_good_tree() -> None:
    result = build_tree(SMALL, SMALL, first_word="crane", max_depth=6, top_k=None)
    tree = parse_tree(serialize_tree(result))
    report = validate_tree(
        tree,
        SMALL,
        SMALL,
        expected_max_depth=result.max_depth,
        expected_total=result.total_depth,
        expected_mean=result.mean,
    )
    assert report.answer_count == len(SMALL)
    assert report.max_depth == result.max_depth
    assert report.total_guesses == result.total_depth


def test_validate_rejects_unsolved_answer() -> None:
    # A lone root guessing "crane" solves "crane" but nothing else.
    crane = SMALL.index("crane")
    tree = ParsedTree(
        version=1, allowed_count=len(SMALL), nodes=[ParsedNode(crane, {})]
    )
    # "crane" alone is fine...
    validate_tree(tree, SMALL, ["crane"])
    # ...but "slate" yields a non-solved code with no child to follow.
    with pytest.raises(ValidationError):
        validate_tree(tree, SMALL, ["crane", "slate"])


def test_validate_rejects_dangling_child_index() -> None:
    crane = SMALL.index("crane")
    slate_code = feedback.feedback_code("crane", "slate")
    tree = ParsedTree(
        version=1,
        allowed_count=len(SMALL),
        nodes=[ParsedNode(crane, {slate_code: 5})],  # index 5 does not exist (1 node)
    )
    with pytest.raises(ValidationError):
        validate_tree(tree, SMALL, ["slate"])


def test_validate_rejects_out_of_range_guess_index() -> None:
    tree = ParsedTree(
        version=1, allowed_count=len(SMALL), nodes=[ParsedNode(99999, {})]
    )
    with pytest.raises(ValidationError):
        validate_tree(tree, SMALL, ["crane"])


def test_validate_rejects_stored_solved_code() -> None:
    crane = SMALL.index("crane")
    tree = ParsedTree(
        version=1,
        allowed_count=len(SMALL),
        nodes=[ParsedNode(crane, {feedback.SOLVED: 0})],
    )
    with pytest.raises(ValidationError):
        validate_tree(tree, SMALL, ["crane"])


def test_validate_rejects_allowed_count_mismatch() -> None:
    crane = SMALL.index("crane")
    tree = ParsedTree(version=1, allowed_count=999, nodes=[ParsedNode(crane, {})])
    with pytest.raises(ValidationError):
        validate_tree(tree, SMALL, ["crane"])


def test_validate_rejects_wrong_expected_metric() -> None:
    result = build_tree(SMALL, SMALL, first_word="crane", max_depth=6, top_k=None)
    tree = parse_tree(serialize_tree(result))
    with pytest.raises(ValidationError):
        validate_tree(tree, SMALL, SMALL, expected_total=result.total_depth + 1)
