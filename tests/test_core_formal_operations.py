from pathlib import Path
import sys

import pytest


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


from src.core.formal_operations import (  # noqa: E402
    FormalOperationError,
    canonical_sha256,
    join_exact_operation_rows,
    materialize_missing_rows,
    stable_operation_id,
    validate_expected_operation_set,
)


def _expected():
    return [
        {"operation_id": "op_a", "role": "primary"},
        {"operation_id": "op_b", "role": "cover"},
    ]


def test_exact_join_materializes_missing_and_reports_unexpected():
    joined = join_exact_operation_rows(
        _expected(),
        [
            {"operation_id": "op_a", "status": "proven"},
            {"operation_id": "op_extra", "status": "covered"},
        ],
    )

    assert joined["operation_set_complete"] is False
    assert joined["missing_operation_ids"] == ["op_b"]
    assert joined["unexpected_operation_ids"] == ["op_extra"]
    assert joined["rows"] == [
        {"operation_id": "op_a", "status": "proven"},
        {
            "operation_id": "op_b",
            "status": "not_run",
            "reason": "missing_operation_result",
        },
    ]
    assert materialize_missing_rows(_expected(), [])[-1]["status"] == "not_run"


@pytest.mark.parametrize("side", ["expected", "actual"])
def test_exact_join_rejects_duplicate_ids(side):
    duplicate = [{"operation_id": "op_a"}, {"operation_id": "op_a"}]
    with pytest.raises(FormalOperationError, match="duplicate"):
        if side == "expected":
            validate_expected_operation_set(duplicate)
        else:
            join_exact_operation_rows(_expected(), duplicate)


def test_stable_id_and_canonical_hash_are_deterministic():
    assert stable_operation_id(("instance", "role", "target")) == (
        "instance__role__target"
    )
    assert canonical_sha256({"b": 2, "a": 1}) == canonical_sha256(
        {"a": 1, "b": 2}
    )
    with pytest.raises(FormalOperationError):
        stable_operation_id(())
