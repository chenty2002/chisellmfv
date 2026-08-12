import json
import logging
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from src.coupledl2.backend import CoupledL2BuildOperations, parse_jaspergold_report
from src.coupledl2.config import CoupledL2RunConfig
from src.coupledl2.indexer import generate_indexes
from src.coupledl2.preflight import CoupledL2Preflight
from src.coupledl2.workspace import create_coupledl2_workspace


def _make_case(root: Path, name: str = "XiangShan-CoupledL2-deadlock-v0") -> Path:
    case = root / name
    chisel_dir = case / "Chisel"
    verify_dir = chisel_dir / "src" / "test" / "scala" / "coupledL2"
    verilog_dir = case / "Verilog"
    verify_dir.mkdir(parents=True)
    verilog_dir.mkdir(parents=True)

    (chisel_dir / "Makefile").write_text(
        "auto-l2l3l2:\n\t@mkdir -p Verilog/L2L3L2 && printf 'module VerifyTop(); endmodule\\n' > Verilog/L2L3L2/VerifyTop.sv\n"
        "auto:\n\t@mkdir -p Verilog/L2L3L2 && printf 'module VerifyTop(); endmodule\\n' > Verilog/L2L3L2/VerifyTop.sv\n",
        encoding="utf-8",
    )
    (verify_dir / "VerifyTop.scala").write_text(
        "import chisel3._\n"
        "import chiselFv.Formal\n"
        "class VerifyTop extends Module {\n"
        "  val verify_timer = RegInit(0.U(50.W))\n"
        "  verify_timer := verify_timer + 1.U\n"
        "}\n",
        encoding="utf-8",
    )
    mshr_ctl = chisel_dir / "src" / "main" / "scala" / "coupledL2" / "MSHRCtl.scala"
    mshr_ctl.parent.mkdir(parents=True)
    mshr_ctl.write_text(
        "class MSHRCtl {\n"
        "  for (((timer, m), i) <- timers.zip(mshrs).zipWithIndex) {\n"
        "    when(m.io.status.bits.channel === 1.U) {\n"
        "    }\n"
        "  }\n"
        "}\n",
        encoding="utf-8",
    )
    (verilog_dir / "setup.sh").write_text("#!/usr/bin/env bash\njg -batch verify.tcl\n", encoding="utf-8")
    return case


def test_coupledl2_baseline_build_uses_contract_target_env_and_writes_artifacts(tmp_path, monkeypatch):
    case = _make_case(tmp_path / "cases")
    workspace = create_coupledl2_workspace(
        CoupledL2RunConfig(
            case_path=case,
            verify_mode="small",
            property_profile="mshr_wait_bound_poc",
            run_root=tmp_path / "runs",
        )
    )
    generate_indexes(workspace.run_dir, workspace.case_workspace, workspace.config)
    calls = []
    mill_binary = tmp_path / "tools" / "mill"
    mill_binary.parent.mkdir()
    mill_binary.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
    mill_binary.chmod(0o755)

    monkeypatch.setattr(
        "src.coupledl2.backend.shutil.which",
        lambda name: str(mill_binary) if name == "mill" else None,
    )
    monkeypatch.setattr(
        "src.coupledl2.backend._directory_is_writable",
        lambda _path: False,
    )

    def fake_run(command, cwd, env, text, stdout, stderr, timeout, check):
        calls.append((command, cwd, env))
        generated = Path(cwd) / "Verilog" / "L2L3L2"
        generated.mkdir(parents=True)
        (generated / "VerifyTop.sv").write_text(
            "module VerifyTop(); endmodule\n",
            encoding="utf-8",
        )
        return subprocess.CompletedProcess(command, 0, stdout="built\n", stderr="")

    monkeypatch.setattr(subprocess, "run", fake_run)

    backend = CoupledL2BuildOperations(workspace, logging.getLogger("test-backend"))
    result = backend.run_baseline_build()

    assert result["success"] is True
    assert calls[0][0] == ["make", "auto-l2l3l2"]
    assert calls[0][1] == workspace.case_workspace / "Chisel"
    assert calls[0][2]["VERIFY_MODE"] == "small"
    assert calls[0][2]["VERIFY_INPUT_MODE"] == "coupledl2asl1"
    wrapper = workspace.run_dir / "tool_wrappers" / "mill"
    assert wrapper.is_file()
    assert f'exec "{mill_binary}" --mill-version "0.11.5" "$@"' in wrapper.read_text(encoding="utf-8")
    assert calls[0][2]["PATH"].split(os.pathsep)[0] == str(wrapper.parent)
    assert calls[0][2]["JAVA_OPTS"].split()[-1] == (
        f"-Duser.home={workspace.run_dir / 'tool_home'}"
    )
    assert (workspace.run_dir / "tool_home").is_dir()
    assert result["top_module"] == "VerifyTop"
    assert result["generated_files"] == [
        str(workspace.case_workspace / "Chisel" / "Verilog" / "L2L3L2" / "VerifyTop.sv")
    ]

    stage_dir = workspace.results_dir / "preflight"
    assert json.loads(
        (stage_dir / "baseline_build_result.json").read_text(encoding="utf-8")
    )["success"] is True
    assert json.loads((stage_dir / "generated_files.json").read_text(encoding="utf-8"))["top_module"] == "VerifyTop"
    assert (stage_dir / "build.log").read_text(encoding="utf-8") == "built\n"


