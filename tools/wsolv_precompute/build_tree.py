"""Build the lexicographically-optimal Wordle decision tree.

Optimality (LOCKED): the primary objective is to minimize the maximum leaf
depth (worst-case guess count); the secondary objective is to minimize the
total leaf depth (sum over answers of the number of guesses to reach each).
The opener is fixed to ``salet`` by default.

Depth accounting: the depth of a leaf is the number of guesses needed to
reach that answer, so a one-element subset costs ``1`` and the root guess
counts as depth ``1`` for every answer. For a node over subset ``S`` this
gives the clean recurrences::

    total_depth(node) = |S| + sum(child.total_depth for each non-solved bucket)
    max_depth(node)   = 1                                   if |S| == 1
                      = 1 + max(child.max_depth over buckets) otherwise

The full ``(G, A)`` feedback matrix is cached to ``out/pattern_matrix.npy``,
keyed by a hash of the two word lists so a stale cache is ignored.
"""

from __future__ import annotations

import hashlib
import sys
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
from numpy.typing import NDArray

from . import feedback

__all__ = [
    "Node",
    "BuildResult",
    "load_words",
    "build_pattern_matrix",
    "build_tree",
    "DEFAULT_FIRST_WORD",
    "DEFAULT_MAX_DEPTH",
    "DEFAULT_TOP_K",
    "DEFAULT_FULL_THRESHOLD",
    "DEFAULT_SHORTLIST",
    "HARD_DEPTH_CAP",
]

DEFAULT_FIRST_WORD = "salet"
DEFAULT_MAX_DEPTH = 5
DEFAULT_TOP_K = 120
DEFAULT_FULL_THRESHOLD = 15  # nodes with |S| <= this always search all of the pool
# Per-node candidate pool = `subset ∪ shortlist`. By default (0) the shortlist is
# the whole guess list, so every node can reach any probe word it needs to stay
# within the depth budget (worst-case 5 requires this). A positive value caps the
# shortlist to the globally strongest probes by entropy — faster but can make a
# tight depth budget infeasible, so it is opt-in.
DEFAULT_SHORTLIST = 0
HARD_DEPTH_CAP = 6  # safety net; the produced tree must come in at/under budget


_UNSET = object()  # sentinel distinguishing "absent" from a cached ``None`` rollout


class BuildError(Exception):
    """Raised when the tree cannot be built (infeasible or inconsistent)."""


@dataclass(slots=True)
class Node:
    """A decision-tree node.

    Attributes:
        guess: Index of the played guess into the allowed-word order.
        children: Map from feedback code (0..241) to the child node reached
            when that feedback is observed. Empty for a leaf.
        self_solves: Whether this node's guess is itself one of the answers in
            its subset (i.e. a code-242 bucket exists), solving one answer at
            this level. False for probe words that are not possible answers.
            Metric-only; not serialized (runtime infers "solved" from code 242).
    """

    guess: int
    children: dict[int, Node] = field(default_factory=dict)
    self_solves: bool = True


def _copy_node(node: Node) -> Node:
    """Deep-copy a node and its subtree (children dict + recursive copies)."""
    copy = Node(
        node.guess,
        {code: _copy_node(child) for code, child in node.children.items()},
    )
    copy.self_solves = node.self_solves
    return copy


@dataclass(slots=True)
class BuildResult:
    """Result of a tree build: the root node plus aggregate metrics."""

    root: Node
    max_depth: int
    total_depth: int
    mean: float
    node_count: int
    answer_count: int
    allowed_count: int
    first_word: str
    restricted: bool


def load_words(path: Path) -> list[str]:
    """Load a word list: one lowercase word per line, blanks ignored."""
    text = path.read_text(encoding="utf-8")
    return [line.strip().lower() for line in text.splitlines() if line.strip()]


def _lists_hash(allowed: list[str], answers: list[str]) -> str:
    """Stable hash of the two word lists, for cache invalidation."""
    h = hashlib.sha256()
    h.update(b"allowed\n")
    h.update("\n".join(allowed).encode("utf-8"))
    h.update(b"\nanswers\n")
    h.update("\n".join(answers).encode("utf-8"))
    return h.hexdigest()


