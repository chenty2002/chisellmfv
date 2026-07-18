"""Clean baseline compilation/elaboration with retained source locations."""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
from pathlib import Path
from typing import Any, Dict, Iterable, List, Mapping, Optional

from .config import GeneratorConfiguration, ProjectContract


class BaselineElaborationError(RuntimeError):
    """Raised when the deterministic baseline elaboration cannot be trusted."""


_MODULE_RE = re.compile(r"\bmodule\s+([A-Za-z_][A-Za-z0-9_$]*)\s*\(")
_PORT_LINE_RE = re.compile(
    r"^\s*(?:(input|output|inout)\s+)?(?:(?:wire|logic|reg)\s+)?"
    r"(?:\[\s*([0-9]+)\s*:\s*([0-9]+)\s*\]\s*)?"
    r"([A-Za-z_][A-Za-z0-9_$]*)\s*,?\s*(?://\s*(.*))?$"
)
_INTERNAL_RE = re.compile(
    r"^\s*(?:wire|logic|reg)\s*"
    r"(?:\[\s*([0-9]+)\s*:\s*([0-9]+)\s*\]\s*)?"
    r"([A-Za-z_][A-Za-z0-9_$]*)\s*;\s*(?://\s*(.*))?$",
    re.MULTILINE,
)
_LOCATOR_RES = (
    re.compile(r"@\[([^\]]+\.scala)\s+([0-9]+):([0-9]+)\]"),
    re.compile(r"([A-Za-z0-9_./-]+\.scala):([0-9]+):([0-9]+)"),
)


def elaborate_baseline(
    project: ProjectContract,
    configuration: GeneratorConfiguration,
    workspace_project: Path,
    output_path: Path,
) -> Dict[str, Any]:
    workspace_project = Path(workspace_project).resolve()
    output_path = Path(output_path).resolve()
    if any(
        "strip-debug-info" in str(option)
        for option in project.build["firtool_options"]
    ):
        raise BaselineElaborationError("source-locator stripping is forbidden")

    compile_result = _run(list(project.build["compile_argv"]), workspace_project)
    if compile_result.returncode != 0:
        raise BaselineElaborationError(
            "counter clean compile failed:\n" + compile_result.stdout[-5000:]
        )
    generated_root = workspace_project / "specflow-generated"
    parameter_args = [
        f"{name}={_argument_value(configuration.parameters[name])}"
        for name in project.generator["parameter_schema"]
    ]
    run_main = " ".join(
        [
            "runMain",
            str(project.build["elaborate_main"]),
            _sbt_quote(generated_root),
            *parameter_args,
        ]
    )
    elaborate_result = _run(["sbt", "--error", run_main], workspace_project)
    if elaborate_result.returncode != 0:
        raise BaselineElaborationError(
            "counter verification elaboration failed:\n"
            + elaborate_result.stdout[-5000:]
        )

    sv_files = _glob_sv_files(workspace_project, project.build["generated_sv_globs"])
    if not sv_files:
        raise BaselineElaborationError("verification elaboration emitted no SystemVerilog")
    objects = []
    locators = []
    modules = []
    files = []
    for sv_path in sv_files:
        text = sv_path.read_text(encoding="utf-8")
        module_match = _MODULE_RE.search(text)
        if module_match is None:
            raise BaselineElaborationError(f"emitted file has no module: {sv_path}")
        owner = module_match.group(1)
        modules.append(owner)
        file_locators = _source_locators(text)
        locators.extend(file_locators)
        objects.extend(_parse_ports(text, owner))
        port_names = {row["name"] for row in objects if row["owner_module"] == owner}
        for msb, lsb, name, comment in _INTERNAL_RE.findall(text):
            if name not in port_names:
                objects.append(
                    _elaboration_object(
                        owner, name, "internal", msb, lsb, _first_locator(comment)
                    )
                )
        files.append(
            {
                "path": str(sv_path.relative_to(workspace_project)),
                "sha256": _file_sha256(sv_path),
            }
        )
    if not locators:
        raise BaselineElaborationError(
            "verification elaboration retained no Scala source locator"
        )
    if project.generator["top_name"] not in modules:
        raise BaselineElaborationError(
            "emitted top does not match the project contract"
        )

    value = {
        "schema_version": "baseline_elaboration.v1",
        "configuration_id": configuration.configuration_id,
        "top": project.generator["top_name"],
        "modules": sorted(set(modules)),
        "objects": _deduplicate_objects(objects),
        "source_locators": sorted(
            locators,
            key=lambda row: (row["path"], row["line"], row["column"]),
        ),
        "generated_files": files,
        "commands": {
            "compile_argv": list(project.build["compile_argv"]),
            "elaborate_argv": ["sbt", "--error", run_main],
        },
        "firtool_options": list(project.build["firtool_options"]),
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return value


def _run(argv: List[str], cwd: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        argv,
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )


def _glob_sv_files(project_root: Path, patterns: Iterable[str]) -> List[Path]:
    files = set()
    for pattern in patterns:
        files.update(path.resolve() for path in project_root.glob(pattern) if path.is_file())
    return sorted(files)


def _source_locators(text: str) -> List[Dict[str, Any]]:
    rows = []
    seen = set()
    for pattern in _LOCATOR_RES:
        for path, line, column in pattern.findall(text):
            key = (path, int(line), int(column))
            if key in seen:
                continue
            seen.add(key)
            rows.append({"path": path, "line": int(line), "column": int(column)})
    return rows


def _elaboration_object(
    owner: str,
    name: str,
    direction: str,
    msb: str,
    lsb: str,
    source_locator: Optional[Dict[str, Any]],
) -> Dict[str, Any]:
    width = abs(int(msb) - int(lsb)) + 1 if msb and lsb else 1
    return {
        "name": name,
        "owner_module": owner,
        "direction": direction,
        "width": width,
        "signed": False,
        "source_locator_available": source_locator is not None,
        "source_locator": source_locator,
    }


def _parse_ports(text: str, owner: str) -> List[Dict[str, Any]]:
    header = text[text.index("(", _MODULE_RE.search(text).start()) + 1 : text.index(");")]
    rows = []
    direction = None
    width = ("", "")
    for raw_line in header.splitlines():
        line = raw_line.strip().rstrip("\t")
        match = _PORT_LINE_RE.fullmatch(line)
        if match is None:
            continue
        row_direction, msb, lsb, name, comment = match.groups()
        if row_direction is not None:
            direction = row_direction
            width = (msb, lsb)
        elif msb or lsb:
            width = (msb, lsb)
        if direction is None:
            continue
        rows.append(
            _elaboration_object(
                owner, name, direction, width[0], width[1], _first_locator(comment)
            )
        )
    return rows


def _first_locator(comment: str) -> Optional[Dict[str, Any]]:
    for pattern in _LOCATOR_RES:
        match = pattern.search(comment or "")
        if match is not None:
            path, line, column = match.groups()
            return {"path": path, "line": int(line), "column": int(column)}
    return None


def _deduplicate_objects(rows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    by_identity = {}
    for row in rows:
        key = (row["owner_module"], row["name"])
        previous = by_identity.get(key)
        if previous is not None and previous != row:
            raise BaselineElaborationError(f"conflicting elaboration object: {key}")
        by_identity[key] = row
    return [by_identity[key] for key in sorted(by_identity)]


def _argument_value(value: Any) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def _sbt_quote(value: Path) -> str:
    return '"' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '"'


def _file_sha256(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()
