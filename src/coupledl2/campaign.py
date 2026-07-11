"""Strict, reproducible verification-campaign contracts."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any, Dict


class CampaignError(ValueError):
    pass


def validate_campaign(payload: Dict[str, Any]) -> Dict[str, Any]:
    required = {"schema_version", "campaign_id", "case_name", "property_profile_id", "formal_contract", "groups", "run_config"}
    if not isinstance(payload, dict) or set(payload) != required:
        raise CampaignError("verification campaign fields do not match v1 contract")
    if payload["schema_version"] != "verification_campaign.v1":
        raise CampaignError("unsupported verification campaign schema")
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,95}", str(payload["campaign_id"])):
        raise CampaignError("invalid campaign id")
    groups = payload["groups"]
    if not isinstance(groups, list) or not groups:
        raise CampaignError("campaign requires at least one bounded group")
    packages = []
    for group in groups:
        if not isinstance(group, dict) or set(group) != {"group_id", "selector", "property_packages"}:
            raise CampaignError("invalid campaign group")
        items = group["property_packages"]
        if not isinstance(items, list) or not 1 <= len(items) <= 8:
            raise CampaignError("each campaign group requires one to eight packages")
        for item in items:
            fields = {"instance_id", "property_schema_id", "template_id", "base_label", "package_sha256", "proof_budget_s"}
            if not isinstance(item, dict) or set(item) != fields:
                raise CampaignError("invalid property package entry")
            if not re.fullmatch(r"[0-9a-f]{64}", str(item["package_sha256"])):
                raise CampaignError("invalid property package hash")
            if isinstance(item["proof_budget_s"], bool) or not isinstance(item["proof_budget_s"], int) or item["proof_budget_s"] <= 0:
                raise CampaignError("proof budget must be a positive integer")
            packages.append(item)
    if not 1 <= len(packages) <= 8:
        raise CampaignError("campaign v1 supports one to eight total instances")
    for field in ("instance_id", "base_label"):
        values = [item[field] for item in packages]
        if len(values) != len(set(values)):
            raise CampaignError(f"campaign {field} values must be globally unique")
    formal = payload["formal_contract"]
    if not isinstance(formal, dict) or set(formal) != {"path", "sha256"} or not re.fullmatch(r"[0-9a-f]{64}", str(formal["sha256"])):
        raise CampaignError("invalid formal contract reference")
    config = payload["run_config"]
    if not isinstance(config, dict) or set(config) != {"seed", "tool", "tool_version"}:
        raise CampaignError("invalid run config")
    return json.loads(json.dumps(payload))


def write_campaign(path: Path, payload: Dict[str, Any]) -> str:
    checked = validate_campaign(payload)
    data = json.dumps(checked, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(data, encoding="utf-8")
    return hashlib.sha256(data.encode("utf-8")).hexdigest()
