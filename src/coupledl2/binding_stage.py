"""Constrained two-call Stage 2 property binding orchestration."""

from __future__ import annotations

import json
import shutil
from dataclasses import asdict
from pathlib import Path
from typing import Any, Dict, Optional

from .assertion_renderer import RenderResult, render_property_source
from .binding_contract import (
    REPAIRABLE_ERROR_KINDS,
    BindingContractError,
    apply_binding_patch,
    binding_manifest_tool,
    binding_patch_tool,
    validate_binding_manifest,
)
from .property_catalog import load_property_profile, public_catalog
from .rtl_property_labeler import RTLProperty, label_rtl_properties


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
    ):
        self.workspace = workspace
        self.backend = backend
        self.llm_client = llm_client
        self.logger = logger
        self.catalog = load_property_profile(workspace.config.property_profile)
        self.stage_dir = workspace.results_dir / "by_stage" / "02_bind_properties"
        self.stage_dir.mkdir(parents=True, exist_ok=True)
        self.model_calls = 0

    def run(self) -> Dict[str, Any]:
        target = (
            self.workspace.case_workspace
            / self.catalog.profile["target"]["relative_path"]
        )
        original_source = target.read_bytes()
        generated_before = self._snapshot_generated()
        self._write_inputs()
        self._snapshot_source(target, "before")
        manifest: Optional[Dict[str, Any]] = None
        try:
            raw_manifest = self._request(
                "submit_binding_manifest",
                binding_manifest_tool(self.catalog),
                self._binding_messages(),
                max_tokens=2048,
            )
            try:
                manifest = validate_binding_manifest(raw_manifest, self.catalog)
            except BindingContractError as exc:
                if exc.error_kind not in REPAIRABLE_ERROR_KINDS:
                    raise
                raw_patch = self._request(
                    "submit_binding_patch",
                    binding_patch_tool(
                        self.catalog,
                        raw_manifest.get("instances", [{}])[0].get("instance_id", ""),
                    ),
                    self._patch_messages(raw_manifest, exc),
                    max_tokens=1024,
                )
                manifest = apply_binding_patch(raw_manifest, raw_patch, self.catalog)

            _write_json(self.stage_dir / "binding_manifest.json", manifest)
            render = render_property_source(target, manifest, self.catalog)
            self._write_render_artifacts(render)
            build = self.backend.verify_compilation(require_assertions=False)
            _write_json(self.stage_dir / "build_result.json", build)
            if not build.get("success"):
                raise CompilationGateError(
                    str(build.get("error") or "rendered property source did not compile")
                )
            generated = [
                Path(path)
                for path in (
                    build.get("generated_files")
                    or self.backend.discover_generated_verilog_files()
                )
            ]
            rtl_properties = label_rtl_properties(
                generated,
                manifest,
                self.catalog,
            )
            self._write_success_artifacts(manifest, rtl_properties, target)
            result = {
                "schema_version": "stage_result.v2",
                "stage": "bind_properties",
                "success": True,
                "termination_reason": "property_binding_completed",
                "model_calls": self.model_calls,
                "binding_manifest_path": "binding_manifest.json",
                "assertion_traceability_path": "assertion_traceability.json",
                "rtl_property_count": len(rtl_properties),
            }
            _write_json(self.stage_dir / "stage_result.json", result)
            _write_json(
                self.stage_dir / "handoff.json",
                {
                    "schema_version": "stage_handoff.v1",
                    "stage": "bind_properties",
                    "success": True,
                    "artifacts": {
                        "binding_manifest": "binding_manifest.json",
                        "assertion_traceability": "assertion_traceability.json",
                        "rtl_label_result": "rtl_label_result.json",
                    },
                },
            )
            self._event("binding_stage_completed", {"rtl_property_count": len(rtl_properties)})
            return {"success": True, "stage_result": result}
        except Exception as exc:
            target.write_bytes(original_source)
            self._restore_generated(generated_before)
            result = {
                "schema_version": "stage_result.v2",
                "stage": "bind_properties",
                "success": False,
                "termination_reason": "binding_stage_failed",
                "model_calls": self.model_calls,
                "error": str(exc),
                "error_kind": type(exc).__name__,
            }
            if isinstance(exc, BindingContractError):
                result["contract_error"] = exc.to_dict()
            _write_json(self.stage_dir / "stage_result.json", result)
            _write_json(
                self.stage_dir / "handoff.json",
                {
                    "schema_version": "stage_handoff.v1",
                    "stage": "bind_properties",
                    "success": False,
                    "error_kind": type(exc).__name__,
                },
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
            stage="bind_properties",
            usage_metadata={"stage": "bind_properties"},
        )
        calls = response.get("function_calls") if isinstance(response, dict) else None
        if response.get("type") != "function_calls" or not isinstance(calls, list):
            raise BindingStageError("binding model returned plain text or malformed tool output")
        if len(calls) != 1 or calls[0].get("name") != expected_name:
            raise BindingStageError("binding model must make exactly one named tool call")
        arguments = calls[0].get("arguments")
        if not isinstance(arguments, dict):
            raise BindingStageError("binding tool arguments must be a JSON object")
        return arguments

    def _binding_messages(self) -> list[Dict[str, Any]]:
        return [
            {
                "role": "system",
                "content": (
                    "Select exactly one repository property binding. "
                    "Use only candidate IDs and bounded parameters. "
                    "Evidence contains at most four representative candidate IDs "
                    "and does not need to repeat every binding. "
                    "Do not emit source code, file paths, patches, or template text."
                ),
            },
            {
                "role": "user",
                "content": json.dumps(public_catalog(self.catalog), ensure_ascii=False),
            },
        ]

    def _patch_messages(
        self,
        manifest: Dict[str, Any],
        error: BindingContractError,
    ) -> list[Dict[str, Any]]:
        instance = manifest.get("instances", [{}])[0]
        safe_manifest = {
            key: instance.get(key)
            for key in (
                "instance_id", "property_schema_id", "template_id",
                "target", "bindings", "parameters", "base_label",
            )
        }
        return [
            {
                "role": "system",
                "content": (
                    "Submit one minimal binding patch. Only replace an allowed "
                    "binding, bounded parameter, or profile-allowed template."
                ),
            },
            {
                "role": "user",
                "content": json.dumps(
                    {
                        "manifest": safe_manifest,
                        "validation_error": error.to_dict(),
                        "catalog": public_catalog(self.catalog),
                    },
                    ensure_ascii=False,
                ),
            },
        ]

    def _write_inputs(self) -> None:
        public = public_catalog(self.catalog)
        _write_json(
            self.stage_dir / "stage_inputs.json",
            {
                "schema_version": "stage_inputs.v1",
                "stage": "bind_properties",
                "property_profile": self.workspace.config.property_profile,
                "model_contract": "one binding manifest and at most one patch",
            },
        )
        _write_json(
            self.stage_dir / "property_catalog.json",
            {
                "schema_version": "property_catalog_snapshot.v1",
                "schemas": self.catalog.schemas,
                "profile": self.catalog.profile,
            },
        )
        _write_json(
            self.stage_dir / "template_catalog.json",
            {
                "schema_version": "template_catalog_snapshot.v1",
                "templates": self.catalog.templates,
            },
        )
        _write_json(
            self.stage_dir / "binding_candidates.json",
            {
                "schema_version": "binding_candidates.v1",
                "candidates": public["candidates"],
            },
        )

    def _write_render_artifacts(self, result: RenderResult) -> None:
        _write_json(
            self.stage_dir / "render_result.json",
            {
                "schema_version": "render_result.v1",
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
    ) -> None:
        records = [
            {
                **asdict(item),
                "rtl_file": _relative(Path(item.rtl_file), self.workspace.run_dir),
            }
            for item in properties
        ]
        _write_json(
            self.stage_dir / "generated_assertion_scan.json",
            {
                "schema_version": "generated_assertion_scan.v2",
                "assertion_count": len(records),
                "properties": records,
            },
        )
        _write_json(
            self.stage_dir / "rtl_label_result.json",
            {
                "schema_version": "rtl_label_result.v1",
                "properties": records,
            },
        )
        instance = manifest["instances"][0]
        _write_json(
            self.stage_dir / "assertion_traceability.json",
            {
                "schema_version": "assertion_traceability.v1",
                "properties": [
                    {
                        "instance_id": instance["instance_id"],
                        "property_schema_id": instance["property_schema_id"],
                        "template_id": instance["template_id"],
                        "base_label": instance["base_label"],
                        "binding_manifest_path": "binding_manifest.json",
                        "review_status": "not_reviewed",
                        "rtl_properties": records,
                    }
                ],
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
