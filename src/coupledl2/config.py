"""Run configuration for CoupledL2-backed ChiselLMFV workflows."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from .property_catalog import list_property_profiles, load_property_profile

VALID_VERIFY_MODES = {"small"}
VALID_INPUT_MODES = {"coupledl2asl1"}
VALID_PROPERTY_PROFILES = set(list_property_profiles())


@dataclass(frozen=True)
class CoupledL2RunConfig:
    """Configuration needed before preflight and the two active stages."""

    case_path: Path
    property_profile: str
    verify_mode: str = "small"
    input_mode: str = "coupledl2asl1"
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
        valid_property_profiles = set(list_property_profiles())
        if self.property_profile not in valid_property_profiles:
            raise ValueError(
                f"property_profile must be one of {sorted(valid_property_profiles)}"
            )
        profile = load_property_profile(self.property_profile).profile
        if profile["case_name"] != case_path.name:
            raise ValueError(
                f"profile {self.property_profile} requires case {profile['case_name']}"
            )
        if self.copy_strategy != "copy":
            raise ValueError("only copy workspace strategy is supported in commit 1")
        if not case_path.is_dir():
            raise FileNotFoundError(case_path)

    @property
    def case_name(self) -> str:
        return self.case_path.name
