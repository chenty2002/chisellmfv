"""One optional hard token limit for a run."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any, Dict, Optional


class TokenBudgetExceeded(RuntimeError):
    def __init__(self, budget: int, used: int):
        self.budget = budget
        self.used = used
        super().__init__(f"token budget exceeded: {used} > {budget}")


@dataclass(frozen=True)
class BudgetSnapshot:
    hard_token_limit: Optional[int]
    tokens_used: int
    tokens_remaining: Optional[int]
    calls: int

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


class RunBudgetLedger:
    def __init__(self, hard_token_limit: Optional[int] = None):
        if hard_token_limit is not None and hard_token_limit <= 0:
            raise ValueError("hard_token_limit must be positive")
        self.hard_token_limit = hard_token_limit
        self.tokens_used = 0
        self.calls = 0

    def check_request(self, *, estimated_tokens: int) -> None:
        if estimated_tokens < 0:
            raise ValueError("estimated_tokens must not be negative")
        projected = self.tokens_used + estimated_tokens
        if self.hard_token_limit is not None and projected > self.hard_token_limit:
            raise TokenBudgetExceeded(self.hard_token_limit, projected)

    def record_usage(
        self,
        *,
        prompt_tokens: int = 0,
        completion_tokens: int = 0,
    ) -> None:
        total = int(prompt_tokens or 0) + int(completion_tokens or 0)
        if total < 0:
            raise ValueError("token usage must not be negative")
        self.tokens_used += total
        self.calls += 1
        if self.hard_token_limit is not None and self.tokens_used > self.hard_token_limit:
            raise TokenBudgetExceeded(self.hard_token_limit, self.tokens_used)

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
        )

    def reset(self) -> None:
        self.tokens_used = 0
        self.calls = 0