def build_pattern_matrix(
    allowed: list[str],
    answers: list[str],
    cache_path: Path | None = None,
) -> NDArray[np.int16]:
    """Build (or load from cache) the ``(G, A)`` feedback-code matrix.

    The cache is a ``.npy`` file plus a sidecar ``.hash`` holding the hash of
    the two word lists; a mismatch (stale cache) triggers a rebuild.

    Args:
        allowed: The allowed-guess list (rows of the matrix, the ``G`` axis).
        answers: The answer list (columns of the matrix, the ``A`` axis).
        cache_path: Optional path to the ``.npy`` cache file.

    Returns:
        ``(len(allowed), len(answers))`` int16 matrix of base-3 feedback codes.
    """
    want = _lists_hash(allowed, answers)
    if cache_path is not None:
        hash_path = cache_path.with_suffix(".hash")
        if cache_path.exists() and hash_path.exists():
            if hash_path.read_text(encoding="utf-8").strip() == want:
                cached: NDArray[np.int16] = np.load(cache_path)
                if cached.shape == (len(allowed), len(answers)):
                    print(
                        f"loaded cached pattern matrix {cached.shape} "
                        f"from {cache_path}",
                        file=sys.stderr,
                    )
                    return cached
            print("pattern-matrix cache stale; rebuilding", file=sys.stderr)

    print(
        f"building pattern matrix ({len(allowed)} x {len(answers)})...",
        file=sys.stderr,
    )
    g_arr = feedback.words_to_array(allowed)
    a_arr = feedback.words_to_array(answers)
    matrix = feedback.pattern_matrix(g_arr, a_arr)

    if cache_path is not None:
        cache_path.parent.mkdir(parents=True, exist_ok=True)
        np.save(cache_path, matrix)
        cache_path.with_suffix(".hash").write_text(want, encoding="utf-8")
        print(f"cached pattern matrix -> {cache_path}", file=sys.stderr)
    return matrix


def _partition(
    matrix: NDArray[np.int16], guess: int, subset: NDArray[np.intp]
) -> tuple[NDArray[np.int16], list[NDArray[np.intp]]]:
    """Split ``subset`` by the feedback code of ``guess``.

    Returns the sorted unique codes and, parallel to them, the answer-index
    array for each code group. The code ``242`` (solved) group, if present,
    is included; callers handle it specially.
    """
    codes = matrix[guess, subset]
    order = np.argsort(codes, kind="stable")
    sorted_codes = codes[order]
    sorted_subset = subset[order]
    uniq, starts = np.unique(sorted_codes, return_index=True)
    groups = np.split(sorted_subset, starts[1:])
    return uniq, groups


def _candidate_stats(
    matrix: NDArray[np.int16], guesses: NDArray[np.intp], subset: NDArray[np.intp]
) -> tuple[NDArray[np.intp], NDArray[np.intp], NDArray[np.float64]]:
    """Vectorized split statistics for many candidate guesses at once.

    Args:
        matrix: The full feedback matrix.
        guesses: Candidate guess indices to evaluate.
        subset: The answer-index subset being split.

    Returns:
        Three arrays parallel to ``guesses``: the number of non-empty buckets,
        the largest bucket size, and the Shannon entropy of the split.

    Fully vectorized: a single offset ``bincount`` builds the per-guess code
    histogram for every candidate at once, so cost does not pay a Python-loop
    tax per guess (which is otherwise crippling at ~15k guesses per node).
    """
    codes = matrix[np.ix_(guesses, subset)].astype(np.int64)  # (C, |S|)
    c = codes.shape[0]
    n = subset.shape[0]
    p = feedback.NUM_PATTERNS

    # Histogram every row independently by offsetting each row into its own band
    # of [0, p) bins, then one bincount over C*p bins.
    offsets = np.arange(c, dtype=np.int64)[:, None] * p
    counts = np.bincount((codes + offsets).ravel(), minlength=c * p).reshape(c, p)

    nz = counts > 0
    num_buckets = nz.sum(axis=1).astype(np.intp)
    max_bucket = counts.max(axis=1).astype(np.intp)
    probs = counts / n
    logs = np.zeros_like(probs)
    np.log2(probs, where=nz, out=logs)
    entropy = -(np.where(nz, probs * logs, 0.0)).sum(axis=1)
    return num_buckets, max_bucket, entropy.astype(np.float64)


