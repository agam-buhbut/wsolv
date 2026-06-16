"""Validate a serialized decision tree by replaying every answer.

The replay is independent of the builder: for each answer we walk the parsed
node table from the root, applying the scalar :func:`feedback.feedback_code`
rule and following the child for the observed code until the solved sentinel
(``242``) is produced. This proves the tree resolves every answer, never
references a missing child, and matches the recorded metrics.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from . import feedback
from .serialize import ParsedTree, parse_tree

__all__ = ["ValidationError", "ValidationReport", "validate_tree", "validate_file"]


class ValidationError(Exception):
    """Raised when a serialized tree fails validation."""


@dataclass(slots=True)
class ValidationReport:
    """Aggregate outcome of a successful validation."""

    answer_count: int
    max_depth: int
    total_guesses: int
    mean: float


def _replay(tree: ParsedTree, allowed: list[str], answer: str, max_depth: int) -> int:
    """Replay one answer through the tree and return its guess depth.

    Raises:
        ValidationError: On an out-of-range guess index, a missing child for an
            observed feedback code, or exceeding ``max_depth`` (a cycle/too-deep
            guard).
    """
    index = 0
    depth = 0
    while True:
        node = tree.nodes[index]
        if not 0 <= node.guess < len(allowed):
            raise ValidationError(
                f"node {index} guess index {node.guess} out of range "
                f"[0,{len(allowed)})"
            )
        depth += 1
        if depth > max_depth:
            raise ValidationError(
                f"answer {answer!r} not solved within max_depth={max_depth}"
            )
        code = feedback.feedback_code(allowed[node.guess], answer)
        if code == feedback.SOLVED:
            return depth
        child = node.children.get(code)
        if child is None:
            raise ValidationError(
                f"answer {answer!r}: no child for feedback code {code} at node "
                f"{index} (guess {allowed[node.guess]!r})"
            )
        index = child


def validate_tree(
    tree: ParsedTree,
    allowed: list[str],
    answers: list[str],
    *,
    expected_max_depth: int | None = None,
    expected_total: int | None = None,
    expected_mean: float | None = None,
) -> ValidationReport:
    """Validate a parsed tree against the word lists and optional expectations.

    Args:
        tree: The parsed tree to validate.
        allowed: The allowed-guess list (guess indices reference this order).
        answers: Every answer the tree must resolve.
        expected_max_depth: If given, the replayed max depth must equal it.
        expected_total: If given, the replayed total guess count must equal it.
        expected_mean: If given, the replayed mean must match within 1e-9.

    Returns:
        A :class:`ValidationReport` with the recomputed metrics.

    Raises:
        ValidationError: On any structural or metric inconsistency.
    """
    if tree.allowed_count != len(allowed):
        raise ValidationError(
            f"header allowed_count {tree.allowed_count} != list length "
            f"{len(allowed)}"
        )
    if not tree.nodes:
        raise ValidationError("empty node table")

    # Structural guard: every child index is in range (parse_tree also checks,
    # but validating a hand-built ParsedTree should not depend on that path).
    node_count = len(tree.nodes)
    for i, node in enumerate(tree.nodes):
        for code, child_index in node.children.items():
            if code == feedback.SOLVED:
                raise ValidationError(
                    f"node {i} stores reserved solved code {feedback.SOLVED}"
                )
            if not 0 <= child_index < node_count:
                raise ValidationError(
                    f"node {i} child code {code} -> dangling index {child_index}"
                )

    # A generous replay cap independent of the expected depth, so an unsolved
    # answer is reported as such rather than looping forever.
    cap = expected_max_depth if expected_max_depth is not None else node_count

    max_depth = 0
    total = 0
    for answer in answers:
        depth = _replay(tree, allowed, answer, cap)
        max_depth = max(max_depth, depth)
        total += depth

    mean = total / len(answers) if answers else 0.0

    if expected_max_depth is not None and max_depth != expected_max_depth:
        raise ValidationError(
            f"max_depth mismatch: replayed {max_depth} != expected "
            f"{expected_max_depth}"
        )
    if expected_total is not None and total != expected_total:
        raise ValidationError(
            f"total_guesses mismatch: replayed {total} != expected {expected_total}"
        )
    if expected_mean is not None and abs(mean - expected_mean) > 1e-9:
        raise ValidationError(
            f"mean mismatch: replayed {mean} != expected {expected_mean}"
        )

    return ValidationReport(
        answer_count=len(answers),
        max_depth=max_depth,
        total_guesses=total,
        mean=mean,
    )


def validate_file(
    bin_path: Path,
    allowed: list[str],
    answers: list[str],
    *,
    expected_max_depth: int | None = None,
    expected_total: int | None = None,
    expected_mean: float | None = None,
) -> ValidationReport:
    """Parse a tree binary from disk and validate it. See :func:`validate_tree`."""
    tree = parse_tree(bin_path.read_bytes())
    return validate_tree(
        tree,
        allowed,
        answers,
        expected_max_depth=expected_max_depth,
        expected_total=expected_total,
        expected_mean=expected_mean,
    )
