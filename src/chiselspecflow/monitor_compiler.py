"""Typed Monitor IR lowering into an isolated Chisel wrapper overlay."""

from __future__ import annotations

import difflib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Mapping, Optional, Sequence

from src.core.artifact_contract import file_sha256, validate_completed_stage

from .assets import AssetLibrary, load_reviewed_assets, load_run_local_package
from .config import OVERLAY_MANIFEST_SCHEMA_VERSION, SOURCE_ASSERTION_DELTA_SCHEMA_VERSION
from .ir.expression import ExpressionType, infer_expression_type, normalized_root
from .stages import get_stage_spec


class MonitorCompilerError(ValueError):
    """Raised before writing Scala when lowering is unsafe or ambiguous."""


@dataclass(frozen=True)
class ChiselExpr:
    source: str
    result_type: ExpressionType


@dataclass(frozen=True)
class OverlayUnit:
    monitor_id: str
    state_lines: tuple[str, ...]
    property_rows: tuple[Mapping[str, Any], ...]


@dataclass(frozen=True)
class RenderedOverlay:
    source: str
    properties: tuple[Dict[str, Any], ...]
    wrapper_top: str


@dataclass(frozen=True)
class CompiledOverlay:
    source_path: Path
    overlay_manifest_path: Path
    assertion_delta_path: Path
    diff_path: Path


def validate_monitor_ir(
    monitor: Mapping[str, Any],
    semantic_index: Mapping[str, Any],
    asset_library: AssetLibrary,
) -> None:
    """Validate reviewed/normalized monitor references before lowering."""

    if monitor.get("schema_version") != "chisel_monitors.v1":
        raise MonitorCompilerError("monitor is not normalized chisel_monitors.v1")
    archetype_id = monitor.get("archetype_id")
    archetype = asset_library.monitor_archetypes.get(archetype_id)
    if archetype is None or monitor.get("archetype_sha256") != archetype.get("sha256"):
        raise MonitorCompilerError("monitor archetype is missing or hash-mismatched")
    object_types = _object_types(semantic_index)
    state_types = {
        row["state_id"]: row["type"]
        for row in monitor.get("state", [])
        if isinstance(row, Mapping)
    }
    for state in monitor.get("state", []):
        for field in ("init", "update", "clear"):
            infer_expression_type(state[field], object_types, state_types)
    for prop in monitor.get("properties", []):
        for field in ("expression_ir", "guard_ir"):
            if infer_expression_type(prop[field], object_types, state_types) != ExpressionType("Bool", 1, False):
                raise MonitorCompilerError(f"property {field} must lower to Bool")


