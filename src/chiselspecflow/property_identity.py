"""Exact source-property to emitted-property identity for SpecFlow Stage 2."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any, Dict, Iterable, Mapping

from src.core.artifact_contract import file_sha256


class PropertyIdentityError(ValueError):
    """Raised when source locators cannot identify one emitted property exactly."""


_LABEL_RE = re.compile(
    r"^(?P<indent>\s*)(?P<label>[A-Za-z_][A-Za-z0-9_$]*):(?P<rest>.*)$"
)


def label_emitted_properties(
    sv_files: Iterable[Path],
    source_assertion_delta: Mapping[str, Any],
    *,
    wrapper_top: str,
) -> list[Dict[str, Any]]:
    """Assign reviewed labels using exact overlay line locators.

    Matching uses property kind, the exact overlay source line, and (for
    assertions) the expected label token retained in the emitted diagnostic.
    Every source property must match exactly one emitted statement.
    """

    files = [Path(path).resolve() for path in sv_files]
    if not files:
        raise PropertyIdentityError("verification elaboration emitted no SV files")
    candidates: list[Dict[str, Any]] = []
    file_lines: Dict[Path, list[str]] = {}
    for path in files:
        lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
        file_lines[path] = lines
        for index, line in enumerate(lines):
            match = _LABEL_RE.match(line.rstrip("\n"))
            if match is None:
                continue
            rest = match.group("rest")
            context = line if rest.strip() else _statement_context(lines, index)
            kind_match = re.search(r"\b(assert|cover)\s+property\b", context)
            if kind_match is None:
                continue
            candidates.append(
                {
                    "path": path,
                    "line_index": index,
                    "line": line,
                    "label": match.group("label"),
                    "kind": kind_match.group(1),
                    "context": context,
                    "match": match,
                }
            )

    identities = []
    used = set()
    expected_labels = set()
    for source in source_assertion_delta.get("properties", []):
        label = source.get("expected_label")
        if not isinstance(label, str) or not re.fullmatch(r"CSF_[0-9A-F]{16}", label):
            raise PropertyIdentityError("source property has an invalid expected label")
        if label in expected_labels:
            raise PropertyIdentityError(f"duplicate expected label: {label}")
        expected_labels.add(label)
        anchor = source.get("overlay_source_anchor", {})
        source_path = anchor.get("path")
        source_line = anchor.get("line")
        expected_kind = source.get("property_kind")
        if source_path != "SpecFlowOverlay.scala" or not isinstance(source_line, int):
            raise PropertyIdentityError("source property has no exact overlay locator")
        matches = []
        for candidate in candidates:
            key = (candidate["path"], candidate["line_index"])
            if key in used or candidate["kind"] != expected_kind:
                continue
            if not _context_has_locator(candidate["context"], source_path, source_line):
                continue
            if expected_kind == "assert" and label not in _statement_context(
                file_lines[candidate["path"]], candidate["line_index"]
            ):
                continue
            matches.append(candidate)
        if len(matches) != 1:
            raise PropertyIdentityError(
                f"source property {source.get('source_property_id')} matched {len(matches)} emitted properties"
            )
        candidate = matches[0]
        key = (candidate["path"], candidate["line_index"])
        used.add(key)
        match = candidate["match"]
        original_line = candidate["line"]
        replacement = (
            match.group("indent") + label + ":" + match.group("rest")
            + ("\n" if original_line.endswith("\n") else "")
        )
        file_lines[candidate["path"]][candidate["line_index"]] = replacement
        identities.append(
            {
                "source_property_id": source["source_property_id"],
                "obligation_id": source["obligation_id"],
                "role": source["role"],
                "property_kind": expected_kind,
                "expected_label": label,
                "original_emitted_label": candidate["label"],
                "backend_label": label,
                "emitted_property_id": f"{wrapper_top}.{label}",
                "overlay_source_anchor": dict(anchor),
                "emitted_statement_sha256": hashlib.sha256(
                    original_line.encode("utf-8")
                ).hexdigest(),
                "emitted_file": str(candidate["path"]),
            }
        )

    for path, lines in file_lines.items():
        temporary = path.with_name(path.name + ".tmp")
        temporary.write_text("".join(lines), encoding="utf-8")
        temporary.replace(path)
    for row in identities:
        row["labeled_file_sha256"] = file_sha256(Path(row["emitted_file"]))
    return identities


def validate_elaboration_certificate(value: Mapping[str, Any]) -> None:
    required = {
        "schema_version",
        "configuration_id",
        "wrapper_top",
        "verification_package_sha256",
        "source_assertion_delta_sha256",
        "overlay_manifest_sha256",
        "commands",
        "generated_files",
        "property_identities",
    }
    if set(value) != required or value.get("schema_version") != "elaboration_certificate.v1":
        raise PropertyIdentityError("elaboration certificate has an invalid exact schema")
    identities = value.get("property_identities")
    if not isinstance(identities, list) or not identities:
        raise PropertyIdentityError("elaboration certificate has no property identities")
    source_ids = [row.get("source_property_id") for row in identities]
    labels = [row.get("backend_label") for row in identities]
    emitted = [row.get("emitted_property_id") for row in identities]
    if len(set(source_ids)) != len(source_ids):
        raise PropertyIdentityError("certificate contains duplicate source property identity")
    if len(set(labels)) != len(labels) or len(set(emitted)) != len(emitted):
        raise PropertyIdentityError("certificate contains duplicate emitted property identity")
    for row in identities:
        path = Path(str(row.get("emitted_file", "")))
        if not path.is_file() or file_sha256(path) != row.get("labeled_file_sha256"):
            raise PropertyIdentityError("certificate emitted-file hash mismatch")


def write_certificate(path: Path, value: Mapping[str, Any]) -> None:
    validate_elaboration_certificate(value)
    Path(path).write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )


def _context_has_locator(context: str, source_path: str, line: int) -> bool:
    basename = Path(source_path).name
    if basename not in context:
        return False
    return bool(
        re.search(
            rf"(?:{re.escape(basename)}:|,\s*:){line}(?::|:\{{)",
            context,
        )
    )


def _statement_context(lines: list[str], index: int) -> str:
    return "".join(lines[index : min(len(lines), index + 4)])
