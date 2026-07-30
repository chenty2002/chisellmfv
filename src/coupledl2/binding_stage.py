"""Single-call Stage 2 property binding orchestration."""

from __future__ import annotations

import json
import hashlib
import re
import shutil
from dataclasses import asdict
from pathlib import Path
from typing import Any, Dict, Optional

from .assertion_renderer import RenderResult, render_property_source
from .binding_contract import (
    BindingContractError,
    binding_manifest_tool,
    validate_binding_manifest,
)
from .rtl_property_labeler import RTLProperty, label_rtl_properties
from .workspace import StageContext, build_protocol_evidence, initialize_stage_context
from .stages import get_stage_spec
from ..core.artifact_contract import (
    file_sha256,
    write_stage_outcome,
)
from .property_compiler import (
    build_witness_plan,
    compile_manifest,
)
from .result_contract import (
    bind_operation_plan_to_package,
    build_operation_plan,
    build_unmaterialized_observation_map,
    canonical_sha256,
)


class BindingStageError(RuntimeError):
    """Raised for tool-protocol or deterministic Stage 2 failures."""


class CompilationGateError(BindingStageError):
    """Raised when repository-owned rendered source does not compile."""


class BindingStage:
    def __init__(
        self,
        workspace: Any,
        backend: Any,
        llm_client: Any,
        logger: Optional[Any],
        stage_context: Optional[StageContext] = None,
    ):
        self.workspace = workspace
        self.backend = backend
        self.llm_client = llm_client
        self.logger = logger
        self.stage_context = stage_context or initialize_stage_context(
            workspace, "bind_properties"
        )
        if self.stage_context.binding_catalog is None:
            raise BindingStageError("binding StageContext has no candidate catalog")
        self.catalog = self.stage_context.binding_catalog
        self.stage_dir = workspace.results_dir / "by_stage" / "02_bind_properties"
        self.stage_dir.mkdir(parents=True, exist_ok=True)
        self.model_calls = 0

    def run(self) -> Dict[str, Any]:
        target = (
            self.workspace.case_workspace
            / self.catalog.profile["target"]["relative_path"]
        )
        original_source = target.read_bytes()
        original_source_targets = {
            self.workspace.case_workspace / source_target["relative_path"]:
            (self.workspace.case_workspace / source_target["relative_path"]).read_bytes()
            for source_target in self.catalog.profile.get("source_targets", [])
        }
        generated_before = self._snapshot_generated()
        self._snapshot_source(target, "before")
        manifest: Optional[Dict[str, Any]] = None
        try:
            raw_manifest = self._request(
                "submit_binding_manifest",
                binding_manifest_tool(self.catalog),
                self._binding_messages(),
                max_tokens=2048,
            )
            manifest = validate_binding_manifest(raw_manifest, self.catalog)

            _write_json(self.stage_dir / "binding_manifest.json", manifest)
            render = render_property_source(target, manifest, self.catalog)
            self._write_render_artifacts(render)
            build = self.backend.verify_compilation(require_assertions=False)
            _write_json(self.stage_dir / "build_result.json", build)
            if not build.get("success"):
                raise CompilationGateError(
                    str(build.get("error") or "rendered property source did not compile")
                )
            if not isinstance(build.get("top_module"), str) or not build["top_module"]:
                raise CompilationGateError(
                    "successful build did not declare the exact top module"
                )
            generated_files = build.get("generated_files")
            if not isinstance(generated_files, list) or not generated_files:
                raise CompilationGateError(
                    "successful build did not declare generated_files"
                )
            generated = [Path(path) for path in generated_files]
            rtl_properties = label_rtl_properties(
                generated,
                manifest,
                self.catalog,
                require_evidence=True,
            )
            self._write_success_artifacts(
                manifest,
                rtl_properties,
                target,
                top_module=build["top_module"],
            )
            result = {
                "schema_version": "stage_result",
                "stage": "bind_properties",
                "success": True,
                "termination_reason": "property_binding_completed",
                "model_calls": self.model_calls,
                "binding_manifest_path": "binding_manifest.json",
                "property_package_path": "property_package.json",
                "assertion_delta_path": "assertion_delta.json",
                "rtl_property_count": len(rtl_properties),
            }
            result = write_stage_outcome(
                self.stage_dir, get_stage_spec("bind_properties"), result
            )
            self._event("binding_stage_completed", {"rtl_property_count": len(rtl_properties)})
            return {"success": True, "stage_result": result}
        except Exception as exc:
            target.write_bytes(original_source)
            for path, data in original_source_targets.items():
                path.write_bytes(data)
            self._restore_generated(generated_before)
            result = {
                "schema_version": "stage_result",
                "stage": "bind_properties",
                "success": False,
                "termination_reason": "binding_stage_failed",
                "model_calls": self.model_calls,
                "error": str(exc),
                "error_kind": type(exc).__name__,
            }
            if isinstance(exc, BindingContractError):
                result["contract_error"] = exc.to_dict()
            write_stage_outcome(
                self.stage_dir, get_stage_spec("bind_properties"), result
            )
            self._event("binding_stage_failed", {"error_kind": type(exc).__name__})
            return {"success": False, "stage_result": result}

    def _request(
        self,
        expected_name: str,
        tool: Dict[str, Any],
        messages: list[Dict[str, Any]],
        *,
        max_tokens: int,
    ) -> Dict[str, Any]:
        self.model_calls += 1
        response = self.llm_client.chat_with_tools(
            messages=messages,
            tools=[tool],
            tool_choice={
                "type": "function",
                "function": {"name": expected_name},
            },
            max_tokens=max_tokens,
            temperature=0,
            enable_thinking=False,
            parallel_tool_calls=False,
            usage_metadata={"stage": "bind_properties"},
        )
        calls = response.get("function_calls") if isinstance(response, dict) else None
        if (
            isinstance(response, dict)
            and response.get("type") == "function_calls"
            and isinstance(calls, list)
            and len(calls) == 1
            and calls[0].get("name") == expected_name
            and isinstance(calls[0].get("arguments"), dict)
        ):
            return calls[0]["arguments"]
        raise BindingStageError(
            "binding model returned plain text or malformed named tool output"
        )

    def _binding_messages(self) -> list[Dict[str, Any]]:
        payload = self.stage_context.stage_inputs
        return [
            {
                "role": "system",
                "content": (
                    "Select one to eight applicable repository property bindings. "
                    "Select at most one template instance for each property schema; "
                    "alternative approved templates are choices, not additional instances. "
                    "Every instance_id and base_label must be globally unique. "
                    "Use only candidate IDs and bounded parameters. "
                    "For bounded-liveness parameters, choose a conservative "
                    "bound from the schema/template range and the exposed "
                    "project scale; do not use short smoke-test bounds. "
                    "Evidence contains at most four representative candidate IDs "
                    "and does not need to repeat every binding. "
                    "Do not emit source code, file paths, patches, or template text."
                ),
            },
            {
                "role": "user",
                "content": json.dumps(payload, ensure_ascii=False),
            },
        ]

    def _write_render_artifacts(self, result: RenderResult) -> None:
        _write_json(
            self.stage_dir / "render_result.json",
            {
                "schema_version": "render_result",
                "target_path": _relative(result.target_path, self.workspace.run_dir),
                "base_label": result.base_label,
                "sha256_before": result.sha256_before,
                "sha256_after": result.sha256_after,
                "fragment_count": len(result.rendered_fragments),
            },
        )
        (self.stage_dir / "assertion_diff.patch").write_text(
            result.diff,
            encoding="utf-8",
        )

    def _write_success_artifacts(
        self,
        manifest: Dict[str, Any],
        properties: tuple[RTLProperty, ...],
        target: Path,
        *,
        top_module: str,
    ) -> None:
        records = [
            {
                **asdict(item),
                "rtl_file": _relative(Path(item.rtl_file), self.workspace.run_dir),
            }
            for item in properties
        ]
        protocol_rules = build_protocol_evidence(self.catalog).get("rules", [])
        trace_records = []
        for instance in manifest["instances"]:
            schema = self.catalog.schemas[instance["property_schema_id"]]
            protocol_rule = next((rule for rule in protocol_rules if rule.get("locator") == schema["source"].get("locator")), None)
            trace_record = {
                "instance_id": instance["instance_id"],
                "property_schema_id": instance["property_schema_id"],
                "template_id": instance["template_id"],
                "base_label": instance["base_label"],
                "binding_manifest_path": "binding_manifest.json",
                "source": schema["source"],
                "review_status": self.catalog.review["review_status"] if self.catalog.review else "not_reviewed",
                "rtl_properties": [
                    item
                    for item in records
                    if item["instance_id"] == instance["instance_id"]
                    and item["role"] == "primary_assertion"
                ],
            }
            if self.catalog.review:
                trace_record["review"] = {
                "review_id": self.catalog.review["review_id"],
                "reviewer": self.catalog.review["reviewer"],
                "reviewed_at": self.catalog.review["reviewed_at"],
                "asset_hashes": {
                    item["path"]: item["sha256"]
                    for item in self.catalog.review["assets"]
                },
                }
            if protocol_rule is not None:
                trace_record["protocol_rule"] = protocol_rule
            trace_records.append(trace_record)
        traceability = {
            "schema_version": "assertion_traceability",
            "properties": trace_records,
        }
        baseline_path = (
            self.workspace.results_dir / "preflight" / "baseline_assertion_inventory.json"
        )
        baseline_sha = _file_sha256(baseline_path) if baseline_path.is_file() else None
        labelled_records = []
        for record in records:
            label = record["rtl_label"]
            labelled_records.append({
                **record,
                "expected_property_id": f"{top_module}.{label}" if top_module else None,
            })
        delta_records = [
            item for item in labelled_records if item["role"] == "primary_assertion"
        ]
        expected_by_label = {
            item["rtl_label"]: item["expected_property_id"]
            for item in delta_records
        }
        for trace_record in traceability["properties"]:
            for rtl_record in trace_record["rtl_properties"]:
                rtl_record["expected_property_id"] = expected_by_label[rtl_record["rtl_label"]]
        baseline_scan_path = (
            self.workspace.results_dir / "preflight" / "generated_assertion_scan.json"
        )
        baseline_labels = set()
        if baseline_scan_path.is_file():
            baseline_scan = json.loads(baseline_scan_path.read_text(encoding="utf-8"))
            baseline_labels = {
                item.get("label")
                for item in baseline_scan.get("cl2_labels", [])
                if item.get("label")
            }
        delta_labels = {item["rtl_label"] for item in delta_records}
        if baseline_labels & delta_labels:
            raise BindingStageError(
                "assertion delta overlaps inherited baseline property labels"
            )
        certificate = compile_manifest(manifest, self.catalog, rtl_properties=records)
        witness_plan = build_witness_plan(manifest, self.catalog)
        operation_plan = build_operation_plan(
            traceability,
            [
                item
                for item in labelled_records
                if item["role"] != "primary_assertion"
            ],
            package_sha256="0" * 64,
        )
        property_package = {
            "schema_version": "property_package",
            "property_profile_id": self.catalog.profile["property_profile_id"],
            "binding_manifest": {
                "path": "binding_manifest.json",
                "sha256": file_sha256(self.stage_dir / "binding_manifest.json"),
            },
            "stage_inputs": {
                "path": "stage_inputs.json",
                "sha256": file_sha256(self.stage_dir / "stage_inputs.json"),
            },
            "review": self.catalog.review,
            "selected_candidates": [
                {
                    "instance_id": instance["instance_id"],
                    "bindings": {
                        role: {
                            "candidate_id": candidate_id,
                            "type": self.catalog.candidates[candidate_id]["type"],
                            "roles": self.catalog.candidates[candidate_id]["roles"],
                            "provenance": self.catalog.candidates[candidate_id]["provenance"],
                        }
                        for role, candidate_id in sorted(instance["bindings"].items())
                    },
                }
                for instance in manifest["instances"]
            ],
            "compilation_certificate": certificate,
            "witness_plan": witness_plan,
            "semantic_requirements": {
                "required_roles": [
                    "trigger_cover",
                    "observer_cover",
                    "state_cover",
                    "assumption_sat",
                    "negative_oracle",
                ],
                "status": "positive_portfolio_compiled",
            },
            "operation_plan": operation_plan,
            "package_semantics_sha256": "",
            "observation_map": build_unmaterialized_observation_map(
                top_module=top_module,
                package_sha256="0" * 64,
                reason="elaboration-time observation mapping is not part of binding",
            ),
            "traceability": traceability,
        }
        property_package = bind_operation_plan_to_package(property_package)
        _write_json(self.stage_dir / "property_package.json", property_package)
        _write_json(
            self.stage_dir / "assertion_delta.json",
            {
                "schema_version": "assertion_delta",
                "property_profile_id": self.catalog.profile["property_profile_id"],
                "instance_ids": [item["instance_id"] for item in manifest["instances"]],
                "base_labels": [item["base_label"] for item in manifest["instances"]],
                "top_module": top_module or None,
                "source": {
                    "path": _relative(target, self.workspace.run_dir),
                    "sha256": _file_sha256(target),
                    "render_result": "render_result.json",
                },
                "baseline_inventory": {
                    "path": "../../preflight/baseline_assertion_inventory.json",
                    "sha256": baseline_sha,
                },
                "property_package_sha256": file_sha256(
                    self.stage_dir / "property_package.json"
                ),
                "operation_plan_sha256": canonical_sha256(property_package["operation_plan"]),
                "operation_ids": [
                    item["operation_id"] for item in property_package["operation_plan"]["operations"]
                ],
                "rtl_properties": delta_records,
                "rtl_property_count": len(delta_records),
                "baseline_label_overlap": [],
            },
        )
        self._snapshot_source(target, "after")

    def _snapshot_source(self, target: Path, phase: str) -> None:
        destination = self.stage_dir / "source_snapshot" / phase / target.name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(target, destination)

    def _snapshot_generated(self) -> Dict[Path, bytes]:
        return {
            Path(path).resolve(): Path(path).read_bytes()
            for path in self.backend.discover_generated_verilog_files()
            if Path(path).is_file()
        }

    def _restore_generated(self, snapshot: Dict[Path, bytes]) -> None:
        current = {
            Path(path).resolve()
            for path in self.backend.discover_generated_verilog_files()
            if Path(path).is_file()
        }
        for path in current - set(snapshot):
            path.unlink()
        for path, content in snapshot.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)

    def _event(self, event: str, details: Dict[str, Any]) -> None:
        path = self.stage_dir / "stage_events.jsonl"
        with path.open("a", encoding="utf-8") as handle:
            handle.write(
                json.dumps(
                    {"event": event, **details},
                    ensure_ascii=False,
                    sort_keys=True,
                )
                + "\n"
            )


def _relative(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(Path(root).resolve()).as_posix()
    except ValueError:
        return str(path)


def _write_json(path: Path, value: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _file_sha256(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()
