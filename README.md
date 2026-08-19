# ChiselLMFV

ChiselLMFV 是面向论文实验的硬件形式化验证与错误定位项目。当前代码包含三条路线：

| 路线 | 做什么 | 入口 |
|---|---|---|
| SpecFlow | 从 Chisel 规范和意图生成性质，用 JasperGold 编译验证，并对 CEX 做确定性诊断与候选排序 | `main.py specflow ...` |
| CoupledL2 | TileLink/CoupledL2 的属性预检、绑定与确定性形式验证 | `main.py run ...` |
| VerilogCause | 原生 Verilog 可执行语句定位：VCA 精确动态证据 + 源码级反事实回放 | `python -m src.experiments.verilogcause ...` |

三条路线各自维护代码、运行目录和指标，不互相覆盖结果。

## 目录结构

```text
main.py                          SpecFlow / CoupledL2 / 论文实验 CLI
src/chiselspecflow/              SpecFlow 性质生成、审阅、编译验证与诊断
src/coupledl2/                   CoupledL2 预检、属性绑定与形式验证
src/core/                        共享预算、记录与模型客户端
src/experiments/                 实验入口（paper、specflow_exp、chiselcause_exp、verilogcause 等）
src/verilog2chisel/              Verilog 到 Chisel 转换
VerilogCausalAnalysis/           VCA：精确因果图、源端证据与结构基线
benchmark/Wit-HW/                原生 Verilog 38-case 语料
benchmark/synth/                 Wit-HW Chisel 语料与 SpecFlow 项目
CoupledL2-Verification/          CoupledL2 案例与属性资产
tests/                           当前聚焦回归
```

## 环境准备

需要 Python 3.10+。推荐使用 uv：

```bash
uv sync --extra causal --extra dev
```

或使用 pip：

```bash
python -m pip install -e '.[causal,dev]'
```

外部工具由对应入口调用，不在项目内安装：

- Verilator：Verilog 编译与波形回放（VerilogCause、SpecFlow 诊断）。
- JasperGold：SpecFlow 与 CoupledL2 的形式验证。
- Chisel/sbt 工具链：`benchmark/synth` 的 Chisel 生成与等价检查。

需要模型提交的步骤使用以下环境变量：

```bash
CHISELLMFV_LLM_API_KEY=
CHISELLMFV_LLM_URL=
CHISELLMFV_LLM_BASE_URL=
CHISELLMFV_LLM_MODEL=
CHISELLMFV_LLM_EXTRA_BODY=
```

## 常用命令

以下命令只使用普通 `uv run`。`main.py` 会把 `src/` 加入 `sys.path`；VCA 相关入口需要额外的 `PYTHONPATH`。

### SpecFlow

性质生成（一次模型提交）：

```bash
uv run python main.py specflow start \
  --project-contract benchmark/synth/counter/specflow/project.json \
  --spec benchmark/synth/counter/specflow/spec.md \
  --config benchmark/synth/counter/specflow/configs/cfg_000.json \
  --run-root runs/specflow
```

安装审阅记录：

```bash
uv run python main.py specflow review \
  --run <run_dir> \
  --review-record <review_record.json>
```

编译验证（不调用模型）：

```bash
uv run python main.py specflow resume \
  --run <run_dir> \
  --through compile_verify
```

CEX 诊断（不调用模型，使用 VCA）：

```bash
PYTHONPATH=.:VerilogCausalAnalysis/src uv run python main.py specflow resume \
  --run <run_dir> \
  --through diagnose
```

### CoupledL2

预检：

```bash
uv run python main.py run \
  --case CoupledL2-Verification/code/CaseStudy_1/XiangShan-CoupledL2-deadlock-v0 \
  --property-profile mshr_wait_bound_poc \
  --preflight-only
```

属性绑定（一次模型提交）：

```bash
uv run python main.py run \
  --case CoupledL2-Verification/code/CaseStudy_1/XiangShan-CoupledL2-deadlock-v0 \
  --property-profile mshr_wait_bound_poc \
  --stage bind_properties
```

形式验证（不调用模型）：

```bash
uv run python main.py run \
  --resume-run <run_dir> \
  --stage invoke_verification
```

### VerilogCause

准备 38-case 输入：

```bash
PYTHONPATH=.:VerilogCausalAnalysis/src uv run python -m src.experiments.verilogcause prepare \
  --run <new_run_dir> \
  --corpus benchmark/Wit-HW/buggy_designs \
  --families combinational,sequential
```

后续阶段按序执行 `candidates`、`pilot`、`baselines`、`counterfactual-pilot`、`stage5`，每个阶段只读自己的 run 目录。`train` 当前不可用：只有预先约定的效果门禁通过后才允许训练。

### 论文实验

```bash
uv run python main.py experiment prepare \
  --model <model> \
  --url <chat-completions-url> \
  --max-output-tokens 32768
```

`experiment` 还提供 `validate`、`record`、`report`、`convert-vcd` 等子命令，见 `src/experiments/paper.py`。每次正式实验从新 run 目录开始。

### 测试

```bash
PYTHONPATH=.:VerilogCausalAnalysis/src uv run pytest -q
```

## 结果与证据边界

- 单元测试、编译完成或进程退出只能说明局部能力；形式验证必须读取 JasperGold 结果文件，missing、timeout、tool error 和 CEX 分别记录，不能从退出码推断证明成功。
- VerilogCause 中，`active_exact` 是精确动态证据，反事实回放是干预证据；两者都不能替代“正确语句、目标、周期和源码位置”的完整证据链。
- 25/13 分组只用于评价解释，方法运行时不得读取 gold 或分组。
- 删除语句、缺失语句和现有语句定位是不同任务，分别使用自己的输入、候选和标签。
- 失败结果保留为失败结果；不通过换模型、放宽输入、替换样本或只报告有利子集来改写结论。
