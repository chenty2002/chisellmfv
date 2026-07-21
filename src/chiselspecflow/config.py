"""Frozen input contracts and strict Iteration-1 validators."""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import Any, Dict, Mapping, Optional, Tuple


PROJECT_SCHEMA_VERSION = "specflow_project.v1"
PUBLIC_SPEC_PACKAGE_SCHEMA_VERSION = "public_spec_package.v1"
PROPERTY_DECOMPOSITION_SCHEMA_VERSION = "specflow_property_decomposition.v1"
RUN_MANIFEST_SCHEMA_VERSION = "specflow_run_manifest.v1"
MODEL_VIEW_MANIFEST_SCHEMA_VERSION = "model_view_manifest.v1"
OBLIGATION_SCHEMA_VERSION = "verification_obligations.v1"
SEMANTIC_INDEX_SCHEMA_VERSION = "chisel_semantic_index.v1"
BINDING_SCHEMA_VERSION = "chisel_bindings.v1"
MONITOR_SCHEMA_VERSION = "chisel_monitors.v1"
EXPRESSION_SCHEMA_VERSION = "expression_ir.v1"
REVIEW_RECORD_SCHEMA_VERSION = "review_record.v1"
STAGE_INPUTS_SCHEMA_VERSION = "specflow_stage_inputs.v1"
AUTHORING_CANDIDATES_SCHEMA_VERSION = "authoring_candidates.v1"
CANDIDATE_ASSET_DELTA_SCHEMA_VERSION = "candidate_asset_delta.v1"
REVIEW_REQUEST_SCHEMA_VERSION = "review_request.v1"
VERIFICATION_PACKAGE_SCHEMA_VERSION = "verification_package.v1"
OVERLAY_MANIFEST_SCHEMA_VERSION = "overlay_manifest.v1"
SOURCE_ASSERTION_DELTA_SCHEMA_VERSION = "source_assertion_delta.v1"
ELABORATION_CERTIFICATE_SCHEMA_VERSION = "elaboration_certificate.v1"
OPERATION_PLAN_SCHEMA_VERSION = "verification_operation_plan.v2"
PROPERTY_RESULT_MAP_SCHEMA_VERSION = "property_result_map.v5"
SEMANTIC_EVIDENCE_SCHEMA_VERSION = "semantic_evidence.v3"
TRACE_MANIFEST_SCHEMA_VERSION = "trace_manifest.v1"
EVIDENCE_PROJECTION_SCHEMA_VERSION = "evidence_projection.v1"
DIAGNOSIS_CANDIDATE_SCHEMA_VERSION = "diagnosis_candidate.v1"
FINAL_VERDICT_SCHEMA_VERSION = "final_verdict.v1"
REVISION_REQUEST_SCHEMA_VERSION = "revision_request.v1"

SCHEMA_VERSIONS: Mapping[str, str] = MappingProxyType(
    {
        "project": PROJECT_SCHEMA_VERSION,
        "public_spec_package": PUBLIC_SPEC_PACKAGE_SCHEMA_VERSION,
        "property_decomposition": PROPERTY_DECOMPOSITION_SCHEMA_VERSION,
        "run_manifest": RUN_MANIFEST_SCHEMA_VERSION,
        "model_view_manifest": MODEL_VIEW_MANIFEST_SCHEMA_VERSION,
        "obligations": OBLIGATION_SCHEMA_VERSION,
        "semantic_index": SEMANTIC_INDEX_SCHEMA_VERSION,
        "bindings": BINDING_SCHEMA_VERSION,
        "monitors": MONITOR_SCHEMA_VERSION,
        "expression": EXPRESSION_SCHEMA_VERSION,
        "review_record": REVIEW_RECORD_SCHEMA_VERSION,
        "stage_inputs": STAGE_INPUTS_SCHEMA_VERSION,
        "authoring_candidates": AUTHORING_CANDIDATES_SCHEMA_VERSION,
        "candidate_asset_delta": CANDIDATE_ASSET_DELTA_SCHEMA_VERSION,
        "review_request": REVIEW_REQUEST_SCHEMA_VERSION,
        "verification_package": VERIFICATION_PACKAGE_SCHEMA_VERSION,
        "overlay_manifest": OVERLAY_MANIFEST_SCHEMA_VERSION,
        "source_assertion_delta": SOURCE_ASSERTION_DELTA_SCHEMA_VERSION,
        "elaboration_certificate": ELABORATION_CERTIFICATE_SCHEMA_VERSION,
        "operation_plan": OPERATION_PLAN_SCHEMA_VERSION,
        "property_result_map": PROPERTY_RESULT_MAP_SCHEMA_VERSION,
        "semantic_evidence": SEMANTIC_EVIDENCE_SCHEMA_VERSION,
        "trace_manifest": TRACE_MANIFEST_SCHEMA_VERSION,
        "evidence_projection": EVIDENCE_PROJECTION_SCHEMA_VERSION,
        "diagnosis_candidate": DIAGNOSIS_CANDIDATE_SCHEMA_VERSION,
        "final_verdict": FINAL_VERDICT_SCHEMA_VERSION,
        "revision_request": REVISION_REQUEST_SCHEMA_VERSION,
    }
)


