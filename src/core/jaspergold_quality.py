"""JasperGold-backed quality evaluation for ChiselLMFV outputs.

The runner in this module is intentionally non-LLM: it renders deterministic
Tcl stages, executes JasperGold, parses ``CHISELLMFV_*`` machine-readable
lines, and writes per-stage artifacts plus a sample-level JSON record.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence


ERROR_RE = re.compile(
    r"(?im)(^|\n)\s*(ERROR|FATAL)\s*\(|"
    r"(^|\n)\s*ERROR:\s+|"
    r"No such switch|Invalid command formation|Invalid argument|"
    r"wrong # args|unknown command|License call failed|cannot run without"
)


@dataclass
class ParsedJGOutput:
    kv: Dict[str, str] = field(default_factory=dict)
    lists: Dict[str, List[str]] = field(default_factory=dict)
    properties: List[Dict[str, Any]] = field(default_factory=list)
    assumptions: List[Dict[str, Any]] = field(default_factory=list)
    design_typed: Dict[str, List[str]] = field(default_factory=dict)
    coi: Dict[str, List[str]] = field(default_factory=dict)
    signals: Dict[str, Dict[str, str]] = field(default_factory=dict)
    trace_info: Dict[str, List[str]] = field(default_factory=dict)
    raw_machine_lines: List[str] = field(default_factory=list)


@dataclass
class JGRunResult:
    stage: str
    command: List[str]
    returncode: int
    stdout: str
    stderr: str
    tcl_path: Path
    log_path: Path
    parsed: ParsedJGOutput

    @property
    def raw_output(self) -> str:
        return self.stdout + "\n" + self.stderr

    @property
    def ok(self) -> bool:
        return self.returncode == 0 and not ERROR_RE.search(self.raw_output)


@dataclass
class QualityConfig:
    case_id: str
    workdir: Path | str
    dut_sv: List[str]
    extra_sv: List[str]
    top: str
    clock: str
    reset: str
    report_root: Path | str = Path("reports/jg")
    candidate_id: str = "run_001"
    expected_inputs: List[str] = field(default_factory=list)
    expected_outputs: List[str] = field(default_factory=list)
    prove_time_limit: str = "5s"
    assume_time_limit: str = "5s"
    nv_time_limit: str = "5s"
    mutation_time_limit: str = "5s"
    repair_regression_time_limit: str = "5s"
    sec_time_limit: str = "5s"
    xprop_time_limit: str = "5s"
    jg_timeout: int = 900
    trace_signals: List[str] = field(default_factory=list)

    def __post_init__(self) -> None:
        self.workdir = Path(self.workdir).resolve()
        self.report_root = Path(self.report_root).resolve()


def parse_tcl_list(text: str) -> List[str]:
    """Parse the subset of Tcl list syntax used by JasperGold output."""
    items: List[str] = []
    current: List[str] = []
    brace_depth = 0
    in_quote = False
    escape = False
    token_started = False

    for ch in text.strip():
        if escape:
            current.append(ch)
            token_started = True
            escape = False
            continue
        if ch == "\\":
            escape = True
            token_started = True
            continue
        if brace_depth:
            if ch == "{":
                brace_depth += 1
                current.append(ch)
            elif ch == "}":
                brace_depth -= 1
                if brace_depth:
                    current.append(ch)
                token_started = True
            else:
                current.append(ch)
            continue
        if in_quote:
            if ch == '"':
                in_quote = False
                token_started = True
            else:
                current.append(ch)
            continue
        if ch.isspace():
            if token_started or current:
                items.append("".join(current))
                current = []
                token_started = False
            continue
        if ch == "{":
            brace_depth = 1
            token_started = True
            continue
        if ch == '"':
            in_quote = True
            token_started = True
            continue
        current.append(ch)
        token_started = True

    if token_started or current:
        items.append("".join(current))
    return items


def parse_design_typed(text: str) -> Dict[str, List[str]]:
    tokens = parse_tcl_list(text)
    parsed: Dict[str, List[str]] = {}
    for idx in range(0, len(tokens) - 1, 2):
        parsed[tokens[idx]] = parse_tcl_list(tokens[idx + 1])
    return parsed


def parse_chisellmfv_output(output: str) -> ParsedJGOutput:
    parsed = ParsedJGOutput()
    for raw_line in output.splitlines():
        line = raw_line.strip()
        if not line.startswith("CHISELLMFV_"):
            continue
        parsed.raw_machine_lines.append(line)

        if line.startswith("CHISELLMFV_KV "):
            payload = line[len("CHISELLMFV_KV ") :]
            key, _, value = payload.partition("=")
            parsed.kv[key] = value
        elif line.startswith("CHISELLMFV_LIST "):
            payload = line[len("CHISELLMFV_LIST ") :]
            key, _, values = payload.partition(" ")
            parsed.lists[key] = parse_tcl_list(values)
        elif line.startswith("CHISELLMFV_DESIGN_TYPED "):
            payload = line[len("CHISELLMFV_DESIGN_TYPED ") :]
            parsed.design_typed = parse_design_typed(payload)
        elif line.startswith("CHISELLMFV_PROP "):
            payload = line[len("CHISELLMFV_PROP ") :]
            tokens = parse_tcl_list(payload)
            if len(tokens) >= 4:
                parsed.properties.append(
                    {
                        "tag": tokens[0],
                        "property": tokens[1],
                        "fields": parse_tcl_list(tokens[2]),
                        "values": parse_tcl_list(tokens[3]),
                    }
                )
        elif line.startswith("CHISELLMFV_ASSUMPTION "):
            payload = line[len("CHISELLMFV_ASSUMPTION ") :]
            tokens = parse_tcl_list(payload)
            if len(tokens) >= 3:
                parsed.assumptions.append(
                    {
                        "property": tokens[0],
                        "fields": parse_tcl_list(tokens[1]),
                        "values": parse_tcl_list(tokens[2]),
                    }
                )
        elif line.startswith("CHISELLMFV_COI "):
            payload = line[len("CHISELLMFV_COI ") :]
            prop, _, values = payload.partition(" ")
            parsed.coi[prop] = _parse_flat_tcl_values(values)
        elif line.startswith("CHISELLMFV_SIGNAL "):
            payload = line[len("CHISELLMFV_SIGNAL ") :]
            signal, _, rest = payload.partition(" ")
            parsed.signals.setdefault(signal, {}).update(_parse_key_values(rest))
        elif line.startswith("CHISELLMFV_TRACE_INFO "):
            payload = line[len("CHISELLMFV_TRACE_INFO ") :]
            prop, _, values = payload.partition(" ")
            parsed.trace_info[prop] = parse_tcl_list(values)
    return parsed


def _parse_key_values(text: str) -> Dict[str, str]:
    result: Dict[str, str] = {}
    for token in parse_tcl_list(text):
        key, sep, value = token.partition("=")
        if sep:
            result[key] = value
    return result


def _parse_flat_tcl_values(text: str) -> List[str]:
    values = parse_tcl_list(text)
    if len(values) == 1 and " " in values[0]:
        return parse_tcl_list(values[0])
    return values


def compute_jaccard_score(actual: Sequence[str], expected: Sequence[str]) -> float:
    actual_set = set(actual)
    expected_set = set(expected)
    if not actual_set and not expected_set:
        return 1.0
    union = actual_set | expected_set
    if not union:
        return 0.0
    return len(actual_set & expected_set) / len(union)


def _tcl_quote(value: str | Path) -> str:
    raw = str(value)
    return "{" + raw.replace("\\", "\\\\").replace("}", "\\}") + "}"


def _tcl_list(values: Iterable[str | Path]) -> str:
    return " ".join(_tcl_quote(value) for value in values)


def _bool_from_result(ok: bool) -> float:
    return 1.0 if ok else 0.0


class JasperGoldQualityRunner:
    """Run quality evaluation stages described in ``chisellmfv_guide.md``."""

    def __init__(self, config: QualityConfig):
        self.config = config
        self.case_dir = Path(config.report_root) / config.case_id
        self.record: Dict[str, Any] = {
            "case_id": config.case_id,
            "candidate_id": config.candidate_id,
            "artifacts_dir": str(self.case_dir),
        }

    def run(
        self,
        stages: Sequence[str],
        non_vacuity_sidecars: Optional[Sequence[Dict[str, Any]]] = None,
        max_mutants: int = 5,
        repair_target_properties: Optional[Sequence[str]] = None,
        sec_spec_sv: Optional[Sequence[str]] = None,
        sec_imp_sv: Optional[Sequence[str]] = None,
    ) -> Dict[str, Any]:
        for stage in stages:
            if stage == "build":
                self.record["build"] = self.run_build()
            elif stage == "assertions":
                self.record["assertions"] = self.run_assertions()
            elif stage == "assumptions":
                self.record["assumptions"] = self.run_assumption_hygiene()
            elif stage == "non_vacuity":
                self.record["non_vacuity"] = self.run_non_vacuity(non_vacuity_sidecars or [])
            elif stage == "mutation":
                self.record["mutation"] = self.run_mutation(max_mutants=max_mutants)
            elif stage == "repair_regression":
                self.record["repair_regression"] = self.run_repair_regression(
                    repair_target_properties or []
                )
            elif stage == "sec":
                self.record["sec"] = self.run_sec(spec_sv=sec_spec_sv, imp_sv=sec_imp_sv)
            elif stage == "xprop":
                self.record["xprop"] = self.run_xprop()
            else:
                raise ValueError(f"Unknown quality stage: {stage}")
        self.record["scores"] = self.compute_scores(self.record)
        self._write_json(self.case_dir / "quality_record.json", self.record)
        return self.record

    def run_build(self) -> Dict[str, Any]:
        result = self._run_stage("build", self._build_tcl(), subdir="build")
        metrics = self._build_metrics(result.parsed, result.returncode, result.raw_output)
        metrics.update(self._stage_artifacts(result))
        self._write_json(self.case_dir / "build" / "build_metrics.json", metrics)
        return metrics

    def run_assertions(self) -> Dict[str, Any]:
        result = self._run_stage(
            "assertions",
            self._assertions_tcl(),
            subdir="assertions",
            env={"PROVE_TIME_LIMIT": self.config.prove_time_limit},
        )
        metrics = self._assertion_metrics(result.parsed, result.ok)
        metrics.update(self._stage_artifacts(result))
        self._write_json(self.case_dir / "assertions" / "assertion_metrics.json", metrics)
        return metrics

    def run_assumption_hygiene(self) -> Dict[str, Any]:
        result = self._run_stage(
            "assumptions",
            self._assumption_tcl(),
            subdir="assumptions",
            env={"ASSUME_TIME_LIMIT": self.config.assume_time_limit},
        )
        metrics = {
            "success": result.ok,
            "assumptions": result.parsed.assumptions,
            "no_conflict_status": self._assumption_status(result.parsed, ":noConflict"),
            "no_dead_end_status": self._assumption_status(result.parsed, ":noDeadEnd"),
            "live_status": self._assumption_status(result.parsed, ":live"),
        }
        metrics.update(self._stage_artifacts(result))
        self._write_json(self.case_dir / "assumptions" / "assumption_metrics.json", metrics)
        return metrics

    def run_non_vacuity(self, sidecars: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
        if not sidecars:
            metrics = {
                "success": True,
                "skipped": True,
                "reason": "no assertion sidecar metadata was provided",
                "covered": 0,
                "total": 0,
                "non_vacuity_rate": 0.0,
            }
            stage_dir = self.case_dir / "non_vacuity"
            stage_dir.mkdir(parents=True, exist_ok=True)
            self._write_json(stage_dir / "non_vacuity_metrics.json", metrics)
            return metrics
        result = self._run_stage(
            "non_vacuity",
            self._non_vacuity_tcl(sidecars),
            subdir="non_vacuity",
            env={"NV_TIME_LIMIT": self.config.nv_time_limit},
        )
        covered = self._count_properties(result.parsed, "non_vacuity", {"covered"})
        total = len([p for p in result.parsed.properties if p.get("tag") == "non_vacuity"])
        metrics = {
            "success": result.ok,
            "covered": covered,
            "total": total,
            "non_vacuity_rate": covered / total if total else 0.0,
            "properties": result.parsed.properties,
        }
        metrics.update(self._stage_artifacts(result))
        self._write_json(self.case_dir / "non_vacuity" / "non_vacuity_metrics.json", metrics)
        return metrics

    def run_mutation(self, max_mutants: int = 5) -> Dict[str, Any]:
        mutation_root = self.case_dir / "mutation"
        source = self._resolve_workdir_path(self.config.dut_sv[0])
        mutants = generate_text_mutants(source, mutation_root, max_mutants=max_mutants)
        killed: List[str] = []
        valid = 0
        results: List[Dict[str, Any]] = []

        for mutant in mutants:
            mutant_dir = mutation_root / mutant["mutant_id"]
            result = self._run_stage(
                "mutation",
                self._mutation_tcl(mutant_dir / "dut_mutated.sv", mutant["mutant_id"]),
                subdir=f"mutation/{mutant['mutant_id']}",
                env={"MUTATION_TIME_LIMIT": self.config.mutation_time_limit},
            )
            mutant_result = {
                **mutant,
                "valid": result.ok or "killed" in result.parsed.kv,
                "killed": result.parsed.kv.get("killed") in {"1", "true", "True"},
                "killed_by": result.parsed.lists.get("killed_by", []),
                **self._stage_artifacts(result),
            }
            if mutant_result["valid"]:
                valid += 1
            if mutant_result["killed"]:
                killed.append(mutant["mutant_id"])
            results.append(mutant_result)

        metrics = {
            "total_mutants": len(mutants),
            "valid_mutants": valid,
            "killed_mutants": len(killed),
            "survivors": [
                item["mutant_id"]
                for item in results
                if item["valid"] and not item["killed"]
            ],
            "mutation_score": len(killed) / valid if valid else 0.0,
            "results": results,
        }
        self._write_json(mutation_root / "mutation_metrics.json", metrics)
        return metrics

    def run_repair_regression(self, target_properties: Sequence[str]) -> Dict[str, Any]:
        targets = [target for target in target_properties if target]
        stage_dir = self.case_dir / "repair_regression"
        if not targets:
            metrics = {
                "success": True,
                "skipped": True,
                "reason": "no repair target properties were provided",
                "targets": [],
                "proven": 0,
                "cex": 0,
                "missing": 0,
                "repair_target_proven_rate": 0.0,
                "repair_target_present": 1.0,
                "repair_target_cex_count": 0,
                "repair_cex_persisted": False,
            }
            stage_dir.mkdir(parents=True, exist_ok=True)
            self._write_json(stage_dir / "repair_regression_metrics.json", metrics)
            return metrics

        result = self._run_stage(
            "repair_regression",
            self._repair_regression_tcl(targets),
            subdir="repair_regression",
            env={"REPAIR_REGRESSION_TIME_LIMIT": self.config.repair_regression_time_limit},
        )
        metrics = self._repair_regression_metrics(result.parsed, result.ok, targets)
        metrics.update(self._stage_artifacts(result))
        self._write_json(stage_dir / "repair_regression_metrics.json", metrics)
        return metrics

    def run_sec(
        self,
        spec_sv: Optional[Sequence[str]] = None,
        imp_sv: Optional[Sequence[str]] = None,
    ) -> Dict[str, Any]:
        spec_files = list(spec_sv or self.config.dut_sv)
        imp_files = list(imp_sv or self.config.dut_sv)
        result = self._run_stage(
            "sec",
            self._sec_tcl(spec_files, imp_files),
            subdir="sec",
            mode="-sec",
            env={"SEC_TIME_LIMIT": self.config.sec_time_limit},
        )
        total = len([p for p in result.parsed.properties if p.get("tag") == "sec"])
        proven = self._count_properties(result.parsed, "sec", {"proven"})
        metrics = {
            "success": result.ok,
            "targets": total,
            "proven": proven,
            "sec_proven_rate": proven / total if total else 0.0,
            "properties": result.parsed.properties,
        }
        metrics.update(self._stage_artifacts(result))
        self._write_json(self.case_dir / "sec" / "sec_metrics.json", metrics)
        return metrics

    def run_xprop(self) -> Dict[str, Any]:
        result = self._run_stage(
            "xprop",
            self._xprop_tcl(),
            subdir="xprop",
            mode="-xprop",
            env={"XPROP_TIME_LIMIT": self.config.xprop_time_limit},
        )
        props = [p for p in result.parsed.properties if p.get("tag") == "xprop"]
        if not props:
            props = parse_xprop_summary(result.raw_output)
        non_xprop = sum(
            1
            for prop in props
            if any("NON-X" in value.upper() or value.lower() == "proven" for value in prop["values"])
        )
        metrics = {
            "success": result.ok,
            "targets": len(props),
            "non_xprop": non_xprop,
            "non_xprop_rate": non_xprop / len(props) if props else 0.0,
            "properties": props,
        }
        metrics.update(self._stage_artifacts(result))
        self._write_json(self.case_dir / "xprop" / "xprop_metrics.json", metrics)
        return metrics

    def _run_stage(
        self,
        stage: str,
        tcl: str,
        subdir: str,
        mode: Optional[str] = None,
        env: Optional[Dict[str, str]] = None,
    ) -> JGRunResult:
        stage_dir = self.case_dir / subdir
        stage_dir.mkdir(parents=True, exist_ok=True)
        tcl_path = stage_dir / f"{stage}.tcl"
        log_path = stage_dir / f"{stage}.log"
        tcl_path.write_text(tcl, encoding="utf-8")

        command = ["jg", "-allow_unsupported_OS"]
        if mode:
            command.append(mode)
        command.extend(["-no_gui", "-tcl", str(tcl_path.resolve())])

        run_env = os.environ.copy()
        run_env.update(env or {})
        try:
            completed = subprocess.run(
                command,
                cwd=self.config.workdir,
                env=run_env,
                capture_output=True,
                text=True,
                timeout=self.config.jg_timeout,
            )
            stdout = completed.stdout
            stderr = completed.stderr
            returncode = completed.returncode
        except FileNotFoundError as exc:
            stdout = ""
            stderr = str(exc)
            returncode = 127
        except subprocess.TimeoutExpired as exc:
            stdout = exc.stdout or ""
            stderr = (exc.stderr or "") + f"\nTimed out after {self.config.jg_timeout}s"
            returncode = 124

        log_path.write_text(stdout + "\n" + stderr, encoding="utf-8", errors="replace")
        parsed = parse_chisellmfv_output(stdout + "\n" + stderr)
        return JGRunResult(stage, command, returncode, stdout, stderr, tcl_path, log_path, parsed)

    def _common_tcl(self) -> str:
        dut_files = [self._resolve_workdir_path(path) for path in self.config.dut_sv]
        extra_files = [self._resolve_workdir_path(path) for path in self.config.extra_sv]
        return f"""