def _ordered_candidates(
    matrix: NDArray[np.int16],
    subset: NDArray[np.intp],
    pool: NDArray[np.intp],
    top_k: int | None,
    full_threshold: int,
    node_log: list[str],
) -> NDArray[np.intp]:
    """Return candidate guesses for a node, ordered best-first.

    Ordering key (ascending) is ``(-num_buckets, max_bucket, -entropy)`` so the
    guesses that split hardest are explored first. ``pool`` is the node's
    candidate set (``subset ∪ shortlist``). Small nodes consider the whole pool;
    wide nodes may be further restricted to ``subset ∪ top-K`` (logged).
    """
    n = subset.shape[0]
    restrict = top_k is not None and n > full_threshold

    num_buckets, max_bucket, entropy = _candidate_stats(matrix, pool, subset)
    key = np.lexsort((-entropy, max_bucket, -num_buckets))
    ordered = pool[key]

    if not restrict:
        return ordered

    assert top_k is not None  # narrowed by `restrict`
    top = ordered[:top_k]
    # Always keep the answers themselves: a guess equal to an answer can solve
    # its own one-element bucket in place, which a heuristic top-K might drop.
    candidates = np.union1d(top, subset)
    node_log.append(
        f"restricted node |S|={n} to {candidates.shape[0]} candidates (top_k={top_k})"
    )
    print(
        f"  [restrict] node |S|={n} -> {candidates.shape[0]} candidates "
        f"(top_k={top_k})",
        file=sys.stderr,
    )
    return candidates


