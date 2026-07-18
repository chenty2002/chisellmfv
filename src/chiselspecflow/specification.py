"""Checksum-backed public specification package construction."""

from __future__ import annotations

import hashlib
import re
from pathlib import Path
from typing import Any, Dict, List, Tuple

from .config import PUBLIC_SPEC_PACKAGE_SCHEMA_VERSION


class PublicSpecificationError(ValueError):
    """Raised when the frozen public suite or one authority spec is invalid."""


_LEDGER_RE = re.compile(r"^([0-9a-f]{64})  (benchmark/synth/[^\s]+)$")
_TABLE_FIELD_RE = re.compile(r"^\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*$", re.MULTILINE)
_SPEC_ID_RE = re.compile(r"^CHISELLMFV-SYNTH-[A-Z0-9-]+$")
_CLAUSE_RE = re.compile(r"\b([A-Z][A-Z0-9]*-(?:N-)?[0-9]{3})\b")
_PROPERTY_RE = re.compile(
    r"\b([A-Z][A-Z0-9]*-(?:P-[A-Z0-9]+-[0-9]{3}|P[0-9]{3}|C[0-9]{3}))\b"
)
_ASSUMPTION_RE = re.compile(r"\b([A-Z][A-Z0-9]*-(?:A-[0-9]{3}|A[0-9]{3}))\b")
_DECLARATION_RE = re.compile(
    r"^(?:-\s+\*\*|\|\s*`)([A-Z][A-Z0-9]*-(?:N-)?[0-9]{3}|"
    r"[A-Z][A-Z0-9]*-(?:P-[A-Z0-9]+-[0-9]{3}|P[0-9]{3}|C[0-9]{3}|"
    r"A-[0-9]{3}|A[0-9]{3}))\b",
    re.MULTILINE,
)


def validate_suite_ledger(ledger_path: Path) -> Dict[str, Any]:
    ledger_path = Path(ledger_path).resolve()
    repository_root = _repository_root(ledger_path)
    entries: List[Dict[str, str]] = []
    seen = set()
    for line_number, raw in enumerate(
        ledger_path.read_text(encoding="utf-8").splitlines(), start=1
    ):
        if not raw.strip():
            continue
        match = _LEDGER_RE.fullmatch(raw)
        if match is None:
            raise PublicSpecificationError(
                f"invalid suite ledger row at line {line_number}"
            )
        expected, relative = match.groups()
        if relative in seen:
            raise PublicSpecificationError(f"duplicate suite ledger path: {relative}")
        seen.add(relative)
        path = (repository_root / relative).resolve()
        try:
            path.relative_to(repository_root)
        except ValueError as exc:
            raise PublicSpecificationError("suite ledger path escapes repository") from exc
        if not path.is_file():
            raise PublicSpecificationError(f"suite ledger path is missing: {relative}")
        actual = _file_sha256(path)
        if actual != expected:
            raise PublicSpecificationError(f"suite ledger hash mismatch: {relative}")
        entries.append({"path": relative, "sha256": actual})
    spec_entries = [row for row in entries if row["path"].endswith("/specflow/spec.md")]
    if len(entries) != 12 or len(spec_entries) != 11:
        raise PublicSpecificationError(
            "suite ledger must freeze SPECIFICATIONS.md and exactly 11 public specs"
        )
    return {
        "schema_version": "public_spec_suite_ledger.v1",
        "ledger_path": str(ledger_path.relative_to(repository_root)),
        "ledger_sha256": _file_sha256(ledger_path),
        "entries": entries,
    }


