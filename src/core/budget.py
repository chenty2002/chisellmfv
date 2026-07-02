"""Run-scoped budgets shared by every model role."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from enum import Enum
from typing import Any, Dict, Mapping, Optional, Sequence, Set


class BudgetViolation(RuntimeError):
    """A request cannot be executed without violating a hard run budget."""


class TokenBudgetExceeded(BudgetViolation):
    """A run or stage token budget cannot cover a request or provider usage."""

    def __init__(self, budget: int, used: int):
        self.budget = budget
        self.used = used
        super().__init__(
            f"Token budget exceeded: used {used} tokens, budget is {budget} tokens"
        )


class BudgetPhase(str, Enum):
    DISCOVERY = "DISCOVERY"
    EXECUTION = "EXECUTION"
    FINALIZATION = "FINALIZATION"


@dataclass(frozen=True)
class StageBudgetSnapshot:
    stage: str
    phase: BudgetPhase
    tool_call_limit: int
    tool_calls_used: int
    tool_calls_remaining: int
    non_completion_calls_remaining: int
    completion_reserved: int
    model_turn_limit: int
    model_turns_used: int
    model_turns_remaining: int
    completion_attempted: bool
    completion_accepted: bool
    completion_required: bool
    forced_finalization: bool
    token_finalization: bool
    required_next_action: str

    def to_dict(self) -> Dict[str, Any]:
        payload = asdict(self)
        payload["phase"] = self.phase.value
        return payload


class StageBudget:
    """Hard per-stage tool and model-turn budget with completion reserve."""

    def __init__(
        self,
        *,
        stage: str,
        tool_call_limit: int,
        discovery_limit: int,
        finalization_reserve: int,
        model_turn_limit: int,
    ):
        if tool_call_limit <= 0:
            raise ValueError("tool_call_limit must be positive")
        if discovery_limit < 0 or discovery_limit >= tool_call_limit:
            raise ValueError("discovery_limit must be below tool_call_limit")
        if finalization_reserve <= 0 or finalization_reserve > tool_call_limit:
            raise ValueError("finalization_reserve must be within the tool budget")
        if model_turn_limit <= 0:
            raise ValueError("model_turn_limit must be positive")
        self.stage = stage
        self.tool_call_limit = tool_call_limit
        self.discovery_limit = discovery_limit
        self.finalization_reserve = finalization_reserve
        self.model_turn_limit = model_turn_limit
        self.tool_calls_used = 0
        self.model_turns_used = 0
        self.completion_attempted = False
        self.completion_accepted = False
        self.completion_required = False
        self.forced_finalization = False
        self.token_finalization = False

    @property
    def tool_calls_remaining(self) -> int:
        return self.tool_call_limit - self.tool_calls_used

    @property
    def model_turns_remaining(self) -> int:
        return self.model_turn_limit - self.model_turns_used

    @property
    def phase(self) -> BudgetPhase:
        return self._phase_at(self.tool_calls_used)

    def _phase_at(self, tool_calls_used: int) -> BudgetPhase:
        remaining = self.tool_call_limit - tool_calls_used
        if (
            self.forced_finalization
            or self.token_finalization
            or remaining <= self.finalization_reserve
        ):
            return BudgetPhase.FINALIZATION
        if tool_calls_used >= self.discovery_limit:
            return BudgetPhase.EXECUTION
        return BudgetPhase.DISCOVERY

    def _tool_allowed_at(self, tool_name: str, tool_calls_used: int) -> bool:
        remaining = self.tool_call_limit - tool_calls_used
        if remaining == 1:
            return tool_name == "complete_stage"
        phase = self._phase_at(tool_calls_used)
        if phase is BudgetPhase.DISCOVERY:
            return True
        if self.stage == "waveform_explanation":
            execution_tools = {
                "read_files",
                "write_report",
                "complete_stage",
            }
            execution_allowed = (
                tool_name in execution_tools
                or tool_name.startswith("waveform_")
                or tool_name.startswith("causal_")
            )
            if phase is BudgetPhase.EXECUTION:
                return execution_allowed
            return tool_name in {"write_report", "complete_stage"}
        if phase is BudgetPhase.EXECUTION:
            return tool_name in {"read_files", "edit_file", "complete_stage"}
        return tool_name in {"edit_file", "complete_stage"}

    def begin_model_turn(self) -> None:
        if self.model_turns_remaining <= 0:
            raise BudgetViolation("model turn budget is exhausted")
        self.model_turns_used += 1

    def force_finalization(self) -> None:
        self.forced_finalization = True

    def enter_token_finalization(self) -> None:
        self.token_finalization = True

    def attempt_completion(self) -> None:
        """Record a workflow-owned completion attempt without spending a tool slot."""
        self.completion_attempted = True
        self.completion_required = False

    def consume_batch(
        self,
        tool_names: Sequence[str],
        *,
        available_tools: Optional[Set[str]] = None,
    ) -> None:
        """Validate the whole batch before consuming any tool-call slots."""
        names = list(tool_names)
        if not names:
            raise BudgetViolation("tool call batch must not be empty")
        if len(names) > self.tool_calls_remaining:
            raise BudgetViolation(
                f"tool call batch exceeds remaining budget: "
                f"{len(names)} > {self.tool_calls_remaining}"
            )
        if names.count("complete_stage") > 1:
            raise BudgetViolation("complete_stage may appear at most once per batch")
        if "complete_stage" in names and names[-1] != "complete_stage":
            raise BudgetViolation("complete_stage must be the last call in a batch")
        if available_tools is not None:
            unavailable = [name for name in names if name not in available_tools]
            if unavailable:
                raise BudgetViolation(
                    f"tools not available in {self.phase.value}: {unavailable}"
                )
        if self.completion_required and names[0] != "complete_stage":
            raise BudgetViolation(
                "complete_stage is required on the model turn after edit_file"
            )
        discovery_calls_remaining = max(0, self.discovery_limit - self.tool_calls_used)
        if (
            self.phase is BudgetPhase.DISCOVERY
            and len(names) > discovery_calls_remaining
        ):
            raise BudgetViolation(
                "tools not available: batch crosses the DISCOVERY boundary"
            )
        for offset, name in enumerate(names):
            projected_used = self.tool_calls_used + offset
            projected_remaining = self.tool_call_limit - projected_used
            if projected_remaining == 1 and name != "complete_stage":
                raise BudgetViolation(
                    "the final tool slot is reserved for complete_stage"
                )
            if not self._tool_allowed_at(name, projected_used):
                phase = self._phase_at(projected_used)
                raise BudgetViolation(
                    f"tool {name!r} is not available in {phase.value}"
                )

        self.tool_calls_used += len(names)
        if "complete_stage" in names:
            self.completion_attempted = True
            self.completion_required = False
        elif "edit_file" in names:
            self.completion_required = True

    def mark_completion(self, accepted: bool) -> None:
        if not self.completion_attempted:
            raise BudgetViolation("completion result recorded before complete_stage")
        self.completion_accepted = bool(accepted)

    def snapshot(self) -> StageBudgetSnapshot:
        remaining = self.tool_calls_remaining
        phase = self.phase
        if remaining == 1 or self.forced_finalization or self.completion_required:
            required = "complete"
        elif phase is BudgetPhase.DISCOVERY:
            required = "discover"
        elif phase is BudgetPhase.EXECUTION:
            required = "edit_or_complete"
        else:
            required = "finalize"
        return StageBudgetSnapshot(
            stage=self.stage,
            phase=phase,
            tool_call_limit=self.tool_call_limit,
            tool_calls_used=self.tool_calls_used,
            tool_calls_remaining=remaining,
            non_completion_calls_remaining=max(0, remaining - 1),
            completion_reserved=min(self.finalization_reserve, remaining),
            model_turn_limit=self.model_turn_limit,
            model_turns_used=self.model_turns_used,
            model_turns_remaining=self.model_turns_remaining,
            completion_attempted=self.completion_attempted,
            completion_accepted=self.completion_accepted,
            completion_required=self.completion_required,
            forced_finalization=self.forced_finalization,
            token_finalization=self.token_finalization,
            required_next_action=required,
        )


@dataclass(frozen=True)
class BudgetSnapshot:
    hard_token_limit: Optional[int]
    tokens_used: int
    tokens_remaining: Optional[int]
    calls: int
    stage_token_limits: Dict[str, int]
    stage_tokens_used: Dict[str, int]
    scope_token_limits: Dict[str, int]
    scope_tokens_used: Dict[str, int]

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


class RunBudgetLedger:
    """Authoritative token ledger for PRO, FLASH, and internal model calls."""

    def __init__(
        self,
        hard_token_limit: Optional[int] = None,
        stage_token_limits: Optional[Mapping[str, int]] = None,
    ):
        if hard_token_limit is not None and hard_token_limit <= 0:
            raise ValueError("hard_token_limit must be positive")
        self.hard_token_limit = hard_token_limit
        self.stage_token_limits = {
            str(stage): int(limit)
            for stage, limit in (stage_token_limits or {}).items()
        }
        if any(limit <= 0 for limit in self.stage_token_limits.values()):
            raise ValueError("stage token limits must be positive")
        self.tokens_used = 0
        self.calls = 0
        self.usage_by_role: Dict[str, int] = {}
        self.usage_by_stage: Dict[str, int] = {}
        self.scope_token_limits: Dict[str, int] = {}
        self.usage_by_scope: Dict[str, int] = {}

    def _register_scope_limit(
        self,
        budget_scope: Optional[str],
        budget_scope_limit: Optional[int],
    ) -> Optional[int]:
        if not budget_scope:
            return None
        if budget_scope_limit is not None:
            limit = int(budget_scope_limit)
            if limit <= 0:
                raise ValueError("budget_scope_limit must be positive")
            existing = self.scope_token_limits.get(budget_scope)
            if existing is not None and existing != limit:
                raise ValueError("budget scope limit changed during a run")
            self.scope_token_limits[budget_scope] = limit
        return self.scope_token_limits.get(budget_scope)

    def check_request(
        self,
        *,
        estimated_tokens: int,
        role: Optional[str] = None,
        stage: Optional[str] = None,
        budget_scope: Optional[str] = None,
        budget_scope_limit: Optional[int] = None,
    ) -> None:
        """Reject before HTTP when the estimated request cannot fit."""
        if estimated_tokens < 0:
            raise ValueError("estimated_tokens must not be negative")
        if (
            self.hard_token_limit is not None
            and self.tokens_used + estimated_tokens > self.hard_token_limit
        ):
            raise TokenBudgetExceeded(
                self.hard_token_limit,
                self.tokens_used + estimated_tokens,
            )
        stage_limit = self.stage_token_limits.get(stage or "")
        stage_used = self.usage_by_stage.get(stage or "", 0)
        if stage_limit is not None and stage_used + estimated_tokens > stage_limit:
            raise TokenBudgetExceeded(stage_limit, stage_used + estimated_tokens)
        scope_limit = self._register_scope_limit(
            budget_scope,
            budget_scope_limit,
        )
        scope_used = self.usage_by_scope.get(budget_scope or "", 0)
        if scope_limit is not None and scope_used + estimated_tokens > scope_limit:
            raise TokenBudgetExceeded(scope_limit, scope_used + estimated_tokens)

    def record_usage(
        self,
        *,
        role: Optional[str],
        stage: Optional[str],
        budget_scope: Optional[str] = None,
        budget_scope_limit: Optional[int] = None,
        prompt_tokens: int = 0,
        completion_tokens: int = 0,
        other_tokens: int = 0,
    ) -> None:
        total = int(prompt_tokens or 0) + int(completion_tokens or 0) + int(
            other_tokens or 0
        )
        if total < 0:
            raise ValueError("token usage must not be negative")
        self.tokens_used += total
        self.calls += 1
        if role:
            self.usage_by_role[role] = self.usage_by_role.get(role, 0) + total
        if stage:
            self.usage_by_stage[stage] = self.usage_by_stage.get(stage, 0) + total
        scope_limit = self._register_scope_limit(
            budget_scope,
            budget_scope_limit,
        )
        if budget_scope:
            self.usage_by_scope[budget_scope] = (
                self.usage_by_scope.get(budget_scope, 0) + total
            )
        if (
            self.hard_token_limit is not None
            and self.tokens_used > self.hard_token_limit
        ):
            raise TokenBudgetExceeded(self.hard_token_limit, self.tokens_used)
        stage_limit = self.stage_token_limits.get(stage or "")
        stage_used = self.usage_by_stage.get(stage or "", 0)
        if stage_limit is not None and stage_used > stage_limit:
            raise TokenBudgetExceeded(stage_limit, stage_used)
        scope_used = self.usage_by_scope.get(budget_scope or "", 0)
        if scope_limit is not None and scope_used > scope_limit:
            raise TokenBudgetExceeded(scope_limit, scope_used)

    def snapshot(self) -> BudgetSnapshot:
        remaining = (
            None
            if self.hard_token_limit is None
            else max(0, self.hard_token_limit - self.tokens_used)
        )
        return BudgetSnapshot(
            hard_token_limit=self.hard_token_limit,
            tokens_used=self.tokens_used,
            tokens_remaining=remaining,
            calls=self.calls,
            stage_token_limits=dict(self.stage_token_limits),
            stage_tokens_used=dict(self.usage_by_stage),
            scope_token_limits=dict(self.scope_token_limits),
            scope_tokens_used=dict(self.usage_by_scope),
        )

    def tokens_remaining(
        self,
        stage: Optional[str] = None,
        *,
        budget_scope: Optional[str] = None,
        budget_scope_limit: Optional[int] = None,
    ) -> Optional[int]:
        remaining = []
        if self.hard_token_limit is not None:
            remaining.append(max(0, self.hard_token_limit - self.tokens_used))
        stage_limit = self.stage_token_limits.get(stage or "")
        if stage_limit is not None:
            remaining.append(
                max(0, stage_limit - self.usage_by_stage.get(stage or "", 0))
            )
        scope_limit = self._register_scope_limit(
            budget_scope,
            budget_scope_limit,
        )
        if scope_limit is not None:
            remaining.append(
                max(0, scope_limit - self.usage_by_scope.get(budget_scope or "", 0))
            )
        return min(remaining) if remaining else None

    def spendable_tokens(
        self,
        stage: str,
        *,
        reserved_tokens: int,
        budget_scope: Optional[str] = None,
        budget_scope_limit: Optional[int] = None,
    ) -> Optional[int]:
        """Return tokens available after preserving a caller-owned reserve."""
        remaining = self.tokens_remaining(
            stage,
            budget_scope=budget_scope,
            budget_scope_limit=budget_scope_limit,
        )
        if remaining is None:
            return None
        return max(0, remaining - max(0, int(reserved_tokens)))

    def restore(self, snapshot: Mapping[str, Any]) -> None:
        """Restore persisted counters before continuing the same run."""
        tokens_used = int(snapshot.get("tokens_used", 0) or 0)
        calls = int(snapshot.get("calls", 0) or 0)
        if tokens_used < 0 or calls < 0:
            raise ValueError("persisted budget counters must not be negative")
        persisted_limit = snapshot.get("hard_token_limit")
        if self.hard_token_limit is None and persisted_limit is not None:
            self.hard_token_limit = int(persisted_limit)
        if self.hard_token_limit is not None and tokens_used > self.hard_token_limit:
            raise TokenBudgetExceeded(self.hard_token_limit, tokens_used)
        stage_usage = {
            str(stage): int(value or 0)
            for stage, value in (snapshot.get("stage_tokens_used") or {}).items()
        }
        if any(value < 0 for value in stage_usage.values()):
            raise ValueError("persisted stage token counters must not be negative")
        for stage, used in stage_usage.items():
            limit = self.stage_token_limits.get(stage)
            if limit is not None and used > limit:
                raise TokenBudgetExceeded(limit, used)
        scope_limits = {
            str(scope): int(value)
            for scope, value in (snapshot.get("scope_token_limits") or {}).items()
        }
        scope_usage = {
            str(scope): int(value or 0)
            for scope, value in (snapshot.get("scope_tokens_used") or {}).items()
        }
        if any(limit <= 0 for limit in scope_limits.values()):
            raise ValueError("persisted scope token limits must be positive")
        if any(value < 0 for value in scope_usage.values()):
            raise ValueError("persisted scope token counters must not be negative")
        for scope, used in scope_usage.items():
            limit = scope_limits.get(scope)
            if limit is not None and used > limit:
                raise TokenBudgetExceeded(limit, used)
        self.tokens_used = tokens_used
        self.calls = calls
        self.usage_by_stage = stage_usage
        self.scope_token_limits = scope_limits
        self.usage_by_scope = scope_usage

    def reset(self) -> None:
        self.tokens_used = 0
        self.calls = 0
        self.usage_by_role.clear()
        self.usage_by_stage.clear()
        self.scope_token_limits.clear()
        self.usage_by_scope.clear()