@dataclass(frozen=True)
class SpecFlowRunConfig:
    """Paths fixed before a production SpecFlow run is materialized."""

    project_contract: Path
    specification: Path
    configuration: Path
    run_root: Path = Path("runs/specflow")
    copy_strategy: str = "isolated_copy"
    opaque_task_id: Optional[str] = None
    expected_property_ids: Tuple[str, ...] = ()
    component_ids: Tuple[str, ...] = ()

    def __post_init__(self) -> None:
        for field_name in (
            "project_contract",
            "specification",
            "configuration",
            "run_root",
        ):
            value = Path(getattr(self, field_name)).resolve()
            object.__setattr__(self, field_name, value)
        if self.copy_strategy != "isolated_copy":
            raise ValueError(
                "SpecFlow requires the isolated_copy workspace strategy"
            )
        if self.opaque_task_id is not None and (
            not isinstance(self.opaque_task_id, str) or not self.opaque_task_id.strip()
        ):
            raise ValueError("opaque_task_id must be a non-empty string when supplied")
        selected = tuple(self.expected_property_ids)
        if (
            len(set(selected)) != len(selected)
            or any(not isinstance(row, str) or not _SAFE_ID_RE.fullmatch(row) for row in selected)
        ):
            raise ValueError("expected_property_ids must contain unique safe public IDs")
        object.__setattr__(self, "expected_property_ids", selected)
        selected_components = tuple(self.component_ids)
        if (
            len(set(selected_components)) != len(selected_components)
            or any(
                not isinstance(row, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]*", row)
                for row in selected_components
            )
        ):
            raise ValueError("component_ids must contain unique safe component IDs")
        object.__setattr__(self, "component_ids", selected_components)


class SpecFlowConfigError(ValueError):
    """Raised when a project or generator configuration is not exact and safe."""


@dataclass(frozen=True)
class ProjectContract:
    path: Path
    repository_root: Path
    project_root: Path
    project_id: str
    source_roots: Tuple[Path, ...]
    model_visible_roots: Tuple[Path, ...]
    model_visible_files: Tuple[Path, ...]
    model_view: Mapping[str, Any]
    build: Mapping[str, Any]
    generator: Mapping[str, Any]
    formal: Mapping[str, Any]
    raw: Mapping[str, Any]


@dataclass(frozen=True)
class GeneratorConfiguration:
    path: Path
    configuration_id: str
    parameters: Mapping[str, Any]
    raw: Mapping[str, Any]