class _Solver:
    """Recursive lexicographic solver over answer-index subsets.

    A single instance owns the feedback matrix, the memo table and the search
    knobs. ``solve`` returns ``(max_depth, total_depth, node)`` for the optimal
    subtree over a subset, or ``None`` if no subtree fits the budget.
    """

    def __init__(
        self,
        matrix: NDArray[np.int16],
        answer_to_allowed: NDArray[np.intp],
        top_k: int | None,
        full_threshold: int,
        shortlist_size: int = DEFAULT_SHORTLIST,
        greedy: bool = False,
        lookahead_k: int = 0,
    ) -> None:
        self._matrix = matrix
        # Maps an answer-column index (matrix col / subset value) to the
        # allowed-row index of the same word, so leaves store a real guess index.
        self._answer_to_allowed = answer_to_allowed
        self._top_k = top_k
        self._full_threshold = full_threshold
        self._greedy = greedy
        self._lookahead_k = lookahead_k
        self._all_guesses = np.arange(matrix.shape[0], dtype=np.intp)
        self._memo: dict[tuple[bytes, int], tuple[int, int, Node] | None] = {}
        # Separate, lightweight memo for greedy rollout scoring (no Node objects),
        # so lookahead's per-node best-of-K scoring stays cheap.
        self._rollout_memo: dict[tuple[bytes, int], tuple[int, int] | None] = {}
        self.node_log: list[str] = []
        self._shortlist = self._compute_shortlist(shortlist_size)

    def _compute_shortlist(self, size: int) -> NDArray[np.intp]:
        """Globally strongest probe words by entropy over the full answer set.

        Computed once. Per-node candidate pools are ``subset ∪ shortlist``. With
        ``size <= 0`` (or larger than the guess list) the shortlist is every
        guess, so any probe word remains reachable — required for the worst-case
        depth budget to be feasible. A positive ``size`` trades feasibility head-
        room for speed.
        """
        all_g = self._all_guesses
        if size <= 0 or size >= all_g.shape[0]:
            return all_g
        full_answers = np.arange(self._matrix.shape[1], dtype=np.intp)
        _, _, entropy = _candidate_stats(self._matrix, all_g, full_answers)
        order = np.argsort(-entropy, kind="stable")
        return all_g[order[:size]]

    def _pool(self, subset: NDArray[np.intp]) -> NDArray[np.intp]:
        """Candidate pool for a node: still-possible answers plus the shortlist."""
        return np.union1d(subset, self._shortlist)

    def solve(
        self, subset: NDArray[np.intp], budget: int
    ) -> tuple[int, int, Node] | None:
        """Optimal subtree for ``subset`` within ``budget`` remaining guesses.

        Returns ``(max_depth, total_depth, node)`` measured from this node
        inclusive, or ``None`` if ``subset`` cannot be resolved in ``budget``.
        """
        n = subset.shape[0]
        if n == 1:
            # Leaf: play the answer word itself. subset holds answer-column
            # indices; the guess must be that word's allowed-row index.
            return 1, 1, Node(int(self._answer_to_allowed[subset[0]]))
        if budget <= 1:
            return None  # more than one answer left but no guesses to spend

        key = (np.sort(subset).tobytes(), budget)
        if key in self._memo:
            cached = self._memo[key]
            if cached is None:
                return None
            # Return a fresh subtree so the output is a strict tree (no Node is
            # ever aliased across parents), which keeps node-counting and
            # serialization unambiguous.
            cmax, ctotal, cnode = cached
            return cmax, ctotal, _copy_node(cnode)

        result = self._search(subset, budget)
        self._memo[key] = result
        return result

    def solve_forced(
        self, guess: int, subset: NDArray[np.intp], budget: int
    ) -> tuple[int, int, Node] | None:
        """Build a subtree that forces ``guess`` as the first play.

        Used to pin the opener at the root without searching the root level.
        Equivalent to evaluating the single candidate ``guess`` with no
        incumbent to prune against.
        """
        return self._eval_guess(guess, subset, budget, None)

    def _search(
        self, subset: NDArray[np.intp], budget: int
    ) -> tuple[int, int, Node] | None:
        candidates = _ordered_candidates(
            self._matrix,
            subset,
            self._pool(subset),
            self._top_k,
            self._full_threshold,
            self.node_log,
        )

        if self._lookahead_k > 0:
            return self._lookahead_search(subset, budget, candidates)

        if self._greedy:
            # Greedy: take the first guess (in best-splitter order) that yields a
            # feasible subtree within budget. Worst-case depth is still optimal —
            # with budget = the proven minimum (5), any feasible tree has that
            # exact max depth — but the mean is heuristic, not provably minimal.
            # This is the tractable path; the exhaustive search above is optimal
            # but intractable at full scale.
            for guess in candidates:
                evaluated = self._eval_guess(int(guess), subset, budget, None)
                if evaluated is not None:
                    return evaluated
            return None

        best: tuple[int, int, Node] | None = None
        best_key: tuple[int, int] | None = None
        for guess in candidates:
            evaluated = self._eval_guess(int(guess), subset, budget, best)
            if evaluated is None:
                continue
            cand_key = (evaluated[0], evaluated[1])
            if best_key is None or cand_key < best_key:
                best = evaluated
                best_key = cand_key
                # The known global optimum for this problem is max-depth 5 with
                # a 1-guess solved bucket present; a node that already reaches
                # the theoretical floor cannot be beaten, so stop early.
                if cand_key[0] == 2 and self._is_total_floor(subset, cand_key[1]):
                    break
        return best

    def _lookahead_search(
        self,
        subset: NDArray[np.intp],
        budget: int,
        candidates: NDArray[np.intp],
    ) -> tuple[int, int, Node] | None:
        """Bounded best-of-K lookahead: commit the best-rolled-out top candidate.

        Score the top-K candidates (heuristic order) by a cheap greedy rollout
        yielding ``(rollout_max_depth, rollout_total)``; skip any whose rollout is
        infeasible within ``budget``. Commit to the lexicographically smallest
        ``(rollout_max_depth, rollout_total)`` and BUILD only that guess by
        recursing into its non-solved buckets. The greedy rollout proves the
        committed guess is feasible within ``budget``, so the recursive build —
        which itself only commits feasible children — stays within budget too.
        This keeps the worst case at the proven floor while lowering the mean.
        """
        best_guess: int | None = None
        best_score: tuple[int, int] | None = None
        for guess in candidates[: self._lookahead_k]:
            rollout = self._greedy_eval(int(guess), subset, budget)
            if rollout is None:
                continue
            if best_score is None or rollout < best_score:
                best_score = rollout
                best_guess = int(guess)
        if best_guess is not None:
            committed = self._build_committed(best_guess, subset, budget)
            if committed is not None:
                return committed

        # Fallback: none of the top-K rolled out feasibly within budget (or the
        # commit failed because a child's own top-K was infeasible). The proven
        # depth floor needs a deeper probe than the heuristic top-K surfaces here,
        # so revert to the greedy first-feasible scan over ALL candidates. This is
        # what preserves the worst-case guarantee: lookahead never gives up on a
        # node the greedy solver could resolve, it only improves the mean when it
        # can. The greedy children it builds are still re-solved with lookahead on
        # the next recursion level.
        for guess in candidates:
            evaluated = self._eval_guess(int(guess), subset, budget, None)
            if evaluated is not None:
                return evaluated
        return None

    def _build_committed(
        self, guess: int, subset: NDArray[np.intp], budget: int
    ) -> tuple[int, int, Node] | None:
        """Build the committed guess's node by recursing ``solve`` on each bucket.

        Mirrors :meth:`_eval_guess` but without pruning: the guess is fixed, and
        children are resolved with the full solver (lookahead included) so each
        bucket gets its own best-of-K decision.
        """
        uniq, groups = _partition(self._matrix, guess, subset)
        n = subset.shape[0]
        node = Node(guess)
        node.self_solves = bool((uniq == feedback.SOLVED).any())
        node_max = 1
        node_total = n
        for code, group in zip(uniq, groups, strict=True):
            if int(code) == feedback.SOLVED:
                continue
            child = self.solve(group, budget - 1)
            if child is None:
                return None
            child_max, child_total, child_node = child
            node_max = max(node_max, 1 + child_max)
            node_total += child_total
            node.children[int(code)] = child_node
        return node_max, node_total, node

    def _greedy_eval(
        self, guess: int, subset: NDArray[np.intp], budget: int
    ) -> tuple[int, int] | None:
        """Cheap greedy rollout cost of playing ``guess`` at this node.

        Returns ``(max_depth, total_depth)`` for the subtree built by recursively
        taking the first feasible best-splitter guess (the greedy path) under each
        bucket, or ``None`` if infeasible within ``budget``. Used only to SCORE a
        candidate during lookahead; it does not build or memoize a node.
        """
        uniq, groups = _partition(self._matrix, guess, subset)
        if uniq.shape[0] == 1 and int(uniq[0]) != feedback.SOLVED:
            return None
        node_max = 1
        node_total = subset.shape[0]
        for code, group in zip(uniq, groups, strict=True):
            if int(code) == feedback.SOLVED:
                continue
            child = self._greedy_rollout(group, budget - 1)
            if child is None:
                return None
            child_max, child_total = child
            node_max = max(node_max, 1 + child_max)
            node_total += child_total
        return node_max, node_total

    def _greedy_rollout(
        self, subset: NDArray[np.intp], budget: int
    ) -> tuple[int, int] | None:
        """Metrics ``(max_depth, total_depth)`` of a greedy subtree over ``subset``.

        First-feasible best-splitter rollout: no node objects are built and the
        result is not memoized into the real ``solve`` table (it lives in a small
        dedicated rollout memo), so scoring stays cheap.
        """
        n = subset.shape[0]
        if n == 1:
            return 1, 1
        if budget <= 1:
            return None
        key = (np.sort(subset).tobytes(), budget)
        cached = self._rollout_memo.get(key, _UNSET)
        if cached is not _UNSET:
            return cached  # type: ignore[return-value]
        candidates = _ordered_candidates(
            self._matrix,
            subset,
            self._pool(subset),
            self._top_k,
            self._full_threshold,
            self.node_log,
        )
        result: tuple[int, int] | None = None
        for guess in candidates:
            evaluated = self._greedy_eval(int(guess), subset, budget)
            if evaluated is not None:
                result = evaluated
                break
        self._rollout_memo[key] = result
        return result

    @staticmethod
    def _is_total_floor(subset: NDArray[np.intp], total: int) -> bool:
        """Whether ``total`` is the unbeatable floor for a depth-2 subtree.

        With max-depth 2 the best possible total is ``2*|S| - 1`` (every answer
        costs 2 except the single one solved by the node's own guess).
        """
        return total == 2 * subset.shape[0] - 1

    def _eval_guess(
        self,
        guess: int,
        subset: NDArray[np.intp],
        budget: int,
        best: tuple[int, int, Node] | None,
    ) -> tuple[int, int, Node] | None:
        """Evaluate one candidate guess, with alpha-beta pruning on ``best``.

        Returns the resulting ``(max_depth, total_depth, node)`` for playing
        ``guess`` at this node, or ``None`` if infeasible or provably worse
        than ``best``.
        """
        uniq, groups = _partition(self._matrix, guess, subset)
        n = subset.shape[0]

        # A guess that fails to split the subset (single non-solved bucket equal
        # to the whole subset) makes no progress; skip it.
        if uniq.shape[0] == 1 and int(uniq[0]) != feedback.SOLVED:
            return None

        node = Node(guess)
        # A code-242 bucket means the guess is itself a still-possible answer,
        # solved here in one guess. Probe words (not possible answers) solve none.
        node.self_solves = bool((uniq == feedback.SOLVED).any())
        node_max = 1
        node_total = n  # every answer in S pays +1 for this guess
        best_max = best[0] if best is not None else None
        best_total = best[1] if best is not None else None

        for code, group in zip(uniq, groups, strict=True):
            if int(code) == feedback.SOLVED:
                continue  # solved here in one guess; already counted in node_total
            child = self.solve(group, budget - 1)
            if child is None:
                return None
            child_max, child_total, child_node = child
            node_max = max(node_max, 1 + child_max)
            node_total += child_total
            node.children[int(code)] = child_node

            # Alpha-beta: prune as soon as we are provably no better than best.
            if best_max is not None and best_total is not None:
                if node_max > best_max:
                    return None
                if node_max == best_max and node_total >= best_total:
                    return None

        return node_max, node_total, node