proc emit_kv {{key value}} {{
    puts "CHISELLMFV_KV $key=$value"
}}
proc emit_list {{key values}} {{
    puts "CHISELLMFV_LIST $key [join $values {{ }}]"
}}
proc emit_prop {{tag prop fields}} {{
    if {{![catch {{get_property_info -list $fields $prop}} values]}} {{
        puts "CHISELLMFV_PROP $tag $prop [list $fields] [list $values]"
    }}
}}
clear -all
set dut_files [list {_tcl_list(dut_files)}]
set extra_files [list {_tcl_list(extra_files)}]
analyze -sv12 {{*}}$dut_files {{*}}$extra_files
elaborate -top {_tcl_quote(self.config.top)} -bbox_a 65536
clock {_tcl_quote(self.config.clock)}
reset {_tcl_quote(self.config.reset)}
"""

    def _build_tcl(self) -> str:
        return self._common_tcl() + """
set clocks  [clock -list signal -silent]
set resets  [reset -list]
set typed   [get_design_info -list input output register bbox_inst -typed_list -silent]
set modules [get_design_info -list module -silent]
set signals [get_design_info -list signal -silent]

emit_list clocks $clocks
emit_list resets $resets
emit_list modules $modules
emit_kv signal_count [llength $signals]
puts "CHISELLMFV_DESIGN_TYPED $typed"

