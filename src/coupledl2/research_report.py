"""Reproducible thesis-experiment reports from CoupledL2 run artifacts.

The report intentionally treats absent, malformed, unreviewed, and tool-error
evidence as data.  It never filters those records before calculating a rate.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
from collections import Counter
from pathlib import Path
from typing import Any, Dict, Iterable, List, Mapping, Optional

from .property_ir import semantic_review_completeness, structural_completeness


ASSET_ROOT = Path(__file__).with_name("property_assets")
SCHEMA_ROOT = ASSET_ROOT / "schemas"
PRIMARY_STATUSES = {
    "proven", "cex", "covered", "unreachable", "inconclusive", "not_run", "tool_error",
}
REQUIRED_EXPERIMENT_CASES = (
    "XiangShan-CoupledL2-deadlock-v0",
    "XiangShan-CoupledL2-write_read",
)
class ResearchReportError(ValueError):
    """Raised when a requested report scope is not well formed."""


def generate_research_report(
    run_dirs: Iterable[Path | str], output_dir: Path | str
) -> Dict[str, Any]:
    """Aggregate explicit run artifacts and write the Iteration-4 deliverables.

    ``run_dirs`` are run roots (the directories that contain ``results``), not
    arbitrary artifact directories.  A partial run is accepted: its missing
    artifacts are recorded in every relevant denominator.
    """
    runs = _load_runs(run_dirs)
    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    schemas = _load_schema_catalog()

    coverage = _property_coverage(runs, schemas)
    binding = _binding_metrics(runs)
    generation = _generation_metrics(runs)
    formal = _formal_metrics(runs)
    diagnosis = _diagnosis_metrics(runs, formal["records"])
    revision = _revision_metrics(runs)
    matrix = _experiment_matrix(runs, coverage, binding, formal, diagnosis, revision)

    artifacts = {
        "property_coverage.json": coverage,
        "binding_metrics.json": binding,
        "generation_metrics.json": generation,
        "formal_metrics.json": formal,
        "diagnosis_metrics.json": diagnosis,
        "revision_metrics.json": revision,
        "experiment_matrix.json": matrix,
    }
    for name, value in artifacts.items():
        _write_json(output / name, value)
    _write_coverage_csv(output / "property_coverage.csv", coverage["properties"])

    return {
        "schema_version": "research_report.v1",
        "output_dir": str(output),
        "run_ids": [run["run_id"] for run in runs],
        "artifacts": sorted(artifacts) + ["property_coverage.csv"],
    }


def _load_runs(run_dirs: Iterable[Path | str]) -> List[Dict[str, Any]]:
    paths = [Path(path).resolve() for path in run_dirs]
    if not paths:
        raise ResearchReportError("research report requires at least one run directory")
    if len(paths) != len(set(paths)):
        raise ResearchReportError("research report run directories must be unique")
    runs = []
    for run_dir in paths:
        if not run_dir.is_dir():
            raise ResearchReportError(f"run directory not found: {run_dir}")
        results = run_dir / "results"
        package = _read_artifact(
            results / "by_stage/02_bind_properties/property_package.json"
        )
        artifacts = {
            "property_package": package,
            "traceability": _embedded_artifact(package, "traceability"),
            "delta": _read_artifact(results / "by_stage/02_bind_properties/assertion_delta.json"),
            "binding_build": _read_artifact(results / "by_stage/02_bind_properties/build_result.json"),
            "baseline": _read_artifact(results / "preflight/baseline_assertion_inventory.json"),
            "result_map": _read_artifact(results / "by_stage/03_invoke_verification/property_result_map.json"),
            "diagnosis": _read_artifact(results / "by_stage/04_waveform_explanation/diagnosis.json"),
            "diagnosis_evidence": _read_artifact(results / "by_stage/04_waveform_explanation/diagnosis_evidence.json"),
            "semantic_evidence": _read_artifact(results / "by_stage/03_invoke_verification/semantic_evidence.json"),
        }
        revision_files = sorted(results.rglob("revision_outcome.json")) if results.is_dir() else []
        manifest_artifact = _read_artifact(run_dir / "manifest.json")
        manifest_value = manifest_artifact["value"]
        case_name = manifest_value.get("case_name") if isinstance(manifest_value, dict) else None
        runs.append(
            {
                "run_id": run_dir.name,
                "case_name": case_name or run_dir.name,
                "run_dir": run_dir,
                "artifacts": artifacts,
                "revision_artifacts": [_read_artifact(path) for path in revision_files],
            }
        )
    return runs


def _read_artifact(path: Path) -> Dict[str, Any]:
    if not path.is_file():
        return {"state": "missing", "path": str(path), "value": None}
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return {"state": "invalid", "path": str(path), "value": None, "error": str(exc)}
    return {"state": "present", "path": str(path), "value": value}


def _embedded_artifact(parent: Dict[str, Any], key: str) -> Dict[str, Any]:
    value = parent.get("value")
    if parent.get("state") != "present" or not isinstance(value, dict):
        return {"state": parent.get("state", "missing"), "path": parent.get("path"), "value": None}
    embedded = value.get(key)
    if not isinstance(embedded, dict):
        return {"state": "invalid", "path": parent.get("path"), "value": None}
    return {"state": "present", "path": parent.get("path") + f"#{key}", "value": embedded}


def _load_schema_catalog() -> Dict[str, Dict[str, Any]]:
    catalog = {}
    for path in sorted(SCHEMA_ROOT.glob("*.json")):
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        schema_id = payload.get("property_schema_id")
        if isinstance(schema_id, str):
            catalog[schema_id] = {
                "payload": payload,
                "path": str(path.relative_to(ASSET_ROOT)),
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            }
    return catalog


def _property_coverage(runs: List[Dict[str, Any]], schemas: Mapping[str, Dict[str, Any]]) -> Dict[str, Any]:
    rows = []
    artifact_states = Counter()
    for run in runs:
        trace = run["artifacts"]["traceability"]
        artifact_states[trace["state"]] += 1
        if trace["state"] != "present" or not isinstance(trace["value"], dict):
            continue
        properties = trace["value"].get("properties", [])
        if not isinstance(properties, list):
            artifact_states["invalid_payload"] += 1
            continue
        semantic_artifact = run["artifacts"]["semantic_evidence"]
        semantic_by_instance = {}
        independent_gold = {"status": "not_adjudicated"}
        semantic_status = "inconclusive"
        experiment_status = "excluded"
        if semantic_artifact["state"] == "present" and isinstance(semantic_artifact["value"], dict):
            semantic_by_instance = {
                entry.get("instance_id"): entry
                for entry in semantic_artifact["value"].get("instances", [])
                if isinstance(entry, dict)
            }
            independent_gold = semantic_artifact["value"].get(
                "independent_gold_label", independent_gold
            )
            semantic_status = semantic_artifact["value"].get("semantic_status", semantic_status)
            experiment_status = semantic_artifact["value"].get("experiment_status", experiment_status)
        for item in properties:
            if not isinstance(item, dict):
                artifact_states["invalid_property"] += 1
                continue
            schema_id = item.get("property_schema_id")
            schema = schemas.get(schema_id, {})
            semantic = schema.get("payload", {})
            source = item.get("source") if isinstance(item.get("source"), dict) else {}
            protocol_rule = item.get("protocol_rule") if isinstance(item.get("protocol_rule"), dict) else {}
            channels = semantic.get("channel_scope", {}).get("channels", []) if isinstance(semantic.get("channel_scope"), dict) else []
            review_status = str(item.get("review_status", "not_reviewed"))
            structural_complete = bool(semantic) and structural_completeness(semantic)
            reviewed_complete = bool(semantic) and semantic_review_completeness(
                semantic, review_status
            )
            events = semantic.get("event_automaton", {}).get("events", []) if isinstance(semantic.get("event_automaton"), dict) else []
            trigger_events = [event for event in events if event.get("kind") == "trigger"]
            response_events = [event for event in events if event.get("kind") == "response"]
            instance_evidence = semantic_by_instance.get(item.get("instance_id"), {})
            rows.append(
                {
                    "run_id": run["run_id"],
                    "instance_id": item.get("instance_id"),
                    "property_schema_id": schema_id,
                    "template_id": item.get("template_id"),
                    "approved_by_codex": review_status == "approved",
                    "review_status": review_status,
                    "semantic_status": instance_evidence.get("semantic_verdict", {}).get("status", semantic_status),
                    "experiment_status": experiment_status,
                    "experiment_eligible": experiment_status == "eligible"
                    and instance_evidence.get("semantic_verdict", {}).get("status") == "eligible",
                    "independent_gold_status": independent_gold.get("status", "not_adjudicated"),
                    "layer": semantic.get("layer"),
                    "channels": channels if isinstance(channels, list) else [],
                    "trigger_events": trigger_events,
                    "response_events": response_events,
                    "obligation_kind": semantic.get("obligation_kind"),
                    "lowering_family": semantic.get("lowering_family"),
                    "rule_id": protocol_rule.get("rule_id", semantic.get("rule_id")),
                    "rule_locator": protocol_rule.get("locator", source.get("locator")),
                    "source_kind": source.get("kind", semantic.get("source", {}).get("kind") if isinstance(semantic.get("source"), dict) else None),
                    "structural_complete": structural_complete,
                    "semantic_review_complete": reviewed_complete,
                    "schema_resolution": "resolved" if semantic else "missing_from_repository",
                    "schema_path": schema.get("path"),
                    "schema_current_sha256": schema.get("sha256"),
                    "rtl_property_count": len(item.get("rtl_properties", [])) if isinstance(item.get("rtl_properties"), list) else 0,
                    "traceability_artifact": trace["path"],
                }
            )
    review_counts = Counter(str(row["review_status"]) for row in rows)
    layer_counts = Counter(str(row["layer"] or "missing") for row in rows)
    channel_counts = Counter(channel for row in rows for channel in row["channels"])
    source_counts = Counter(str(row["source_kind"] or "missing") for row in rows)
    return {
        "schema_version": "property_coverage.v1",
        "denominators": {
            "runs_requested": len(runs),
            "runs_with_traceability": artifact_states["present"],
            "runs_missing_or_invalid_traceability": len(runs) - artifact_states["present"],
            "property_instances": len(rows),
            "schema_resolved_instances": sum(row["schema_resolution"] == "resolved" for row in rows),
        },
        "counts": {
            "by_layer": dict(sorted(layer_counts.items())),
            "by_channel": dict(sorted(channel_counts.items())),
            "by_source_kind": dict(sorted(source_counts.items())),
            "by_review_status": dict(sorted(review_counts.items())),
            "structural_complete": sum(row["structural_complete"] for row in rows),
            "structural_incomplete_or_unresolved": sum(not row["structural_complete"] for row in rows),
            "semantic_review_complete": sum(row["semantic_review_complete"] for row in rows),
            "experiment_eligible": sum(row["experiment_eligible"] for row in rows),
            "approved_by_codex": sum(row["approved_by_codex"] for row in rows),
            "independently_adjudicated": sum(row["independent_gold_status"] == "adjudicated" for row in rows),
        },
        "properties": rows,
        "artifact_states": dict(sorted(artifact_states.items())),
    }


def _binding_metrics(runs: List[Dict[str, Any]]) -> Dict[str, Any]:
    packages, traceability = [], {}
    package_states = Counter()
    for run in runs:
        package = run["artifacts"]["property_package"]
        package_states[package["state"]] += 1
        if package["state"] == "present" and isinstance(package["value"], dict):
            for item in package["value"].get("traceability", {}).get("properties", []):
                if isinstance(item, dict):
                    packages.append({"run_id": run["run_id"], **item})
        trace = run["artifacts"]["traceability"]
        if trace["state"] == "present" and isinstance(trace["value"], dict):
            for item in trace["value"].get("properties", []):
                if isinstance(item, dict) and isinstance(item.get("instance_id"), str):
                    traceability[(run["run_id"], item["instance_id"])] = item

    profile_ids = sorted({
        run["artifacts"]["property_package"]["value"].get("property_profile_id")
        for run in runs
        if run["artifacts"]["property_package"]["state"] == "present"
        and isinstance(run["artifacts"]["property_package"]["value"], dict)
        and isinstance(run["artifacts"]["property_package"]["value"].get("property_profile_id"), str)
    })
    ranking = _gold_binding_ranking(profile_ids)
    approved = sum(
        traceability.get((package["run_id"], package.get("instance_id")), {}).get("review_status") == "approved"
        for package in packages
    )
    return {
        "schema_version": "binding_metrics.v1",
        "denominators": {
            "runs_requested": len(runs),
            "property_package_runs": package_states["present"],
            "property_package_runs_missing_or_invalid": len(runs) - package_states["present"],
            "property_instances": len(packages),
            "ranking_trials": ranking["evaluated_slot_count"],
        },
        "counts": {
            "approved_property_bindings": approved,
            "not_approved_or_unresolved_property_bindings": len(packages) - approved,
            "review_intervention_count": ranking["review_intervention_count"],
        },
        "top_k_recall": {
            "top_1": ranking["top_1_recall"],
            "top_3": ranking["top_3_recall"],
        },
        "profiles": ranking["profiles"],
        "artifact_states": dict(sorted(package_states.items())),
    }


def _gold_binding_ranking(profile_ids: Iterable[str]) -> Dict[str, Any]:
    trials = []
    profiles = []
    for profile_id in profile_ids:
        path = ASSET_ROOT / "gold_bindings" / f"{profile_id}.json"
        if not path.is_file():
            profiles.append({"property_profile_id": profile_id, "state": "missing_gold_binding"})
            continue
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            profiles.append({"property_profile_id": profile_id, "state": "invalid_gold_binding"})
            continue
        selection = payload.get("selection_trials", [])
        if not isinstance(selection, list):
            selection = []
        profiles.append({
            "property_profile_id": profile_id,
            "state": "present",
            "approved_binding_count": len(payload.get("bindings", {})) if isinstance(payload.get("bindings"), dict) else 0,
            "selection_trial_count": len(selection),
            "artifact": str(path),
        })
        trials.extend(item for item in selection if isinstance(item, dict))
    ranks = []
    for trial in trials:
        candidates, gold = trial.get("ranked_candidate_ids"), trial.get("gold_candidate_id")
        if isinstance(candidates, list) and gold in candidates:
            ranks.append(candidates.index(gold) + 1)
    count = len(ranks)
    return {
        "evaluated_slot_count": count,
        "top_1_recall": sum(rank <= 1 for rank in ranks) / count if count else None,
        "top_3_recall": sum(rank <= 3 for rank in ranks) / count if count else None,
        "review_intervention_count": sum(bool(trial.get("review_intervened")) for trial in trials),
        "profiles": profiles,
    }


def _generation_metrics(runs: List[Dict[str, Any]]) -> Dict[str, Any]:
    totals = Counter()
    run_rows = []
    for run in runs:
        artifacts = run["artifacts"]
        package = artifacts["property_package"]
        trace = artifacts["traceability"]
        delta = artifacts["delta"]
        baseline = artifacts["baseline"]
        build = artifacts["binding_build"]
        packages = _property_instances(package["value"]) if package["state"] == "present" else []
        trace_labels = _trace_labels(trace["value"]) if trace["state"] == "present" else []
        delta_labels = _delta_labels(delta["value"]) if delta["state"] == "present" else []
        totals["property_instances"] += len(packages)
        totals["traceability_labels"] += len(trace_labels)
        totals["delta_labels"] += len(delta_labels)
        totals[f"build_{build['state']}"] += 1
        totals[f"baseline_{baseline['state']}"] += 1
        if build["state"] == "present":
            totals["compile_success"] += bool(build["value"].get("success")) if isinstance(build["value"], dict) else 0
            totals["compile_failure"] += not bool(build["value"].get("success")) if isinstance(build["value"], dict) else 1
        if baseline["state"] == "present" and isinstance(baseline["value"], dict):
            totals["baseline_entries"] += len(baseline["value"].get("entries", []))
            totals["baseline_disabled"] += int(baseline["value"].get("disabled_count", 0))
        run_rows.append({
            "run_id": run["run_id"],
            "property_instance_count": len(packages),
            "traceability_rtl_label_count": len(trace_labels),
            "assertion_delta_rtl_label_count": len(delta_labels),
            "rtl_label_survival": _ratio(len(delta_labels), len(trace_labels)) if trace["state"] == "present" else None,
            "compile_state": build["state"],
            "compile_success": build["value"].get("success") if build["state"] == "present" and isinstance(build["value"], dict) else None,
            "baseline_state": baseline["state"],
            "baseline_entry_count": len(baseline["value"].get("entries", [])) if baseline["state"] == "present" and isinstance(baseline["value"], dict) else None,
        })
    return {
        "schema_version": "generation_metrics.v2",
        "metric_role": "engineering_gates_only",
        "denominators": {
            "runs_requested": len(runs),
            "property_instances": totals["property_instances"],
            "traceability_rtl_labels": totals["traceability_labels"],
            "compile_attempts": totals["build_present"],
            "baseline_inventories": totals["baseline_present"],
        },
        "counts": dict(sorted(totals.items())),
        "rates": {
            "compile_success_rate": _ratio(totals["compile_success"], totals["build_present"]),
            "rtl_label_survival_rate": _ratio(totals["delta_labels"], totals["traceability_labels"]),
        },
        "runs": run_rows,
    }


def _formal_metrics(runs: List[Dict[str, Any]]) -> Dict[str, Any]:
    records, states = [], Counter()
    operation_denominators = Counter()
    instance_denominators = Counter()
    for run in runs:
        artifacts = run["artifacts"]
        result_map = artifacts["result_map"]
        expected = _trace_labels(artifacts["traceability"]["value"]) if artifacts["traceability"]["state"] == "present" else _delta_labels(artifacts["delta"]["value"]) if artifacts["delta"]["state"] == "present" else []
        result_payload = result_map["value"] if result_map["state"] == "present" else {}
        states[f"result_map_{result_map['state']}"] += 1
        by_label = {}
        instance_by_operation = {}
        if isinstance(result_payload, dict):
            if result_payload.get("schema_version") == "property_result_map.v4":
                trace_value = artifacts["traceability"].get("value")
                trace_properties = trace_value.get("properties", []) if isinstance(trace_value, dict) else []
                label_by_property_id = {
                    item.get("expected_property_id"): item.get("rtl_label")
                    for prop in trace_properties
                    if isinstance(prop, dict)
                    for item in prop.get("rtl_properties", [])
                    if isinstance(item, dict)
                    and isinstance(item.get("expected_property_id"), str)
                    and isinstance(item.get("rtl_label"), str)
                }
                for instance in result_payload.get("instances", []):
                    if not isinstance(instance, dict):
                        continue
                    semantic_verdict = instance.get("semantic_verdict", {})
                    instance_denominators["instances"] += 1
                    instance_denominators[
                        f"semantic_{semantic_verdict.get('status', 'inconclusive')}"
                    ] += 1
                    if (
                        semantic_verdict.get("status") == "eligible"
                        and result_payload.get("experiment_status") == "eligible"
                    ):
                        instance_denominators["experiment_eligible"] += 1
                    for item in instance.get("operations", []):
                        if item.get("role") != "primary_assertion":
                            continue
                        label = label_by_property_id.get(item.get("rtl_property_id"))
                        if label:
                            by_label[label] = item
                            instance_by_operation[item.get("operation_id")] = semantic_verdict
                operation_denominators["requested"] += int(
                    result_payload.get("expected_operation_count", 0)
                )
                operation_denominators["accounted"] += int(
                    result_payload.get("accounted_operation_count", 0)
                )
        for label in expected:
            item = by_label.pop(label, None)
            status = item.get("status") if isinstance(item, dict) else "missing"
            if status not in PRIMARY_STATUSES:
                status = "missing"
            semantic_verdict = (
                instance_by_operation.get(item.get("operation_id"), {})
                if isinstance(item, dict)
                else {}
            )
            records.append(
                _formal_record(
                    run["run_id"],
                    label,
                    status,
                    item,
                    result_map,
                    semantic_verdict=semantic_verdict,
                    experiment_status=(
                        result_payload.get("experiment_status")
                        if isinstance(result_payload, dict)
                        else "excluded"
                    ),
                )
            )
        for label, item in sorted(by_label.items()):
            records.append(_formal_record(run["run_id"], label, "unexpected", item, result_map))
    counts = Counter(record["status"] for record in records)
    runtime = sum(record["runtime_s"] for record in records if isinstance(record["runtime_s"], (int, float)))
    cex_records = [record for record in records if record["status"] == "cex"]
    return {
        "schema_version": "formal_metrics.v1",
        "denominators": {
            "runs_requested": len(runs),
            "expected_primary_properties": sum(record["status"] != "unexpected" for record in records),
            "requested_operations": operation_denominators["requested"],
            "accounted_operations": operation_denominators["accounted"],
            "semantic_instances": instance_denominators["instances"],
            "semantic_eligible_instances": instance_denominators["semantic_eligible"],
            "experiment_eligible_instances": instance_denominators[
                "experiment_eligible"
            ],
            "experiment_excluded_instances": instance_denominators["instances"]
            - instance_denominators["experiment_eligible"],
            "result_map_runs": states["result_map_present"],
            "counterexamples": len(cex_records),
        },
        "counts": dict(sorted(counts.items())),
        "trace_availability": {
            "cex_with_trace": sum(record["trace_available"] for record in cex_records),
            "cex_without_trace": sum(not record["trace_available"] for record in cex_records),
            "all_primary_with_trace": sum(record["trace_available"] for record in records if record["status"] != "unexpected"),
        },
        "runtime_s_total": runtime,
        "records": records,
        "artifact_states": dict(sorted(states.items())),
        "status_layers": [
            {
                "run_id": run["run_id"],
                **{
                    key: run["artifacts"]["result_map"]["value"].get(key)
                    for key in ("execution_status", "formal_outcome", "semantic_status", "experiment_status")
                },
            }
            for run in runs
            if run["artifacts"]["result_map"]["state"] == "present"
            and isinstance(run["artifacts"]["result_map"]["value"], dict)
        ],
    }


def _formal_record(
    run_id: str,
    label: str,
    status: str,
    item: Optional[Dict[str, Any]],
    result_map: Dict[str, Any],
    *,
    semantic_verdict: Optional[Mapping[str, Any]] = None,
    experiment_status: str = "excluded",
) -> Dict[str, Any]:
    item = item or {}
    semantic_verdict = semantic_verdict or {}
    semantic_status = semantic_verdict.get("status", "inconclusive")
    return {
        "run_id": run_id,
        "rtl_label": label,
        "status": status,
        "reason": item.get("reason", "missing_primary_result" if status == "missing" else None),
        "bound": item.get("bound"),
        "runtime_s": item.get("runtime_s"),
        "trace_path": item.get("trace_path"),
        "trace_available": bool(item.get("trace_path")),
        "semantic_status": semantic_status,
        "experiment_status": experiment_status,
        "experiment_eligible": (
            semantic_status == "eligible" and experiment_status == "eligible"
        ),
        "result_map_artifact": result_map["path"],
    }


def _diagnosis_metrics(runs: List[Dict[str, Any]], formal_records: List[Dict[str, Any]]) -> Dict[str, Any]:
    cex = {(item["run_id"], item["rtl_label"]): item for item in formal_records if item["status"] == "cex"}
    diagnoses, evidence = {}, set()
    states = Counter()
    for run in runs:
        diagnosis = run["artifacts"]["diagnosis"]
        evidence_artifact = run["artifacts"]["diagnosis_evidence"]
        states[f"diagnosis_{diagnosis['state']}"] += 1
        states[f"diagnosis_evidence_{evidence_artifact['state']}"] += 1
        if diagnosis["state"] == "present" and isinstance(diagnosis["value"], dict):
            for item in diagnosis["value"].get("diagnoses", []):
                if isinstance(item, dict) and isinstance(item.get("property"), str):
                    diagnoses[(run["run_id"], item["property"])] = item
        if evidence_artifact["state"] == "present" and isinstance(evidence_artifact["value"], dict):
            for item in evidence_artifact["value"].get("properties", []):
                if isinstance(item, dict) and isinstance(item.get("property"), str):
                    evidence.add((run["run_id"], item["property"]))
    records = []
    for key, formal in sorted(cex.items()):
        item = diagnoses.pop(key, None)
        raw = item.get("classification") if item else "missing"
        records.append({
            "run_id": key[0], "rtl_label": key[1], "classification": raw,
            "category": _diagnosis_category(raw),
            "revision_target": item.get("revision_target") if item else None,
            "has_evidence_refs": bool(item and item.get("evidence_refs")),
            "reconstruction_available": key in evidence,
            "formal_trace_available": formal["trace_available"],
        })
    orphan_count = len(diagnoses)
    counts = Counter(record["category"] for record in records)
    return {
        "schema_version": "diagnosis_metrics.v1",
        "denominators": {
            "counterexample_primary_properties": len(cex),
            "diagnosis_records_matched_to_cex": len(records) - counts["missing"],
            "missing_diagnoses": counts["missing"],
            "orphan_diagnoses": orphan_count,
        },
        "counts": {"by_category": dict(sorted(counts.items()))},
        "evidence": {
            "diagnoses_with_evidence_refs": sum(record["has_evidence_refs"] for record in records),
            "counterexamples_with_reconstruction": sum(record["reconstruction_available"] for record in records),
            "counterexamples_with_formal_trace": sum(record["formal_trace_available"] for record in records),
        },
        "records": records,
        "artifact_states": dict(sorted(states.items())),
    }


def _revision_metrics(runs: List[Dict[str, Any]]) -> Dict[str, Any]:
    records, states = [], Counter()
    for run in runs:
        revision_artifacts = run["revision_artifacts"]
        if not revision_artifacts:
            states["runs_without_revision_outcome"] += 1
        for artifact in revision_artifacts:
            states[artifact["state"]] += 1
            if artifact["state"] != "present" or not isinstance(artifact["value"], dict):
                continue
            payload = artifact["value"]
            for item in payload.get("properties", []):
                if isinstance(item, dict):
                    records.append({
                        "reporting_run_id": run["run_id"],
                        "parent_run_id": payload.get("parent_run_id"),
                        "rerun_id": payload.get("rerun_id"),
                        "revision_target": payload.get("revision_target"),
                        "property": item.get("property"),
                        "parent_status": item.get("parent_status"),
                        "rerun_status": item.get("rerun_status"),
                        "outcome": item.get("outcome", "missing"),
                        "status_transition": f"{item.get('parent_status', 'missing')}->{item.get('rerun_status', 'missing')}",
                        "cex_disappeared": item.get("parent_status") == "cex" and item.get("rerun_status") != "cex",
                        "semantic_correctness_claim": False,
                        "artifact": artifact["path"],
                    })
    return {
        "schema_version": "revision_metrics.v1",
        "denominators": {
            "runs_requested": len(runs),
            "revision_outcome_artifacts": states["present"],
            "revision_property_outcomes": len(records),
        },
        "counts": {
            "by_revision_target": dict(sorted(Counter(str(row["revision_target"] or "missing") for row in records).items())),
            "by_outcome": dict(sorted(Counter(str(row["outcome"]) for row in records).items())),
            "by_status_transition": dict(sorted(Counter(row["status_transition"] for row in records).items())),
            "cex_disappeared": sum(row["cex_disappeared"] for row in records),
        },
        "records": records,
        "artifact_states": dict(sorted(states.items())),
    }


def _experiment_matrix(
    runs: List[Dict[str, Any]], coverage: Dict[str, Any], binding: Dict[str, Any],
    formal: Dict[str, Any], diagnosis: Dict[str, Any], revision: Dict[str, Any],
) -> Dict[str, Any]:
    """Record the fixed P3 matrix without inventing a missing case result."""
    by_case: Dict[str, List[str]] = {}
    for run in runs:
        by_case.setdefault(run["case_name"], []).append(run["run_id"])
    cases = [
        {
            "case_name": case_name,
            "role": "transaction_liveness_grant_probe" if case_name.endswith("deadlock-v0") else "write_read_data_consistency_regression",
            "status": "present" if case_name in by_case else "missing",
            "run_ids": sorted(by_case.get(case_name, [])),
        }
        for case_name in REQUIRED_EXPERIMENT_CASES
    ]
    approved_rows = [row for row in coverage["properties"] if row["review_status"] == "approved"]
    return {
        "schema_version": "experiment_matrix.v1",
        "scope": "approved_gold_set_only",
        "cases": cases,
        "denominators": {
            "required_cases": len(REQUIRED_EXPERIMENT_CASES),
            "present_required_cases": sum(case["status"] == "present" for case in cases),
            "runs_requested": len(runs),
            "generated_property_instances": coverage["denominators"]["property_instances"],
            "approved_property_instances": len(approved_rows),
            "not_approved_property_instances": coverage["denominators"]["property_instances"] - len(approved_rows),
        },
        "experiments": {
            "expression": {
                "property_coverage": "property_coverage.json",
                "structural_complete": coverage["counts"]["structural_complete"],
                "semantic_review_complete": coverage["counts"]["semantic_review_complete"],
            },
            "binding_generation": {
                "binding_metrics": "binding_metrics.json",
                "property_instances": binding["denominators"]["property_instances"],
            },
            "formal_counterexample": {
                "formal_metrics": "formal_metrics.json",
                "diagnosis_metrics": "diagnosis_metrics.json",
                "revision_metrics": "revision_metrics.json",
                "expected_primary_properties": formal["denominators"]["expected_primary_properties"],
                "counterexamples": formal["denominators"]["counterexamples"],
                "revision_property_outcomes": revision["denominators"]["revision_property_outcomes"],
            },
        },
    }


def _property_instances(payload: Any) -> List[Dict[str, Any]]:
    if not isinstance(payload, dict):
        return []
    traceability = payload.get("traceability", {})
    return [
        item for item in traceability.get("properties", [])
        if isinstance(item, dict)
    ]


def _trace_labels(payload: Any) -> List[str]:
    if not isinstance(payload, dict):
        return []
    return [item["rtl_label"] for prop in payload.get("properties", []) if isinstance(prop, dict) for item in prop.get("rtl_properties", []) if isinstance(item, dict) and isinstance(item.get("rtl_label"), str)]


def _delta_labels(payload: Any) -> List[str]:
    if not isinstance(payload, dict):
        return []
    return [item["rtl_label"] for item in payload.get("rtl_properties", []) if isinstance(item, dict) and isinstance(item.get("rtl_label"), str)]


def _diagnosis_category(value: Any) -> str:
    text = str(value or "missing").lower()
    for token, category in (("design", "design"), ("schema", "schema"), ("template", "template"), ("binding", "binding"), ("environment", "environment"), ("assumption", "assumption"), ("inconclusive", "inconclusive")):
        if token in text:
            return category
    return "other" if text != "missing" else "missing"


def _ratio(numerator: int, denominator: int) -> Optional[float]:
    return numerator / denominator if denominator else None


def _write_json(path: Path, payload: Dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _write_coverage_csv(path: Path, rows: List[Dict[str, Any]]) -> None:
    fields = (
        "run_id", "instance_id", "property_schema_id", "template_id",
        "approved_by_codex", "review_status", "experiment_eligible",
        "semantic_status", "experiment_status",
        "independent_gold_status", "layer", "channels", "trigger_events",
        "response_events", "obligation_kind", "lowering_family", "rule_id",
        "rule_locator", "source_kind", "structural_complete",
        "semantic_review_complete", "schema_resolution", "schema_path",
        "schema_current_sha256", "rtl_property_count", "traceability_artifact",
    )
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            item = dict(row)
            item["channels"] = "|".join(item["channels"])
            for key in ("trigger_events", "response_events"):
                item[key] = json.dumps(item[key], ensure_ascii=False, sort_keys=True) if isinstance(item[key], (dict, list)) else item[key]
            writer.writerow(item)


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Aggregate CoupledL2 thesis experiment artifacts")
    parser.add_argument("--run", dest="runs", action="append", required=True, help="run root containing results/")
    parser.add_argument("--output", required=True, help="directory for report JSON/CSV artifacts")
    args = parser.parse_args(argv)
    result = generate_research_report(args.runs, args.output)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