def lower_expression(
    expression: Mapping[str, Any],
    typed_bindings: Mapping[str, ChiselExpr],
    typed_states: Optional[Mapping[str, ChiselExpr]] = None,
) -> ChiselExpr:
    """Lower one validated expression node without accepting raw source text."""

    states = typed_states or {}
    node = normalized_root(expression)
    op = node["op"]
    result = _result_type(node)
    if op == "literal":
        value = node["value"]
        if result.kind == "Bool":
            return ChiselExpr("true.B" if value else "false.B", result)
        suffix = "S" if result.kind == "SInt" else "U"
        literal = f"({value})" if result.kind == "SInt" and value < 0 else str(value)
        return ChiselExpr(f"{literal}.{suffix}({result.width}.W)", result)
    if op == "object_ref":
        try:
            return typed_bindings[node["object_id"]]
        except KeyError as exc:
            raise MonitorCompilerError(f"expression references an unbound object: {node['object_id']}") from exc
    if op in {"past_valid", "previous_value"}:
        try:
            return states[node["state_id"]]
        except KeyError as exc:
            raise MonitorCompilerError(f"expression references unknown monitor state: {node['state_id']}") from exc
    if op == "not":
        arg = lower_expression(node["arg"], typed_bindings, states)
        return ChiselExpr(f"(!({arg.source}))", result)
    if op in {"and", "or"}:
        operator = " && " if op == "and" else " || "
        args = [lower_expression(arg, typed_bindings, states).source for arg in node["args"]]
        return ChiselExpr("(" + operator.join(f"({arg})" for arg in args) + ")", result)
    if op in {"eq", "neq", "ult", "ule", "ugt", "uge", "slt", "sle", "sgt", "sge", "add", "sub"}:
        lhs = lower_expression(node["lhs"], typed_bindings, states).source
        rhs = lower_expression(node["rhs"], typed_bindings, states).source
        operators = {
            "eq": "===",
            "neq": "=/=",
            "ult": "<",
            "ule": "<=",
            "ugt": ">",
            "uge": ">=",
            "slt": "<",
            "sle": "<=",
            "sgt": ">",
            "sge": ">=",
            "add": "+%",
            "sub": "-%",
        }
        return ChiselExpr(f"(({lhs}) {operators[op]} ({rhs}))", result)
    if op == "mux":
        condition = lower_expression(node["condition"], typed_bindings, states).source
        when_true = lower_expression(node["when_true"], typed_bindings, states).source
        when_false = lower_expression(node["when_false"], typed_bindings, states).source
        return ChiselExpr(f"Mux({condition}, {when_true}, {when_false})", result)
    if op in {"onehot", "popcount"}:
        arg = lower_expression(node["arg"], typed_bindings, states).source
        source = f"(PopCount({arg}) === 1.U)" if op == "onehot" else f"PopCount({arg})"
        return ChiselExpr(source, result)
    if op == "bit_select":
        arg = lower_expression(node["arg"], typed_bindings, states).source
        return ChiselExpr(f"({arg})({node['index']})", result)
    if op == "slice":
        arg = lower_expression(node["arg"], typed_bindings, states).source
        return ChiselExpr(f"({arg})({node['high']}, {node['low']})", result)
    if op == "bounded_counter_relation":
        state = states.get(node["counter_state_id"])
        if state is None:
            raise MonitorCompilerError("bounded counter relation references unknown state")
        operators = {"lt": "<", "le": "<=", "eq": "===", "ge": ">=", "gt": ">"}
        return ChiselExpr(
            f"({state.source} {operators[node['relation']]} {node['bound']}.U({state.result_type.width}.W))",
            result,
        )
    raise MonitorCompilerError(f"unsupported expression operator: {op}")


def lower_monitor(
    monitor_ir: Mapping[str, Any],
    semantic_index: Mapping[str, Any],
    bindings: Mapping[str, Mapping[str, Any]],
    *,
    reset_source: str = "reset.asBool",
) -> OverlayUnit:
    if not isinstance(reset_source, str) or not re.fullmatch(
        r"!?[A-Za-z_][A-Za-z0-9_.]*", reset_source
    ):
        raise MonitorCompilerError("monitor reset source is not a bounded identifier")
    object_rows = {row["object_id"]: row for row in semantic_index["objects"]}
    required_binding_ids = set(monitor_ir["binding_refs"])
    if not required_binding_ids <= set(bindings):
        raise MonitorCompilerError("monitor references a binding outside the package")
    required_object_ids = {
        bindings[binding_id]["object_id"] for binding_id in required_binding_ids
    }
    observer_lines = []
    typed_objects = {}
    for object_id, row in object_rows.items():
        if row.get("fact_status") != "elaboration_confirmed":
            continue
        name = _scala_identifier(row["name"])
        source = "dut." + name
        if (
            object_id in required_object_ids
            and row.get("direction") == "internal"
            and row.get("accessibility") == "wrapper"
        ):
            source = "csf_observe_" + name
            observer_lines.append(
                "val "
                + source
                + " = chisel3.util.experimental.BoringUtils.bore(dut."
                + name
                + ")"
            )
        typed_objects[object_id] = ChiselExpr(
            source, _type_from_mapping(row["chisel_type"])
        )
    for binding_id in required_binding_ids:
        object_id = bindings[binding_id]["object_id"]
        if object_id not in typed_objects:
            raise MonitorCompilerError("monitor binding is not elaboration-confirmed")

    state_exprs: Dict[str, ChiselExpr] = {}
    for state in monitor_ir["state"]:
        state_id = state["state_id"]
        if state_id in state_exprs:
            raise MonitorCompilerError(f"duplicate state ID: {state_id}")
        state_exprs[state_id] = ChiselExpr(
            "csf_" + _scala_identifier(state_id),
            _type_from_mapping(state["type"]),
        )
    state_lines = list(observer_lines)
    for state in monitor_ir["state"]:
        target = state_exprs[state["state_id"]]
        init = lower_expression(state["init"], typed_objects, state_exprs)
        update = lower_expression(state["update"], typed_objects, state_exprs)
        clear = lower_expression(state["clear"], typed_objects, state_exprs)
        state_lines.append(f"val {target.source} = RegInit({init.source})")
        state_lines.append(f"when (({reset_source}) || ({clear.source})) {{")
        state_lines.append(f"  {target.source} := {init.source}")
        state_lines.append("} .otherwise {")
        state_lines.append(f"  {target.source} := {update.source}")
        state_lines.append("}")
    properties = []
    for prop in monitor_ir["properties"]:
        expression = lower_expression(prop["expression_ir"], typed_objects, state_exprs)
        guard = lower_expression(prop["guard_ir"], typed_objects, state_exprs)
        guard_source = guard.source
        if monitor_ir.get("reset_policy") == "disable_while_reset":
            guard_source = f"((!({reset_source})) && ({guard_source}))"
        properties.append(
            {
                "source_property_id": prop["source_property_id"],
                "role": prop["role"],
                "expected_label": prop["expected_label"],
                "expression": expression.source,
                "guard": guard_source,
                "obligation_id": monitor_ir["obligation_id"],
                "binding_refs": list(monitor_ir["binding_refs"]),
            }
        )
    return OverlayUnit(monitor_ir["monitor_id"], tuple(state_lines), tuple(properties))