_PROJECT_FIELDS = {
    "schema_version",
    "project_id",
    "project_root",
    "source_roots",
    "model_visible_roots",
    "model_visible_files",
    "model_view",
    "build",
    "generator",
    "formal",
}
_MODEL_VIEW_FIELDS = {"strategy", "exclusions"}
_MODEL_VIEW_EXCLUSION_FIELDS = {"path", "source_sha256", "start_line", "end_line"}
_BUILD_FIELDS = {
    "kind",
    "compile_argv",
    "overlay_source_root",
    "elaborate_main",
    "generated_sv_globs",
    "firtool_options",
}
_GENERATOR_FIELDS = {
    "package",
    "constructor",
    "top_name",
    "configuration_schema",
    "parameter_schema",
}
_FORMAL_FIELDS = {"clock", "reset", "reset_active_high", "backend"}
_CONFIG_FIELDS = {"schema_version", "configuration_id", "parameters"}
_SAFE_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]*$")


def load_project_contract(path: Path) -> ProjectContract:
    path = Path(path).resolve()
    value = _read_json_object(path, "project contract")
    _require_exact_fields(value, _PROJECT_FIELDS, "project contract")
    if value["schema_version"] != PROJECT_SCHEMA_VERSION:
        raise SpecFlowConfigError("unsupported project contract schema version")
    project_id = _safe_id(value["project_id"], "project_id")
    repository_root = _find_repository_root(path.parent)
    project_root_rel = _safe_relative(value["project_root"], "project_root")
    project_root = (repository_root / project_root_rel).resolve()
    _require_within(project_root, repository_root, "project_root")
    if not project_root.is_dir():
        raise SpecFlowConfigError(f"project_root is not a directory: {project_root}")

    source_roots = _validated_roots(
        project_root, value["source_roots"], "source_roots"
    )
    visible_roots = _validated_roots(
        project_root, value["model_visible_roots"], "model_visible_roots"
    )
    for visible in visible_roots:
        if not any(_is_within(visible, source) for source in source_roots):
            raise SpecFlowConfigError(
                "model_visible_roots entries must be contained by source_roots"
            )

    visible_files = tuple(
        _safe_relative(item, "model_visible_files")
        for item in _nonempty_list(value["model_visible_files"], "model_visible_files")
    )
    for relative in visible_files:
        absolute = (project_root / relative).resolve()
        _require_within(absolute, project_root, "model_visible_files")
        if absolute.suffix != ".scala" or not absolute.is_file():
            raise SpecFlowConfigError(
                f"model-visible source must be an existing Scala file: {relative}"
            )
        if not any(_is_within(absolute, root) for root in visible_roots):
            raise SpecFlowConfigError(
                f"model-visible file is outside the visible roots: {relative}"
            )

    model_view = _object(value["model_view"], "model_view")
    _require_exact_fields(model_view, _MODEL_VIEW_FIELDS, "model_view")
    strategy = model_view["strategy"]
    if strategy not in {"exact_copy", "line_redaction"}:
        raise SpecFlowConfigError("model_view.strategy is unsupported")
    exclusions = model_view["exclusions"]
    if not isinstance(exclusions, list):
        raise SpecFlowConfigError("model_view.exclusions must be a list")
    normalized_exclusions = []
    occupied: Dict[Path, list[tuple[int, int]]] = {}
    for index, exclusion in enumerate(exclusions):
        exclusion = _object(exclusion, f"model_view.exclusions[{index}]")
        _require_exact_fields(
            exclusion,
            _MODEL_VIEW_EXCLUSION_FIELDS,
            f"model_view.exclusions[{index}]",
        )
        relative = _safe_relative(exclusion["path"], "model_view exclusion path")
        if relative not in visible_files:
            raise SpecFlowConfigError("model_view exclusion path is not model-visible")
        source = project_root / relative
        if exclusion["source_sha256"] != _file_sha256(source):
            raise SpecFlowConfigError("model_view exclusion source hash mismatch")
        start, end = exclusion["start_line"], exclusion["end_line"]
        line_count = len(source.read_text(encoding="utf-8").splitlines())
        if (
            not isinstance(start, int)
            or isinstance(start, bool)
            or not isinstance(end, int)
            or isinstance(end, bool)
            or start < 1
            or end < start
            or end > line_count
        ):
            raise SpecFlowConfigError("model_view exclusion line range is invalid")
        if any(not (end < prior_start or start > prior_end) for prior_start, prior_end in occupied.setdefault(relative, [])):
            raise SpecFlowConfigError("model_view exclusion line ranges overlap")
        occupied[relative].append((start, end))
        normalized_exclusions.append(dict(exclusion))
    if strategy == "exact_copy" and normalized_exclusions:
        raise SpecFlowConfigError("exact_copy model view cannot declare exclusions")
    if strategy == "line_redaction" and not normalized_exclusions:
        raise SpecFlowConfigError("line_redaction model view requires exclusions")
    normalized_model_view = {
        "strategy": strategy,
        "exclusions": normalized_exclusions,
    }

    build = _object(value["build"], "build")
    _require_exact_fields(build, _BUILD_FIELDS, "build")
    if build["kind"] != "sbt":
        raise SpecFlowConfigError("Iteration 1 supports only an sbt build")
    _validate_argv(build["compile_argv"], "build.compile_argv")
    _safe_relative(build["overlay_source_root"], "build.overlay_source_root")
    _nonempty_string(build["elaborate_main"], "build.elaborate_main")
    for pattern in _nonempty_list(
        build["generated_sv_globs"], "build.generated_sv_globs"
    ):
        _nonempty_string(pattern, "build.generated_sv_globs entry")
        if Path(pattern).is_absolute() or ".." in Path(pattern).parts:
            raise SpecFlowConfigError("generated SV globs must be project-relative")
    firtool_options = _nonempty_list(build["firtool_options"], "build.firtool_options")
    if any("strip-debug-info" in str(option) for option in firtool_options):
        raise SpecFlowConfigError(
            "verification elaboration must not use --strip-debug-info"
        )

    generator = _object(value["generator"], "generator")
    _require_exact_fields(generator, _GENERATOR_FIELDS, "generator")
    for field in ("package", "constructor", "top_name", "configuration_schema"):
        _nonempty_string(generator[field], f"generator.{field}")
    parameter_schema = _object(generator["parameter_schema"], "parameter_schema")
    if not parameter_schema:
        raise SpecFlowConfigError("generator.parameter_schema cannot be empty")
    for name, rule in parameter_schema.items():
        _safe_id(name, "generator parameter name")
        _validate_parameter_rule(rule, name)

    formal = _object(value["formal"], "formal")
    _require_exact_fields(formal, _FORMAL_FIELDS, "formal")
    _nonempty_string(formal["clock"], "formal.clock")
    _nonempty_string(formal["reset"], "formal.reset")
    if not isinstance(formal["reset_active_high"], bool):
        raise SpecFlowConfigError("formal.reset_active_high must be boolean")
    if formal["backend"] != "jaspergold":
        raise SpecFlowConfigError("Iteration 1 supports only the jaspergold backend")

    return ProjectContract(
        path=path,
        repository_root=repository_root,
        project_root=project_root,
        project_id=project_id,
        source_roots=source_roots,
        model_visible_roots=visible_roots,
        model_visible_files=visible_files,
        model_view=MappingProxyType(normalized_model_view),
        build=MappingProxyType(dict(build)),
        generator=MappingProxyType(dict(generator)),
        formal=MappingProxyType(dict(formal)),
        raw=MappingProxyType(dict(value)),
    )


