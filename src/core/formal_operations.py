"""Workflow-independent primitives for exact formal-operation accounting."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Callable, Iterable, Mapping, Sequence
from typing import Any, Dict


class FormalOperationError(ValueError):
    """Raised when an expected or observed operation set is malformed."""


def canonical_sha256(value: Any) -> str:
    """Hash a JSON value using a deterministic, whitespace-free encoding."""

    payload = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def stable_operation_id(parts: Sequence[str]) -> str:
    """Build a deterministic operation ID from already domain-validated parts."""

    if (
        isinstance(parts, (str, bytes))
        or not isinstance(parts, Sequence)
        or not parts
    ):
        raise FormalOperationError("operation ID parts must be a non-empty sequence")
    normalized = []
    for index, part in enumerate(parts):
        if not isinstance(part, str) or not part.strip():
            raise FormalOperationError(
                f"operation ID part {index} must be a non-empty string"
            )
        normalized.append(part)
    return "__".join(normalized)


def validate_expected_operation_set(
    expected_rows: Iterable[Mapping[str, Any]],
    *,
    id_key: str = "operation_id",
) -> Dict[str, Dict[str, Any]]:
    """Validate expected rows and return their unique ID-indexed copies."""

    indexed: Dict[str, Dict[str, Any]] = {}
    for index, row in enumerate(expected_rows):
        if not isinstance(row, Mapping):
            raise FormalOperationError(
                f"expected operation row {index} must be an object"
            )
        operation_id = row.get(id_key)
        if not isinstance(operation_id, str) or not operation_id:
            raise FormalOperationError(
                f"expected operation row {index} has no {id_key}"
            )
        if operation_id in indexed:
            raise FormalOperationError(f"duplicate expected operation: {operation_id}")
        indexed[operation_id] = dict(row)
    return indexed


def materialize_missing_rows(
    expected_rows: Iterable[Mapping[str, Any]],
    actual_rows: Iterable[Mapping[str, Any]],
    *,
    id_key: str = "operation_id",
    missing_row_factory: (
        Callable[[Mapping[str, Any]], Mapping[str, Any]] | None
    ) = None,
) -> list[Dict[str, Any]]:
    """Return one row per expected operation, materializing absent observations."""

    joined = join_exact_operation_rows(
        expected_rows,
        actual_rows,
        id_key=id_key,
        missing_row_factory=missing_row_factory,
    )
    return joined["rows"]


def join_exact_operation_rows(
    expected_rows: Iterable[Mapping[str, Any]],
    actual_rows: Iterable[Mapping[str, Any]],
    *,
    id_key: str = "operation_id",
    missing_row_factory: (
        Callable[[Mapping[str, Any]], Mapping[str, Any]] | None
    ) = None,
) -> Dict[str, Any]:
    """Join actual rows to the expected set and expose every set discrepancy.

    Unexpected rows are reported but never allowed into the materialized row
    list.  Missing rows are made explicit as ``not_run`` by default.  Duplicate
    expected or actual IDs are malformed input and raise immediately.
    """

    expected_by_id = validate_expected_operation_set(expected_rows, id_key=id_key)
    actual_by_id: Dict[str, Dict[str, Any]] = {}
    unexpected: list[str] = []
    for index, row in enumerate(actual_rows):
        if not isinstance(row, Mapping):
            raise FormalOperationError(
                f"actual operation row {index} must be an object"
            )
        operation_id = row.get(id_key)
        if not isinstance(operation_id, str) or not operation_id:
            raise FormalOperationError(
                f"actual operation row {index} has no {id_key}"
            )
        if operation_id in actual_by_id:
            raise FormalOperationError(f"duplicate actual operation: {operation_id}")
        actual_by_id[operation_id] = dict(row)
        if operation_id not in expected_by_id:
            unexpected.append(operation_id)

    missing_factory = missing_row_factory or _default_missing_row
    materialized: list[Dict[str, Any]] = []
    missing: list[str] = []
    for operation_id, expected in expected_by_id.items():
        actual = actual_by_id.get(operation_id)
        if actual is None:
            missing.append(operation_id)
            actual = dict(missing_factory(expected))
            actual.setdefault(id_key, operation_id)
        materialized.append(dict(actual))

    return {
        "expected_by_id": expected_by_id,
        "actual_by_id": actual_by_id,
        "rows": materialized,
        "missing_operation_ids": missing,
        "unexpected_operation_ids": sorted(set(unexpected)),
        "operation_set_complete": not missing and not unexpected,
    }


def _default_missing_row(expected: Mapping[str, Any]) -> Mapping[str, Any]:
    return {
        "operation_id": expected["operation_id"],
        "status": "not_run",
        "reason": "missing_operation_result",
    }
