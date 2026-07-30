"""Pure reducers for paper-experiment JSONL rows.

This module intentionally does not read raw tool directories.  Paper tables
must be reducible from the canonical JSONL ledgers alone.
"""

from __future__ import annotations

from collections import Counter
from typing import Any, Iterable


def status_counts(rows: Iterable[dict[str, Any]]) -> dict[str, int]:
    """Return deterministic status counts without inventing missing rows."""

    counts = Counter(str(row["status"]) for row in rows)
    return dict(sorted(counts.items()))


def reciprocal_rank(rank: int | None) -> float:
    """Return one reciprocal rank; absent roots contribute zero only in admitted rows."""

    if rank is None:
        return 0.0
    if rank < 1:
        raise ValueError("rank must be positive")
    return 1.0 / rank