report -summary -file build_summary.txt -force
save -script build_replay.tcl -force
exit
"""

    def _assertions_tcl(self) -> str:
        trace_loop = self._trace_signal_loop()
        return self._common_tcl() + f"""
set asserts [get_property_list -include {{type assert}} -no_task_prefix]
emit_kv assertion_count [llength $asserts]

foreach p $asserts {{
    emit_prop assertion_static $p {{name type file expression disabled store_trace trace_extension}}
    if {{![catch {{assert -show $p}} shown]}} {{
        puts "CHISELLMFV_ASSERT_SHOW $p [list $shown]"
    }}
}}

set_trace_optimization standard
set_trace_optimization -irrelevant_value_computation true
prove -all -time_limit $env(PROVE_TIME_LIMIT)

set proven [get_property_list -include {{status proven}} -no_task_prefix]
set cex    [get_property_list -include {{status cex}} -no_task_prefix]
emit_kv proven_count [llength $proven]
emit_kv cex_count [llength $cex]
emit_kv global_status [get_status $asserts]

foreach p $asserts {{
    emit_prop assertion_result $p {{status engine time min_length max_length trace_id trace_length num_traces}}
}}

foreach p $cex {{
    set safe_name [string map {{"/" "_" "." "_" ":" "_"}} $p]
    if {{![catch {{visualize -violation -property $p -new_window}}]}} {{
        if {{![catch {{visualize -get_type}} trace_type]}} {{
            puts "CHISELLMFV_TRACE_TYPE $p $trace_type"
        }}
        emit_prop trace_info $p {{trace_id trace_length min_length max_length}}
{trace_loop}
        catch {{visualize -save -vcd "cex_${{safe_name}}.vcd" -force}}
        catch {{visualize -save -script "cex_${{safe_name}}_visualize.tcl" -force}}
    }}
}}