def render_overlay(
    units: Sequence[OverlayUnit],
    project_contract: Mapping[str, Any],
    configuration: Mapping[str, Any],
    semantic_index: Mapping[str, Any],
    adapter: Mapping[str, Any],
) -> RenderedOverlay:
    if not units:
        raise MonitorCompilerError("no overlay units to render")
    if adapter.get("project_id") != project_contract.get("project_id") or adapter.get("strategy") != "wrapper":
        raise MonitorCompilerError("API adapter does not match the wrapper project")
    constructor = _render_constructor(adapter["constructor_template"], configuration["parameters"])
    imports = [
        "import chisel3._",
        "import chisel3.util._",
        "import _root_.circt.stage.ChiselStage",
        "import java.nio.file.Paths",
    ] + [
        f"import {item}" for item in adapter["imports"]
    ]
    lines = ["package chisellmfv.generated", "", *imports, "", "final class SpecFlowOverlay extends Module {", f"  val dut = Module({constructor})"]
    confirmed = [row for row in semantic_index["objects"] if row.get("fact_status") == "elaboration_confirmed"]
    for row in sorted(confirmed, key=lambda item: item["name"]):
        direction = row.get("direction")
        if direction not in {"input", "output"}:
            continue
        name = _scala_identifier(row["name"])
        chisel_type = _chisel_type(_type_from_mapping(row["chisel_type"]))
        io_direction = "Input" if direction == "input" else "Output"
        lines.append(f"  val {name} = IO({io_direction}({chisel_type}))")
        if direction == "input":
            lines.append(f"  dut.{name} := {name}")
        else:
            lines.append(f"  {name} := dut.{name}")
    properties = []
    for unit in units:
        lines.append("")
        lines.append(f"  // Monitor {unit.monitor_id}")
        lines.extend("  " + line for line in unit.state_lines)
        for row in unit.property_rows:
            label = row["expected_label"]
            role = row["role"]
            lines.append(f"  // {label} {row['source_property_id']} {role}")
            anchor_line = len(lines) + 1
            if role == "primary_assertion":
                predicate = f"((!({row['guard']})) || ({row['expression']}))"
                lines.append(f"  assert({predicate}, \"{label}\")")
                kind = "assert"
            elif role == "assumption_sat":
                predicate = f"(({row['guard']}) && ({row['expression']}))"
                lines.append(f"  cover({predicate})")
                kind = "cover"
            else:
                predicate = f"(({row['guard']}) && ({row['expression']}))"
                lines.append(f"  cover({predicate})")
                kind = "cover"
            properties.append(
                {
                    **dict(row),
                    "property_kind": kind,
                    "overlay_source_anchor": {
                        "path": "SpecFlowOverlay.scala",
                        "line": anchor_line,
                        "column": 3,
                    },
                }
            )
    lines.extend(
        [
            "}",
            "",
            "/** Verification-only overlay emitter; source locations are retained. */",
            "object EmitSpecFlowOverlay extends App {",
            '  require(args.length == 1, "output directory is required")',
            '  val targetDir = Paths.get(args(0)).resolve("rtl").toAbsolutePath.toString',
            "  ChiselStage.emitSystemVerilogFile(",
            "    new SpecFlowOverlay,",
            '    args = Array("--target-dir", targetDir),',
            "    firtoolOpts = Array(",
            '      "--disable-all-randomization",',
            '      "--emit-chisel-asserts-as-sva",',
            '      "--lowering-options=disallowLocalVariables,disallowPackedArrays,verifLabels"',
            "    )",
            "  )",
            "}",
        ]
    )
    return RenderedOverlay("\n".join(lines) + "\n", tuple(properties), "SpecFlowOverlay")


