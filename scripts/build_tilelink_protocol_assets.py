#!/usr/bin/env python3
"""Build deterministic md-only TileLink protocol retrieval assets."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any, Dict, Iterable, List


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = ROOT / "tilelink_spec_1.8.1.md"
DEFAULT_OUTPUT = ROOT / "src" / "coupledl2" / "protocol_assets" / "tilelink"
DOCUMENT_ID = "tilelink_spec_1.8.1"
SOURCE_PATH = "tilelink_spec_1.8.1.md"
SCRIPT_VERSION = "build_tilelink_protocol_assets.v1"
HEADING_RE = re.compile(r"^(#{1,6})\s*(.+?)\s*$")
TABLE_RE = re.compile(r"^Table\s+\d+\.", re.IGNORECASE)


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def build_chunks(source_path: Path) -> List[Dict[str, Any]]:
    """Split the markdown spec by headings and explicit table blocks."""
    lines = source_path.read_text(encoding="utf-8").splitlines()
    blocks = _heading_blocks(lines) + _table_blocks(lines)
    blocks.sort(key=lambda item: (item["md_line_start"], item["sort_order"]))

    chunks: List[Dict[str, Any]] = []
    for index, block in enumerate(blocks, start=1):
        start = block["md_line_start"]
        end = block["md_line_end"]
        text = "\n".join(lines[start - 1 : end])
        chunks.append(
            {
                "schema_version": "tilelink_protocol_chunk.v1",
                "chunk_id": f"tl_chunk_{index:04d}",
                "document_id": DOCUMENT_ID,
                "source_path": SOURCE_PATH,
                "chunk_kind": block["chunk_kind"],
                "heading_path": block["heading_path"],
                "md_line_start": start,
                "md_line_end": end,
                "locator": f"{SOURCE_PATH}:{start}-{end}",
                "text_sha256": sha256_text(text),
                "char_count": len(text),
                "text": text,
            }
        )
    return chunks


def build_manifest(
    source_path: Path,
    chunks: List[Dict[str, Any]],
    rules: Dict[str, Any],
    coverage: Dict[str, Any],
) -> Dict[str, Any]:
    source_text = source_path.read_text(encoding="utf-8")
    return {
        "schema_version": "tilelink_protocol_source_manifest.v1",
        "document_id": DOCUMENT_ID,
        "source_path": SOURCE_PATH,
        "source_sha256": sha256_text(source_text),
        "line_count": len(source_text.splitlines()),
        "md_only": True,
        "script_version": SCRIPT_VERSION,
        "chunk_count": len(chunks),
        "chunk_index_sha256": sha256_text(
            "\n".join(json.dumps(chunk, sort_keys=True) for chunk in chunks) + "\n"
        ),
        "rule_count": len(rules["rules"]),
        "rules_sha256": sha256_text(json.dumps(rules, sort_keys=True)),
        "coverage_sha256": sha256_text(json.dumps(coverage, sort_keys=True)),
    }


def build_coverage(
    source_path: Path,
    chunks: List[Dict[str, Any]],
    rules: Dict[str, Any],
) -> Dict[str, Any]:
    source_sha = sha256_text(source_path.read_text(encoding="utf-8"))
    return {
        "schema_version": "tilelink_protocol_coverage.v1",
        "document_id": DOCUMENT_ID,
        "source_path": SOURCE_PATH,
        "source_sha256": source_sha,
        "chunk_count": len(chunks),
        "rule_count": len(rules["rules"]),
        "rules": [
            {
                "rule_id": rule["rule_id"],
                "locator": rule["locator"],
                "chunk_ids": _chunks_for_locator(rule["locator"], chunks),
                "candidate_schema_ids": rule["candidate_schema_ids"],
            }
            for rule in rules["rules"]
        ],
    }


def load_rules(output_dir: Path, source_path: Path) -> Dict[str, Any]:
    rules_path = output_dir / "rules.json"
    if not rules_path.is_file():
        raise FileNotFoundError(
            "rules.json must be maintained by hand before building generated assets"
        )
    rules = json.loads(rules_path.read_text(encoding="utf-8"))
    _validate_rules(rules, source_path)
    source_sha = sha256_text(source_path.read_text(encoding="utf-8"))
    if rules.get("source_sha256") != source_sha:
        rules["source_sha256"] = source_sha
    return rules


def write_assets(source_path: Path, output_dir: Path) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    chunks = build_chunks(source_path)
    rules = load_rules(output_dir, source_path)
    coverage = build_coverage(source_path, chunks, rules)
    manifest = build_manifest(source_path, chunks, rules, coverage)

    _write_json(output_dir / "rules.json", rules)
    _write_jsonl(output_dir / "chunks.jsonl", chunks)
    _write_json(output_dir / "coverage.json", coverage)
    _write_json(output_dir / "source_manifest.json", manifest)


def _heading_blocks(lines: List[str]) -> List[Dict[str, Any]]:
    headings: List[Dict[str, Any]] = []
    stack: List[str] = []
    for index, line in enumerate(lines, start=1):
        match = HEADING_RE.match(line)
        if not match:
            continue
        level = len(match.group(1))
        title = match.group(2).strip()
        stack = stack[: level - 1]
        stack.append(title)
        headings.append(
            {
                "line": index,
                "level": level,
                "heading_path": list(stack),
            }
        )

    blocks: List[Dict[str, Any]] = []
    for idx, heading in enumerate(headings):
        start = heading["line"]
        next_start = headings[idx + 1]["line"] if idx + 1 < len(headings) else len(lines) + 1
        blocks.append(
            {
                "chunk_kind": "heading",
                "heading_path": heading["heading_path"],
                "md_line_start": start,
                "md_line_end": max(start, next_start - 1),
                "sort_order": 0,
            }
        )
    return blocks


def _table_blocks(lines: List[str]) -> List[Dict[str, Any]]:
    blocks: List[Dict[str, Any]] = []
    current_heading: List[str] = []
    for index, line in enumerate(lines, start=1):
        heading = HEADING_RE.match(line)
        if heading:
            level = len(heading.group(1))
            current_heading = current_heading[: level - 1]
            current_heading.append(heading.group(2).strip())
        if not TABLE_RE.match(line.strip()):
            continue
        end = index
        cursor = index + 1
        while cursor <= len(lines):
            candidate = lines[cursor - 1].strip()
            if HEADING_RE.match(candidate):
                break
            if cursor > index and candidate == "":
                after_blank = lines[cursor].strip() if cursor < len(lines) else ""
                if not after_blank.startswith("<table"):
                    break
            end = cursor
            if candidate.endswith("</table>"):
                break
            cursor += 1
        blocks.append(
            {
                "chunk_kind": "table",
                "heading_path": [*current_heading, line.strip()],
                "md_line_start": index,
                "md_line_end": end,
                "sort_order": 1,
            }
        )
    return blocks


def _chunks_for_locator(locator: str, chunks: Iterable[Dict[str, Any]]) -> List[str]:
    start, end = _parse_locator(locator)
    return [
        chunk["chunk_id"]
        for chunk in chunks
        if chunk["md_line_start"] <= end and chunk["md_line_end"] >= start
    ]


def _parse_locator(locator: str) -> tuple[int, int]:
    prefix = f"{SOURCE_PATH}:"
    if not locator.startswith(prefix):
        raise ValueError(f"locator must start with {prefix}: {locator}")
    start_text, end_text = locator[len(prefix) :].split("-", 1)
    return int(start_text), int(end_text)


def _validate_rules(rules: Dict[str, Any], source_path: Path) -> None:
    source_lines = source_path.read_text(encoding="utf-8").splitlines()
    if rules.get("schema_version") != "tilelink_rule_index.v1":
        raise ValueError("unsupported rules schema_version")
    if rules.get("generation_policy") != "handwritten":
        raise ValueError("rules.json must declare handwritten generation_policy")
    seen: set[str] = set()
    for rule in rules.get("rules", []):
        if rule["rule_id"] in seen:
            raise ValueError(f"duplicate rule id: {rule['rule_id']}")
        seen.add(rule["rule_id"])
        if "pdf" in json.dumps(rule).lower():
            raise ValueError(f"rules must be md-only: {rule['rule_id']}")
        start, end = _parse_locator(rule["locator"])
        if start < 1 or end < start or end > len(source_lines):
            raise ValueError(f"invalid locator: {rule['locator']}")
        referenced = "\n".join(source_lines[start - 1 : end])
        for term in rule["evidence_terms"]:
            if term not in referenced:
                raise ValueError(f"rule evidence term not found: {rule['rule_id']}: {term}")


def _write_json(path: Path, payload: Dict[str, Any]) -> None:
    path.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _write_jsonl(path: Path, rows: Iterable[Dict[str, Any]]) -> None:
    path.write_text(
        "".join(json.dumps(row, sort_keys=True) + "\n" for row in rows),
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    write_assets(args.source, args.output)


if __name__ == "__main__":
    main()
