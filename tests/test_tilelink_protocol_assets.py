import hashlib
import importlib.util
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

SPEC_PATH = ROOT / "tilelink_spec_1.8.1.md"
ASSET_DIR = ROOT / "src" / "coupledl2" / "protocol_assets" / "tilelink"


def _load_builder():
    path = ROOT / "scripts" / "build_tilelink_protocol_assets.py"
    spec = importlib.util.spec_from_file_location("build_tilelink_protocol_assets", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def _read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def test_builder_parses_md_headings_into_stable_chunks():
    builder = _load_builder()

    chunks = builder.build_chunks(SPEC_PATH)

    assert len(chunks) >= 40
    first = chunks[0]
    assert first["chunk_id"] == "tl_chunk_0001"
    assert first["heading_path"] == ["SiFive TileLink Specification"]
    assert first["md_line_start"] == 1
    assert first["md_line_end"] >= first["md_line_start"]
    assert first["locator"] == "tilelink_spec_1.8.1.md:1-4"
    assert first["char_count"] == len(first["text"])
    assert first["text_sha256"] == hashlib.sha256(
        first["text"].encode("utf-8")
    ).hexdigest()

    flow = next(chunk for chunk in chunks if chunk["heading_path"][-1].startswith("4.1."))
    assert "Flow Control Rules" in flow["heading_path"][-1]
    assert "valid must never depend on ready" in flow["text"]
    assert flow["locator"].startswith("tilelink_spec_1.8.1.md:")


def test_committed_protocol_assets_are_md_only_and_hash_stable():
    manifest = _read_json(ASSET_DIR / "source_manifest.json")
    chunks = [
        json.loads(line)
        for line in (ASSET_DIR / "chunks.jsonl").read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    coverage = _read_json(ASSET_DIR / "coverage.json")
    rules = _read_json(ASSET_DIR / "rules.json")

    source_text = SPEC_PATH.read_text(encoding="utf-8")
    source_sha = hashlib.sha256(source_text.encode("utf-8")).hexdigest()

    assert manifest["schema_version"] == "tilelink_protocol_source_manifest"
    assert manifest["document_id"] == "tilelink_spec_1.8.1"
    assert manifest["source_path"] == "tilelink_spec_1.8.1.md"
    assert manifest["source_sha256"] == source_sha
    assert manifest["md_only"] is True
    assert not any("pdf" in key.lower() for key in manifest)

    assert manifest["chunk_count"] == len(chunks)
    assert coverage["chunk_count"] == len(chunks)
    assert coverage["rule_count"] == len(rules["rules"])
    assert coverage["source_sha256"] == source_sha
    assert rules["source_sha256"] == source_sha

    for chunk in chunks:
        assert chunk["locator"]
        assert chunk["source_path"] == "tilelink_spec_1.8.1.md"
        assert chunk["md_line_start"] <= chunk["md_line_end"]
        assert chunk["char_count"] == len(chunk["text"])
        assert chunk["text_sha256"] == hashlib.sha256(
            chunk["text"].encode("utf-8")
        ).hexdigest()
        assert not any("pdf" in key.lower() for key in chunk)


def test_rules_are_handwritten_and_locators_resolve_to_md_lines():
    rules = _read_json(ASSET_DIR / "rules.json")
    coverage = _read_json(ASSET_DIR / "coverage.json")
    source_lines = SPEC_PATH.read_text(encoding="utf-8").splitlines()

    rule_ids = {rule["rule_id"] for rule in rules["rules"]}
    assert {
        "TL_4_1_BURST_CONTROL_STABLE",
        "TL_9_2_GRANT_PROBE_SERIALIZATION",
        "TL_9_2_PROBE_ACK_RESPONSE",
        "TL_9_2_RELEASE_ACK_PAIRING",
        "TL_6_4_IDENTIFIER_PAIRING",
    }.issubset(rule_ids)

    assert rules["generation_policy"] == "handwritten"
    assert coverage["schema_version"] == "tilelink_protocol_coverage"
    coverage_by_rule = {
        item["rule_id"] for item in coverage["candidate_schema_progress"]
    }
    assert coverage_by_rule == rule_ids

    for rule in rules["rules"]:
        assert rule["document"] == "tilelink_spec_1.8.1"
        assert rule["locator"].startswith("tilelink_spec_1.8.1.md:")
        assert "pdf" not in json.dumps(rule).lower()
        assert rule["statement"]
        line_range = rule["locator"].split(":", 1)[1]
        start, end = [int(part) for part in line_range.split("-", 1)]
        assert 1 <= start <= end <= len(source_lines)
        referenced_text = "\n".join(source_lines[start - 1 : end])
        for needle in rule["evidence_terms"]:
            assert needle in referenced_text
        progress = next(
            item for item in coverage["candidate_schema_progress"]
            if item["rule_id"] == rule["rule_id"]
        )
        assert progress["chunk_ids"]
    assert all(
        item["source_kind"] == "protocol_requirement"
        for item in coverage["approved_executable_coverage"]
    )
