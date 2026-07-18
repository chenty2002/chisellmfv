"""Bounded Stage-1 controller with typed candidate-only model calls."""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Dict, Mapping, Optional, Protocol

from src.core.artifact_contract import file_sha256, write_stage_outcome
from src.core.formal_operations import canonical_sha256

from .assets import AssetLibrary, load_reviewed_assets
from .authoring_tools import (
    AMBIGUITY_TOOL_NAME,
    binding_tools,
    monitor_tools,
    obligation_tools,
)
from .config import (
    AUTHORING_CANDIDATES_SCHEMA_VERSION,
    CANDIDATE_ASSET_DELTA_SCHEMA_VERSION,
    REVIEW_REQUEST_SCHEMA_VERSION,
    STAGE_INPUTS_SCHEMA_VERSION,
)
from .ir.binding import BindingValidationError, validate_binding
from .ir.monitor import MonitorValidationError, validate_monitor
from .ir.obligation import ObligationValidationError, validate_obligation
from .ir.semantic import validate_semantic_index
from .stages import get_stage_spec
from .workspace import SpecFlowWorkspace


class AuthoringModel(Protocol):
    def chat_with_tools(self, **kwargs: Any) -> Dict[str, Any]: ...


class AuthoringError(ValueError):
    """Raised for a bounded protocol/validation failure after one repair."""

    def __init__(self, code: str, message: str):
        self.code = code
        super().__init__(f"{code}: {message}")


@dataclass(frozen=True)
class AuthoringResult:
    status: str
    stage_dir: Path
    review_request: Optional[Path]
    model_calls: int