def compile_reviewed_package(
    workspace: Any,
    output_dir: Optional[Path] = None,
    asset_library: Optional[AssetLibrary] = None,
    package_path: Optional[Path] = None,
    frozen_replay: bool = False,
) -> CompiledOverlay:
    """Render the reviewed package into the copied project, transactionally."""

    assets = asset_library or load_reviewed_assets()
    manifest = _read_json(workspace.manifest_path)
    round_id = manifest["current_round"]
    if frozen_replay:
        if package_path is None:
            raise MonitorCompilerError("frozen replay requires an exact package path")
        package_path = Path(package_path).resolve()
    else:
        if manifest.get("review_state") != "approved":
            raise MonitorCompilerError("verification package is not review-approved")
        stage1 = workspace.stage_dir(round_id, "asset_authoring")
        if validate_completed_stage(stage1, get_stage_spec("asset_authoring")) is None:
            raise MonitorCompilerError("asset_authoring handoff or artifact hash is invalid")
        package_path = stage1 / "verification_package.json"
    package = load_run_local_package(package_path)
    semantic = _read_json(workspace.indexes_dir / "chisel_semantic_index.json")
    project = _read_json(workspace.inputs_dir / "project_contract.json")
    configuration = _read_json(workspace.inputs_dir / "configuration.json")
    bindings = {row["binding_id"]: row for row in package["bindings"]}
    reset_source = _monitor_reset_source(project)
    units = []
    for monitor in package["monitors"]:
        validate_monitor_ir(monitor, semantic, assets)
        units.append(
            lower_monitor(
                monitor,
                semantic,
                bindings,
                reset_source=reset_source,
            )
        )
    adapters = {
        row["acquisition"]["adapter_id"]
        for row in package["bindings"]
    }
    if len(adapters) != 1:
        raise MonitorCompilerError("the current compiler requires one exact wrapper API adapter")
    adapter_id = next(iter(adapters))
    adapter = assets.api_adapters.get(adapter_id)
    if adapter is None:
        raise MonitorCompilerError(f"reviewed API adapter is missing: {adapter_id}")
    rendered = render_overlay(units, project, configuration, semantic, adapter)
    source_root = workspace.project_workspace / project["build"]["overlay_source_root"]
    source_path = source_root / "SpecFlowOverlay.scala"
    if source_path.exists():
        raise FileExistsError(f"overlay source already exists: {source_path}")
    source_root.mkdir(parents=True, exist_ok=True)
    temporary = source_path.with_name(source_path.name + ".tmp")
    temporary.write_text(rendered.source, encoding="utf-8")
    temporary.replace(source_path)

    output = Path(output_dir or workspace.stage_dir(round_id, "compile_verify")).resolve()
    output.mkdir(parents=True, exist_ok=True)
    assertion_delta_path = output / "source_assertion_delta.json"
    _write_json(
        assertion_delta_path,
        {
            "schema_version": SOURCE_ASSERTION_DELTA_SCHEMA_VERSION,
            "verification_package_sha256": file_sha256(package_path),
            "properties": list(rendered.properties),
        },
    )
    overlay_manifest_path = output / "overlay_manifest.json"
    _write_json(
        overlay_manifest_path,
        {
            "schema_version": OVERLAY_MANIFEST_SCHEMA_VERSION,
            "verification_package_sha256": file_sha256(package_path),
            "wrapper_top": rendered.wrapper_top,
            "strategy": "wrapper",
            "source": {
                "path": str(source_path.relative_to(workspace.project_workspace)),
                "sha256": file_sha256(source_path),
            },
            "monitor_ids": [unit.monitor_id for unit in units],
            "property_count": len(rendered.properties),
            "compiler": "chiselspecflow.monitor_compiler.v1",
        },
    )
    diff_path = output / "overlay_diff.patch"
    diff_path.write_text(
        "".join(
            difflib.unified_diff(
                [],
                rendered.source.splitlines(keepends=True),
                fromfile="/dev/null",
                tofile=str(source_path.relative_to(workspace.project_workspace)),
            )
        ),
        encoding="utf-8",
    )
    return CompiledOverlay(source_path, overlay_manifest_path, assertion_delta_path, diff_path)


