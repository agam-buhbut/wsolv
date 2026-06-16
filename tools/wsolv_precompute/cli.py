"""Command-line entry point for the wsolv precompute pipeline.

Subcommands:
    build     Build the decision tree, serialize it, then validate the result.
    validate  Replay every answer through an existing tree binary.

Data (metrics) goes to stdout; progress and errors go to stderr. Default asset
and output paths are resolved relative to the repository root so the tool works
regardless of the current working directory.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

from . import build_tree as bt
from .build_tree import BuildError, build_tree, load_words
from .serialize import write_meta, write_tree
from .validate import ValidationError, validate_file

# tools/wsolv_precompute/cli.py -> repo root is three parents up.
_REPO_ROOT = Path(__file__).resolve().parents[2]
_DEFAULT_ASSETS = _REPO_ROOT / "app" / "src" / "main" / "assets"
_DEFAULT_OUT = _REPO_ROOT / "tools" / "out"

_ANSWERS_FILE = "wordle_answers.txt"
_ALLOWED_FILE = "wordle_allowed.txt"
_BIN_FILE = "wordle_tree.bin"
_META_FILE = "tree_meta.json"
_MATRIX_CACHE = "pattern_matrix.npy"


def _load_lists(assets: Path) -> tuple[list[str], list[str]]:
    answers_path = assets / _ANSWERS_FILE
    allowed_path = assets / _ALLOWED_FILE
    for path in (answers_path, allowed_path):
        if not path.exists():
            raise BuildError(f"missing asset: {path}")
    answers = load_words(answers_path)
    allowed = load_words(allowed_path)
    return allowed, answers


def _cmd_build(args: argparse.Namespace) -> int:
    assets: Path = args.assets
    out: Path = args.out
    top_k = None if args.top_k <= 0 else args.top_k

    allowed, answers = _load_lists(assets)
    print(
        f"loaded {len(answers)} answers, {len(allowed)} allowed guesses",
        file=sys.stderr,
    )

    start = time.perf_counter()
    result = build_tree(
        allowed,
        answers,
        first_word=args.first_word,
        max_depth=args.max_depth,
        top_k=top_k,
        full_threshold=args.full_threshold,
        shortlist_size=args.pool_size,
        greedy=args.greedy,
        lookahead_k=args.lookahead,
        cache_path=out / _MATRIX_CACHE,
    )
    elapsed = time.perf_counter() - start
    print(f"built tree in {elapsed:.1f}s", file=sys.stderr)

    if result.max_depth != args.max_depth:
        # Not fatal (a better tree may beat the budget), but worth surfacing.
        print(
            f"note: achieved max_depth {result.max_depth} "
            f"(budget was {args.max_depth})",
            file=sys.stderr,
        )

    bin_path = out / _BIN_FILE
    meta_path = out / _META_FILE
    tree_bytes = write_tree(result, bin_path)
    meta = write_meta(
        result,
        tree_bytes,
        assets / _ANSWERS_FILE,
        assets / _ALLOWED_FILE,
        meta_path,
    )
    print(f"wrote {bin_path} ({len(tree_bytes)} bytes)", file=sys.stderr)
    print(f"wrote {meta_path}", file=sys.stderr)

    report = validate_file(
        bin_path,
        allowed,
        answers,
        expected_max_depth=result.max_depth,
        expected_total=result.total_depth,
        expected_mean=result.mean,
    )
    print(
        f"validation OK: {report.answer_count} answers, "
        f"max_depth={report.max_depth}, mean={report.mean:.5f}",
        file=sys.stderr,
    )

    summary = {
        "first_word": result.first_word,
        "max_depth": result.max_depth,
        "mean": result.mean,
        "total_guesses": result.total_depth,
        "answer_count": result.answer_count,
        "allowed_count": result.allowed_count,
        "node_count": result.node_count,
        "bin_bytes": len(tree_bytes),
        "restricted": result.restricted,
        "build_seconds": round(elapsed, 3),
        "tree_sha256": meta["tree_sha256"],
    }
    print(json.dumps(summary, indent=2))
    return 0


def _cmd_validate(args: argparse.Namespace) -> int:
    bin_path: Path = args.bin
    assets: Path = args.assets
    if not bin_path.exists():
        raise ValidationError(f"missing tree binary: {bin_path}")

    allowed, answers = _load_lists(assets)
    meta_path = bin_path.parent / _META_FILE
    expected_max = expected_total = expected_mean = None
    if meta_path.exists():
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        expected_max = meta.get("max_depth")
        expected_total = meta.get("total_guesses")
        expected_mean = meta.get("mean")
        print(f"cross-checking against {meta_path}", file=sys.stderr)

    report = validate_file(
        bin_path,
        allowed,
        answers,
        expected_max_depth=expected_max,
        expected_total=expected_total,
        expected_mean=expected_mean,
    )
    print(
        json.dumps(
            {
                "answer_count": report.answer_count,
                "max_depth": report.max_depth,
                "total_guesses": report.total_guesses,
                "mean": report.mean,
            },
            indent=2,
        )
    )
    print("validation OK", file=sys.stderr)
    return 0


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="wsolv-precompute",
        description="Build and validate the wsolv Wordle decision tree.",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    build_p = sub.add_parser("build", help="build, serialize, and validate the tree")
    build_p.add_argument(
        "--first-word",
        default=bt.DEFAULT_FIRST_WORD,
        help="fixed opener, or 'search' to search the root level (default: salet)",
    )
    build_p.add_argument(
        "--assets",
        type=Path,
        default=_DEFAULT_ASSETS,
        help="directory holding the word-list assets",
    )
    build_p.add_argument(
        "--out",
        type=Path,
        default=_DEFAULT_OUT,
        help="output directory for the tree binary and metadata",
    )
    build_p.add_argument(
        "--max-depth",
        type=int,
        default=bt.DEFAULT_MAX_DEPTH,
        help="worst-case depth budget (default: 5)",
    )
    build_p.add_argument(
        "--top-k",
        type=int,
        default=bt.DEFAULT_TOP_K,
        help="candidate cap at wide nodes; <=0 uses the whole pool (default: 120)",
    )
    build_p.add_argument(
        "--full-threshold",
        type=int,
        default=bt.DEFAULT_FULL_THRESHOLD,
        help="nodes with |S| <= this consider the whole pool (default: 15)",
    )
    build_p.add_argument(
        "--pool-size",
        type=int,
        default=bt.DEFAULT_SHORTLIST,
        help="global probe-word shortlist size; pool = subset union shortlist; "
        "0 = whole guess list (default: 0)",
    )
    build_p.add_argument(
        "--greedy",
        action="store_true",
        help="take the first feasible best-splitter guess per node: worst-case "
        "depth is still optimal, mean is near-optimal, build is fast",
    )
    build_p.add_argument(
        "--lookahead",
        type=int,
        default=0,
        metavar="K",
        help="bounded best-of-K lookahead: score the top-K candidates per node "
        "by a greedy rollout and commit the lowest-cost one; lowers the mean "
        "while keeping the depth budget; 0 = disabled (default: 0)",
    )
    build_p.set_defaults(func=_cmd_build)

    val_p = sub.add_parser("validate", help="replay every answer through a tree")
    val_p.add_argument(
        "--bin", type=Path, required=True, help="path to wordle_tree.bin"
    )
    val_p.add_argument(
        "--assets",
        type=Path,
        default=_DEFAULT_ASSETS,
        help="directory holding the word-list assets",
    )
    val_p.set_defaults(func=_cmd_validate)
    return parser


def main(argv: list[str] | None = None) -> int:
    """CLI entry point. Returns a process exit code."""
    parser = _build_parser()
    args = parser.parse_args(argv)
    try:
        result: int = args.func(args)
        return result
    except (BuildError, ValidationError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
