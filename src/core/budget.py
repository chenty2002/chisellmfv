"""Run-scoped budgets shared by every model role."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any, Dict, Mapping, Optional


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


@dataclass(frozen=True)
class BudgetSnapshot:
    hard_token_limit: Optional[int]
    tokens_used: int
    tokens_remaining: Optional[int]
    calls: int
    stage_token_limits: Dict[str, int]
    stage_tokens_used: Dict[str, int]

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

    def check_request(
        self,
        *,
        estimated_tokens: int,
        role: Optional[str] = None,
        stage: Optional[str] = None,
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

    def record_usage(
        self,
        *,
        role: Optional[str],
        stage: Optional[str],
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
        if (
            self.hard_token_limit is not None
            and self.tokens_used > self.hard_token_limit
        ):
            raise TokenBudgetExceeded(self.hard_token_limit, self.tokens_used)
        stage_limit = self.stage_token_limits.get(stage or "")
        stage_used = self.usage_by_stage.get(stage or "", 0)
        if stage_limit is not None and stage_used > stage_limit:
            raise TokenBudgetExceeded(stage_limit, stage_used)

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
        )

    def tokens_remaining(self, stage: Optional[str] = None) -> Optional[int]:
        remaining = []
        if self.hard_token_limit is not None:
            remaining.append(max(0, self.hard_token_limit - self.tokens_used))
        stage_limit = self.stage_token_limits.get(stage or "")
        if stage_limit is not None:
            remaining.append(
                max(0, stage_limit - self.usage_by_stage.get(stage or "", 0))
            )
        return min(remaining) if remaining else None

    def reset(self) -> None:
        self.tokens_used = 0
        self.calls = 0
        self.usage_by_role.clear()
        self.usage_by_stage.clear()
