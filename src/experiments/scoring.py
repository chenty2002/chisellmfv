"""Pure reducers for paper-experiment JSONL rows.

This module intentionally does not read raw tool directories.  Paper tables
must be reducible from the canonical JSONL ledgers alone.
"""

from __future__ import annotations

from collections import Counter
import random
from statistics import mean
from typing import Any, Iterable


def status_counts(rows: Iterable[dict[str, Any]]) -> dict[str, int]:
    """Return deterministic status counts without inventing missing rows."""

    counts = Counter(str(row["status"]) for row in rows)
    return dict(sorted(counts.items()))


def reciprocal_rank(rank: int | None) -> float:
    """Return one reciprocal rank; absent roots contribute zero only in admitted rows."""

    if rank is None:
        return 0.0
    if rank < 1:
        raise ValueError("rank must be positive")
    return 1.0 / rank


SPECFLOW_RESULT_FIELDS = frozenset(
    {
        "bug_id",
        "family",
        "difficulty",
        "development_exposed",
        "method",
        "status",
        "generated_property_count",
        "compiled_on_clean",
        "compiled_on_faulty",
        "clean_proven_count",
        "clean_cex_count",
        "faulty_cex_count",
        "non_vacuous_count",
        "valid_bug_detecting_property_count",
        "property_funnel",
        "funnel_failure",
        "model_calls",
        "input_tokens",
        "output_tokens",
        "authoring_seconds",
        "formal_seconds",
        "obligation_families",
        "monitor_archetypes",
        "failure_class",
        "artifact_paths",
    }
)


def validate_specflow_result(row: dict[str, Any]) -> dict[str, Any]:
    missing = SPECFLOW_RESULT_FIELDS - set(row)
    if missing:
        raise ValueError(f"SpecFlow result is missing fields: {sorted(missing)}")
    if row["method"] not in {"s0", "s1", "s2"}:
        raise ValueError("unknown SpecFlow experiment method")
    counts = (
        "generated_property_count",
        "clean_proven_count",
        "clean_cex_count",
        "faulty_cex_count",
        "non_vacuous_count",
        "valid_bug_detecting_property_count",
        "model_calls",
        "input_tokens",
        "output_tokens",
    )
    if any(
        not isinstance(row[name], int)
        or isinstance(row[name], bool)
        or row[name] < 0
        for name in counts
    ):
        raise ValueError("SpecFlow result counts must be non-negative integers")
    if row["valid_bug_detecting_property_count"] > row["generated_property_count"]:
        raise ValueError("valid property count exceeds generated count")
    funnel = row["property_funnel"]
    if not isinstance(funnel, list):
        raise ValueError("SpecFlow property funnel must be a list")
    ids = [item.get("property_id") for item in funnel if isinstance(item, dict)]
    if len(ids) != len(funnel) or len(set(ids)) != len(ids):
        raise ValueError("SpecFlow property funnel IDs must be exact and unique")
    required_gates = {
        "property_id",
        "authoring_success",
        "executable_on_clean",
        "executable_on_faulty",
        "clean_proven",
        "clean_non_vacuous",
        "faulty_exact_cex",
        "valid_detection",
        "clean_false_alarm",
        "classification",
    }
    if any(set(item) != required_gates for item in funnel):
        raise ValueError("SpecFlow property funnel fields differ")
    if any(
        item["valid_detection"]
        != all(
            item[gate]
            for gate in (
                "authoring_success",
                "executable_on_clean",
                "executable_on_faulty",
                "clean_proven",
                "clean_non_vacuous",
                "faulty_exact_cex",
            )
        )
        for item in funnel
    ):
        raise ValueError("SpecFlow valid detection is not conjunctive")
    expected_counts = {
        "generated_property_count": len(funnel),
        "compiled_on_clean": sum(item["executable_on_clean"] for item in funnel),
        "compiled_on_faulty": sum(item["executable_on_faulty"] for item in funnel),
        "clean_proven_count": sum(item["clean_proven"] for item in funnel),
        "clean_cex_count": sum(item["clean_false_alarm"] for item in funnel),
        "faulty_cex_count": sum(item["faulty_exact_cex"] for item in funnel),
        "non_vacuous_count": sum(item["clean_non_vacuous"] for item in funnel),
        "valid_bug_detecting_property_count": sum(
            item["valid_detection"] for item in funnel
        ),
    }
    if any(row[name] != value for name, value in expected_counts.items()):
        raise ValueError("SpecFlow funnel counts do not match exact property rows")
    return row