def run_asset_authoring(
    workspace: SpecFlowWorkspace,
    model: AuthoringModel,
    asset_library: Optional[AssetLibrary] = None,
) -> AuthoringResult:
    """Author typed candidates and stop at the mandatory external review gate."""

    assets = asset_library or load_reviewed_assets()
    manifest = _read_json(workspace.manifest_path)
    if manifest.get("preflight_status") != "index_ready":
        raise AuthoringError("preflight_incomplete", "semantic index is not frozen")
    round_id = manifest["current_round"]
    stage_dir = workspace.stage_dir(round_id, "asset_authoring")
    if (stage_dir / "stage_result.json").exists():
        raise FileExistsError("asset_authoring already has a stage outcome")
    semantic = validate_semantic_index(
        _read_json(workspace.indexes_dir / "chisel_semantic_index.json")
    )
    public_spec = _read_json(workspace.inputs_dir / "public_spec_package.json")
    project = _read_json(workspace.inputs_dir / "project_contract.json")
    configuration = _read_json(workspace.inputs_dir / "configuration.json")
    revision_context = _load_revision_context(workspace, round_id)
    confirmed = [
        row for row in semantic.get("objects", [])
        if row.get("fact_status") == "elaboration_confirmed"
    ]
    if not confirmed:
        raise AuthoringError("no_confirmed_objects", "Stage 1 has no binding authority")
    object_types = {
        row["object_id"]: {
            "kind": row["chisel_type"]["kind"],
            "width": row["chisel_type"]["width"],
            "signed": row["chisel_type"]["signed"],
        }
        for row in confirmed
    }
    clause_slices = _clause_slices(
        workspace.inputs_dir / "specification.md",
        public_spec["normative_clause_ids"],
    )
    stage_inputs = {
        "schema_version": STAGE_INPUTS_SCHEMA_VERSION,
        "round_id": round_id,
        "project": {
            "project_id": project["project_id"],
            "generator": {
                "top_name": project["generator"]["top_name"],
                "configuration_schema": project["generator"]["configuration_schema"],
            },
            "formal": project["formal"],
        },
        "configuration": {
            "configuration_id": configuration["configuration_id"],
            "parameters": configuration["parameters"],
        },
        "specification": {
            "specification_id": public_spec["specification_id"],
            "spec_sha256": public_spec["spec_sha256"],
            "clauses": clause_slices,
        },
        "semantic_objects": confirmed,
        "asset_library": assets.snapshot(),
        "input_hashes": manifest["input_hashes"],
        "index_hashes": manifest["index_hashes"],
        "revision_context": revision_context,
    }
    _write_json(stage_dir / "stage_inputs.json", stage_inputs)
    calls = 0
    call_refs: list[str] = []
    call_audit: list[Dict[str, Any]] = []

    try:
        raw_obligations, used, refs = _request_candidates(
            model,
            expected_tool="submit_obligation_candidates",
            tools=obligation_tools(
                [row["locator"] for row in clause_slices],
                object_types,
                configuration["configuration_id"],
            ),
            context={"stage_inputs": stage_inputs, "task": "author verification obligations"},
            validator=lambda rows: _validate_obligations(
                rows,
                object_types,
                clause_slices,
                public_spec["spec_sha256"],
                configuration["configuration_id"],
            ),
            audit_log=call_audit,
        )
        calls += used
        call_refs += refs
        if any(row["support_status"] in {"unsupported", "ambiguous"} for row in raw_obligations):
            raise AuthoringError("unsupported_obligation", "candidate obligation is unsupported or ambiguous")
        obligation_ids = {row["obligation_id"] for row in raw_obligations}

        raw_bindings, used, refs = _request_candidates(
            model,
            expected_tool="submit_binding_candidates",
            tools=binding_tools(
                obligation_ids,
                confirmed,
                configuration["configuration_id"],
                assets.api_adapters,
            ),
            context={
                "task": "bind obligations to confirmed object IDs",
                "obligations": raw_obligations,
                "semantic_objects": confirmed,
                "api_adapters": assets.snapshot()["api_adapters"],
            },
            validator=lambda rows: _validate_bindings(
                rows,
                semantic,
                obligation_ids,
                configuration["configuration_id"],
                set(assets.api_adapters),
            ),
            audit_log=call_audit,
        )
        calls += used
        call_refs += refs
        binding_ids = {row["binding_id"] for row in raw_bindings}

        raw_monitors, used, refs = _request_candidates(
            model,
            expected_tool="submit_monitor_candidates",
            tools=monitor_tools(
                obligation_ids,
                binding_ids,
                object_types,
                configuration["configuration_id"],
                ("direct_relation.v1", "previous_value.v1"),
                public_spec["expected_property_ids"],
            ),
            context={
                "task": "compose typed monitor IR from reviewed archetype IDs",
                "obligations": raw_obligations,
                "bindings": raw_bindings,
                "archetypes": assets.snapshot()["monitor_archetypes"],
            },
            validator=lambda rows: _validate_monitors(
                rows,
                object_types,
                obligation_ids,
                binding_ids,
                assets,
                configuration["configuration_id"],
                set(public_spec["expected_property_ids"]),
            ),
            audit_log=call_audit,
        )
        calls += used
        call_refs += refs
        if revision_context["status"] == "required":
            _validate_revision_scope(
                workspace,
                revision_context,
                raw_obligations,
                raw_bindings,
                raw_monitors,
            )
    except AuthoringError as exc:
        calls = len(call_audit)
        _write_jsonl(_model_log_path(workspace, round_id), call_audit)
        _write_json(
            stage_dir / "authoring_candidates.json",
            {
                "schema_version": AUTHORING_CANDIDATES_SCHEMA_VERSION,
                "status": "unsupported" if exc.code != "spec_ambiguity" else "ambiguous",
                "obligations": locals().get("raw_obligations", []),
                "bindings": locals().get("raw_bindings", []),
                "monitors": locals().get("raw_monitors", []),
                "model_call_refs": call_refs,
                "error": {"code": exc.code, "message": str(exc)},
            },
        )
        write_stage_outcome(
            stage_dir,
            get_stage_spec("asset_authoring"),
            {
                "success": False,
                "status": "ambiguous" if exc.code == "spec_ambiguity" else "unsupported",
                "error_kind": exc.code,
                "error": str(exc),
                "round_id": round_id,
                "model_calls": calls,
            },
            source_state=manifest,
        )
        _set_review_state(workspace, "not_applicable", "authoring_unsupported")
        return AuthoringResult(
            "ambiguous" if exc.code == "spec_ambiguity" else "unsupported",
            stage_dir,
            None,
            calls,
        )

    candidates = {
        "schema_version": AUTHORING_CANDIDATES_SCHEMA_VERSION,
        "status": "candidate",
        "obligations": raw_obligations,
        "bindings": raw_bindings,
        "monitors": raw_monitors,
        "model_call_refs": call_refs,
    }
    calls = len(call_audit)
    _write_jsonl(_model_log_path(workspace, round_id), call_audit)
    _write_json(stage_dir / "authoring_candidates.json", candidates)
    delta = {
        "schema_version": CANDIDATE_ASSET_DELTA_SCHEMA_VERSION,
        "base_asset_library_sha256": canonical_sha256(stage_inputs["asset_library"]),
        "new_run_local_ids": sorted(
            obligation_ids
            | binding_ids
            | {row["monitor_id"] for row in raw_monitors}
        ),
        "modified_reviewed_asset_ids": [],
    }
    _write_json(stage_dir / "candidate_asset_delta.json", delta)
    review_request = _build_review_request(stage_dir, candidates, delta, round_id)
    _write_json(stage_dir / "review_request.json", review_request)
    write_stage_outcome(
        stage_dir,
        get_stage_spec("asset_authoring"),
        {
            "success": False,
            "status": "awaiting_review",
            "error_kind": "review_required",
            "round_id": round_id,
            "model_calls": calls,
        },
        source_state=manifest,
    )
    _set_review_state(workspace, "awaiting_review", "awaiting_review")
    return AuthoringResult("awaiting_review", stage_dir, stage_dir / "review_request.json", calls)


