"""Atomic isolated workspaces and allowlisted model views."""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict

from .config import (
    MODEL_VIEW_MANIFEST_SCHEMA,
    RUN_MANIFEST_SCHEMA,
    GeneratorConfiguration,
    ProjectContract,
    SpecFlowRunConfig,
)
from .stages import get_stage_spec, stage_contract_snapshot
from .property_decomposition import (
    build_authoring_scope,
    load_property_decomposition,
)


_MODEL_LEAK_RE = re.compile(
    r"\b(?:buggy(?:_[0-9]+)?|gold(?:en)?|mutation|expected[_ -]?verdict|"
    r"private[_ -]?trigger|reference[_ -]?diff)\b",
    re.IGNORECASE,
)
_COPY_IGNORES = {
    ".git",
    ".bloop",
    ".metals",
    ".scala-build",
    "target",
    "generated",
    "jgproject",
    "formal",
    "__pycache__",
}


@dataclass(frozen=True)
class SpecFlowWorkspace:
    """Resolved workspace layout with explicit, fail-if-existing materialization."""

    run_dir: Path
    config: SpecFlowRunConfig

    def __post_init__(self) -> None:
        object.__setattr__(self, "run_dir", Path(self.run_dir).resolve())

    @property
    def manifest_path(self) -> Path:
        return self.run_dir / "manifest.json"

    @property
    def inputs_dir(self) -> Path:
        return self.run_dir / "inputs"

    @property
    def project_workspace(self) -> Path:
        return self.run_dir / "workspace" / "project"

    @property
    def indexes_dir(self) -> Path:
        return self.run_dir / "indexes"

    @property
    def logs_dir(self) -> Path:
        return self.run_dir / "logs"

    @property
    def stages_dir(self) -> Path:
        return self.run_dir / "stages"

    @property
    def final_result_path(self) -> Path:
        return self.run_dir / "final_result.json"

    @property
    def cost_summary_path(self) -> Path:
        return self.run_dir / "run_cost_summary.json"

    def stage_dir(self, stage: str) -> Path:
        return self.stages_dir / get_stage_spec(stage).directory_name

    def manifest_contract(self) -> dict:
        """Return the stage/layout portion embedded in every live manifest."""

        return {
            "schema_version": RUN_MANIFEST_SCHEMA,
            "copy_strategy": self.config.copy_strategy,
            "stages": list(stage_contract_snapshot()),
            "stages_root": "stages",
            "workspace_project": "workspace/project",
        }

    def materialize(
        self,
        project: ProjectContract,
        configuration: GeneratorConfiguration,
        public_spec_package: Dict[str, Any],
    ) -> Dict[str, Any]:
        """Create one isolated run atomically and return its frozen manifest."""

        if self.run_dir.exists():
            raise FileExistsError(f"SpecFlow run already exists: {self.run_dir}")
        if project.path != self.config.project_contract:
            raise ValueError("validated project contract does not match run config")
        if configuration.path != self.config.configuration:
            raise ValueError("validated configuration does not match run config")
        if Path(self.config.specification).resolve() != (
            project.repository_root / public_spec_package["spec_path"]
        ).resolve():
            raise ValueError("validated public spec does not match run config")

        self.run_dir.parent.mkdir(parents=True, exist_ok=True)
        staging = Path(
            tempfile.mkdtemp(prefix=f".{self.run_dir.name}.staging-", dir=self.run_dir.parent)
        )
        try:
            project_copy = staging / "workspace" / "project"
            shutil.copytree(
                project.project_root,
                project_copy,
                ignore=lambda _directory, names: sorted(set(names) & _COPY_IGNORES),
            )
            inputs = staging / "inputs"
            indexes = staging / "indexes"
            logs = staging / "logs"
            inputs.mkdir(parents=True)
            indexes.mkdir(parents=True)
            logs.mkdir(parents=True)
            (staging / "stages").mkdir()
            _copy_file(project.path, inputs / "project_contract.json")
            _copy_file(configuration.path, inputs / "configuration.json")
            _copy_file(self.config.specification, inputs / "specification.md")
            _write_json(inputs / "public_spec_package.json", public_spec_package)
            decomposition_path = self.config.specification.parent / "property_decomposition.json"
            if not decomposition_path.is_file():
                raise FileNotFoundError(
                    f"missing required property decomposition: {decomposition_path}"
                )
            decomposition = load_property_decomposition(
                decomposition_path, public_spec_package
            )
            _write_json(inputs / "property_decomposition.json", decomposition)
            authoring_scope = build_authoring_scope(
                decomposition,
                public_spec_package,
                self.config.expected_property_ids,
                self.config.component_ids,
                self.config.clause_ids,
            )
            _write_json(inputs / "authoring_scope.json", authoring_scope)
            model_view = _materialize_model_view(
                project, project_copy, inputs / "model_sources"
            )
            _write_json(inputs / "model_view_manifest.json", model_view)
            _write_json(inputs / "diagnosis_config.json", dict(project.diagnosis))

            input_hashes = {
                "project_contract_sha256": _file_sha256(inputs / "project_contract.json"),
                "configuration_sha256": _file_sha256(inputs / "configuration.json"),
                "specification_sha256": _file_sha256(inputs / "specification.md"),
                "public_spec_package_sha256": _file_sha256(
                    inputs / "public_spec_package.json"
                ),
                "property_decomposition_sha256": _file_sha256(
                    inputs / "property_decomposition.json"
                ),
                "authoring_scope_sha256": _file_sha256(inputs / "authoring_scope.json"),
                "model_view_manifest_sha256": _file_sha256(
                    inputs / "model_view_manifest.json"
                ),
                "diagnosis_config_sha256": _file_sha256(
                    inputs / "diagnosis_config.json"
                ),
            }
            _write_json(inputs / "input_hashes.json", input_hashes)
            for stage in stage_contract_snapshot():
                (staging / "stages" / (
                    f"{stage['ordinal']:02d}_{stage['name']}"
                )).mkdir(parents=True)

            manifest = self.manifest_contract()
            manifest.update(
                {
                    "project_id": project.project_id,
                    "configuration_id": configuration.configuration_id,
                    "opaque_task_id": self.config.opaque_task_id,
                    "input_hashes": input_hashes,
                    "workspace_hash": _tree_sha256(project_copy),
                    "visible_source_root_allowlist": [
                        str(root.relative_to(project.project_root))
                        for root in project.model_visible_roots
                    ],
                    "model_view_manifest_sha256": input_hashes[
                        "model_view_manifest_sha256"
                    ],
                    "diagnosis": dict(project.diagnosis),
                    "diagnosis_config_sha256": input_hashes[
                        "diagnosis_config_sha256"
                    ],
                    "public_spec_sha256": public_spec_package["spec_sha256"],
                    "suite_ledger_sha256": public_spec_package[
                        "suite_ledger_sha256"
                    ],
                    "review_state": "not_started",
                    "index_hashes": {},
                }
            )
            _write_json(staging / "manifest.json", manifest)
            staging.rename(self.run_dir)
            return manifest
        except BaseException:
            if staging.exists():
                shutil.rmtree(staging)
            raise

    def record_indexes(self, artifacts: Dict[str, Path]) -> Dict[str, str]:
        """Bind completed deterministic index artifacts into the run manifest."""

        manifest = _read_json(self.manifest_path)
        if manifest.get("index_hashes"):
            raise ValueError("index hashes are immutable once recorded")
        hashes = {}
        for name, path in sorted(artifacts.items()):
            path = Path(path).resolve()
            try:
                path.relative_to(self.indexes_dir)
            except ValueError as exc:
                raise ValueError("index artifact is outside the run indexes directory") from exc
            if not path.is_file():
                raise FileNotFoundError(path)
            hashes[name] = _file_sha256(path)
        manifest["index_hashes"] = hashes
        manifest["preflight_status"] = "index_ready"
        _write_json(self.manifest_path, manifest)
        return hashes


