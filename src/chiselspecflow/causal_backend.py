"""Model-free SpecFlow adapter for VerilogCausalAnalysis V2."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Callable, Dict, Mapping, Optional

from src.core.artifact_contract import file_sha256
from src.core.formal_operations import canonical_sha256

from .causal_contract import (
    effective_causal_config,
    validate_causal_graph_manifest,
    validate_causal_seed,
)
from .causal_source_projection import (
    CausalSourceProjectionError,
    not_required_source_projection,
    project_causal_sources,
)


class CausalBackendError(ValueError):
    """Raised when the adapter itself cannot preserve exact identity."""


GraphBuilder = Callable[[Any], Mapping[str, Any]]


def materialize_causal_evidence(
    workspace: Any,
    round_id: int,
    projection: Mapping[str, Any],
    *,
    config: Optional[Mapping[str, Any]] = None,
    graph_builder: Optional[GraphBuilder] = None,
) -> tuple[Dict[str, Any], Dict[str, Any], Dict[str, Dict[str, Any]]]:
    """Build immutable causal graphs only from certified Stage-2 artifacts."""

    config_row = effective_causal_config(config)
    stage2 = workspace.stage_dir(round_id, "compile_verify")
    stage3 = workspace.stage_dir(round_id, "diagnose")
    certificate_path = stage2 / "elaboration_certificate.json"
    trace_manifest_path = stage2 / "trace_manifest.json"
    semantic_path = workspace.indexes_dir / "chisel_semantic_index.json"
    certificate = _read_json(certificate_path)
    trace_manifest = _read_json(trace_manifest_path)
    semantic = _read_json(semantic_path)
    projection_identity = projection.get("identity", {})
    observation_identity = projection.get("observation_map", {})
    if (
        projection_identity.get("elaboration_certificate_sha256")
        != file_sha256(certificate_path)
        or projection_identity.get("trace_manifest_sha256")
        != file_sha256(trace_manifest_path)
        or observation_identity.get("semantic_index_sha256")
        != file_sha256(semantic_path)
    ):
        raise CausalBackendError(
            "causal adapter input identity drifted after evidence projection"
        )
    analyzer = _analyzer_identity()
    rtl_rows = certificate.get("generated_files")
    rtl_set_sha256 = _rtl_set_identity(rtl_rows)
    inputs = {
        "evidence_projection_sha256": canonical_sha256(dict(projection)),
        "elaboration_certificate_sha256": file_sha256(certificate_path),
        "trace_manifest_sha256": file_sha256(trace_manifest_path),
        "semantic_index_sha256": file_sha256(semantic_path),
        "generated_rtl_set_sha256": rtl_set_sha256,
    }
    base_manifest = {
        "schema_version": "causal_graph_manifest.v1",
        "round_id": round_id,
        "policy": config_row["causal_policy"],
        "analyzer": analyzer,
        "inputs": inputs,
    }
    traces = [
        row
        for row in projection.get("traces", [])
        if isinstance(row, Mapping) and row.get("operation_id")
    ]
    if config_row["causal_policy"] == "off" or not traces:
        reason = (
            "causal_policy_off"
            if config_row["causal_policy"] == "off"
            else "no_exact_cex"
        )
        manifest = {
            **base_manifest,
            "status": "not_required",
            "graphs": [],
            "errors": [{"code": reason, "detail": reason}],
        }
        source = not_required_source_projection(round_id, "not_required", reason)
        return validate_causal_graph_manifest(manifest), source, {}

    trace_by_operation = {
        str(row["operation_id"]): row
        for row in trace_manifest.get("traces", [])
        if isinstance(row, Mapping)
    }
    errors = []
    requests = []
    for trace in sorted(traces, key=lambda row: str(row["operation_id"])):
        try:
            seed = validate_causal_seed(trace.get("causal_seed", {}))
        except Exception as exc:
            errors.append(
                {
                    "code": "causal_seed_invalid",
                    "detail": _safe_error(exc),
                    "operation_id": str(trace["operation_id"]),
                }
            )
            continue
        operation_id = str(trace["operation_id"])
        if (
            seed["operation_id"] != operation_id
            or seed["failure_cycle"] != trace.get("failure_cycle")
        ):
            errors.append(
                {
                    "code": "causal_seed_trace_identity_mismatch",
                    "detail": operation_id,
                    "operation_id": operation_id,
                }
            )
            continue
        if seed["status"] != "ready" or trace.get("status") != "complete":
            errors.append(
                {
                    "code": "causal_seed_not_ready",
                    "detail": str(trace["operation_id"]),
                    "operation_id": str(trace["operation_id"]),
                }
            )
            continue
        trace_row = trace_by_operation.get(operation_id)
        if trace_row is None:
            errors.append(
                {
                    "code": "causal_trace_identity_missing",
                    "detail": str(trace["operation_id"]),
                    "operation_id": str(trace["operation_id"]),
                }
            )
            continue
        if trace_row.get("emitted_property_id") != trace.get(
            "emitted_property_id"
        ):
            errors.append(
                {
                    "code": "causal_trace_property_identity_mismatch",
                    "detail": operation_id,
                    "operation_id": operation_id,
                }
            )
            continue
        source_rows = {
            (
                str(row.get("object_id")),
                str(row.get("binding_id")),
                str(row.get("emitted_signal")),
            )
            for row in trace.get("source_objects", [])
            if isinstance(row, Mapping)
        }
        for endpoint in seed["endpoint_candidates"]:
            endpoint_identity = (
                str(endpoint["object_id"]),
                str(endpoint["binding_id"]),
                str(endpoint["emitted_signal"]),
            )
            if endpoint_identity not in source_rows:
                errors.append(
                    {
                        "code": "causal_endpoint_trace_join_mismatch",
                        "detail": operation_id,
                        "operation_id": operation_id,
                        "endpoint_object_id": endpoint["object_id"],
                    }
                )
                continue
            requests.append((trace, trace_row, seed, endpoint))

    if not requests:
        manifest = {
            **base_manifest,
            "status": "incomplete",
            "graphs": [],
            "errors": errors
            or [
                {
                    "code": "causal_endpoint_unavailable",
                    "detail": "no typed endpoint was available",
                }
            ],
        }
        source = not_required_source_projection(
            round_id, "incomplete", "causal_endpoint_unavailable"
        )
        return validate_causal_graph_manifest(manifest), source, {}

    try:
        from verilog_causal_analysis import (
            build_causal_graph_v2,
            make_request_v2,
            validate_graph_v2,
        )
    except Exception as exc:
        manifest = {
            **base_manifest,
            "status": "incomplete",
            "graphs": [],
            "errors": errors
            + [
                {
                    "code": "causal_backend_unavailable",
                    "detail": _safe_error(exc),
                }
            ],
        }
        source = not_required_source_projection(
            round_id, "incomplete", "causal_backend_unavailable"
        )
        return validate_causal_graph_manifest(manifest), source, {}

    builder = graph_builder or build_causal_graph_v2
    graphs_by_id: Dict[str, Dict[str, Any]] = {}
    graph_rows = []
    graph_dir = stage3 / "causal_graphs"
    graph_dir.mkdir(parents=True, exist_ok=True)
    for trace, trace_row, seed, endpoint in requests:
        operation_id = str(trace["operation_id"])
        if trace_row.get("format") != "fst":
            errors.append(
                {
                    "code": "causal_trace_format_unsupported",
                    "detail": str(trace_row.get("format")),
                    "operation_id": operation_id,
                }
            )
            continue
        try:
            trace_path = Path(str(trace_row["path"])).resolve()
            if (
                not trace_path.is_file()
                or trace_path.stat().st_size != trace_row.get("bytes")
                or file_sha256(trace_path) != trace_row.get("sha256")
            ):
                raise CausalBackendError("causal trace hash drifted")
            request = make_request_v2(
                trace={
                    "path": str(trace_path),
                    "format": "fst",
                    "sha256": trace_row["sha256"],
                    "bytes": trace_row["bytes"],
                },
                rtl_files=[dict(row) for row in rtl_rows],
                clock_signal=seed["clock_signal"],
                endpoint_signal=endpoint["emitted_signal"],
                endpoint_cycle=seed["failure_cycle"],
                max_depth=config_row["max_depth"],
                max_nodes=config_row["max_nodes"],
                random_seed=config_row["random_seed"],
                strict=True,
            )
            graph = validate_graph_v2(builder(request))
            identity = graph["identity"]
            if (
                identity["request_sha256"] != request.request_sha256
                or identity["trace_sha256"] != request.trace.sha256
                or identity["random_seed"] != request.random_seed
            ):
                raise CausalBackendError(
                    "causal graph identity does not match its exact request"
                )
            existing = graphs_by_id.get(str(graph["graph_id"]))
            if existing is not None and existing != graph:
                raise CausalBackendError(
                    "one graph ID has conflicting payloads"
                )
            graph_path = graph_dir / f"{graph['graph_id']}.json"
            _write_json(graph_path, graph)
            relative = graph_path.relative_to(stage3).as_posix()
            graphs_by_id[str(graph["graph_id"])] = graph
            graph_rows.append(
                {
                    "operation_id": operation_id,
                    "endpoint_object_id": endpoint["object_id"],
                    "graph_id": graph["graph_id"],
                    "path": relative,
                    "sha256": file_sha256(graph_path),
                    "status": graph["status"],
                }
            )
        except Exception as exc:
            errors.append(
                {
                    "code": "causal_graph_build_failed",
                    "detail": _safe_error(exc),
                    "operation_id": operation_id,
                    "endpoint_object_id": endpoint["object_id"],
                }
            )
    graph_rows.sort(
        key=lambda row: (
            row["operation_id"],
            row["endpoint_object_id"],
            row["graph_id"],
        )
    )
    if graph_rows:
        manifest_status = (
            "complete"
            if not errors and all(row["status"] == "complete" for row in graph_rows)
            else "incomplete"
        )
    else:
        manifest_status = (
            "unsupported"
            if errors
            and all(row["code"] == "causal_trace_format_unsupported" for row in errors)
            else "incomplete"
        )
    manifest = validate_causal_graph_manifest(
        {
            **base_manifest,
            "status": manifest_status,
            "graphs": graph_rows,
            "errors": errors,
        }
    )
    if graphs_by_id:
        try:
            source = project_causal_sources(
                list(graphs_by_id.values()),
                rtl_files=rtl_rows,
                semantic_index=semantic,
                project_root=workspace.project_workspace,
                round_id=round_id,
            )
        except CausalSourceProjectionError as exc:
            source = not_required_source_projection(
                round_id, "incomplete", _safe_error(exc)
            )
    else:
        source = not_required_source_projection(
            round_id,
            "unsupported" if manifest_status == "unsupported" else "incomplete",
            "no_causal_graph",
        )
    return manifest, source, graphs_by_id


def load_bound_graphs(
    stage3: Path, manifest: Mapping[str, Any]
) -> Dict[str, Dict[str, Any]]:
    stage3 = Path(stage3).resolve()
    graphs = {}
    for row in manifest.get("graphs", []):
        relative = Path(str(row["path"]))
        if relative.is_absolute() or ".." in relative.parts:
            raise CausalBackendError("causal graph path escapes Stage 3")
        path = (stage3 / relative).resolve()
        try:
            path.relative_to(stage3)
        except ValueError as exc:
            raise CausalBackendError("causal graph path escapes Stage 3") from exc
        if not path.is_file() or file_sha256(path) != row["sha256"]:
            raise CausalBackendError("causal graph hash drifted")
        graph = _read_json(path)
        if graph.get("graph_id") != row["graph_id"]:
            raise CausalBackendError("causal graph identity mismatch")
        graphs[str(row["graph_id"])] = graph
    return graphs


def _rtl_set_identity(value: Any) -> str:
    if not isinstance(value, list) or not value:
        raise CausalBackendError("certificate has no generated RTL closure")
    identities = []
    seen = set()
    for row in value:
        required = {"artifact_id", "path", "sha256", "bytes"}
        if not isinstance(row, Mapping) or set(row) != required:
            raise CausalBackendError(
                "certificate generated RTL row has invalid exact fields"
            )
        path = Path(str(row["path"])).resolve()
        if (
            not path.is_file()
            or path.stat().st_size != row["bytes"]
            or file_sha256(path) != row["sha256"]
        ):
            raise CausalBackendError("certificate generated RTL hash drifted")
        artifact_id = str(row["artifact_id"])
        if artifact_id in seen:
            raise CausalBackendError("certificate repeats an RTL artifact ID")
        seen.add(artifact_id)
        identities.append(
            {
                "artifact_id": artifact_id,
                "sha256": row["sha256"],
                "bytes": row["bytes"],
            }
        )
    return canonical_sha256(
        sorted(identities, key=lambda row: row["artifact_id"])
    )


def _analyzer_identity() -> Dict[str, Any]:
    analyzer_root = (
        Path(__file__).resolve().parents[2] / "VerilogCausalAnalysis"
    )
    lock_path = analyzer_root / "TOOLCHAIN.lock.json"
    revision = "unavailable"
    hdl_revision = "unavailable"
    tree_sha256 = canonical_sha256({"status": "unavailable"})
    if lock_path.is_file():
        lock = _read_json(lock_path)
        revision = str(
            lock.get("verilog_causal_analysis", {}).get("revision", "unavailable")
        )
        hdl_revision = str(
            lock.get("hdlconvertor", {}).get("revision", "unavailable")
        )
        _validate_locked_dirty_paths(analyzer_root, lock)
        runtime_files = [analyzer_root / "pyproject.toml"]
        runtime_files.extend(
            sorted(
                (analyzer_root / "src" / "verilog_causal_analysis").rglob(
                    "*.py"
                )
            )
        )
        if not all(path.is_file() for path in runtime_files):
            raise CausalBackendError(
                "causal analyzer runtime closure is incomplete"
            )
        tree_sha256 = canonical_sha256(
            {
                "toolchain_lock_sha256": file_sha256(lock_path),
                "runtime_files": [
                    {
                        "path": path.relative_to(analyzer_root).as_posix(),
                        "sha256": file_sha256(path),
                        "bytes": path.stat().st_size,
                    }
                    for path in runtime_files
                ],
            }
        )
    return {
        "name": "VerilogCausalAnalysis",
        "revision": revision,
        "tree_sha256": tree_sha256,
        "hdlconvertor_revision": hdl_revision,
    }


def _validate_locked_dirty_paths(
    analyzer_root: Path, lock: Mapping[str, Any]
) -> None:
    for section, base in (
        ("verilog_causal_analysis", analyzer_root),
        ("hdlconvertor", analyzer_root),
    ):
        rows = lock.get(section, {}).get("dirty_paths", [])
        if not isinstance(rows, list):
            raise CausalBackendError("causal toolchain dirty baseline is invalid")
        for row in rows:
            if (
                not isinstance(row, Mapping)
                or set(row) != {"path", "sha256"}
                or not isinstance(row["path"], str)
            ):
                raise CausalBackendError(
                    "causal toolchain dirty path row is invalid"
                )
            relative = Path(row["path"])
            if relative.is_absolute() or ".." in relative.parts:
                raise CausalBackendError(
                    "causal toolchain dirty path is unsafe"
                )
            path = (base / relative).resolve()
            try:
                path.relative_to(analyzer_root.resolve())
            except ValueError as exc:
                raise CausalBackendError(
                    "causal toolchain dirty path escapes analyzer root"
                ) from exc
            if not path.is_file() or file_sha256(path) != row["sha256"]:
                raise CausalBackendError(
                    "causal toolchain dirty baseline drifted"
                )


def _safe_error(error: Exception) -> str:
    text = f"{type(error).__name__}: {error}"
    parts = []
    for part in text.split():
        parts.append("<redacted-path>" if part.startswith("/") else part)
    return " ".join(parts)[:1000]


def _read_json(path: Path) -> Dict[str, Any]:
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise CausalBackendError(f"cannot read JSON artifact: {Path(path).name}") from exc
    if not isinstance(value, dict):
        raise CausalBackendError(f"JSON object required: {Path(path).name}")
    return value


def _write_json(path: Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    temporary.replace(path)
