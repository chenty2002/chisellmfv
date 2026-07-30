"""Reviewed, run-local, and promoted SpecFlow asset loading boundaries."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import Any, Dict, Mapping

from src.core.artifact_contract import file_sha256


class AssetError(ValueError):
    """Raised when an asset is unreviewed, hash-mismatched, or misplaced."""


@dataclass(frozen=True)
class AssetLibrary:
    root: Path
    obligation_schemas: Mapping[str, Mapping[str, Any]]
    monitor_archetypes: Mapping[str, Mapping[str, Any]]
    api_adapters: Mapping[str, Mapping[str, Any]]

    def snapshot(self) -> Dict[str, Any]:
        def rows(assets: Mapping[str, Mapping[str, Any]]) -> list[Dict[str, Any]]:
            return [
                {
                    "asset_id": asset_id,
                    "sha256": asset["sha256"],
                    "path": asset["path"],
                }
                for asset_id, asset in sorted(assets.items())
            ]

        return {
            "schema_version": "specflow_asset_library",
            "obligation_schemas": rows(self.obligation_schemas),
            "monitor_archetypes": rows(self.monitor_archetypes),
            "api_adapters": rows(self.api_adapters),
        }


def load_reviewed_assets(root: Path | None = None) -> AssetLibrary:
    root = Path(root or Path(__file__).with_name("property_assets")).resolve()
    reviewed_hashes = _load_repository_reviews(root)
    obligation_schemas = _load_kind(
        root, "obligation_schemas", "obligation_schema", reviewed_hashes
    )
    monitor_archetypes = _load_kind(
        root, "monitor_archetypes", "monitor_archetype", reviewed_hashes
    )
    api_adapters = _load_kind(
        root, "api_adapters", "api_adapter", reviewed_hashes
    )
    loaded_paths = {
        row["path"]
        for collection in (obligation_schemas, monitor_archetypes, api_adapters)
        for row in collection.values()
    }
    if set(reviewed_hashes) != loaded_paths:
        raise AssetError("repository review set does not exactly match the asset library")
    return AssetLibrary(
        root=root,
        obligation_schemas=MappingProxyType(obligation_schemas),
        monitor_archetypes=MappingProxyType(monitor_archetypes),
        api_adapters=MappingProxyType(api_adapters),
    )


def load_run_local_package(path: Path) -> Dict[str, Any]:
    """Read only the canonical, reviewed Stage-1 package shape."""

    value = _read_json(Path(path))
    required = {
        "schema_version",
        "package_id",
        "project_id",
        "configuration_id",
        "round_id",
        "input_hashes",
        "asset_library",
        "obligations",
        "bindings",
        "monitors",
        "review",
    }
    if set(value) != required or value.get("schema_version") != "verification_package":
        raise AssetError("run-local verification package has an invalid exact schema")
    if not all(isinstance(value.get(name), list) and value[name] for name in ("obligations", "bindings", "monitors")):
        raise AssetError("verification package must contain reviewed IR rows")
    return value


def promote_run_local_asset(
    candidate_path: Path,
    destination: Path,
    review_record: Mapping[str, Any],
) -> Path:
    """Promote one reviewed candidate without overwriting repository assets."""

    candidate_path = Path(candidate_path).resolve()
    destination = Path(destination).resolve()
    if destination.exists():
        raise FileExistsError(f"promoted asset already exists: {destination}")
    reviewer = review_record.get("reviewer")
    if reviewer != "codex" and not (
        isinstance(reviewer, str)
        and re.fullmatch(r"human:[A-Za-z0-9_.@-]+", reviewer)
    ):
        raise AssetError("promotion requires a Codex or identified human reviewer")
    if (
        review_record.get("decision") != "approved"
        or not review_record.get("evidence_refs")
        or not review_record.get("reviewed_at")
        or not review_record.get("reason")
    ):
        raise AssetError("promotion review is not approved or has no evidence")
    reviewed = _reviewed_hash_map(review_record.get("reviewed_hashes"))
    if reviewed.get(candidate_path.name) != file_sha256(candidate_path):
        raise AssetError("promotion review does not bind the candidate hash")
    value = _read_json(candidate_path)
    if set(value) != {"schema_version", "asset_kind", "asset_id", "payload"}:
        raise AssetError("promoted candidate is not a repository asset object")
    asset_root = destination.parents[1]
    try:
        relative = str(destination.relative_to(asset_root))
    except ValueError as exc:
        raise AssetError("promotion destination has no property-asset root") from exc
    review_path = asset_root / "reviews" / (
        destination.stem + "." + file_sha256(candidate_path)[:12] + ".review.json"
    )
    if review_path.exists():
        raise FileExistsError(f"promotion review already exists: {review_path}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(destination)
    review_value = {
        "schema_version": "specflow_repository_asset_review",
        "reviewer": reviewer,
        "decision": "approved",
        "reviewed_at": review_record.get("reviewed_at"),
        "reviewed_hashes": [{"path": relative, "sha256": file_sha256(destination)}],
        "evidence_refs": list(review_record["evidence_refs"]),
        "reason": review_record.get("reason"),
    }
    review_path.parent.mkdir(parents=True, exist_ok=True)
    review_temporary = review_path.with_name(review_path.name + ".tmp")
    review_temporary.write_text(
        json.dumps(review_value, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    review_temporary.replace(review_path)
    return destination


def _load_kind(
    root: Path,
    directory: str,
    expected_kind: str,
    reviewed_hashes: Mapping[str, str],
) -> Dict[str, Mapping[str, Any]]:
    assets = {}
    location = root / directory
    for path in sorted(location.glob("*.json")):
        value = _read_json(path)
        if set(value) != {"schema_version", "asset_kind", "asset_id", "payload"}:
            raise AssetError(f"reviewed asset fields differ: {path}")
        if value["schema_version"] != "specflow_repository_asset" or value["asset_kind"] != expected_kind:
            raise AssetError(f"reviewed asset kind/schema mismatch: {path}")
        relative = str(path.relative_to(root))
        digest = file_sha256(path)
        if reviewed_hashes.get(relative) != digest:
            raise AssetError(f"repository asset is not hash-bound by a Codex review: {path}")
        asset_id = value["asset_id"]
        if not isinstance(asset_id, str) or not asset_id or asset_id in assets:
            raise AssetError(f"duplicate or invalid repository asset ID: {asset_id!r}")
        row = dict(value["payload"])
        row.update(
            {
                "asset_id": asset_id,
                "sha256": digest,
                "path": relative,
            }
        )
        assets[asset_id] = MappingProxyType(row)
    if not assets:
        raise AssetError(f"no reviewed {expected_kind} assets under {location}")
    return assets


def _load_repository_reviews(root: Path) -> Dict[str, str]:
    approved: Dict[str, str] = {}
    review_paths = sorted((root / "reviews").glob("*.json"))
    if not review_paths:
        raise AssetError("reviewed asset library has no external review records")
    for path in review_paths:
        value = _read_json(path)
        if set(value) != {
            "schema_version",
            "reviewer",
            "decision",
            "reviewed_at",
            "reviewed_hashes",
            "evidence_refs",
            "reason",
        }:
            raise AssetError(f"repository asset review fields differ: {path}")
        if (
            value["schema_version"] != "specflow_repository_asset_review"
            or value["reviewer"] != "codex"
            or value["decision"] != "approved"
            or not value["evidence_refs"]
            or not value["reason"]
        ):
            raise AssetError(f"repository asset review is not approved by Codex: {path}")
        for row in value["reviewed_hashes"]:
            if not isinstance(row, Mapping) or set(row) != {"path", "sha256"}:
                raise AssetError(f"malformed reviewed asset hash: {path}")
            relative = row["path"]
            if relative in approved:
                raise AssetError(f"asset is reviewed more than once: {relative}")
            approved[relative] = row["sha256"]
    return approved


def _reviewed_hash_map(value: Any) -> Dict[str, str]:
    if not isinstance(value, list):
        return {}
    result = {}
    for row in value:
        if isinstance(row, Mapping) and set(row) == {"artifact", "sha256"}:
            result[row["artifact"]] = row["sha256"]
    return result


def _read_json(path: Path) -> Dict[str, Any]:
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise AssetError(f"cannot read asset: {path}") from exc
    if not isinstance(value, dict):
        raise AssetError(f"asset must be a JSON object: {path}")
    return value