def load_generator_configuration(
    path: Path, project: ProjectContract
) -> GeneratorConfiguration:
    path = Path(path).resolve()
    value = _read_json_object(path, "generator configuration")
    _require_exact_fields(value, _CONFIG_FIELDS, "generator configuration")
    if value["schema_version"] != project.generator["configuration_schema"]:
        raise SpecFlowConfigError("configuration schema does not match project contract")
    configuration_id = _safe_id(value["configuration_id"], "configuration_id")
    parameters = _object(value["parameters"], "configuration parameters")
    schema = project.generator["parameter_schema"]
    _require_exact_fields(parameters, set(schema), "configuration parameters")
    for name, rule in schema.items():
        _validate_parameter_value(parameters[name], rule, name)
    return GeneratorConfiguration(
        path=path,
        configuration_id=configuration_id,
        parameters=MappingProxyType(dict(parameters)),
        raw=MappingProxyType(dict(value)),
    )


def _validate_parameter_rule(rule: Any, name: str) -> None:
    rule = _object(rule, f"parameter schema {name}")
    kind = rule.get("type")
    if kind == "boolean" and set(rule) == {"type"}:
        return
    if kind == "integer" and set(rule) == {"type", "minimum", "maximum"}:
        minimum, maximum = rule["minimum"], rule["maximum"]
        if (
            isinstance(minimum, int)
            and not isinstance(minimum, bool)
            and isinstance(maximum, int)
            and not isinstance(maximum, bool)
            and minimum <= maximum
        ):
            return
    raise SpecFlowConfigError(f"unsupported parameter schema for {name}")


