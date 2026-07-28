"""Model-free SpecFlow adapter for VerilogCausalAnalysis V2/V3."""

from __future__ import annotations

from contextlib import ExitStack
import json
from pathlib import Path
import re
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
    semantic_by_id = {
        str(row["object_id"]): row
        for row in semantic.get("objects", [])
        if isinstance(row, Mapping) and row.get("object_id")
    }
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
            make_request_v2,
            make_request_v3,
            prepare_causal_analysis,
            prepare_causal_session_v3,
            validate_semantic_graph_v3,
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

    graphs_by_id: Dict[str, Dict[str, Any]] = {}
    graph_rows = []
    graph_dir = stage3 / "causal_graphs"
    graph_dir.mkdir(parents=True, exist_ok=True)
    prepared_stack = ExitStack()
    prepared_by_identity: Dict[tuple[Any, ...], Any] = {}
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
            endpoint_signal = str(endpoint["emitted_signal"])
            if graph_builder is None:
                endpoint_signal = _resolve_fst_endpoint(
                    trace_path,
                    endpoint_signal,
                    semantic_by_id.get(str(endpoint["object_id"]), {}),
                )
            use_v3 = (
                config_row["causal_backend"]
                == "verilog_causal_analysis.v3"
            )
            if use_v3:
                request = make_request_v3(
                    trace={
                        "artifact_id": f"trace_{canonical_sha256(operation_id)[:16]}",
                        "path": str(trace_path),
                        "format": "fst",
                        "sha256": trace_row["sha256"],
                        "bytes": trace_row["bytes"],
                    },
                    rtl_files=[dict(row) for row in rtl_rows],
                    semantic_profile={
                        "name": "chisel",
                        "version": "chisel-semantic-profile.v1",
                        "features": [
                            "instance_graph",
                            "compiler_net_normalization",
                            "register_transition",
                            "aggregate",
                            "handshake",
                            "pipeline",
                            "temporal_interval",
                            "waitfor",
                            "source_provenance",
                        ],
                    },
                    clock={"signal": seed["clock_signal"], "edge": "rising"},
                    endpoint={
                        "signal": endpoint_signal,
                        "cycle": seed["failure_cycle"],
                        "projection": None,
                    },
                    semantic_inputs=[],
                    bounds={
                        "max_signal_nodes": config_row["max_nodes"],
                        "max_semantic_nodes": config_row["max_nodes"],
                        "max_edges": config_row["max_nodes"] * 4,
                        "max_seed_count": 8,
                        "max_intervals_per_signal": 64,
                        "max_temporal_samples": 64000,
                        "max_waitfor_nodes": 120,
                        "max_waitfor_edges": 240,
                        "max_scc_candidates": 8,
                    },
                    random_seed=config_row["random_seed"],
                    strict=True,
                )
            else:
                request = make_request_v2(
                    trace={
                        "path": str(trace_path),
                        "format": "fst",
                        "sha256": trace_row["sha256"],
                        "bytes": trace_row["bytes"],
                    },
                    rtl_files=[dict(row) for row in rtl_rows],
                    clock_signal=seed["clock_signal"],
                    endpoint_signal=endpoint_signal,
                    endpoint_cycle=seed["failure_cycle"],
                    max_depth=config_row["max_depth"],
                    max_nodes=config_row["max_nodes"],
                    random_seed=config_row["random_seed"],
                    strict=True,
                )
            if graph_builder is not None:
                built = graph_builder(request)
                graph = (
                    validate_semantic_graph_v3(built)
                    if use_v3
                    else validate_graph_v2(built)
                )
            else:
                prepared_identity = (
                    config_row["causal_backend"],
                    request.trace.path,
                    request.trace.sha256,
                    request.trace.bytes,
                    tuple(
                        (
                            artifact.artifact_id,
                            artifact.path,
                            artifact.sha256,
                            artifact.bytes,
                        )
                        for artifact in request.rtl_files
                    ),
                    (
                        request.clock_signal
                        if not use_v3
                        else request.clock_signal
                    ),
                    request.strict,
                )
                prepared = prepared_by_identity.get(prepared_identity)
                if prepared is None:
                    prepared = prepared_stack.enter_context(
                        prepare_causal_session_v3(request)
                        if use_v3
                        else prepare_causal_analysis(request)
                    )
                    prepared_by_identity[prepared_identity] = prepared
                built = prepared.build(request)
                graph = (
                    validate_semantic_graph_v3(built)
                    if use_v3
                    else validate_graph_v2(built)
                )
            identity = graph["identity"]
            if (
                identity["request_sha256"] != request.request_sha256
                or identity["trace_sha256"] != request.trace.sha256
                or (
                    not use_v3
                    and identity["random_seed"] != request.random_seed
                )
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
    prepared_stack.close()
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


def _resolve_fst_endpoint(
    trace_path: Path,
    emitted_signal: str,
    semantic_object: Mapping[str, Any],
) -> str:
    """Resolve only the FST display suffix for one hash-bound typed object."""

    try:
        import pylibfst
    except Exception as exc:
        raise CausalBackendError("pylibfst is unavailable for endpoint identity") from exc
    fst = pylibfst.lib.fstReaderOpen(str(trace_path).encode("UTF-8"))
    if fst == pylibfst.ffi.NULL:
        raise CausalBackendError("cannot open the certified FST for endpoint identity")
    try:
        _scopes, signals = pylibfst.get_scopes_signals2(fst)
        rows = [
            (str(name), int(signal.length))
            for name, signal in signals.by_name.items()
        ]
    finally:
        pylibfst.lib.fstReaderClose(fst)
    expected_width = (
        semantic_object.get("chisel_type", {}).get("width")
        if isinstance(semantic_object.get("chisel_type"), Mapping)
        else None
    )
    return _resolve_fst_display_name(emitted_signal, rows, expected_width)


def _resolve_fst_display_name(
    emitted_signal: str,
    waveform_signals: list[tuple[str, int]],
    expected_width: Any,
) -> str:
    """Join a typed emitted name to its unique exact FST packed-range spelling."""

    exact = [name for name, _width in waveform_signals if name == emitted_signal]
    if len(exact) == 1:
        return exact[0]
    if (
        isinstance(expected_width, bool)
        or not isinstance(expected_width, int)
        or expected_width < 1
    ):
        raise CausalBackendError("typed endpoint width is unavailable")
    packed = re.compile(rf"^{re.escape(emitted_signal)} \[[0-9]+:[0-9]+\]$")
    matches = [
        name
        for name, width in waveform_signals
        if width == expected_width and packed.fullmatch(name)
    ]
    if len(matches) != 1:
        raise CausalBackendError(
            "typed endpoint does not have one exact FST display identity"
        )
    return matches[0]


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