def _request_candidates(
    model: AuthoringModel,
    *,
    expected_tool: str,
    tools: list[Dict[str, Any]],
    context: Mapping[str, Any],
    validator: Callable[[list[Mapping[str, Any]]], list[Dict[str, Any]]],
    audit_log: Optional[list[Dict[str, Any]]] = None,
) -> tuple[list[Dict[str, Any]], int, list[str]]:
    messages = [
        {
            "role": "system",
            "content": (
                "You are a bounded candidate generator. Use exactly the required named tool. "
                "Submit only IDs and typed IR from the supplied context. Never emit Scala, file edits, review, or approval."
            ),
        },
        {"role": "user", "content": json.dumps(context, sort_keys=True)},
    ]
    refs = []
    last_error = ""
    for attempt in range(2):
        if attempt:
            messages.append(
                {
                    "role": "user",
                    "content": "One bounded protocol repair is allowed. Correct this deterministic error: " + last_error,
                }
            )
        response = model.chat_with_tools(
            messages=messages,
            tools=tools,
            max_tokens=4096,
            temperature=0.0,
            tool_choice="required",
            parallel_tool_calls=False,
            usage_metadata={"stage": "asset_authoring", "task_type": "candidate_authoring"},
        )
        audit = {
            "schema_version": "specflow_model_call.v1",
            "sequence": len(audit_log or []) + 1,
            "stage": "asset_authoring",
            "expected_tool": expected_tool,
            "attempt": attempt + 1,
            "parallel_tool_calls": False,
            "response_type": response.get("type"),
        }
        if audit_log is not None:
            audit_log.append(audit)
        calls = response.get("function_calls") if response.get("type") == "function_calls" else None
        if not isinstance(calls, list) or len(calls) != 1:
            last_error = "exactly one function call is required"
            audit["outcome"] = "protocol_repair"
            audit["error"] = last_error
            continue
        call = calls[0]
        refs.append(str(call.get("id") or f"{expected_tool}:{attempt + 1}"))
        audit["call_id"] = refs[-1]
        audit["returned_tool"] = call.get("name")
        if call.get("name") == AMBIGUITY_TOOL_NAME:
            arguments = call.get("arguments")
            if not isinstance(arguments, Mapping) or set(arguments) != {"clause_ids", "reason"}:
                last_error = "malformed report_spec_ambiguity arguments"
                audit["outcome"] = "protocol_repair"
                audit["error"] = last_error
                continue
            audit["outcome"] = "spec_ambiguity"
            raise AuthoringError("spec_ambiguity", str(arguments["reason"]))
        if call.get("name") != expected_tool:
            last_error = f"unexpected tool {call.get('name')!r}"
            audit["outcome"] = "protocol_repair"
            audit["error"] = last_error
            continue
        arguments = call.get("arguments")
        if not isinstance(arguments, Mapping) or set(arguments) != {"candidates"} or not isinstance(arguments["candidates"], list):
            last_error = "tool arguments must contain only candidates[]"
            audit["outcome"] = "protocol_repair"
            audit["error"] = last_error
            continue
        if _contains_forbidden_authoring_content(arguments):
            last_error = "raw Scala, approval, and file-write fields are forbidden"
            audit["outcome"] = "protocol_repair"
            audit["error"] = last_error
            continue
        try:
            validated = validator(arguments["candidates"])
            audit["outcome"] = "accepted_candidates"
            audit["candidate_count"] = len(validated)
            return validated, attempt + 1, refs
        except (ObligationValidationError, BindingValidationError, MonitorValidationError, AuthoringError) as exc:
            last_error = str(exc)
            audit["outcome"] = "validation_repair"
            audit["error"] = last_error
    raise AuthoringError("candidate_repair_exhausted", last_error)


