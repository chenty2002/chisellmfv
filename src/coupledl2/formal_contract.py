"""Strict, repository-owned formal-environment contracts for CoupledL2."""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, Optional


FORMAL_CONTRACT_ROOT = Path(__file__).with_name("property_assets") / "formal_contracts"
FORMAL_CONTRACT_SCHEMA_VERSION = "formal_contract.v1"
PRIMARY_STATUSES = {"proven", "cex", "inconclusive", "not_run", "tool_error"}


class FormalContractError(ValueError):
    """Raised when a repository contract or case-local formal setup is unsafe."""


@dataclass(frozen=True)
class ResolvedFormalContract:
    payload: Dict[str, Any]
    asset_path: Path
    sha256: str
    case_setup: Dict[str, Any]

    def artifact(self) -> Dict[str, Any]:
        return {
            **self.payload,
            "asset_path": self.asset_path.as_posix(),
            "sha256": self.sha256,
            "case_setup": self.case_setup,
        }


def load_formal_contract(
    contract_id: str,
    *,
    profile_id: Optional[str] = None,
    case_name: Optional[str] = None,
    case_workspace: Optional[Path] = None,
) -> ResolvedFormalContract:
    """Load, validate, hash, and optionally audit one formal contract."""
    if not re.fullmatch(r"[a-z0-9_]+", contract_id):
        raise FormalContractError("invalid formal contract id")
    path = FORMAL_CONTRACT_ROOT / f"{contract_id}.json"
    try:
        raw = path.read_bytes()
        payload = json.loads(raw)
    except (OSError, json.JSONDecodeError) as exc:
        raise FormalContractError(f"invalid formal contract asset: {path.name}") from exc
    if not isinstance(payload, dict):
        raise FormalContractError("formal contract must be an object")
    _validate_contract(payload, contract_id)
    if profile_id is not None and payload["property_profile_id"] != profile_id:
        raise FormalContractError("formal contract profile mismatch")
    if case_name is not None and payload["case_name"] != case_name:
        raise FormalContractError("formal contract case mismatch")
    setup = (
        audit_case_formal_setup(Path(case_workspace), payload)
        if case_workspace is not None
        else {"status": "not_audited", "files": []}
    )
    return ResolvedFormalContract(
        payload=payload,
        asset_path=path,
        sha256=hashlib.sha256(raw).hexdigest(),
        case_setup=setup,
    )


def audit_case_formal_setup(
    case_workspace: Path,
    contract: Dict[str, Any],
) -> Dict[str, Any]:
    """Model supported semantics from case-local setup files and fail closed.

    The copied scripts are evidence, not executable truth.  Analyze/elaborate,
    clock/reset, assumptions, and proof selection are the critical commands.
    Unknown commands in those families cannot be silently discarded.
    """
    verilog_dir = case_workspace / "Verilog"
    verify_path = verilog_dir / "verify.tcl"
    setup_path = verilog_dir / "setup.sh"
    files = [
        path.relative_to(case_workspace).as_posix()
        for path in (setup_path, verify_path)
        if path.is_file()
    ]
    if not verify_path.is_file():
        return {
            "status": "launcher_only" if setup_path.is_file() else "absent",
            "files": files,
            "modeled_commands": [],
            "superseded_commands": [],
        }

    text = verify_path.read_text(encoding="utf-8", errors="replace")
    commands = _critical_tcl_commands(text.splitlines())
    modeled = []
    superseded = []
    assumptions = set(contract["preserved_assumptions"])
    clock_signal = contract["clock"]["signal"]
    reset_signal = contract["reset"]["signal"]
    for command in commands:
        head = command.split(None, 1)[0]
        if head == "analyze" and re.match(r"^analyze\s+-sv\b", command):
            modeled.append({"kind": "analyze", "command": command})
        elif head == "elaborate" and re.match(r"^elaborate(?:\s|$)", command):
            modeled.append({"kind": "elaborate", "command": command})
        elif head == "clock" and command == f"clock {clock_signal}":
            modeled.append({"kind": "clock", "command": command})
        elif head == "reset" and command == f"reset {reset_signal}":
            modeled.append({"kind": "reset", "command": command})
        elif head in {"assume", "assumption"} and command in assumptions:
            modeled.append({"kind": "assumption", "command": command})
        elif head == "prove" and command == "prove -all":
            superseded.append({
                "kind": "proof_selection",
                "command": command,
                "reason": "replaced_by_exact_assertion_delta",
            })
        elif head in {
            "set_prove_time_limit",
            "set_engine_threads",
            "set_proofgrid_per_engine_max_jobs",
        }:
            superseded.append({
                "kind": "resource_limit",
                "command": command,
                "reason": "replaced_by_repository_formal_contract",
            })
        else:
            raise FormalContractError(
                f"unmodeled critical formal command in Verilog/verify.tcl: {command}"
            )

    kinds = {item["kind"] for item in modeled}
    required = {"analyze", "elaborate", "clock", "reset"}
    if commands and not required <= kinds:
        raise FormalContractError(
            "case-local verify.tcl does not expose a complete supported "
            "analyze/elaborate/clock/reset setup"
        )
    return {
        "status": "modeled",
        "files": files,
        "verify_tcl_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
        "modeled_commands": modeled,
        "superseded_commands": superseded,
    }