def load_public_spec_package(spec_path: Path, ledger_path: Path) -> Dict[str, Any]:
    spec_path = Path(spec_path).resolve()
    ledger = validate_suite_ledger(ledger_path)
    repository_root = _repository_root(Path(ledger_path).resolve())
    try:
        spec_relative = str(spec_path.relative_to(repository_root))
    except ValueError as exc:
        raise PublicSpecificationError("public spec is outside the repository") from exc
    ledger_row = next(
        (row for row in ledger["entries"] if row["path"] == spec_relative), None
    )
    if ledger_row is None:
        raise PublicSpecificationError("public spec is not frozen by the suite ledger")
    text = spec_path.read_text(encoding="utf-8")
    fields = _table_fields(text)
    specification_id = _required_field(fields, "Specification ID")
    version = _required_field(fields, "Version")
    reviewer = _required_field(fields, "Reviewer")
    reviewed_at = _required_field(fields, "Review date")
    difficulty_text = _required_field(fields, "Difficulty")
    status = fields.get("Status", "")
    if not _SPEC_ID_RE.fullmatch(specification_id):
        raise PublicSpecificationError("invalid public specification ID")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise PublicSpecificationError("invalid public specification version")
    if reviewer != "codex":
        raise PublicSpecificationError("public spec must be reviewed by codex")
    if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", reviewed_at):
        raise PublicSpecificationError("invalid review date")
    difficulty = difficulty_text[:1]
    if difficulty not in {"S", "M", "L"}:
        raise PublicSpecificationError("public spec has no S/M/L difficulty")
    if not any(
        marker in (status + " " + text[-1500:]).lower()
        for marker in ("approved", "reviewed authority snapshot")
    ):
        raise PublicSpecificationError("public spec review is not approved")

    declarations = _DECLARATION_RE.findall(text)
    duplicates = sorted(
        identity for identity in set(declarations) if declarations.count(identity) > 1
    )
    if duplicates:
        raise PublicSpecificationError(f"duplicate declared IDs: {duplicates}")
    clauses = [identity for identity in declarations if _CLAUSE_RE.fullmatch(identity)]
    properties = [
        identity for identity in declarations if _PROPERTY_RE.fullmatch(identity)
    ]
    assumptions = [
        identity for identity in declarations if _ASSUMPTION_RE.fullmatch(identity)
    ]
    if not clauses or not properties:
        raise PublicSpecificationError("public spec has no normative clauses or properties")
    family = spec_path.parents[1].name
    return {
        "schema_version": PUBLIC_SPEC_PACKAGE_SCHEMA_VERSION,
        "specification_id": specification_id,
        "version": version,
        "family": family,
        "difficulty": difficulty,
        "spec_path": spec_relative,
        "spec_sha256": ledger_row["sha256"],
        "suite_ledger_sha256": ledger["ledger_sha256"],
        "authority_refs": [f"{spec_relative}#status-and-authority"],
        "configuration_refs": [f"{spec_relative}#configuration"],
        "normative_clause_ids": clauses,
        "expected_property_ids": properties,
        "allowed_assumption_ids": assumptions,
        "review": {
            "reviewer": reviewer,
            "reviewed_at": reviewed_at,
            "decision": "approved",
        },
        "visibility": "public",
    }


def _table_fields(text: str) -> Dict[str, str]:
    status_heading = re.search(
        r"^##\s+(?:[0-9]+\.\s*)?Status and authority\s*$", text, re.MULTILINE
    )
    if status_heading is None:
        raise PublicSpecificationError("public spec has no status-and-authority section")
    next_heading = re.search(r"^##\s+", text[status_heading.end() :], re.MULTILINE)
    section_end = (
        status_heading.end() + next_heading.start()
        if next_heading is not None
        else len(text)
    )
    metadata_text = text[status_heading.start() : section_end]
    fields: Dict[str, str] = {}
    for key, value in _TABLE_FIELD_RE.findall(metadata_text):
        key = key.strip()
        if key in {"Field", "---"} or set(key) == {"-"}:
            continue
        normalized = value.strip().strip("`")
        if key in fields and fields[key] != normalized:
            raise PublicSpecificationError(f"duplicate metadata field: {key}")
        fields[key] = normalized
    return fields


def _required_field(fields: Dict[str, str], key: str) -> str:
    value = fields.get(key)
    if not value:
        raise PublicSpecificationError(f"public spec is missing metadata field: {key}")
    return value


def _repository_root(path: Path) -> Path:
    for candidate in (path.parent, *path.parents):
        if (candidate / ".git").exists():
            return candidate.resolve()
    raise PublicSpecificationError("suite ledger is not inside a repository")


def _file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()