def specflow_rates(row: dict[str, Any]) -> dict[str, float]:
    validate_specflow_result(row)
    generated = row["generated_property_count"]
    denominator = generated if generated else 1
    return {
        "valid_property_rate": row["valid_bug_detecting_property_count"] / denominator,
        "bug_detected": float(row["valid_bug_detecting_property_count"] > 0),
        "clean_false_alarm_rate": row["clean_cex_count"] / denominator,
        "executable_property_rate": (
            min(row["compiled_on_clean"], row["compiled_on_faulty"]) / denominator
        ),
        "non_vacuous_rate": row["non_vacuous_count"] / denominator,
    }


def family_method_summary(rows: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    groups: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for row in rows:
        validate_specflow_result(row)
        groups.setdefault((row["family"], row["method"]), []).append(row)
    result = []
    for (family, method), items in sorted(groups.items()):
        rates = [specflow_rates(row) for row in items]
        result.append(
            {
                "family": family,
                "difficulty": items[0]["difficulty"],
                "method": method,
                "scheduled_bugs": len(items),
                "result_rows": len(items),
                "detected_bugs": sum(rate["bug_detected"] for rate in rates),
                "bug_detection_rate": mean(rate["bug_detected"] for rate in rates),
                "valid_property_rate": _ratio(
                    sum(row["valid_bug_detecting_property_count"] for row in items),
                    sum(row["generated_property_count"] for row in items),
                ),
                "clean_false_alarm_rate": _ratio(
                    sum(row["clean_cex_count"] for row in items),
                    sum(row["generated_property_count"] for row in items),
                ),
                "model_calls": sum(row["model_calls"] for row in items),
                "output_tokens": sum(row["output_tokens"] for row in items),
                "authoring_seconds": sum(row["authoring_seconds"] for row in items),
                "formal_seconds": sum(row["formal_seconds"] for row in items),
            }
        )
    return result


def method_macro_summary(
    rows: Iterable[dict[str, Any]], *, bootstrap_samples: int = 2000
) -> list[dict[str, Any]]:
    family_rows = family_method_summary(rows)
    by_method: dict[str, list[dict[str, Any]]] = {}
    for row in family_rows:
        by_method.setdefault(row["method"], []).append(row)
    result = []
    for method, items in sorted(by_method.items()):
        values = [row["bug_detection_rate"] for row in items]
        low, high = family_bootstrap_ci(values, samples=bootstrap_samples)
        result.append(
            {
                "method": method,
                "family_count": len(items),
                "family_macro_bug_detection_rate": mean(values),
                "family_bootstrap_95ci": [low, high],
            }
        )
    return result


def paired_family_differences(rows: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    family_rows = family_method_summary(rows)
    indexed = {(row["family"], row["method"]): row for row in family_rows}
    families = sorted({row["family"] for row in family_rows})
    result = []
    for baseline, treatment in (("s0", "s1"), ("s1", "s2"), ("s0", "s2")):
        differences = [
            indexed[(family, treatment)]["bug_detection_rate"]
            - indexed[(family, baseline)]["bug_detection_rate"]
            for family in families
            if (family, baseline) in indexed and (family, treatment) in indexed
        ]
        result.append(
            {
                "comparison": f"{treatment}-{baseline}",
                "paired_family_count": len(differences),
                "mean_bug_detection_difference": mean(differences) if differences else None,
            }
        )
    return result


def template_reuse_summary(rows: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    uses: dict[tuple[str, str], set[str]] = {}
    tasks: Counter[tuple[str, str]] = Counter()
    for row in rows:
        validate_specflow_result(row)
        for family_name in row["obligation_families"]:
            uses.setdefault(("obligation", family_name), set()).add(row["family"])
            tasks[("obligation", family_name)] += 1
        for archetype in row["monitor_archetypes"]:
            uses.setdefault(("monitor", archetype), set()).add(row["family"])
            tasks[("monitor", archetype)] += 1
    return [
        {
            "kind": kind,
            "template": template,
            "family_count": len(families),
            "task_count": tasks[(kind, template)],
        }
        for (kind, template), families in sorted(uses.items())
    ]


def family_bootstrap_ci(
    values: list[float], *, samples: int = 2000, seed: int = 0
) -> tuple[float, float]:
    if not values:
        raise ValueError("bootstrap requires at least one family")
    if samples < 1:
        raise ValueError("bootstrap sample count must be positive")
    random_source = random.Random(seed)
    means = sorted(
        mean(random_source.choice(values) for _ in values) for _ in range(samples)
    )
    return means[int(0.025 * (samples - 1))], means[int(0.975 * (samples - 1))]


def _ratio(numerator: int | float, denominator: int | float) -> float:
    return float(numerator) / float(denominator) if denominator else 0.0
