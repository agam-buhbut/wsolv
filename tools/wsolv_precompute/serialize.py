"""Serialize / parse the compact ``WSV1`` decision-tree binary.

The on-disk format is little-endian and must match the Kotlin reader
byte-for-byte::

    Header (13 bytes):
      [0:4]  magic = ASCII "WSV1"
      [4]    version u8 = 1
      [5:9]  allowed_count u32
      [9:13] node_count u32
    Root node index = 0, then node_count nodes written consecutively.
    Node layout (variable length):
      guess_index u16
      child_count u8
      child_count * ( feedback_code u8 (0..241) ; child_index u32 )
    Leaf: child_count == 0.

Node indices are assigned by a deterministic BFS traversal with the root at
index 0; children reference absolute indices into the node table. Feedback
code ``242`` (solved) is the implicit sentinel and is never stored.
"""

from __future__ import annotations

import hashlib
import json
import struct
from collections import deque
from dataclasses import dataclass
from pathlib import Path

from . import feedback
from .build_tree import BuildResult, Node

__all__ = [
    "MAGIC",
    "VERSION",
    "HEADER_SIZE",
    "ParsedNode",
    "ParsedTree",
    "flatten",
    "serialize_tree",
    "parse_tree",
    "write_tree",
    "write_meta",
    "sha256_file",
    "sha256_bytes",
]

MAGIC = b"WSV1"
VERSION = 1
HEADER_SIZE = 13

_HEADER = struct.Struct("<4sBII")  # magic, version, allowed_count, node_count
_NODE_HEAD = struct.Struct("<HB")  # guess_index, child_count
_CHILD = struct.Struct("<BI")  # feedback_code, child_index


class SerializeError(Exception):
    """Raised when a tree cannot be serialized or parsed."""


@dataclass(slots=True)
class ParsedNode:
    """A node read back from the binary (children keyed by feedback code)."""

    guess: int
    children: dict[int, int]  # feedback code -> absolute node index


@dataclass(slots=True)
class ParsedTree:
    """A fully parsed ``WSV1`` tree."""

    version: int
    allowed_count: int
    nodes: list[ParsedNode]


def flatten(root: Node) -> list[Node]:
    """Assign node indices via BFS (root=0) and return nodes in index order.

    Children are visited in ascending feedback-code order so the traversal --
    and therefore the serialized byte layout -- is fully deterministic.
    """
    order: list[Node] = []
    index_of: dict[int, int] = {}
    queue: deque[Node] = deque([root])
    index_of[id(root)] = 0
    order.append(root)
    while queue:
        node = queue.popleft()
        for code in sorted(node.children):
            child = node.children[code]
            index_of[id(child)] = len(order)
            order.append(child)
            queue.append(child)
    return order


def serialize_tree(result: BuildResult) -> bytes:
    """Serialize a built tree to the ``WSV1`` byte layout.

    Args:
        result: The build result whose ``root`` and ``allowed_count`` are
            written. ``answer_count`` etc. live in the JSON sidecar, not here.

    Returns:
        The complete little-endian byte string (header + nodes).

    Raises:
        SerializeError: If any value overflows its field, or a feedback code is
            the reserved solved sentinel, or the allowed count is out of range.
    """
    order = flatten(result.root)
    index_of = {id(node): i for i, node in enumerate(order)}

    if not 0 <= result.allowed_count <= 0xFFFFFFFF:
        raise SerializeError(f"allowed_count out of u32 range: {result.allowed_count}")
    if len(order) > 0xFFFFFFFF:
        raise SerializeError(f"node_count out of u32 range: {len(order)}")

    buf = bytearray()
    buf += _HEADER.pack(MAGIC, VERSION, result.allowed_count, len(order))

    for node in order:
        if not 0 <= node.guess <= 0xFFFF:
            raise SerializeError(f"guess index out of u16 range: {node.guess}")
        codes = sorted(node.children)
        if len(codes) > 0xFF:
            raise SerializeError(f"child_count out of u8 range: {len(codes)}")
        buf += _NODE_HEAD.pack(node.guess, len(codes))
        for code in codes:
            if not 0 <= code < feedback.SOLVED:
                raise SerializeError(
                    f"feedback code {code} not storable (must be 0..241)"
                )
            buf += _CHILD.pack(code, index_of[id(node.children[code])])
    return bytes(buf)