def _measure(node: Node) -> tuple[int, int, int, int]:
    """Recompute ``(max_depth, total_depth, node_count, answer_count)``.

    Independent of the solver bookkeeping, so the serialized/validated metrics
    can be cross-checked against the search output. The accounting mirrors the
    solver exactly::

        answer_count(node) = self_solves + sum(child answer_count)
        total_depth(node)  = answer_count + sum(child total_depth)
        max_depth(node)    = 1                              if leaf
                           = 1 + max(child max_depth)       otherwise

    ``self_solves`` is 1 when the node's guess is a possible answer (a code-242
    bucket solved in place) and 0 for a probe word, so probe nodes are not
    miscounted as solving an answer.
    """
    if not node.children:
        # Leaf: one answer, reached in one guess from here.
        return 1, 1, 1, 1
    max_depth = 1
    node_count = 1
    answer_count = 1 if node.self_solves else 0
    children_total = 0
    for child in node.children.values():
        child_max, child_total, child_count, child_answers = _measure(child)
        max_depth = max(max_depth, 1 + child_max)
        node_count += child_count
        answer_count += child_answers
        children_total += child_total
    total_depth = answer_count + children_total
    return max_depth, total_depth, node_count, answer_count


def _resolve_root(
    solver: _Solver,
    first_word: str,
    allowed_index: dict[str, int],
    full_subset: NDArray[np.intp],
    max_depth: int,
) -> tuple[int, int, Node]:
    """Build the root subtree, either searching the opener or forcing one.

    Raises:
        BuildError: If the opener is unknown or the root is infeasible.
    """
    if first_word == "search":
        print("searching root level over all guesses...", file=sys.stderr)
        result = solver.solve(full_subset, max_depth)
        if result is None:
            raise BuildError(f"infeasible within max_depth={max_depth}")
        return result

    if first_word not in allowed_index:
        raise BuildError(f"first word {first_word!r} not in allowed list")
    opener = allowed_index[first_word]
    print(f"fixing opener to {first_word!r} (index {opener})", file=sys.stderr)
    root_result = solver.solve_forced(opener, full_subset, max_depth)
    if root_result is None:
        raise BuildError(
            f"opener {first_word!r} infeasible within max_depth={max_depth}"
        )
    return root_result