def _materialize_model_view(
    project: ProjectContract, project_copy: Path, destination: Path
) -> Dict[str, Any]:
    files = []
    source_ids = set()
    for relative in project.model_visible_files:
        source = project_copy / relative
        text = source.read_text(encoding="utf-8")
        exclusions = [
            row
            for row in project.model_view["exclusions"]
            if Path(row["path"]) == relative
        ]
        if exclusions:
            source_lines = text.splitlines(keepends=True)
            for exclusion in exclusions:
                for line_index in range(exclusion["start_line"] - 1, exclusion["end_line"]):
                    ending = "\n" if source_lines[line_index].endswith("\n") else ""
                    source_lines[line_index] = ending
            text = "".join(source_lines)
        match = _MODEL_LEAK_RE.search(text)
        if match is not None:
            raise ValueError(
                f"model-visible source contains evaluator leakage token {match.group(0)!r}: {relative}"
            )
        source_id = "source_" + hashlib.sha256(str(relative).encode("utf-8")).hexdigest()[:16]
        if source_id in source_ids:
            raise ValueError("duplicate model-visible source ID")
        source_ids.add(source_id)
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
        files.append(
            {
                "source_id": source_id,
                "path": str(relative),
                "sha256": _file_sha256(target),
                "provenance": {
                    "kind": (
                        "allowlisted_exact_copy"
                        if not exclusions
                        else "hash_bound_line_redaction"
                    ),
                    "project_contract_sha256": _file_sha256(project.path),
                    "source_sha256": _file_sha256(source),
                    "excluded_line_ranges": [
                        {
                            "start_line": row["start_line"],
                            "end_line": row["end_line"],
                        }
                        for row in exclusions
                    ],
                },
            }
        )
    return {
        "schema_version": MODEL_VIEW_MANIFEST_SCHEMA,
        "files": files,
    }


def _copy_file(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(Path(source), destination)


def _file_sha256(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def _tree_sha256(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(item for item in Path(root).rglob("*") if item.is_file()):
        relative = path.relative_to(root).as_posix()
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(bytes.fromhex(_file_sha256(path)))
    return digest.hexdigest()


def _write_json(path: Path, value: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    temporary.replace(path)


def _read_json(path: Path) -> Dict[str, Any]:
    value = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON object required: {path}")
    return value