def _validate_parameter_value(value: Any, rule: Mapping[str, Any], name: str) -> None:
    if rule["type"] == "boolean":
        if not isinstance(value, bool):
            raise SpecFlowConfigError(f"configuration parameter {name} must be boolean")
        return
    if isinstance(value, bool) or not isinstance(value, int):
        raise SpecFlowConfigError(f"configuration parameter {name} must be integer")
    if value < rule["minimum"] or value > rule["maximum"]:
        raise SpecFlowConfigError(f"configuration parameter {name} is out of range")


def _validated_roots(project_root: Path, values: Any, label: str) -> Tuple[Path, ...]:
    roots = []
    for item in _nonempty_list(values, label):
        relative = _safe_relative(item, label)
        absolute = (project_root / relative).resolve()
        _require_within(absolute, project_root, label)
        if not absolute.is_dir():
            raise SpecFlowConfigError(f"{label} entry is not a directory: {item}")
        roots.append(absolute)
    if len(set(roots)) != len(roots):
        raise SpecFlowConfigError(f"{label} contains duplicate roots")
    return tuple(roots)


def _validate_argv(value: Any, label: str) -> Tuple[str, ...]:
    argv = _nonempty_list(value, label)
    for item in argv:
        _nonempty_string(item, f"{label} entry")
        if "\x00" in item or "\n" in item or "\r" in item:
            raise SpecFlowConfigError(f"{label} contains an invalid control character")
    return tuple(argv)


def _find_repository_root(start: Path) -> Path:
    for candidate in (start, *start.parents):
        if (candidate / ".git").exists():
            return candidate.resolve()
    raise SpecFlowConfigError("project contract is not inside a repository")


def _safe_relative(value: Any, label: str) -> Path:
    text = _nonempty_string(value, label)
    path = Path(text)
    if path.is_absolute() or ".." in path.parts or path == Path("."):
        raise SpecFlowConfigError(f"{label} must be a non-root relative path")
    return path


def _safe_id(value: Any, label: str) -> str:
    text = _nonempty_string(value, label)
    if not _SAFE_ID_RE.fullmatch(text):
        raise SpecFlowConfigError(f"{label} contains unsupported characters")
    return text


def _nonempty_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise SpecFlowConfigError(f"{label} must be a non-empty string")
    return value


def _nonempty_list(value: Any, label: str) -> list:
    if not isinstance(value, list) or not value:
        raise SpecFlowConfigError(f"{label} must be a non-empty list")
    return value


def _object(value: Any, label: str) -> Dict[str, Any]:
    if not isinstance(value, dict):
        raise SpecFlowConfigError(f"{label} must be an object")
    return value


def _require_exact_fields(value: Mapping[str, Any], fields: set, label: str) -> None:
    actual = set(value)
    if actual != fields:
        missing = sorted(fields - actual)
        extra = sorted(actual - fields)
        raise SpecFlowConfigError(f"{label} fields mismatch: missing={missing}, extra={extra}")


def _read_json_object(path: Path, label: str) -> Dict[str, Any]:
    if not path.is_file():
        raise SpecFlowConfigError(f"{label} does not exist: {path}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise SpecFlowConfigError(f"cannot read {label}: {path}") from exc
    return _object(value, label)


def _is_within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _require_within(path: Path, root: Path, label: str) -> None:
    if not _is_within(path, root):
        raise SpecFlowConfigError(f"{label} escapes its allowed root")


def _file_sha256(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()
