"""Offline pipeline that builds the optimal Wordle decision tree for wsolv.

The artifacts produced here (``wordle_tree.bin`` + ``tree_meta.json``) are bundled
into the Android app and consumed by the Kotlin ``:core`` runtime. The feedback
rule in :mod:`wsolv_precompute.feedback` is the shared contract that must stay
byte-for-byte equivalent to the Kotlin ``Feedback`` object.
"""