report -csv -file assertion_results.csv -force
report -all -include_type -file assertion_all.txt -force
save -jdb assertion_session.jdb -capture_setup -capture_session_data
exit
"""

    def _assumption_tcl(self) -> str:
        return self._common_tcl() + """
check_assumptions -conflict -dead_end -live -time_limit $env(ASSUME_TIME_LIMIT)
catch {check_assumptions -show}
catch {check_assumptions -show -dead_end}
catch {check_assumptions -show -live}

foreach p {:noConflict :noDeadEnd :live} {
    if {![catch {get_property_info -list {name type status trace_id trace_length} $p} info]} {
        puts "CHISELLMFV_ASSUMPTION $p [list {name type status trace_id trace_length}] [list $info]"
    }
}

report -summary -file assumption_summary.txt -force
report -csv -file assumption_results.csv -force
exit
"""

    def _non_vacuity_tcl(self, sidecars: Sequence[Dict[str, Any]]) -> str:
        covers = []
        for idx, sidecar in enumerate(sidecars):
            expr = sidecar.get("antecedent_sv")
            if not expr:
                continue
            cover_id = sidecar.get("assert_id") or f"nv_{idx:04d}"
            covers.append(f"cover -name {_tcl_quote(str(cover_id))} {_tcl_quote(str(expr))}")
        cover_text = "\n".join(covers)
        return self._common_tcl() + f"""
{cover_text}
set covers [get_property_list -include {{type cover}} -no_task_prefix]
prove -property $covers -covers -time_limit $env(NV_TIME_LIMIT)
foreach c $covers {{
    emit_prop non_vacuity $c {{name type status engine time min_length max_length trace_id trace_length}}
}}
report -csv -file non_vacuity_results.csv -force
exit
"""

    def _mutation_tcl(self, mutated_dut: Path, mutant_id: str) -> str:
        extra_files = [self._resolve_workdir_path(path) for path in self.config.extra_sv]
        return f"""
