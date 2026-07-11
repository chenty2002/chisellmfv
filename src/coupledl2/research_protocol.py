"""Frozen V3 research-protocol and dataset-manifest contracts."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any, Dict, Iterable


RESEARCH_ROOT = Path(__file__).with_name("research_assets")
SPLITS = {"development", "validation", "test"}
BUILD_STATES = {"buildable", "not_buildable", "not_checked"}


class ResearchProtocolError(ValueError):
    """Raised when a frozen experiment contract is incomplete or leaks lineages."""


def load_research_protocol() -> Dict[str, Any]:
    value = _read_json(RESEARCH_ROOT / "research_protocol.v1.json")
    validate_research_protocol(value)
    return value


def load_dataset_manifest() -> Dict[str, Any]:
    value = _read_json(RESEARCH_ROOT / "dataset_manifest.v1.json")
    validate_dataset_manifest(value)
    return value


def validate_research_protocol(value: Dict[str, Any]) -> None:
    required = {
        "schema_version", "protocol_id", "frozen_at", "research_questions", "hypotheses",
        "outcomes", "benchmark_units", "split_policy", "methods", "models",
        "execution", "result_accounting", "statistics", "success_criteria",
        "stopping_rules",
    }
    _exact(value, required, "research_protocol")
    if value["schema_version"] != "research_protocol.v1":
        raise ResearchProtocolError("unsupported research protocol version")
    if not value["research_questions"] or not all(
        set(item) == {"rq_id", "hypothesis_id", "question", "falsifiable_claim"}
        for item in value["research_questions"]
    ):
        raise ResearchProtocolError("research questions must bind hypotheses")
    hypotheses = {item.get("hypothesis_id") for item in value["hypotheses"] if isinstance(item, dict)}
    if hypotheses != {"H1", "H2", "H3", "H4"} or not all(
        set(item) == {"hypothesis_id", "claim", "falsification_condition"}
        for item in value["hypotheses"]
    ):
        raise ResearchProtocolError("research protocol must freeze H1 through H4")
    if any(item["hypothesis_id"] not in hypotheses for item in value["research_questions"]):
        raise ResearchProtocolError("research question references an unknown hypothesis")
    outcomes = value["outcomes"]
    _exact(outcomes, {"primary", "secondary", "engineering_gates"}, "outcomes")
    if not outcomes["primary"]:
        raise ResearchProtocolError("at least one primary outcome is required")
    if set(outcomes["primary"]) & set(outcomes["engineering_gates"]):
        raise ResearchProtocolError("engineering gates cannot be primary outcomes")
    if not {"compile_success", "rtl_label_survival"} <= set(outcomes["engineering_gates"]):
        raise ResearchProtocolError("compile and RTL survival must be engineering gates")
    methods = {item["method_id"] for item in value["methods"]}
    if not {"direct_llm", "template_only", "tool_feedback", "v3_full"} <= methods:
        raise ResearchProtocolError("required component-matched baselines are missing")
    models = value["models"]
    if models.get("minimum_model_count", 0) < 2:
        raise ResearchProtocolError("research protocol requires at least two models")
    seeds = models.get("llm_seeds", [])
    if len(seeds) < 5 or len(seeds) != len(set(seeds)):
        raise ResearchProtocolError("LLM baselines require at least five unique seeds")
    execution = value["execution"]
    if execution.get("per_property_timeout_s", 0) <= 0 or not execution.get("tool_version"):
        raise ResearchProtocolError("tool version and positive proof budget must be frozen")
    accounting = value["result_accounting"]
    required_states = {
        "proven", "cex", "inconclusive", "timeout", "missing", "invalid",
        "vacuous", "environment_excluded", "not_applicable", "tool_error",
    }
    if not required_states <= set(accounting["reported_statuses"]):
        raise ResearchProtocolError("result accounting omits a required status")
    if accounting.get("drop_missing_or_inconclusive") is not False:
        raise ResearchProtocolError("missing and inconclusive results must remain in denominators")
    if value["statistics"].get("confidence_interval") != "bootstrap_95_percent":
        raise ResearchProtocolError("primary confidence interval is not frozen")
    if not value["success_criteria"] or not value["stopping_rules"]:
        raise ResearchProtocolError("success and stopping rules must be frozen")


def validate_dataset_manifest(value: Dict[str, Any]) -> None:
    required = {
        "schema_version", "dataset_id", "frozen_at", "source_repository",
        "split_policy", "entries",
    }
    _exact(value, required, "dataset_manifest")
    if value["schema_version"] != "dataset_manifest.v1":
        raise ResearchProtocolError("unsupported dataset manifest version")
    entries = value["entries"]
    if not isinstance(entries, list) or not entries:
        raise ResearchProtocolError("dataset manifest requires entries")
    ids: set[str] = set()
    by_bug: Dict[str, set[str]] = {}
    by_source: Dict[str, set[str]] = {}
    for index, item in enumerate(entries):
        fields = {
            "case_id", "relative_path", "source_lineage", "bug_lineage", "split",
            "difference_summary", "bug_label", "bug_kind", "buildability",
            "oracle", "source_fingerprint",
        }
        _exact(item, fields, f"dataset_manifest.entries[{index}]")
        case_id = item["case_id"]
        if case_id in ids:
            raise ResearchProtocolError("dataset manifest repeats a case id")
        ids.add(case_id)
        if item["split"] not in SPLITS:
            raise ResearchProtocolError("dataset entry has invalid split")
        relative_path = Path(item["relative_path"])
        if relative_path.is_absolute() or ".." in relative_path.parts:
            raise ResearchProtocolError("dataset path must remain source-repository relative")
        _exact(item["buildability"], {"status", "evidence"}, "dataset buildability")
        if item["buildability"]["status"] not in BUILD_STATES:
            raise ResearchProtocolError("dataset entry has invalid buildability status")
        _exact(item["oracle"], {"source", "independent_of_method"}, "dataset oracle")
        if not item["oracle"].get("source") or not item["oracle"].get("independent_of_method"):
            raise ResearchProtocolError("dataset oracle must be independent of the tested method")
        fingerprint = item["source_fingerprint"]
        if set(fingerprint) != {"kind", "value"} or not fingerprint["value"]:
            raise ResearchProtocolError("dataset source fingerprint is invalid")
        by_bug.setdefault(item["bug_lineage"], set()).add(item["split"])
        by_source.setdefault(item["source_lineage"], set()).add(item["split"])
    leaking_bug = sorted(key for key, splits in by_bug.items() if len(splits) != 1)
    leaking_source = sorted(key for key, splits in by_source.items() if len(splits) != 1)
    if leaking_bug or leaking_source:
        raise ResearchProtocolError(
            f"lineage leakage across splits: bug={leaking_bug}, source={leaking_source}"
        )
    present_splits = {item["split"] for item in entries}
    if present_splits != SPLITS:
        raise ResearchProtocolError("dataset must contain development, validation, and test")
    lineages_per_split = {
        split: {item["bug_lineage"] for item in entries if item["split"] == split}
        for split in SPLITS
    }
    if any(len(lineages) < 2 for lineages in lineages_per_split.values()):
        raise ResearchProtocolError("each split requires at least two independent bug lineages")
    if len(by_bug) < 6:
        raise ResearchProtocolError("initial corpus requires at least six bug lineages")


def protocol_fingerprint() -> str:
    """Hash both frozen contracts for experiment-registry binding."""
    digest = hashlib.sha256()
    for name in ("research_protocol.v1.json", "dataset_manifest.v1.json"):
        digest.update((RESEARCH_ROOT / name).read_bytes())
    return digest.hexdigest()


def _read_json(path: Path) -> Dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ResearchProtocolError(f"invalid research asset: {path.name}") from exc
    if not isinstance(value, dict):
        raise ResearchProtocolError(f"research asset must be an object: {path.name}")
    return value


def _exact(value: Any, fields: Iterable[str], path: str) -> None:
    if not isinstance(value, dict) or set(value) != set(fields):
        raise ResearchProtocolError(f"{path} fields do not match frozen contract")
