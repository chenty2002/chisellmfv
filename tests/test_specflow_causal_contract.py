from pathlib import Path
import sys

import pytest


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


from src.chiselspecflow.causal_contract import (  # noqa: E402
    CausalContractError,
    effective_causal_config,
    validate_causal_seed,
)
from src.chiselspecflow.trace_projection import _derive_causal_seed  # noqa: E402


def test_typed_causal_seed_uses_expression_objects_without_signal_guessing():
    seed = _derive_causal_seed(
        operation_id="op_primary",
        failure_cycle=7,
        primary={
            "expression_ir": {
                "op": "eq",
                "lhs": {"op": "object_ref", "object_id": "obj_result"},
                "rhs": {
                    "op": "previous_value",
                    "state_id": "previous_result",
                },
            }
        },
        source_objects=[
            {
                "object_id": "obj_result",
                "binding_id": "bind_result",
                "emitted_signal": "SpecFlowOverlay.dut.result",
            },
            {
                "object_id": "obj_guard",
                "binding_id": "bind_guard",
                "emitted_signal": "SpecFlowOverlay.dut.guard",
            },
        ],
        clock_signal="SpecFlowOverlay.clock",
    )

    assert validate_causal_seed(seed)["status"] == "ready"
    assert seed["endpoint_candidates"] == [
        {
            "object_id": "obj_result",
            "binding_id": "bind_result",
            "emitted_signal": "SpecFlowOverlay.dut.result",
            "selection_reason": "failed_expression_observer",
        }
    ]


def test_ambiguous_endpoint_fails_closed():
    seed = _derive_causal_seed(
        operation_id="op_primary",
        failure_cycle=2,
        primary={
            "expression_ir": {
                "op": "object_ref",
                "object_id": "obj_missing",
            }
        },
        source_objects=[],
        clock_signal="SpecFlowOverlay.clock",
    )
    assert seed["status"] == "ambiguous"
    assert seed["endpoint_candidates"] == []
    assert seed["errors"][0]["code"] == "causal_endpoint_join_ambiguous"


def test_causal_bounds_and_policy_are_fixed():
    assert effective_causal_config()["max_depth"] == 12
    invalid = effective_causal_config()
    invalid["max_depth"] = 0
    with pytest.raises(CausalContractError, match="positive"):
        effective_causal_config(invalid)