def test_coupledl2_backend_writes_run_local_verify_tcl_and_file_list(tmp_path):
    case = _make_case(tmp_path / "cases", name="XiangShan-CoupledL2-write_read")
    workspace = create_coupledl2_workspace(
        CoupledL2RunConfig(
            case_path=case,
            property_profile="write_read_poc",
            run_root=tmp_path / "runs",
        )
    )
    generate_indexes(workspace.run_dir, workspace.case_workspace, workspace.config)
    generated = workspace.case_workspace / "Chisel" / "Verilog"
    generated.mkdir()
    (generated / "Helper.sv").write_text("module Helper(); endmodule\n", encoding="utf-8")
    (generated / "VerifyTop.sv").write_text("module VerifyTop(input clock, input reset); endmodule\n", encoding="utf-8")
    stage2_dir = workspace.results_dir / "by_stage" / "02_bind_properties"
    stage2_dir.mkdir(parents=True, exist_ok=True)
    from src.coupledl2.result_contract import build_primary_operation_plan

    traceability = {
        "properties": [
            {
                "instance_id": "trace_0",
                "rtl_properties": [
                    {
                        "rtl_label": "CL2_TRACE_LABEL__E0",
                        "expected_property_id": "VerifyTop.CL2_TRACE_LABEL__E0",
                    }
                ],
            }
        ]
    }
    (stage2_dir / "property_package.json").write_text(
        json.dumps(
            {
                "operation_plan": build_primary_operation_plan(
                    traceability, package_sha256="0" * 64
                )
            }
        ),
        encoding="utf-8",
    )
    (stage2_dir / "assertion_delta.json").write_text(
        json.dumps(
            {
                "schema_version": "assertion_delta",
                "top_module": "VerifyTop",
                "rtl_properties": [
                    {
                        "rtl_label": "CL2_TRACE_LABEL__E0",
                        "expected_property_id": "VerifyTop.CL2_TRACE_LABEL__E0",
                    }
                ],
            }
        ),
        encoding="utf-8",
    )
    from src.coupledl2.formal_contract import load_formal_contract

    preflight = workspace.results_dir / "preflight"
    preflight.mkdir(parents=True, exist_ok=True)
    (preflight / "formal_contract.json").write_text(
        json.dumps(
            load_formal_contract(
                "write_read_poc",
                profile_id="write_read_poc",
                case_name="XiangShan-CoupledL2-write_read",
                case_workspace=workspace.case_workspace,
            ).artifact()
        ),
        encoding="utf-8",
    )

    backend = CoupledL2BuildOperations(workspace, logging.getLogger("test-backend"))
    result = backend.prepare_verification_inputs(top_module="VerifyTop")

    assert result["success"] is True
    assert result["top_module"] == "VerifyTop"
    assert [Path(path).name for path in result["verilog_files"]] == ["00_Helper.sv", "01_VerifyTop.sv"]

    stage_dir = workspace.results_dir / "by_stage" / "03_invoke_verification"
    file_list = (stage_dir / "verilog_files.json").read_text(encoding="utf-8")
    assert "VerifyTop.sv" in file_list
    verify_tcl = (stage_dir / "verify.tcl").read_text(encoding="utf-8")
    assert "analyze -sv" in verify_tcl
    assert "elaborate -bbox_a 300000 -top VerifyTop" in verify_tcl
    assert "prove -all" not in verify_tcl
    assert "prove -property {VerifyTop.CL2_TRACE_LABEL__E0}" in verify_tcl
    assert "set_trace_optimization standard" in verify_tcl
    assert "visualize -violation -property $property" in verify_tcl
    assert "visualize -save -force -vcd $filename" in verify_tcl
    assert "chisellmfv_save_trace {VerifyTop.CL2_TRACE_LABEL__E0}" in verify_tcl
    assert "{traces/trace_0__primary_assertion__CL2_TRACE_LABEL__E0.vcd}" in verify_tcl
    assert (workspace.case_workspace / "Verilog" / "verify.tcl").read_text(encoding="utf-8") == verify_tcl


