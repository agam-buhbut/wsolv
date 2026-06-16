"""Generate the shared feedback parity fixture used by both test suites.

Reproducible: derives every vector from :func:`wsolv_precompute.feedback.score`
plus a fixed-seed sample of real word pairs. Writes
``core/src/test/resources/feedback_vectors.json``. Also asserts the vectorized
:func:`pattern_matrix` agrees with the scalar rule, so the pipeline can trust it.

Run from the ``tools/`` directory: ``python gen_feedback_vectors.py``
"""

from __future__ import annotations

import json
import random
import sys
from pathlib import Path

from wsolv_precompute import feedback

REPO = Path(__file__).resolve().parent.parent
ASSETS = REPO / "app" / "src" / "main" / "assets"
OUT = REPO / "core" / "src" / "test" / "resources" / "feedback_vectors.json"

# Hand-picked cases that pin down the duplicate-letter rule.
EDGE_CASES: list[tuple[str, str]] = [
    ("level", "lever"),  # trailing L -> gray, not yellow
    ("speed", "erase"),  # one E yellow, one E gray
    ("alloy", "loyal"),  # duplicate L handling
    ("eerie", "there"),  # repeated E against single E
    ("array", "razor"),  # repeated R
    ("sassy", "mossy"),  # repeated S
    ("argue", "argue"),  # all green -> 242
    ("xylyl", "abate"),  # all gray
    ("crane", "slate"),  # mixed
    ("salet", "aback"),  # the opener vs an answer
]


def _load(name: str) -> list[str]:
    return [w.strip() for w in (ASSETS / name).read_text().splitlines() if w.strip()]


def main() -> int:
    answers = _load("wordle_answers.txt")
    allowed = _load("wordle_allowed.txt")

    rng = random.Random(20240607)
    pairs: list[tuple[str, str]] = list(EDGE_CASES)
    for _ in range(2000):
        pairs.append((rng.choice(allowed), rng.choice(answers)))

    # Verify the vectorized matrix matches the scalar rule on a real slice.
    sample_guesses = [rng.choice(allowed) for _ in range(300)]
    g_arr = feedback.words_to_array(sample_guesses)
    a_arr = feedback.words_to_array(answers)
    matrix = feedback.pattern_matrix(g_arr, a_arr)
    for gi, guess in enumerate(sample_guesses):
        for ai, answer in enumerate(answers):
            expected = feedback.feedback_code(guess, answer)
            if int(matrix[gi, ai]) != expected:
                print(
                    f"matrix mismatch {guess}/{answer}: "
                    f"{matrix[gi, ai]} != {expected}",
                    file=sys.stderr,
                )
                return 1
    print(f"pattern_matrix parity OK over {len(sample_guesses)}x{len(answers)} pairs")

    vectors = []
    seen: set[tuple[str, str]] = set()
    for guess, answer in pairs:
        if (guess, answer) in seen:
            continue
        seen.add((guess, answer))
        pattern = feedback.score(guess, answer)
        vectors.append(
            {
                "guess": guess,
                "answer": answer,
                "pattern": list(pattern),
                "code": feedback.encode(pattern),
            }
        )

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(vectors, indent=0))
    print(f"wrote {len(vectors)} vectors -> {OUT.relative_to(REPO)}")
    # Spot-check a couple of the canonical patterns are what we expect.
    assert feedback.score("level", "lever") == (2, 2, 2, 2, 0), "LEVEL/LEVER"
    assert feedback.encode((2, 2, 2, 2, 2)) == feedback.SOLVED
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