proc emit_kv {{key value}} {{
    puts "CHISELLMFV_KV $key=$value"
}}
proc emit_list {{key values}} {{
    puts "CHISELLMFV_LIST $key [join $values {{ }}]"
}}
proc emit_prop {{tag prop fields}} {{
    if {{![catch {{get_property_info -list $fields $prop}} values]}} {{
        puts "CHISELLMFV_PROP $tag $prop [list $fields] [list $values]"
    }}
}}
clear -all
set extra_files [list {_tcl_list(extra_files)}]
analyze -sv12 {_tcl_quote(mutated_dut)} {{*}}$extra_files
elaborate -top {_tcl_quote(self.config.top)} -bbox_a 65536
clock {_tcl_quote(self.config.clock)}
reset {_tcl_quote(self.config.reset)}

set asserts [get_property_list -include {{type assert}} -no_task_prefix]
set_stop_on_cex_limit 1
prove -all -time_limit $env(MUTATION_TIME_LIMIT)
set_stop_on_cex_limit 0

set killed_by [get_property_list -include {{status cex}} -no_task_prefix]
emit_kv mutant_id {_tcl_quote(mutant_id)}
emit_kv killed [expr {{[llength $killed_by] > 0}}]
emit_list killed_by $killed_by
foreach p $killed_by {{
    emit_prop mutation_kill $p {{status engine time min_length max_length trace_id trace_length}}
}}
set first [lindex $killed_by 0]
if {{$first ne ""}} {{
    catch {{visualize -violation -property $first -new_window}}
    catch {{visualize -save -vcd first_kill.vcd -force}}
    catch {{visualize -save -script first_kill_visualize.tcl -force}}
}}
report -csv -file mutation_results.csv -force
exit
"""

    def _repair_regression_tcl(self, target_properties: Sequence[str]) -> str:
        targets = [str(target) for target in target_properties if str(target)]
        return self._common_tcl() + f"""
set targets [list {_tcl_list(targets)}]
set existing_asserts [get_property_list -include {{type assert}} -no_task_prefix]
set target_asserts {{}}

foreach p $targets {{
    set p [string trim $p]
    if {{$p eq ""}} {{
        continue
    }}
    if {{[lsearch -exact $existing_asserts $p] >= 0}} {{
        lappend target_asserts $p
    }} else {{
        puts "CHISELLMFV_REPAIR_REGRESSION missing_property $p"
    }}
}}

emit_kv repair_target_count [llength $target_asserts]
emit_list repair_targets $target_asserts

if {{[llength $target_asserts] > 0}} {{
    set_trace_optimization standard
    set_trace_optimization -irrelevant_value_computation true
    prove -property $target_asserts -time_limit $env(REPAIR_REGRESSION_TIME_LIMIT)

    foreach p $target_asserts {{
        emit_prop repair_regression $p {{name type status engine time min_length max_length trace_id trace_length num_traces}}
    }}

    set cex_after_repair [get_property_list -include {{status cex}} -no_task_prefix]
    set target_cex_after_repair {{}}
    foreach p $target_asserts {{
        if {{[lsearch -exact $cex_after_repair $p] >= 0}} {{
            lappend target_cex_after_repair $p
        }}
    }}
    emit_list repair_target_cex_after_repair $target_cex_after_repair
    emit_kv repair_target_cex_count [llength $target_cex_after_repair]
    emit_kv repair_cex_persisted [expr {{[llength $target_cex_after_repair] > 0}}]
}}

