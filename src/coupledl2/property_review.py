"""Hash-bound Codex review records for repository-owned property packages."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Dict, Mapping


ASSET_ROOT = Path(__file__).with_name("property_assets")
REVIEW_ROOT = ASSET_ROOT / "reviews"
ASSET_KINDS = {
    "rule_index", "profile", "schema", "template", "binding", "formal_contract"
}


class PropertyReviewError(ValueError):
    """Raised when a review record is malformed or no longer binds its assets."""


def load_property_review(review_id: str) -> Dict[str, object]:
    """Load one repository review without interpreting API-model output as approval."""
    path = REVIEW_ROOT / f"{review_id}.json"
    if not path.is_file():
        raise PropertyReviewError(f"property review not found: {review_id}")
    try:
        review = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PropertyReviewError(f"invalid property review: {review_id}") from exc
    _validate_review(review, review_id)
    return review


def load_all_property_reviews() -> Dict[str, Dict[str, object]]:
    """Return every validated repository review keyed by its stable review ID."""
    if not REVIEW_ROOT.is_dir():
        return {}
    reviews = {}
    for path in sorted(REVIEW_ROOT.glob("*.json")):
        review = load_property_review(path.stem)
        reviews[path.stem] = review
    return reviews


def verify_review_assets(
    review: Mapping[str, object], assets: Mapping[str, Path]
) -> None:
    """Require one reviewed SHA-256 for every supplied repository asset."""
    review_assets = review.get("assets")
    if not isinstance(review_assets, list):
        raise PropertyReviewError("property review assets must be a list")
    by_path = {item["path"]: item for item in review_assets if isinstance(item, dict)}
    if set(by_path) != set(assets):
        raise PropertyReviewError("property review asset set does not match package")
    for relative_path, path in assets.items():
        if not path.is_file():
            raise PropertyReviewError(f"reviewed asset not found: {relative_path}")
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        expected = by_path[relative_path]["sha256"]
        if actual != expected:
            raise PropertyReviewError(
                f"property review sha256 mismatch: {relative_path}"
            )


def _validate_review(review: object, review_id: str) -> None:
    if not isinstance(review, dict):
        raise PropertyReviewError("property review must be an object")
    fields = {
        "schema_version", "review_id", "reviewer", "review_status", "reviewed_at",
        "assets", "evidence_refs", "reason",
    }
    if set(review) != fields:
        raise PropertyReviewError("property review has invalid fields")
    if review["schema_version"] != "property_review":
        raise PropertyReviewError("unsupported property review version")
    if review["review_id"] != review_id:
        raise PropertyReviewError("property review id does not match filename")
    if review["reviewer"] != "codex":
        raise PropertyReviewError("repository assets may only be reviewed by codex")
    if review["review_status"] not in {"approved", "rejected"}:
        raise PropertyReviewError("property review status must be approved or rejected")
    if not isinstance(review["reviewed_at"], str) or not review["reviewed_at"]:
        raise PropertyReviewError("property review requires reviewed_at")
    if not isinstance(review["reason"], str) or not review["reason"]:
        raise PropertyReviewError("property review requires a reason")
    evidence = review["evidence_refs"]
    if not isinstance(evidence, list) or not evidence or not all(
        isinstance(item, str) and item for item in evidence
    ):
        raise PropertyReviewError("property review requires concrete evidence refs")
    assets = review["assets"]
    if not isinstance(assets, list) or not assets:
        raise PropertyReviewError("property review requires reviewed assets")
    seen_paths = set()
    for item in assets:
        if not isinstance(item, dict) or set(item) != {"kind", "asset_id", "path", "sha256"}:
            raise PropertyReviewError("property review asset has invalid fields")
        if item["kind"] not in ASSET_KINDS:
            raise PropertyReviewError("property review asset has invalid kind")
        if not all(isinstance(item[key], str) and item[key] for key in item):
            raise PropertyReviewError("property review asset fields must be non-empty strings")
        if len(item["sha256"]) != 64 or any(c not in "0123456789abcdef" for c in item["sha256"]):
            raise PropertyReviewError("property review asset sha256 is invalid")
        if item["path"] in seen_paths:
            raise PropertyReviewError("property review repeats an asset path")
        seen_paths.add(item["path"])