def _validate_obligations(
    rows: list[Mapping[str, Any]],
    object_types: Mapping[str, Mapping[str, Any]],
    clauses: list[Mapping[str, Any]],
    spec_sha256: str,
    configuration_id: str,
) -> list[Dict[str, Any]]:
    if not rows:
        raise AuthoringError("empty_candidates", "at least one obligation is required")
    by_locator = {row["locator"]: row for row in clauses}
    normalized = []
    ids = set()
    for row in rows:
        value = validate_obligation(row, object_types)
        identity = value["obligation_id"]
        if identity in ids:
            raise AuthoringError("duplicate_candidate_id", identity)
        ids.add(identity)
        clause = by_locator.get(value["clause_ref"]["locator"])
        if clause is None or value["clause_ref"]["spec_sha256"] != spec_sha256 or value["clause_ref"]["text_sha256"] != clause["text_sha256"]:
            raise AuthoringError("clause_hash_mismatch", identity)
        if value["configuration_domain"] != [configuration_id]:
            raise AuthoringError("configuration_not_applicable", identity)
        normalized.append(value)
    return normalized


def _validate_bindings(
    rows: list[Mapping[str, Any]],
    semantic: Mapping[str, Any],
    obligation_ids: set[str],
    configuration_id: str,
    adapter_ids: set[str],
) -> list[Dict[str, Any]]:
    if not rows:
        raise AuthoringError("empty_candidates", "at least one binding is required")
    normalized = []
    ids = set()
    for row in rows:
        value = validate_binding(
            row, semantic, obligation_ids, configuration_id, adapter_ids
        )
        identity = value["binding_id"]
        if identity in ids:
            raise AuthoringError("duplicate_candidate_id", identity)
        ids.add(identity)
        normalized.append(value)
    return normalized


def _validate_monitors(
    rows: list[Mapping[str, Any]],
    object_types: Mapping[str, Mapping[str, Any]],
    obligation_ids: set[str],
    binding_ids: set[str],
    assets: AssetLibrary,
    configuration_id: str,
    allowed_property_ids: set[str],
) -> list[Dict[str, Any]]:
    if not rows:
        raise AuthoringError("empty_candidates", "at least one monitor is required")
    normalized = []
    ids = set()
    source_property_ids = set()
    for row in rows:
        value = validate_monitor(
            row,
            object_types=object_types,
            obligation_ids=obligation_ids,
            binding_ids=binding_ids,
            archetypes=assets.monitor_archetypes,
            configuration_id=configuration_id,
        )
        identity = value["monitor_id"]
        if identity in ids:
            raise AuthoringError("duplicate_candidate_id", identity)
        ids.add(identity)
        monitor_property_ids = {
            prop["source_property_id"] for prop in value["properties"]
        }
        if not monitor_property_ids <= allowed_property_ids:
            raise AuthoringError(
                "unknown_source_property_id",
                str(sorted(monitor_property_ids - allowed_property_ids)),
            )
        duplicate_properties = source_property_ids & monitor_property_ids
        if duplicate_properties:
            raise AuthoringError(
                "duplicate_source_property", str(sorted(duplicate_properties))
            )
        source_property_ids |= monitor_property_ids
        normalized.append(value)
    return normalized