def test_parse_jaspergold_report_uses_results_rows_and_range_bounds():
    parsed = parse_jaspergold_report(
        """
        ==============================================================
        SUMMARY
        ==============================================================
                  - bounded_proven (auto)     : 0 (0%)
                  - cex                       : 2 (100%)

        RESULTS
        ==============================================================
        [1]   VerifyTop.CL2_WRITE_READ_REGRESSION__E0           cex             J      62 - 109    0.308 s
        [2]   VerifyTop.CL2_WRITE_READ_REGRESSION__E1           cex             J      62 - 115    0.363 s
        """
    )

    assert "-" not in parsed["property_statuses"]
    assert parsed["cex_count"] == 2
    assert parsed["proven_count"] == 0
    assert (
        parsed["property_statuses"]["VerifyTop.CL2_WRITE_READ_REGRESSION__E0"]["bound"]
        == "62 - 109"
    )


def test_parse_jaspergold_report_normalizes_property_statuses_and_traces(tmp_path):
    trace = tmp_path / "cex_deadlock_progress.fst"
    trace.write_text("fst", encoding="utf-8")
    parsed = parse_jaspergold_report(
        """
        [1] VerifyTop.deadlock_progress proven N Infinite 0.100 s
        [2] VerifyTop.read_after_write cex N 35 0.250 s
        [3] VerifyTop.copy_equal undetermined N 40 1.000 s
        """,
        trace_dir=tmp_path,
    )

    assert parsed["analyze_ok"] is True
    assert parsed["elaborate_ok"] is True
    assert parsed["proven_count"] == 1
    assert parsed["cex_count"] == 1
    assert parsed["inconclusive_count"] == 1
    assert parsed["failing_properties"] == ["VerifyTop.read_after_write"]
    assert parsed["inconclusive_properties"] == ["VerifyTop.copy_equal"]
    assert parsed["property_statuses"]["VerifyTop.read_after_write"]["status"] == "cex"
    assert parsed["trace_artifacts"] == [str(trace)]


def test_parse_jaspergold_report_classifies_no_conflict_cex_as_invalid_environment():
    parsed = parse_jaspergold_report(
        """
        [1] :noConflict cex N 0 0.010 s
        [2] VerifyTop.protocol proven N Infinite 0.100 s
        """
    )

    assert parsed["invalid_environment"] is True
    assert parsed["environment_failure_kind"] == "assumption_conflict"
    assert parsed["cex_count"] == 0
    assert parsed["environment_cex_properties"] == [":noConflict"]