def _object_types(index: Mapping[str, Any]) -> Dict[str, Dict[str, Any]]:
    return {
        row["object_id"]: {
            "kind": row["chisel_type"]["kind"],
            "width": row["chisel_type"]["width"],
            "signed": row["chisel_type"]["signed"],
        }
        for row in index.get("objects", [])
        if row.get("fact_status") == "elaboration_confirmed"
    }


def _result_type(node: Mapping[str, Any]) -> ExpressionType:
    return _type_from_mapping(node["result_type"])


def _type_from_mapping(value: Mapping[str, Any]) -> ExpressionType:
    return ExpressionType(str(value["kind"]), int(value["width"]), bool(value["signed"]))


def _chisel_type(value: ExpressionType) -> str:
    if value.kind == "Bool":
        return "Bool()"
    return f"{value.kind}({value.width}.W)"


def _render_constructor(template: str, parameters: Mapping[str, Any]) -> str:
    placeholders = set(re.findall(r"\{([A-Za-z_][A-Za-z0-9_]*)\}", template))
    if placeholders != set(parameters):
        raise MonitorCompilerError("constructor template parameters do not exactly match configuration")
    rendered = template
    for name, value in parameters.items():
        if isinstance(value, bool):
            literal = "true" if value else "false"
        elif isinstance(value, int) and not isinstance(value, bool):
            literal = str(value)
        else:
            raise MonitorCompilerError(f"unsupported constructor parameter type: {name}")
        rendered = rendered.replace("{" + name + "}", literal)
    if "{" in rendered or "}" in rendered:
        raise MonitorCompilerError("constructor template contains unresolved syntax")
    return rendered


def _monitor_reset_source(project_contract: Mapping[str, Any]) -> str:
    formal = project_contract.get("formal")
    if not isinstance(formal, Mapping):
        raise MonitorCompilerError("project formal contract is missing")
    reset = formal.get("reset")
    active_high = formal.get("reset_active_high")
    if not isinstance(reset, str) or not reset or not isinstance(active_high, bool):
        raise MonitorCompilerError("project formal reset contract is malformed")
    source = "reset.asBool" if reset == "reset" else _scala_identifier(reset)
    return source if active_high else "!" + source


def _scala_identifier(value: str) -> str:
    normalized = re.sub(r"[^A-Za-z0-9_]", "_", str(value))
    if not normalized or not re.match(r"[A-Za-z_]", normalized):
        normalized = "id_" + normalized
    return normalized


def _read_json(path: Path) -> Dict[str, Any]:
    value = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise MonitorCompilerError(f"JSON object required: {path}")
    return value


def _write_json(path: Path, value: Mapping[str, Any]) -> None:
    temporary = Path(path).with_name(Path(path).name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)
