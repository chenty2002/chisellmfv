"""Single path-policy source for CoupledL2 workspace operations."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import FrozenSet


class PathIntent(str, Enum):
    DISCOVER = "discover"
    READ = "read"
    WRITE = "write"


@dataclass(frozen=True)
class PathDecision:
    allowed: bool
    reason: str
    rule: str


SYSTEM_CACHE_DIRS: FrozenSet[str] = frozenset(
    {
        ".git",
        ".bsp",
        ".bloop",
        ".metals",
        ".mill",
        "__pycache__",
        ".pytest_cache",
        ".mypy_cache",
        "node_modules",
    }
)
BUILD_OUTPUT_DIRS: FrozenSet[str] = frozenset(
    {"out", "target", "build", "dist", ".cache", "repair_loop"}
)
GENERATED_DIRS: FrozenSet[str] = frozenset({"generated"})
CONTROL_DIRS: FrozenSet[str] = frozenset(
    {"indexes", "rules", "skills", "results", "logs", "memories"}
)
BINARY_SUFFIXES: FrozenSet[str] = frozenset(
    {".fst", ".vcd", ".class", ".jar", ".o", ".so", ".a", ".pyc"}
)
WRITABLE_TEXT_SUFFIXES: FrozenSet[str] = frozenset(
    {
        ".scala",
        ".sc",
        ".sbt",
        ".py",
        ".sh",
        ".sv",
        ".svh",
        ".v",
        ".vh",
        ".json",
        ".jsonl",
        ".yaml",
        ".yml",
        ".toml",
        ".conf",
        ".cfg",
        ".properties",
        ".txt",
        ".md",
        ".tcl",
        ".mk",
    }
)


def _parts(relative_path: Path):
    return tuple(part for part in Path(relative_path).parts if part not in {"", "."})


def evaluate_workspace_path(
    relative_path: Path,
    *,
    intent: PathIntent,
    explicit_root: bool = False,
) -> PathDecision:
    parts = _parts(relative_path)
    lowered = tuple(part.lower() for part in parts)
    suffix = Path(parts[-1]).suffix.lower() if parts else ""

    if any(part in SYSTEM_CACHE_DIRS for part in lowered):
        return PathDecision(False, "system or cache path is unavailable", "system_cache")
    if suffix in BINARY_SUFFIXES:
        if intent == PathIntent.READ:
            return PathDecision(
                False,
                "binary evidence requires a specialized tool",
                "binary_file",
            )
        return PathDecision(False, "binary artifacts are not workspace files", "binary_file")

    generated = any(part in GENERATED_DIRS for part in lowered) or (
        len(parts) >= 2
        and parts[-2].lower() == "verilog"
        and Path(parts[-1]).match("VerifyTop*.sv")
    )
    build_output = any(part in BUILD_OUTPUT_DIRS for part in lowered)
    controlled = any(part in CONTROL_DIRS for part in lowered)

    if intent == PathIntent.DISCOVER:
        if generated and not explicit_root:
            return PathDecision(
                False,
                "generated verification output is hidden from default discovery",
                "generated_output",
            )
        if build_output and not explicit_root:
            return PathDecision(
                False,
                "build output is hidden from default discovery",
                "build_output",
            )
        return PathDecision(True, "path is discoverable", "discoverable")

    if intent == PathIntent.READ:
        return PathDecision(True, "explicit text reads are allowed", "explicit_read")

    if not lowered or lowered[0] != "case":
        return PathDecision(
            False,
            "writes are restricted to source and configuration files under case/",
            "write_scope",
        )
    if generated:
        return PathDecision(
            False,
            "generated verification output must be regenerated from source",
            "generated_output",
        )
    if build_output:
        return PathDecision(False, "build output is read-only", "build_output")
    if controlled:
        return PathDecision(False, "workflow control artifacts are read-only", "control_dir")
    if parts and Path(parts[-1]).name != "Makefile" and suffix not in WRITABLE_TEXT_SUFFIXES:
        return PathDecision(
            False,
            "writes require a recognized source or text configuration file",
            "non_text_write",
        )
    return PathDecision(True, "source or configuration path is writable", "source_write")


def ignored_copy_entry(name: str) -> bool:
    lowered = str(name).lower()
    return (
        lowered in SYSTEM_CACHE_DIRS
        or lowered in BUILD_OUTPUT_DIRS
        or lowered in GENERATED_DIRS
    )
