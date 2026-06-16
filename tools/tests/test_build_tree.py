"""Tests for the decision-tree solver.

Correctness is checked against an independent, unoptimized brute-force solver
on tiny instances where exhaustive search is cheap, plus a determinism check
(same input -> identical serialized bytes) and the trivial single-answer case.
"""

from __future__ import annotations

import numpy as np

from wsolv_precompute import feedback
from wsolv_precompute.build_tree import Node, build_tree
from wsolv_precompute.serialize import serialize_tree


def _matrix(allowed: list[str], answers: list[str]) -> np.ndarray:
    return feedback.pattern_matrix(
        feedback.words_to_array(allowed), feedback.words_to_array(answers)
    )


def _brute_optimal(
    matrix: np.ndarray, subset: tuple[int, ...], budget: int
) -> tuple[int, int] | None:
    """Independent exhaustive optimum: returns (max_depth, total_depth) or None.

    No heuristics, no pruning, no memo across calls -- the simplest possible
    reference implementation, used only on tiny instances.
    """
    if len(subset) == 1:
        return (1, 1)
    if budget <= 1:
        return None
    g_count = matrix.shape[0]
    best: tuple[int, int] | None = None
    for guess in range(g_count):
        codes = [int(matrix[guess, a]) for a in subset]
        buckets: dict[int, list[int]] = {}
        for a, code in zip(subset, codes, strict=True):
            buckets.setdefault(code, []).append(a)
        # No progress if the only bucket is the whole subset and it is unsolved.
        if len(buckets) == 1 and feedback.SOLVED not in buckets:
            continue
        node_max = 1
        node_total = len(subset)
        feasible = True
        for code, group in buckets.items():
            if code == feedback.SOLVED:
                continue
            child = _brute_optimal(matrix, tuple(group), budget - 1)
            if child is None:
                feasible = False
                break
            node_max = max(node_max, 1 + child[0])
            node_total += child[1]
        if not feasible:
            continue
        cand = (node_max, node_total)
        if best is None or cand < best:
            best = cand
    return best


def _measure_replay(
    node: Node, allowed: list[str], answers: list[str]
) -> tuple[int, int]:
    """Replay each answer through a built tree -> (max_depth, total_depth)."""
    max_depth = 0
    total = 0
    for answer in answers:
        cur = node
        depth = 0
        while True:
            depth += 1
            code = feedback.feedback_code(allowed[cur.guess], answer)
            if code == feedback.SOLVED:
                break
            cur = cur.children[code]
        max_depth = max(max_depth, depth)
        total += depth
    return max_depth, total


SMALL = ["crane", "slate", "trace", "blimp", "ghost", "vodka"]


def test_solver_matches_brute_force() -> None:
    allowed = SMALL
    answers = SMALL
    matrix = _matrix(allowed, answers)
    ref = _brute_optimal(matrix, tuple(range(len(answers))), budget=6)
    assert ref is not None

    result = build_tree(
        allowed,
        answers,
        first_word="search",  # let the solver pick the root too
        max_depth=6,
        top_k=None,  # full search -> must hit the true optimum
    )
    assert (result.max_depth, result.total_depth) == ref


def test_solver_matches_brute_force_fixed_opener() -> None:
    allowed = SMALL
    answers = SMALL
    matrix = _matrix(allowed, answers)

    # Reference: best tree whose root guess is forced to "crane".
    opener = allowed.index("crane")
    codes = [int(matrix[opener, a]) for a in range(len(answers))]
    buckets: dict[int, list[int]] = {}
    for a, code in zip(range(len(answers)), codes, strict=True):
        buckets.setdefault(code, []).append(a)
    ref_max, ref_total = 1, len(answers)
    for code, group in buckets.items():
        if code == feedback.SOLVED:
            continue
        child = _brute_optimal(matrix, tuple(group), budget=5)
        assert child is not None
        ref_max = max(ref_max, 1 + child[0])
        ref_total += child[1]

    result = build_tree(allowed, answers, first_word="crane", max_depth=6, top_k=None)
    assert (result.max_depth, result.total_depth) == (ref_max, ref_total)
    # The built tree, replayed, reproduces its own metrics.
    assert _measure_replay(result.root, allowed, answers) == (
        result.max_depth,
        result.total_depth,
    )


def test_single_answer_is_a_leaf() -> None:
    result = build_tree(["crane"], ["crane"], first_word="crane", max_depth=6)
    assert result.max_depth == 1
    assert result.total_depth == 1
    assert result.node_count == 1
    assert result.root.children == {}


def test_lookahead_never_worse_than_greedy() -> None:
    allowed = SMALL
    answers = SMALL
    greedy = build_tree(
        allowed, answers, first_word="crane", max_depth=6, top_k=None, greedy=True
    )
    look = build_tree(
        allowed,
        answers,
        first_word="crane",
        max_depth=6,
        top_k=None,
        lookahead_k=5,
    )
    # Lookahead commits the best-rolled-out candidate, so its total (and thus
    # mean) is never worse than the first-feasible greedy choice.
    assert look.total_depth <= greedy.total_depth
    # Stays within the depth budget and resolves every answer.
    assert look.max_depth <= 6
    assert look.answer_count == len(answers)
    # Replaying the built tree reproduces its own metrics.
    assert _measure_replay(look.root, allowed, answers) == (
        look.max_depth,
        look.total_depth,
    )


def test_lookahead_deterministic() -> None:
    allowed = SMALL
    answers = SMALL
    a = build_tree(
        allowed, answers, first_word="crane", max_depth=6, top_k=None, lookahead_k=4
    )
    b = build_tree(
        allowed, answers, first_word="crane", max_depth=6, top_k=None, lookahead_k=4
    )
    assert (a.max_depth, a.total_depth, a.node_count) == (
        b.max_depth,
        b.total_depth,
        b.node_count,
    )
    assert serialize_tree(a) == serialize_tree(b)


def test_deterministic_bytes() -> None:
    allowed = SMALL
    answers = SMALL
    a = build_tree(allowed, answers, first_word="crane", max_depth=6, top_k=None)
    b = build_tree(allowed, answers, first_word="crane", max_depth=6, top_k=None)
    assert (a.max_depth, a.total_depth, a.node_count) == (
        b.max_depth,
        b.total_depth,
        b.node_count,
    )
    assert serialize_tree(a) == serialize_tree(b)