report -summary -file post_repair_summary.txt -force
report -csv -file post_repair_assertion_results.csv -force
save -jdb post_repair_assertion_session.jdb -capture_setup -capture_session_data
exit
"""

    def _sec_tcl(self, spec_sv: Sequence[str], imp_sv: Sequence[str]) -> str:
        spec_files = [self._resolve_workdir_path(path) for path in spec_sv]
        imp_files = [self._resolve_workdir_path(path) for path in imp_sv]
        extra_files = [self._resolve_workdir_path(path) for path in self.config.extra_sv]
        return f"""
proc emit_prop {{tag prop fields}} {{
    if {{![catch {{get_property_info -list $fields $prop}} values]}} {{
        puts "CHISELLMFV_PROP $tag $prop [list $fields] [list $values]"
    }}
}}
check_sec -compile_context both
check_sec -analyze -spec -sv12 {_tcl_list(spec_files)} {_tcl_list(extra_files)}
check_sec -analyze -imp -sv12 {_tcl_list(imp_files)} {_tcl_list(extra_files)}
check_sec -elaborate -spec -top {_tcl_quote(self.config.top)} -bbox_a 65536
check_sec -elaborate -imp -top {_tcl_quote(self.config.top)} -bbox_a 65536
clock {_tcl_quote(self.config.clock)}
reset {_tcl_quote(self.config.reset)}
check_sec -setup
check_sec -prove -time_limit $env(SEC_TIME_LIMIT)
set props [concat \
    [get_property_list -include {{status proven}} -no_task_prefix] \
    [get_property_list -include {{status cex}} -no_task_prefix] \
    [get_property_list -include {{status undetermined}} -no_task_prefix] \
    [get_property_list -include {{status unknown}} -no_task_prefix]]
foreach p $props {{
    emit_prop sec $p {{name type status engine time min_length max_length trace_id trace_length}}
}}
report -summary -file sec_summary.txt -force
report -csv -file sec_results.csv -force
exit
"""

    def _xprop_tcl(self) -> str:
        return self._common_tcl() + """
check_xprop -create -outputs
check_xprop -prove -all -time_limit $env(XPROP_TIME_LIMIT)
set xp_props [concat \
    [get_property_list -include {status proven} -no_task_prefix] \
    [get_property_list -include {status cex} -no_task_prefix] \
    [get_property_list -include {status undetermined} -no_task_prefix] \
    [get_property_list -include {status unknown} -no_task_prefix]]
