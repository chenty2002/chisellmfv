"""Typed, bounded expression IR used by Stage-1 candidates and lowering."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, Mapping, Optional

from ..config import EXPRESSION_SCHEMA


class ExpressionValidationError(ValueError):
    """Raised when an expression is malformed or not type safe."""

    def __init__(self, code: str, message: str):
        self.code = code
        super().__init__(f"{code}: {message}")


@dataclass(frozen=True)
class ExpressionType:
    kind: str
    width: int
    signed: bool = False

    def as_dict(self) -> Dict[str, Any]:
        return {"kind": self.kind, "width": self.width, "signed": self.signed}


_BOOL = ExpressionType("Bool", 1, False)
_COMPARISONS = {"ult", "ule", "ugt", "uge", "slt", "sle", "sgt", "sge"}
_BINARY = {"eq", "neq", "add", "sub"} | _COMPARISONS
_ALLOWED_OPS = {
    "literal",
    "object_ref",
    "not",
    "and",
    "or",
    "eq",
    "neq",
    "ult",
    "ule",
    "ugt",
    "uge",
    "slt",
    "sle",
    "sgt",
    "sge",
    "add",
    "sub",
    "mux",
    "past_valid",
    "previous_value",
    "onehot",
    "popcount",
    "bit_select",
    "slice",
    "bounded_counter_relation",
}


def validate_expression(
    expression: Mapping[str, Any],
    object_types: Mapping[str, Mapping[str, Any]],
    state_types: Optional[Mapping[str, Mapping[str, Any]]] = None,
) -> Dict[str, Any]:
    """Return a normalized typed expression or fail closed.

    The IR deliberately has no raw Scala/text escape. Every accepted node has
    an exact field set and carries a deterministic inferred ``result_type``.
    """

    state_types = state_types or {}
    normalized, _ = _validate_node(expression, object_types, state_types, "expression")
    return {
        "schema_version": EXPRESSION_SCHEMA,
        "root": normalized,
    }


def infer_expression_type(
    expression: Mapping[str, Any],
    object_types: Mapping[str, Mapping[str, Any]],
    state_types: Optional[Mapping[str, Mapping[str, Any]]] = None,
) -> ExpressionType:
    node = _unwrap(expression)
    _, result = _validate_node(node, object_types, state_types or {}, "expression")
    return result


def normalized_root(expression: Mapping[str, Any]) -> Mapping[str, Any]:
    """Return the root from either a bare node or expression_ir wrapper."""

    return _unwrap(expression)


def _validate_node(
    value: Mapping[str, Any],
    object_types: Mapping[str, Mapping[str, Any]],
    state_types: Mapping[str, Mapping[str, Any]],
    path: str,
) -> tuple[Dict[str, Any], ExpressionType]:
    if not isinstance(value, Mapping):
        raise ExpressionValidationError("malformed_expression", f"{path} must be an object")
    declared_result = value.get("result_type")
    if declared_result is not None:
        value = {key: item for key, item in value.items() if key != "result_type"}
    op = value.get("op")
    if op not in _ALLOWED_OPS:
        raise ExpressionValidationError("unsupported_operator", f"{path}.op={op!r}")

    if op == "literal":
        _exact(value, {"op", "value", "type"}, path)
        result = _parse_type(value["type"], f"{path}.type")
        literal = value["value"]
        if result.kind == "Bool":
            if not isinstance(literal, bool):
                raise ExpressionValidationError("type_mismatch", f"{path}.value must be boolean")
        elif not isinstance(literal, int) or isinstance(literal, bool):
            raise ExpressionValidationError("type_mismatch", f"{path}.value must be integer")
        elif result.signed:
            if literal < -(1 << (result.width - 1)) or literal >= (1 << (result.width - 1)):
                raise ExpressionValidationError("width_mismatch", f"{path}.value does not fit signed width")
        elif literal < 0 or literal >= (1 << result.width):
            raise ExpressionValidationError("width_mismatch", f"{path}.value does not fit width")
        return _checked_type(_with_type(value, result), result, declared_result, path)

    if op == "object_ref":
        _exact(value, {"op", "object_id"}, path)
        object_id = _identifier(value["object_id"], f"{path}.object_id")
        if object_id not in object_types:
            raise ExpressionValidationError("unknown_object", object_id)
        result = _parse_type(object_types[object_id], f"object_types[{object_id}]")
        return _checked_type(_with_type(value, result), result, declared_result, path)

    if op in {"past_valid", "previous_value"}:
        _exact(value, {"op", "state_id"}, path)
        state_id = _identifier(value["state_id"], f"{path}.state_id")
        if state_id not in state_types:
            raise ExpressionValidationError("unknown_state", state_id)
        result = _parse_type(state_types[state_id], f"state_types[{state_id}]")
        if op == "past_valid" and result != _BOOL:
            raise ExpressionValidationError("type_mismatch", "past_valid state must be Bool")
        return _checked_type(_with_type(value, result), result, declared_result, path)

    if op == "not":
        _exact(value, {"op", "arg"}, path)
        arg, arg_type = _validate_node(value["arg"], object_types, state_types, f"{path}.arg")
        _require_bool(arg_type, path)
        return _checked_type(_with_type({"op": op, "arg": arg}, _BOOL), _BOOL, declared_result, path)

    if op in {"and", "or"}:
        _exact(value, {"op", "args"}, path)
        args = value["args"]
        if not isinstance(args, list) or len(args) < 2:
            raise ExpressionValidationError("malformed_expression", f"{path}.args needs at least two nodes")
        normalized = []
        for index, arg in enumerate(args):
            item, item_type = _validate_node(arg, object_types, state_types, f"{path}.args[{index}]")
            _require_bool(item_type, path)
            normalized.append(item)
        return _checked_type(_with_type({"op": op, "args": normalized}, _BOOL), _BOOL, declared_result, path)

    if op in _BINARY:
        _exact(value, {"op", "lhs", "rhs"}, path)
        lhs, lhs_type = _validate_node(value["lhs"], object_types, state_types, f"{path}.lhs")
        rhs, rhs_type = _validate_node(value["rhs"], object_types, state_types, f"{path}.rhs")
        if lhs_type != rhs_type:
            code = "width_mismatch" if lhs_type.kind == rhs_type.kind else "type_mismatch"
            raise ExpressionValidationError(code, f"{path} operands differ: {lhs_type} != {rhs_type}")
        if op in _COMPARISONS and lhs_type.kind == "Bool":
            raise ExpressionValidationError("type_mismatch", f"{op} does not accept Bool")
        if op.startswith("s") and not lhs_type.signed:
            raise ExpressionValidationError("type_mismatch", f"{op} requires signed operands")
        if op.startswith("u") and lhs_type.signed:
            raise ExpressionValidationError("type_mismatch", f"{op} requires unsigned operands")
        result = lhs_type if op in {"add", "sub"} else _BOOL
        return _checked_type(_with_type({"op": op, "lhs": lhs, "rhs": rhs}, result), result, declared_result, path)

    if op == "mux":
        _exact(value, {"op", "condition", "when_true", "when_false"}, path)
        condition, condition_type = _validate_node(value["condition"], object_types, state_types, f"{path}.condition")
        _require_bool(condition_type, path)
        when_true, true_type = _validate_node(value["when_true"], object_types, state_types, f"{path}.when_true")
        when_false, false_type = _validate_node(value["when_false"], object_types, state_types, f"{path}.when_false")
        if true_type != false_type:
            raise ExpressionValidationError("type_mismatch", f"{path} mux branches differ")
        normalized = _with_type(
            {"op": op, "condition": condition, "when_true": when_true, "when_false": when_false},
            true_type,
        )
        return _checked_type(normalized, true_type, declared_result, path)

    if op in {"onehot", "popcount"}:
        _exact(value, {"op", "arg"}, path)
        arg, arg_type = _validate_node(value["arg"], object_types, state_types, f"{path}.arg")
        if arg_type.kind not in {"UInt", "SInt"}:
            raise ExpressionValidationError("type_mismatch", f"{op} requires a bit vector")
        if op == "onehot":
            result = _BOOL
        else:
            result = ExpressionType("UInt", max(1, arg_type.width.bit_length()), False)
        return _checked_type(_with_type({"op": op, "arg": arg}, result), result, declared_result, path)

    if op in {"bit_select", "slice"}:
        fields = {"op", "arg", "index"} if op == "bit_select" else {"op", "arg", "high", "low"}
        _exact(value, fields, path)
        arg, arg_type = _validate_node(value["arg"], object_types, state_types, f"{path}.arg")
        if arg_type.kind not in {"UInt", "SInt"}:
            raise ExpressionValidationError("type_mismatch", f"{op} requires a bit vector")
        if op == "bit_select":
            index = value["index"]
            if not isinstance(index, int) or isinstance(index, bool) or not 0 <= index < arg_type.width:
                raise ExpressionValidationError("index_out_of_bounds", f"{path}.index")
            normalized = {"op": op, "arg": arg, "index": index}
            result = _BOOL
        else:
            high, low = value["high"], value["low"]
            if (
                not isinstance(high, int)
                or isinstance(high, bool)
                or not isinstance(low, int)
                or isinstance(low, bool)
                or low < 0
                or high < low
                or high >= arg_type.width
            ):
                raise ExpressionValidationError("index_out_of_bounds", f"{path}.high/low")
            normalized = {"op": op, "arg": arg, "high": high, "low": low}
            result = ExpressionType("UInt", high - low + 1, False)
        return _checked_type(_with_type(normalized, result), result, declared_result, path)

    _exact(value, {"op", "counter_state_id", "relation", "bound"}, path)
    state_id = _identifier(value["counter_state_id"], f"{path}.counter_state_id")
    state_type = _parse_type(state_types.get(state_id), f"state_types[{state_id}]")
    if state_type.kind != "UInt":
        raise ExpressionValidationError("type_mismatch", "bounded counter state must be UInt")
    if value["relation"] not in {"lt", "le", "eq", "ge", "gt"}:
        raise ExpressionValidationError("malformed_expression", "unknown bounded counter relation")
    bound = value["bound"]
    if not isinstance(bound, int) or isinstance(bound, bool) or bound < 0 or bound >= (1 << state_type.width):
        raise ExpressionValidationError("width_mismatch", "bounded counter bound does not fit")
    return _checked_type(_with_type(value, _BOOL), _BOOL, declared_result, path)


def _unwrap(expression: Mapping[str, Any]) -> Mapping[str, Any]:
    if not isinstance(expression, Mapping):
        raise ExpressionValidationError("malformed_expression", "expression must be an object")
    if expression.get("schema_version") == EXPRESSION_SCHEMA:
        if set(expression) != {"schema_version", "root"}:
            raise ExpressionValidationError("unexpected_field", "expression wrapper has extra fields")
        return expression["root"]
    return expression


def _parse_type(value: Any, path: str) -> ExpressionType:
    if not isinstance(value, Mapping) or set(value) != {"kind", "width", "signed"}:
        raise ExpressionValidationError("malformed_type", f"{path} must have kind,width,signed")
    kind = value.get("kind")
    width = value.get("width")
    signed = value.get("signed")
    if kind not in {"Bool", "UInt", "SInt"}:
        raise ExpressionValidationError("malformed_type", f"{path}.kind is unsupported")
    if not isinstance(width, int) or isinstance(width, bool) or width < 1:
        raise ExpressionValidationError("malformed_type", f"{path}.width must be positive")
    if not isinstance(signed, bool) or signed != (kind == "SInt"):
        raise ExpressionValidationError("malformed_type", f"{path}.signed disagrees with kind")
    if kind == "Bool" and width != 1:
        raise ExpressionValidationError("width_mismatch", "Bool width must be one")
    return ExpressionType(kind, width, signed)


def _with_type(value: Mapping[str, Any], result: ExpressionType) -> Dict[str, Any]:
    normalized = dict(value)
    normalized["result_type"] = result.as_dict()
    return normalized


def _checked_type(
    normalized: Dict[str, Any],
    inferred: ExpressionType,
    declared: Any,
    path: str,
) -> tuple[Dict[str, Any], ExpressionType]:
    if declared is not None and _parse_type(declared, f"{path}.result_type") != inferred:
        raise ExpressionValidationError("type_mismatch", f"{path}.result_type is not inferred type")
    return normalized, inferred


def _exact(value: Mapping[str, Any], fields: set[str], path: str) -> None:
    actual = set(value)
    if actual != fields:
        raise ExpressionValidationError(
            "unexpected_field",
            f"{path} fields differ: missing={sorted(fields - actual)}, extra={sorted(actual - fields)}",
        )


def _identifier(value: Any, path: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ExpressionValidationError("malformed_identifier", f"{path} must be non-empty")
    return value


def _require_bool(value: ExpressionType, path: str) -> None:
    if value != _BOOL:
        raise ExpressionValidationError("type_mismatch", f"{path} requires Bool")