def _build_review_request(
    stage_dir: Path,
    candidates: Mapping[str, Any],
    delta: Mapping[str, Any],
    round_id: int,
) -> Dict[str, Any]:
    artifacts = []
    for name in ("stage_inputs.json", "authoring_candidates.json", "candidate_asset_delta.json"):
        artifacts.append({"artifact": name, "sha256": file_sha256(stage_dir / name)})
    intent_ids = sorted(
        [row["obligation_id"] for row in candidates["obligations"]]
        + [row["binding_id"] for row in candidates["bindings"]]
        + [row["monitor_id"] for row in candidates["monitors"]]
    )
    return {
        "schema_version": REVIEW_REQUEST_SCHEMA_VERSION,
        "round_id": round_id,
        "reviewed_hashes_required": artifacts,
        "semantic_intent_ids": intent_ids,
        "evidence_refs_required": True,
        "allowed_reviewers": ["codex", "human:<id>"],
        "candidate_delta_sha256": canonical_sha256(delta),
    }


def _clause_slices(path: Path, clause_ids: list[str]) -> list[Dict[str, str]]:
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    rows = []
    for clause_id in clause_ids:
        matches = [index for index, line in enumerate(lines) if re.search(rf"\b{re.escape(clause_id)}\b", line)]
        if not matches:
            raise AuthoringError("missing_clause", clause_id)
        start = matches[0]
        text_lines = [lines[start].strip()]
        index = start + 1
        while index < len(lines) and lines[index].strip() and not lines[index].lstrip().startswith(("- **", "| `")):
            text_lines.append(lines[index].strip())
            index += 1
        text = " ".join(text_lines)
        rows.append(
            {
                "locator": clause_id,
                "text": text,
                "text_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
            }
        )
    return rows


def _contains_forbidden_authoring_content(value: Any) -> bool:
    forbidden_keys = {"scala", "raw_scala", "source_code", "approval", "approve", "reviewer", "file_path", "patch"}
    if isinstance(value, Mapping):
        if any(str(key).lower() in forbidden_keys for key in value):
            return True
        return any(_contains_forbidden_authoring_content(item) for item in value.values())
    if isinstance(value, list):
        return any(_contains_forbidden_authoring_content(item) for item in value)
    return False


def _load_revision_context(
    workspace: SpecFlowWorkspace, round_id: int
) -> Dict[str, Any]:
    if round_id == 1:
        return {
            "schema_version": "revision_context.v1",
            "status": "not_required",
            "reason": "initial_round",
        }
    round_manifest = _read_json(workspace.round_dir(round_id) / "round.json")
    parent = round_manifest.get("parent_round")
    digest = round_manifest.get("revision_request_sha256")
    if not isinstance(parent, int) or parent >= round_id or not isinstance(digest, str):
        raise AuthoringError("revision_identity_invalid", "child round identity is malformed")
    revision_path = workspace.stage_dir(parent, "diagnose") / "revision_request.json"
    if not revision_path.is_file() or file_sha256(revision_path) != digest:
        raise AuthoringError("revision_identity_invalid", "parent revision request hash drifted")
    revision = _read_json(revision_path)
    if (
        revision.get("schema_version") != "revision_request.v1"
        or revision.get("status") != "required"
        or revision.get("parent_round") != parent
    ):
        raise AuthoringError("revision_identity_invalid", "parent revision request is not actionable")
    return {
        "schema_version": "revision_context.v1",
        "status": "required",
        "parent_round": parent,
        "revision_request_sha256": digest,
        "revision_layer": revision["revision_layer"],
        "evidence_refs": revision["evidence_refs"],
        "allowed_change_scope": revision["allowed_change_scope"],
        "forbidden_change_scope": revision["forbidden_change_scope"],
    }