def parse_tree(data: bytes) -> ParsedTree:
    """Parse a ``WSV1`` byte string back into a :class:`ParsedTree`.

    Raises:
        SerializeError: On a bad magic/version, truncated data, an out-of-range
            child index, or trailing bytes.
    """
    if len(data) < HEADER_SIZE:
        raise SerializeError(f"data too short for header: {len(data)} bytes")
    magic, version, allowed_count, node_count = _HEADER.unpack_from(data, 0)
    if magic != MAGIC:
        raise SerializeError(f"bad magic: {magic!r}")
    if version != VERSION:
        raise SerializeError(f"unsupported version: {version}")

    nodes: list[ParsedNode] = []
    offset = HEADER_SIZE
    for _ in range(node_count):
        if offset + _NODE_HEAD.size > len(data):
            raise SerializeError("truncated node header")
        guess, child_count = _NODE_HEAD.unpack_from(data, offset)
        offset += _NODE_HEAD.size
        children: dict[int, int] = {}
        for _ in range(child_count):
            if offset + _CHILD.size > len(data):
                raise SerializeError("truncated child record")
            code, child_index = _CHILD.unpack_from(data, offset)
            offset += _CHILD.size
            children[code] = child_index
        nodes.append(ParsedNode(guess=guess, children=children))

    if offset != len(data):
        raise SerializeError(f"trailing bytes: parsed {offset} of {len(data)}")
    if len(nodes) != node_count:
        raise SerializeError(f"node count mismatch: {len(nodes)} != {node_count}")

    # Cross-check child indices point inside the table (the validator does a
    # deeper replay; this is a cheap structural guard at parse time).
    for i, node in enumerate(nodes):
        for code, child_index in node.children.items():
            if not 0 <= child_index < node_count:
                raise SerializeError(
                    f"node {i} child code {code} -> out-of-range index {child_index}"
                )
    return ParsedTree(version=version, allowed_count=allowed_count, nodes=nodes)


def sha256_bytes(data: bytes) -> str:
    """Hex SHA-256 of a byte string."""
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    """Hex SHA-256 of a file's raw bytes."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_tree(result: BuildResult, bin_path: Path) -> bytes:
    """Serialize and write the tree binary; return the written bytes."""
    data = serialize_tree(result)
    bin_path.parent.mkdir(parents=True, exist_ok=True)
    bin_path.write_bytes(data)
    return data


def write_meta(
    result: BuildResult,
    tree_bytes: bytes,
    answers_path: Path,
    allowed_path: Path,
    meta_path: Path,
) -> dict[str, object]:
    """Write ``tree_meta.json`` and return the metadata dict.

    Args:
        result: The build result (source of the depth metrics).
        tree_bytes: The serialized tree bytes (hashed for ``tree_sha256``).
        answers_path: Path to the answers asset (hashed for ``answers_sha256``).
        allowed_path: Path to the allowed asset (hashed for ``allowed_sha256``).
        meta_path: Destination JSON path.

    Returns:
        The metadata dict that was written.
    """
    meta: dict[str, object] = {
        "max_depth": result.max_depth,
        "total_guesses": result.total_depth,
        "mean": result.mean,
        "answer_count": result.answer_count,
        "allowed_count": result.allowed_count,
        "first_word": result.first_word,
        "node_count": result.node_count,
        "tree_sha256": sha256_bytes(tree_bytes),
        "answers_sha256": sha256_file(answers_path),
        "allowed_sha256": sha256_file(allowed_path),
    }
    meta_path.parent.mkdir(parents=True, exist_ok=True)
    meta_path.write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    return meta