foreach p $xp_props {
    emit_prop xprop $p {name type status engine time min_length max_length trace_id trace_length}
}
report -summary -file xprop_summary.txt -force
report -csv -file xprop_results.csv -force
exit
"""

    def _trace_signal_loop(self) -> str:
        signals = self.config.trace_signals
        if not signals:
            return ""
        return "\n".join(
            [
                f"        if {{![catch {{visualize -get_value {_tcl_quote(sig)} 1 -radix bin}} v]}} {{ puts \"CHISELLMFV_TRACE_VALUE $p cycle=1 signal={sig} value=$v\" }}"
                for sig in signals
            ]
        )

    def _build_metrics(
        self,
        parsed: ParsedJGOutput,
        returncode: int,
        raw_output: str,
    ) -> Dict[str, Any]:
        typed = parsed.design_typed
        inputs = typed.get("input", [])
        outputs = typed.get("output", [])
        expected_ports = self.config.expected_inputs + self.config.expected_outputs
        actual_ports = inputs + outputs
        return {
            "analyze_pass": returncode == 0 and not ERROR_RE.search(raw_output),
            "elaborate_pass": returncode == 0 and not ERROR_RE.search(raw_output),
            "clock_reset_bound": bool(parsed.lists.get("clocks")) and bool(parsed.lists.get("resets")),
            "inputs": inputs,
            "outputs": outputs,
            "modules": parsed.lists.get("modules", []),
            "typed_design_info": typed,
            "signal_count": int(parsed.kv.get("signal_count", "0") or 0),
            "port_match_score": compute_jaccard_score(actual_ports, expected_ports)
            if expected_ports
            else 1.0,
            "structure_delta": {
                "module_count": len(parsed.lists.get("modules", [])),
                "register_count": len(typed.get("register", [])),
                "bbox_inst_count": len(typed.get("bbox_inst", [])),
            },
        }

    def _assertion_metrics(self, parsed: ParsedJGOutput, success: bool) -> Dict[str, Any]:
        assertion_count = int(parsed.kv.get("assertion_count", "0") or 0)
        proven = int(parsed.kv.get("proven_count", "0") or 0)
        cex = int(parsed.kv.get("cex_count", "0") or 0)
        disabled = 0
        static = [p for p in parsed.properties if p.get("tag") == "assertion_static"]
        for prop in static:
            fields = prop.get("fields", [])
            values = prop.get("values", [])
            if "disabled" in fields:
                idx = fields.index("disabled")
                if idx < len(values) and values[idx] not in {"0", "false", "False", ""}:
                    disabled += 1
        cex_props = [p for p in parsed.properties if p.get("tag") == "assertion_result" and "cex" in p.get("values", [])]
        trace_ready = sum(1 for p in cex_props if _prop_field(p, "num_traces") not in {None, "0", ""})
        return {
            "success": success,
            "count": assertion_count,
            "proven": proven,
            "cex": cex,
            "global_status": parsed.kv.get("global_status"),
            "enabled_assertion_rate": (assertion_count - disabled) / assertion_count
            if assertion_count
            else 0.0,
            "proof_determined_rate": (proven + cex) / assertion_count if assertion_count else 0.0,
            "cex_rate": cex / assertion_count if assertion_count else 0.0,
            "trace_availability": trace_ready / cex if cex else 0.0,
            "properties": parsed.properties,
            "coi": parsed.coi,
            "signals": parsed.signals,
        }

    def _assumption_status(self, parsed: ParsedJGOutput, name: str) -> Optional[str]:
        for item in parsed.assumptions:
            if item.get("property") != name:
                continue
            fields = item.get("fields", [])
            values = item.get("values", [])
            if "status" in fields:
                idx = fields.index("status")
                if idx < len(values):
                    return values[idx]
        return None

    def _count_properties(
        self,
        parsed: ParsedJGOutput,
        tag: str,
        statuses: set[str],
    ) -> int:
        count = 0
        for prop in parsed.properties:
            if prop.get("tag") != tag:
                continue
            status = _prop_field(prop, "status")
            if status in statuses:
                count += 1
        return count

    def _repair_regression_metrics(
        self,
        parsed: ParsedJGOutput,
        success: bool,
        requested_targets: Sequence[str],
    ) -> Dict[str, Any]:
        props = [p for p in parsed.properties if p.get("tag") == "repair_regression"]
        proven = sum(1 for prop in props if _prop_field(prop, "status") == "proven")
        cex = int(parsed.kv.get("repair_target_cex_count", "0") or 0)
        if not cex:
            cex = sum(1 for prop in props if _prop_field(prop, "status") == "cex")
        unknown_statuses = {"undetermined", "unknown", "timeout"}
        unknown = sum(1 for prop in props if (_prop_field(prop, "status") or "") in unknown_statuses)
        missing = sum(
            1
            for line in parsed.raw_machine_lines
            if line.startswith("CHISELLMFV_REPAIR_REGRESSION missing_property ")
        )
        found_targets = int(parsed.kv.get("repair_target_count", str(len(props))) or 0)
        requested_count = len([target for target in requested_targets if target])
        repair_cex_persisted = (
            parsed.kv.get("repair_cex_persisted") in {"1", "true", "True"} or cex > 0
        )
        return {
            "success": success,
            "targets": list(requested_targets),
            "proven": proven,
            "cex": cex,
            "unknown": unknown,
            "missing": missing,
            "repair_target_count": found_targets,
            "repair_target_proven_rate": proven / found_targets if found_targets else 0.0,
            "repair_target_present": found_targets / requested_count if requested_count else 1.0,
            "repair_target_cex_count": cex,
            "repair_cex_persisted": repair_cex_persisted,
            "cex_after_repair": parsed.lists.get("repair_target_cex_after_repair", []),
            "properties": props,
        }

    def _stage_artifacts(self, result: JGRunResult) -> Dict[str, Any]:
        return {
            "success": result.ok,
            "returncode": result.returncode,
            "command": result.command,
            "tcl": str(result.tcl_path),
            "log": str(result.log_path),
            "machine_lines": result.parsed.raw_machine_lines,
        }

    def _resolve_workdir_path(self, path: str | Path) -> Path:
        candidate = Path(path)
        if candidate.is_absolute():
            return candidate
        return self.config.workdir / candidate

    def _write_json(self, path: Path, data: Dict[str, Any]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, indent=2, ensure_ascii=False, default=str), encoding="utf-8")

    @staticmethod
    def compute_scores(record: Dict[str, Any]) -> Dict[str, float]:
        build = record.get("build", {})
        assertions = record.get("assertions", {})
        mutation = record.get("mutation", {})
        repair_regression = record.get("repair_regression", {})
        sec = record.get("sec", {})
        xprop = record.get("xprop", {})

        build_score = (
            _bool_from_result(build.get("analyze_pass", False))
            * _bool_from_result(build.get("elaborate_pass", False))
            * _bool_from_result(build.get("clock_reset_bound", False))
            * float(build.get("port_match_score", 0.0))
        )
        assertion_score = (
            float(assertions.get("enabled_assertion_rate", 0.0))
            * float(assertions.get("proof_determined_rate", 0.0))
        )
        bug_detection_score = (
            float(assertions.get("cex_rate", 0.0))
            * float(assertions.get("trace_availability", 0.0))
        )
        mutation_score = float(mutation.get("mutation_score", 0.0))
        if repair_regression and not repair_regression.get("skipped"):
            repair_regression_gate = (
                float(repair_regression.get("repair_target_proven_rate", 0.0))
                * float(repair_regression.get("repair_target_present", 0.0))
            )
        else:
            repair_regression_gate = 1.0
        repair_score = (
            repair_regression_gate
            * float(sec.get("sec_proven_rate", 0.0))
            * float(xprop.get("non_xprop_rate", 0.0))
        )
        overall = (
            0.15 * build_score
            + 0.25 * assertion_score
            + 0.20 * bug_detection_score
            + 0.25 * mutation_score
            + 0.15 * repair_score
        )
        return {
            "build_score": build_score,
            "assertion_score": assertion_score,
            "bug_detection_score": bug_detection_score,
            "mutation_score": mutation_score,
            "repair_score": repair_score,
            "overall": overall,
        }


def _prop_field(prop: Dict[str, Any], field: str) -> Optional[str]:
    fields = prop.get("fields", [])
    values = prop.get("values", [])
    if field not in fields:
        return None
    idx = fields.index(field)
    if idx >= len(values):
        return None
    return values[idx]


def generate_text_mutants(source: Path | str, output_root: Path | str, max_mutants: int = 20) -> List[Dict[str, Any]]:
    """Generate small, explainable textual RTL mutants.

    This is deliberately conservative. It produces candidates for the quality
    runner; JasperGold build/proof determines which candidates are valid.
    """
    source_path = Path(source)
    output_root = Path(output_root)
    text = source_path.read_text(encoding="utf-8", errors="ignore")
    candidates = _mutation_candidates(text)
    mutants: List[Dict[str, Any]] = []
    output_root.mkdir(parents=True, exist_ok=True)

    for idx, candidate in enumerate(candidates[:max_mutants]):
        mutant_id = f"m_{idx:04d}"
        mutant_dir = output_root / mutant_id
        mutant_dir.mkdir(parents=True, exist_ok=True)
        mutated = text[: candidate["start"]] + candidate["after"] + text[candidate["end"] :]
        dut_path = mutant_dir / "dut_mutated.sv"
        dut_path.write_text(mutated, encoding="utf-8")
        metadata = {
            "mutant_id": mutant_id,
            "operator": candidate["operator"],
            "file": str(source_path),
            "line": _line_number(text, candidate["start"]),
            "before": candidate["before"],
            "after": candidate["after"],
            "target_signal": candidate.get("target_signal"),
        }
        (mutant_dir / "mutation.json").write_text(
            json.dumps(metadata, indent=2, ensure_ascii=False),
            encoding="utf-8",
        )
        mutants.append(metadata)
    return mutants


def _mutation_candidates(text: str) -> List[Dict[str, Any]]:
    candidates: List[Dict[str, Any]] = []
    comment_spans = _comment_spans(text)

    def in_comment(pos: int) -> bool:
        return any(start <= pos < end for start, end in comment_spans)

    replacements = [
        ("comparison_replace", r"(?<![<>=!])==(?!=)", "!="),
        ("comparison_replace", r"!=", "=="),
        ("constant_flip", r"\b1'[bhd]0\b|\b1'h0\b|\b1'b0\b", None),
        ("constant_flip", r"\b1'[bhd]1\b|\b1'h1\b|\b1'b1\b", None),
    ]
    for operator, pattern, replacement in replacements:
        for match in re.finditer(pattern, text):
            if in_comment(match.start()):
                continue
            before = match.group(0)
            after = replacement if replacement is not None else _flip_one_bit_literal(before)
            if after and after != before:
                candidates.append(
                    {
                        "operator": operator,
                        "start": match.start(),
                        "end": match.end(),
                        "before": before,
                        "after": after,
                    }
                )

    for match in re.finditer(r"\bif\s*\(([^;\n]+?)\)", text):
        if in_comment(match.start()):
            continue
        expr = match.group(1).strip()
        candidates.append(
            {
                "operator": "invert_condition",
                "start": match.start(1),
                "end": match.end(1),
                "before": expr,
                "after": f"!({expr})",
                "target_signal": _first_identifier(expr),
            }
        )

    for match in re.finditer(r"\bif\s*\(\s*reset\s*\).*?<=\s*(1'[bhd][01])", text):
        if in_comment(match.start()):
            continue
        before = match.group(1)
        after = _flip_one_bit_literal(before)
        if after:
            candidates.append(
                {
                    "operator": "reset_value_flip",
                    "start": match.start(1),
                    "end": match.end(1),
                    "before": before,
                    "after": after,
                    "target_signal": "reset",
                }
            )
    return candidates


def _comment_spans(text: str) -> List[tuple[int, int]]:
    spans = [(m.start(), m.end()) for m in re.finditer(r"//.*?$", text, flags=re.MULTILINE)]
    spans.extend((m.start(), m.end()) for m in re.finditer(r"/\*.*?\*/", text, flags=re.DOTALL))
    return spans


def _flip_one_bit_literal(value: str) -> Optional[str]:
    if value.endswith("0"):
        return value[:-1] + "1"
    if value.endswith("1"):
        return value[:-1] + "0"
    return None


def _first_identifier(expr: str) -> Optional[str]:
    match = re.search(r"[A-Za-z_][A-Za-z0-9_$]*", expr)
    return match.group(0) if match else None


def _line_number(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def load_sidecars(path: Optional[str | Path]) -> List[Dict[str, Any]]:
    if not path:
        return []
    sidecar_path = Path(path)
    data = json.loads(sidecar_path.read_text(encoding="utf-8"))
    if isinstance(data, list):
        return data
    if isinstance(data, dict) and isinstance(data.get("assertions"), list):
        return data["assertions"]
    raise ValueError(f"Unsupported sidecar schema: {sidecar_path}")


def parse_xprop_summary(output: str) -> List[Dict[str, Any]]:
    """Parse JasperGold X-prop app summary lines when properties are hidden."""
    props: List[Dict[str, Any]] = []
    pattern = re.compile(
        r"(?m)^\s*(?:XP_outputs::)?(?P<name>[A-Za-z_][A-Za-z0-9_$.\[\]]*)\s*:\s*"
        r"(?P<status>NON-X-propagatable|X-propagatable|Undetermined)\b"
    )
    for match in pattern.finditer(output):
        name = match.group("name")
        status = match.group("status")
        props.append(
            {
                "tag": "xprop",
                "property": name,
                "fields": ["name", "type", "status"],
                "values": [name, "xprop_output", status],
                "source": "check_xprop_summary",
            }
        )
    return props
