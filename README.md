# ChiselLMFV

ChiselLMFV 是面向论文实验的 Chisel 形式化验证项目。当前代码只保留 SpecFlow、CoupledL2 确定性验证和 Chisel-aware VerilogCausalAnalysis（VCA）三部分。

项目只维护一个当前合同，不保留并行 `v*` API、历史 reader 或兼容路径。

## 当前流程

```text
SpecFlow
  asset_authoring       一次 typed authoring
  compile_verify        确定性编译与 JasperGold
  diagnose              确定性 CEX 投影、VCA、源候选排序

CoupledL2
  preflight
  bind_properties       一次完整 binding manifest
  invoke_verification   确定性 JasperGold
```

SpecFlow 的资产审阅只发生一次，用于批准将要验证的 run-local property package。诊断阶段不调用模型、不进行第二次审阅，也不生成修订或补丁。

## 目录

```text
main.py                         CLI
experiment.md                   唯一论文实验方案
implementation.md               唯一持续迭代计划
src/chiselspecflow/             SpecFlow
src/coupledl2/                  CoupledL2
src/core/                       共享 artifact、预算和模型客户端
VerilogCausalAnalysis/          structural baseline 与当前 Chisel-aware VCA
benchmark/synth/                Wit-HW Chisel 语料
runs/reference/                 两组保留的参考验收证据
tests/                          当前合同的聚焦回归
```

`runs/` 和 `log/` 不进入 Git。正式论文实验必须写入新的 experiment ID；`runs/reference/` 只用于核对历史输入和命令，不能作为新结果。

## 论文 experiment runner

`experiment.md` 的正式入口先执行 9.1/9.2 准备门：

```bash
rtk codex-run /home/chenty/llm/eda-agent/chisellmfv/.venv/bin/python main.py \
  experiment prepare \
  --model <exact-model> \
  --url <exact-chat-completions-url> \
  --max-output-tokens 32768
```

该命令不调用模型或 formal。它创建唯一
`runs/specflow-experiments/<YYYYMMDD-HHMMSS>-paper/`，验证 10 个 family 的
`project.json`/`cfg_000.json`，固定两个 CoupledL2 case，生成 hash-bound
`corpus.json`、`config.json`、四个唯一 JSONL ledger 和原始产物目录。
模型、URL 或预算缺失时直接停止，不写默认值；产物只保存 API key 的环境
变量名，不保存 key。

后续任务 row 只能通过 `experiment record` 追加，重复 task/method 会被拒绝。
CoupledL2 exact CEX 必须通过 `experiment convert-vcd` 调用 `vcd2fst`；转换
失败记录为 `incomplete`，不得借用历史 FST。

## 环境

Python 3.10+。所有本地构建、Chisel、JasperGold 和测试命令通过项目工具链运行：

```bash
rtk codex-run <command> [args...]
rtk codex-run bash -lc '<shell command>'
```

模型配置只使用一组变量：

```bash
CHISELLMFV_LLM_API_KEY=
CHISELLMFV_LLM_URL=
CHISELLMFV_LLM_BASE_URL=
CHISELLMFV_LLM_MODEL=
CHISELLMFV_LLM_EXTRA_BODY=
```

不支持旧变量名、双模型路由或运行中切换模型。

## SpecFlow

### 1. Authoring

```bash
rtk codex-run /home/chenty/llm/eda-agent/chisellmfv/.venv/bin/python main.py \
  specflow start \
  --project-contract benchmark/synth/counter/specflow/project.json \
  --spec benchmark/synth/counter/specflow/spec.md \
  --config benchmark/synth/counter/specflow/configs/cfg_000.json \
  --suite-ledger benchmark/synth/SPECIFICATIONS.sha256 \
  --run-root runs/specflow
```

该步骤只接受一次模型提交。无效提交直接失败。

### 2. 安装资产审阅

```bash
rtk codex-run /home/chenty/llm/eda-agent/chisellmfv/.venv/bin/python main.py \
  specflow review \
  --run <run_dir> \
  --review-record <review_record.json>
```

审阅记录必须绑定实际 artifact hash。

### 3. 编译与验证

```bash
rtk codex-run /home/chenty/llm/eda-agent/chisellmfv/.venv/bin/python main.py \
  specflow resume \
  --run <run_dir> \
  --through compile_verify
```

Stage 2 不调用模型。JasperGold 的 missing、timeout、tool error 和 CEX 分别记录，不从退出码推断 proof。

### 4. 定位

```bash
rtk codex-run env PYTHONPATH=.:VerilogCausalAnalysis/src \
  /home/chenty/llm/eda-agent/chisellmfv/.venv/bin/python main.py \
  specflow resume \
  --run <run_dir> \
  --through diagnose
```

该步骤不调用模型。它从 exact CEX 生成：

- `evidence_projection.json`
- `causal_graph_manifest.json`
- `causal_source_projection.json`
- `root_cause_result.json`
- `source_ranking.json`
- `final_verdict.json`

`source_ranking` 是可评测候选排序，不是形式化根因证明。

## CoupledL2

### Preflight

```bash
rtk codex-run /home/chenty/llm/eda-agent/chisellmfv/.venv/bin/python main.py \
  run \
  --case CoupledL2-Verification/code/CaseStudy_1/XiangShan-CoupledL2-deadlock-v0 \
  --property-profile mshr_wait_bound_poc \
  --preflight-only
```

### Binding

```bash
rtk codex-run /home/chenty/llm/eda-agent/chisellmfv/.venv/bin/python main.py \
  run \
  --case CoupledL2-Verification/code/CaseStudy_1/XiangShan-CoupledL2-deadlock-v0 \
  --property-profile mshr_wait_bound_poc \
  --stage bind_properties
```

模型必须一次提交全部 slot 和参数。系统不会自动补绑定、修参数、复用旧 manifest 或再次调用模型。

### Formal

```bash
rtk codex-run /home/chenty/llm/eda-agent/chisellmfv/.venv/bin/python main.py \
  run \
  --resume-run <run_dir> \
  --stage invoke_verification
```

CoupledL2 的 `run` 入口到此结束。CEX 的论文定位实验走 SpecFlow/VCA 的确定性 `diagnose` 合同。

## VCA 公共接口

当前 Chisel-aware 方法：

```python
from verilog_causal_analysis import (
    make_request,
    build_causal_graph,
    prepare_causal_session,
)
```

结构基线：

```python
from verilog_causal_analysis import (
    make_structural_request,
    build_structural_graph,
    prepare_structural_analysis,
)
```

两者是论文方法与基线，不是软件版本。生产诊断只使用当前 Chisel-aware 接口。

## 测试

```bash
rtk codex-run env PYTHONPATH=.:VerilogCausalAnalysis/src \
  /home/chenty/llm/eda-agent/chisellmfv/.venv/bin/python -m pytest -q
```

单元测试通过只证明当前 Python 合同。JasperGold、真实 FST、CoupledL2 和论文指标必须分别由新生成的原始 artifact 支持。
