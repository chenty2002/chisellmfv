"""Run configuration for CoupledL2-backed ChiselLMFV workflows."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


VALID_VERIFY_MODES = {"small", "large"}
VALID_INPUT_MODES = {"msggen", "coupledl2asl1"}
VALID_PROPERTY_CATEGORIES = {"deadlock", "write_read", "copy_equality", "peer_l2", "custom"}


@dataclass(frozen=True)
class CoupledL2RunConfig:
    """Configuration needed before the five-stage workflow starts."""

    case_path: Path
    verify_mode: str = "small"
    input_mode: str = "msggen"
    property_category: str = "deadlock"
    run_root: Path = Path("runs")
    copy_strategy: str = "copy"

    def __post_init__(self) -> None:
        case_path = Path(self.case_path).resolve()
        run_root = Path(self.run_root).resolve()
        object.__setattr__(self, "case_path", case_path)
        object.__setattr__(self, "run_root", run_root)

        if self.verify_mode not in VALID_VERIFY_MODES:
            raise ValueError(f"verify_mode must be one of {sorted(VALID_VERIFY_MODES)}")
        if self.input_mode not in VALID_INPUT_MODES:
            raise ValueError(f"input_mode must be one of {sorted(VALID_INPUT_MODES)}")
        if self.property_category not in VALID_PROPERTY_CATEGORIES:
            raise ValueError(
                f"property_category must be one of {sorted(VALID_PROPERTY_CATEGORIES)}"
            )
        if self.copy_strategy != "copy":
            raise ValueError("only copy workspace strategy is supported in commit 1")
        if not case_path.is_dir():
            raise FileNotFoundError(case_path)

    @property
    def case_name(self) -> str:
        return self.case_path.name