def test_run_jaspergold_persists_byte_output_after_timeout(tmp_path, monkeypatch):
    workspace = type(
        "Workspace",
        (),
        {
            "results_dir": tmp_path / "results",
            "config": type("Config", (), {"property_profile": "mshr_wait_bound_poc"})(),
        },
    )()
    case_dir = tmp_path / "case"
    (case_dir / "Verilog").mkdir(parents=True)
    backend = object.__new__(CoupledL2BuildOperations)
    backend.workspace = workspace
    backend.case_dir = case_dir

    backend._load_formal_contract_artifact = lambda: {
        "sha256": "0" * 64,
        "resources": {"global_timeout_s": 1},
        "tool": {"name": "jaspergold", "version": "2020"},
    }
    backend._expected_operations = lambda: [
        {
            "operation_id": "timeout__primary_assertion__CL2_TIMEOUT",
            "instance_id": "timeout",
            "role": "primary_assertion",
            "target": "CL2_TIMEOUT",
            "rtl_property_id": "VerifyTop.CL2_TIMEOUT",
            "expected_statuses": ["proven", "cex", "unreachable", "inconclusive", "not_run", "tool_error"],
            "trace_required": False,
            "budget_class": "proof",
            "evidence_target": "primary:CL2_TIMEOUT",
        }
    ]
    def fake_process(*_args, **kwargs):
        output = (
            "0.0.N: Per property time limit expired (1.00 s)\n"
            '0.0.N: Stopped processing property "VerifyTop.CL2_TIMEOUT"\n'
        )
        kwargs["log_path"].write_text(output, encoding="utf-8")
        return output, None, True

    backend._run_jaspergold_process = fake_process
    backend._convert_vcd_trace_artifacts = lambda: None
    backend._collect_trace_artifacts = lambda _stage_dir: None

    result = backend.run_jaspergold(timeout_s=1)

    assert result["success"] is False
    log = (
        workspace.results_dir
        / "by_stage"
        / "03_invoke_verification"
            / "jaspergold.log"
    ).read_text(encoding="utf-8")
    assert "VerifyTop.CL2_TIMEOUT" in log
    assert "VerifyTop.CL2_TIMEOUT" in result["output"]
    assert result["jaspergold_result"]["returncode"] is None
    assert result["cex_count"] == 0
    assert result["jaspergold_result"]["primary_results"][0]["reason"] == (
        "per_property_timeout"
    )
    assert result["jaspergold_result"]["command"][2:4] == [
        "-proj",
        str(
            workspace.results_dir
            / "by_stage"
            / "03_invoke_verification"
            / "jgproject"
        ),
    ]


def test_main_run_preflight_only_executes_coupledl2_backend(tmp_path, monkeypatch, capsys):
    case = _make_case(tmp_path / "cases")
    run_root = tmp_path / "runs"

    def fake_run(command, cwd, env, text, stdout, stderr, timeout, check):
        generated = Path(cwd) / "Verilog" / "L2L3L2"
        generated.mkdir(parents=True)
        (generated / "VerifyTop.sv").write_text(
            "module VerifyTop(); endmodule\n",
            encoding="utf-8",
        )
        return subprocess.CompletedProcess(command, 0, stdout="built\n", stderr="")

    monkeypatch.setattr(subprocess, "run", fake_run)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "main.py",
            "run",
            "--case",
            str(case),
            "--run-root",
            str(run_root),
            "--property-profile",
            "mshr_wait_bound_poc",
            "--preflight-only",
        ],
    )

    from main import main

    main()

    output = capsys.readouterr().out
    assert '"success": true' in output
    run_dirs = list(run_root.iterdir())
    assert len(run_dirs) == 1
    assert (run_dirs[0] / "results" / "preflight" / "baseline_build_result.json").is_file()
