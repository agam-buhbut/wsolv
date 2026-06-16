"""Tests for the WSV1 serializer / parser."""

from __future__ import annotations

import struct

import pytest

from wsolv_precompute.build_tree import BuildResult, Node
from wsolv_precompute.serialize import (
    HEADER_SIZE,
    MAGIC,
    VERSION,
    SerializeError,
    flatten,
    parse_tree,
    serialize_tree,
)


def _result(root: Node, allowed_count: int = 10) -> BuildResult:
    """Wrap a hand-built node in a BuildResult (metrics here are unused)."""
    return BuildResult(
        root=root,
        max_depth=1,
        total_depth=1,
        mean=1.0,
        node_count=len(flatten(root)),
        answer_count=1,
        allowed_count=allowed_count,
        first_word="x",
        restricted=False,
    )


def _sample_tree() -> Node:
    # root guesses index 3; on code 5 -> leaf guessing 7; on code 9 -> a node
    # guessing 2 that on code 1 -> leaf guessing 4.
    leaf_a = Node(7)
    leaf_b = Node(4)
    mid = Node(2, {1: leaf_b})
    return Node(3, {5: leaf_a, 9: mid})


def test_header_layout() -> None:
    data = serialize_tree(_result(_sample_tree(), allowed_count=14855))
    magic, version, allowed_count, node_count = struct.unpack_from("<4sBII", data, 0)
    assert magic == MAGIC
    assert version == VERSION
    assert allowed_count == 14855
    assert node_count == 4


def test_roundtrip_structure() -> None:
    root = _sample_tree()
    data = serialize_tree(_result(root))
    tree = parse_tree(data)

    assert tree.allowed_count == 10
    assert len(tree.nodes) == 4
    # BFS order: root(3), then its children by ascending code: 5->leaf(7),
    # 9->mid(2), then mid's child 1->leaf(4).
    assert tree.nodes[0].guess == 3
    assert tree.nodes[0].children == {5: 1, 9: 2}
    assert tree.nodes[1].guess == 7
    assert tree.nodes[1].children == {}
    assert tree.nodes[2].guess == 2
    assert tree.nodes[2].children == {1: 3}
    assert tree.nodes[3].guess == 4


def test_leaf_has_zero_children() -> None:
    data = serialize_tree(_result(Node(0)))
    tree = parse_tree(data)
    assert len(tree.nodes) == 1
    assert tree.nodes[0].children == {}


def test_solved_code_is_never_stored() -> None:
    # 242 is the implicit sentinel; storing it is a programming error.
    with pytest.raises(SerializeError):
        serialize_tree(_result(Node(0, {242: Node(1)})))


def test_guess_index_overflow_rejected() -> None:
    with pytest.raises(SerializeError):
        serialize_tree(_result(Node(70000)))


def test_parse_rejects_bad_magic() -> None:
    data = bytearray(serialize_tree(_result(Node(0))))
    data[0:4] = b"XXXX"
    with pytest.raises(SerializeError):
        parse_tree(bytes(data))


def test_parse_rejects_bad_version() -> None:
    data = bytearray(serialize_tree(_result(Node(0))))
    data[4] = 99
    with pytest.raises(SerializeError):
        parse_tree(bytes(data))


def test_parse_rejects_truncated() -> None:
    data = serialize_tree(_result(_sample_tree()))
    with pytest.raises(SerializeError):
        parse_tree(data[: HEADER_SIZE + 1])


def test_parse_rejects_trailing_bytes() -> None:
    data = serialize_tree(_result(Node(0)))
    with pytest.raises(SerializeError):
        parse_tree(data + b"\x00")


def test_parse_rejects_dangling_child_index() -> None:
    # Hand-forge a header claiming one node whose child points past the table.
    body = struct.pack("<HB", 0, 1) + struct.pack("<BI", 5, 99)
    data = struct.pack("<4sBII", MAGIC, VERSION, 10, 1) + body
    with pytest.raises(SerializeError):
        parse_tree(data)