def _validate_revision_scope(
    workspace: SpecFlowWorkspace,
    revision: Mapping[str, Any],
    obligations: list[Mapping[str, Any]],
    bindings: list[Mapping[str, Any]],
    monitors: list[Mapping[str, Any]],
) -> None:
    parent = revision["parent_round"]
    reference = _read_json(
        workspace.stage_dir(parent, "compile_verify") / "verification_package_ref.json"
    )
    source_run = Path(reference["source_run"]).resolve()
    relative = Path(reference["path"])
    if relative.is_absolute() or ".." in relative.parts:
        raise AuthoringError("revision_identity_invalid", "parent package path is unsafe")
    package_path = (source_run / relative).resolve()
    try:
        package_path.relative_to(source_run)
    except ValueError as exc:
        raise AuthoringError("revision_identity_invalid", "parent package path escapes") from exc
    if not package_path.is_file() or file_sha256(package_path) != reference.get("sha256"):
        raise AuthoringError("revision_identity_invalid", "parent package hash drifted")
    parent_package = _read_json(package_path)
    layer = revision["revision_layer"]
    changed = {
        "obligation": _revision_rows(obligations, "obligation")
        != _revision_rows(parent_package["obligations"], "obligation"),
        "binding": _revision_rows(bindings, "binding")
        != _revision_rows(parent_package["bindings"], "binding"),
        "monitor": _revision_rows(monitors, "monitor")
        != _revision_rows(parent_package["monitors"], "monitor"),
    }
    allowed = {
        "obligation": {"obligation", "monitor"},
        "assumption": {"obligation", "monitor"},
        "binding": {"binding", "monitor"},
        "monitor": {"monitor"},
    }.get(layer)
    if allowed is None:
        raise AuthoringError("revision_scope_invalid", f"unsupported revision layer: {layer}")
    forbidden_changes = sorted(name for name, differs in changed.items() if differs and name not in allowed)
    if forbidden_changes:
        raise AuthoringError(
            "revision_scope_violation",
            "candidate changes outside typed scope: " + ",".join(forbidden_changes),
        )
    if not any(changed[name] for name in allowed):
        raise AuthoringError("revision_noop", "revision candidate does not change an allowed layer")


def _revision_rows(rows: list[Mapping[str, Any]], layer: str) -> str:
    normalized = []
    for source in rows:
        row = json.loads(json.dumps(source, sort_keys=True))
        row.pop("configuration_domain", None)
        if layer == "obligation":
            row.pop("authoring_provenance", None)
        elif layer == "binding":
            row.pop("rationale", None)
            row.pop("rejected_alternatives", None)
            row.pop("review_state", None)
            row.pop("validation_errors", None)
            if isinstance(row.get("compatibility"), dict):
                row["compatibility"].pop("configuration", None)
        normalized.append(row)
    return json.dumps(normalized, sort_keys=True, separators=(",", ":"))


def _set_review_state(workspace: SpecFlowWorkspace, review_state: str, round_state: str) -> None:
    manifest = _read_json(workspace.manifest_path)
    manifest["review_state"] = review_state
    _write_json(workspace.manifest_path, manifest)
    round_path = workspace.round_dir(manifest["current_round"]) / "round.json"
    round_manifest = _read_json(round_path)
    round_manifest["state"] = round_state
    _write_json(round_path, round_manifest)


def _read_json(path: Path) -> Dict[str, Any]:
    value = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise AuthoringError("invalid_json_artifact", str(path))
    return value


def _write_json(path: Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def _write_jsonl(path: Path, rows: list[Mapping[str, Any]]) -> None:
    if path.exists():
        raise FileExistsError(f"model call log is immutable once written: {path}")
    path.write_text(
        "".join(json.dumps(row, sort_keys=True) + "\n" for row in rows),
        encoding="utf-8",
    )


def _model_log_path(workspace: SpecFlowWorkspace, round_id: int) -> Path:
    """Keep the round-1 name stable while allowing immutable revision logs."""

    name = "model_calls.jsonl" if round_id == 1 else f"model_calls.round-{round_id:04d}.jsonl"
    return workspace.logs_dir / name