def _critical_tcl_commands(lines: Iterable[str]) -> list[str]:
    commands: list[str] = []
    critical = re.compile(
        r"^(analyze|elaborate|clock|reset|assume|assumption|prove|"
        r"set_prove_time_limit|set_engine_threads|"
        r"set_proofgrid_per_engine_max_jobs)\b"
    )
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        # Case scripts commonly wrap analyze/elaborate in `if {[catch {...}`.
        match = re.search(
            r"\{((?:analyze|elaborate|clock|reset|assume|assumption|prove|"
            r"set_prove_time_limit|set_engine_threads|"
            r"set_proofgrid_per_engine_max_jobs)\b[^{}]*)\}",
            line,
        )
        candidate = (match.group(1) if match else line).strip()
        if critical.match(candidate):
            commands.append(" ".join(candidate.split()))
    return commands


def _validate_contract(value: Dict[str, Any], requested_id: str) -> None:
    fields = {
        "schema_version",
        "formal_contract_id",
        "case_name",
        "property_profile_id",
        "top",
        "rtl_source_policy",
        "clock",
        "reset",
        "preserved_assumptions",
        "disabled_baseline_properties",
        "proof_selection_policy",
        "resources",
        "trace_policy",
        "tool",
    }
    _exact_fields(value, fields, fields, "formal_contract")
    if value["schema_version"] != FORMAL_CONTRACT_SCHEMA_VERSION:
        raise FormalContractError("unsupported formal contract version")
    if value["formal_contract_id"] != requested_id:
        raise FormalContractError("formal contract id does not match filename")
    _exact_fields(value["top"], {"policy", "name_prefix"}, {"policy", "name_prefix"}, "top")
    if value["top"]["policy"] != "build_inferred":
        raise FormalContractError("unsupported top policy")
    _exact_fields(
        value["rtl_source_policy"],
        {"kind", "build_contract_globs"},
        {"kind", "build_contract_globs"},
        "rtl_source_policy",
    )
    if value["rtl_source_policy"]["kind"] != "generated_build_contract":
        raise FormalContractError("unsupported RTL source policy")
    for name in ("clock", "reset"):
        _exact_fields(value[name], {"signal"}, {"signal"}, name)
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_$]*", value[name]["signal"]):
            raise FormalContractError(f"invalid {name} signal")
    for name in ("preserved_assumptions", "disabled_baseline_properties"):
        if not isinstance(value[name], list) or not all(
            isinstance(item, str) and item for item in value[name]
        ):
            raise FormalContractError(f"{name} must be a string list")
    if value["proof_selection_policy"] != "exact_assertion_delta":
        raise FormalContractError("formal proof selection must use assertion delta")
    _exact_fields(
        value["resources"],
        {"per_property_timeout_s", "global_timeout_s", "engine_threads", "max_jobs"},
        {"per_property_timeout_s", "global_timeout_s", "engine_threads", "max_jobs"},
        "resources",
    )
    resources = value["resources"]
    if any(not isinstance(resources[key], int) or resources[key] <= 0 for key in resources):
        raise FormalContractError("formal resource limits must be positive integers")
    _exact_fields(
        value["trace_policy"],
        {"statuses", "format", "optimization"},
        {"statuses", "format", "optimization"},
        "trace_policy",
    )
    if set(value["trace_policy"]["statuses"]) - PRIMARY_STATUSES:
        raise FormalContractError("invalid trace status")
    if value["trace_policy"]["format"] != "vcd":
        raise FormalContractError("unsupported trace format")
    _exact_fields(value["tool"], {"name", "version"}, {"name", "version"}, "tool")
    if value["tool"]["name"] != "jaspergold":
        raise FormalContractError("unsupported formal tool")


def _exact_fields(
    value: Dict[str, Any], allowed: set[str], required: set[str], path: str
) -> None:
    if not isinstance(value, dict):
        raise FormalContractError(f"{path} must be an object")
    unknown = set(value) - allowed
    missing = required - set(value)
    if unknown:
        raise FormalContractError(f"{path} unknown fields: {sorted(unknown)}")
    if missing:
        raise FormalContractError(f"{path} missing fields: {sorted(missing)}")