def build_tree(  # pylint: disable=too-many-arguments
    # The builder is intentionally parameter-rich: one keyword per orthogonal
    # search knob (opener, depth budget, candidate caps, mode selectors). They are
    # all keyword-only with defaults, so callers stay readable.
    allowed: list[str],
    answers: list[str],
    *,
    first_word: str = DEFAULT_FIRST_WORD,
    max_depth: int = DEFAULT_MAX_DEPTH,
    top_k: int | None = DEFAULT_TOP_K,
    full_threshold: int = DEFAULT_FULL_THRESHOLD,
    shortlist_size: int = DEFAULT_SHORTLIST,
    greedy: bool = False,
    lookahead_k: int = 0,
    cache_path: Path | None = None,
) -> BuildResult:
    """Build the optimal decision tree and return the root plus metrics.

    Args:
        allowed: Allowed-guess list (A ⊆ G); guess indices reference this order.
        answers: Answer list the tree must fully resolve.
        first_word: Fixed opener, or the literal ``"search"`` to search the
            root level over all of ``G`` (slower).
        max_depth: Worst-case depth budget; the produced tree must not exceed it.
        top_k: If set, wide nodes (``|S| > full_threshold``) are restricted to
            ``subset ∪ top-K`` candidates; ``None`` searches all of ``G``.
        full_threshold: Nodes with ``|S| <= full_threshold`` always search all
            of ``G`` regardless of ``top_k``.
        greedy: First-feasible best-splitter per node (fast, near-optimal mean).
        lookahead_k: If ``> 0``, per node score the top-K heuristic candidates by
            a greedy rollout and commit the one with the smallest
            ``(rollout_max_depth, rollout_total)``, then recurse only into it.
            Lowers the mean below greedy while staying within the depth budget.
            Takes precedence over ``greedy`` when both are set.
        cache_path: Optional pattern-matrix cache path.

    Returns:
        A :class:`BuildResult`.

    Raises:
        BuildError: If the lists are inconsistent or the tree is infeasible.
    """
    if not answers:
        raise BuildError("empty answer list")
    if max_depth < 1 or max_depth > HARD_DEPTH_CAP:
        raise BuildError(f"max_depth must be in [1,{HARD_DEPTH_CAP}]: {max_depth}")

    allowed_index = {w: i for i, w in enumerate(allowed)}
    missing = [a for a in answers if a not in allowed_index]
    if missing:
        raise BuildError(f"answers not in allowed list (need A ⊆ G): {missing[:5]}")

    matrix = build_pattern_matrix(allowed, answers, cache_path)
    answer_to_allowed = np.array([allowed_index[a] for a in answers], dtype=np.intp)
    solver = _Solver(
        matrix,
        answer_to_allowed,
        top_k,
        full_threshold,
        shortlist_size,
        greedy,
        lookahead_k,
    )
    full_subset = np.arange(len(answers), dtype=np.intp)
    root_max, root_total, root = _resolve_root(
        solver, first_word, allowed_index, full_subset, max_depth
    )

    chk_max, chk_total, node_count, chk_answers = _measure(root)
    if chk_max != root_max or chk_total != root_total:
        raise BuildError(
            f"metric mismatch: search ({root_max},{root_total}) vs "
            f"recomputed ({chk_max},{chk_total})"
        )
    if chk_answers != len(answers):
        raise BuildError(
            f"tree resolves {chk_answers} answers, expected {len(answers)}"
        )
    if root_max > max_depth:
        raise BuildError(f"produced max_depth {root_max} exceeds budget {max_depth}")

    return BuildResult(
        root=root,
        max_depth=root_max,
        total_depth=root_total,
        mean=root_total / len(answers),
        node_count=node_count,
        answer_count=len(answers),
        allowed_count=len(allowed),
        first_word=first_word if first_word != "search" else allowed[root.guess],
        restricted=bool(solver.node_log),
    )
