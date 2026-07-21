# ChiselSpecFlow V5 实现级重构方案

> 设计依据：`refactor_v5.md`
>
> 设计日期：2026-07-18
>
> 目标：把 V5 的三阶段 Chisel-native 验证方法落实为可编码、可测试、可审阅和可逐步验收的仓库重构方案。
>
> 本文只规定实现边界与迭代顺序，不宣称其中尚未实现的能力已经可用。

## 1. 结论先行

V5 不应继续扩展 `src/coupledl2/`，也不应复用旧 `formal` agent loop 作为主实现。应新增独立的 `src/chiselspecflow/` 包，并只从现有代码中抽取两个真正通用的确定性内核：

1. hash-bound stage artifact/handoff；
2. exact operation set accounting。

新的生产入口使用 `main.py specflow`，保留现有 `formal`、`run` 和 `v2c` 行为不变。V5 对外只暴露三个论文阶段：

```text
asset_authoring
  -> compile_verify
  -> diagnose
```

其中 review 是 `asset_authoring` 的显式 gate，不增加第四个论文阶段。若候选尚未被人工/Codex 审阅，run 必须停在 `awaiting_review`；API 模型没有 approval 工具，也不能把自己写成 reviewer。

首个真实 vertical slice 固定为 `benchmark/synth/counter`：

- Stage 1 从已知 counter spec、可见 Chisel source 和配置事实生成 obligation/binding/monitor candidates；
- reviewer 审批 run-local package；
- Stage 2 生成独立 Chisel wrapper/monitor，保留 source locator 地 elaboration，并对 reference 配置得到至少一个 proof；
- 冻结相同 verification package 后，在一个不向 authoring 模型暴露 bug label/diff 的 target 上得到至少一个 CEX；
- Stage 3 把 proof/CEX 映射回 spec clause、原 Chisel object 和 monitor state。

在这个闭环成立前，不扩展到 I2C、SDRAM、RS 或 SHA3，也不先建设大规模 evaluation harness。

## 2. 当前实现审计与必须切断的耦合

### 2.1 可以抽取的现有能力

| 当前实现 | 可复用能力 | V5 处理方式 |
|---|---|---|
| `src/coupledl2/artifacts.py` | artifact contract、SHA-256、reference-only handoff、resume 校验 | 移到 `src/core/artifact_contract.py`，CoupledL2 与 V5 都直接导入新位置 |
| `src/coupledl2/result_contract.py` | stable operation ID、expected/actual exact-set 核算、missing materialization | 只抽取无领域语义的基础函数到 `src/core/formal_operations.py`；V4 与 V5 各自保留 reducer |
| `src/coupledl2/backend.py` | run-local writable tool home、JG Tcl/日志/trace、逐 property 核算经验 | 提取小型 process/JG adapter；不让 V5 依赖 `CoupledL2Workspace`、profile 或 TileLink contract |
| `src/coupledl2/property_review.py` | `reviewer: codex`、asset hash 与 evidence refs 的审批边界 | 迁移设计，不直接复用 CoupledL2 asset kind 枚举 |
| `src/core/llm_router.py` | PRO/FLASH routing、run/stage token ledger | V5 authoring/diagnosis controller 直接调用；Stage 2 不构造 LLM client |
| `src/core/records.py` | cost summary 与标准 stage result envelope | 保留并补充 `round_id`、`model_calls` 的使用约束 |

### 2.2 不能直接复用的现有能力

1. `main.py run`、`CoupledL2RunConfig` 和 `CoupledL2Runner` 把 case、profile、stage 名称及 workspace 形状绑定到 CoupledL2。
2. `src/coupledl2/indexer.py` 是 TileLink 正则索引，只可靠覆盖少量 `Bool/UInt` 和 channel pattern；它不能充当 V5 的 Chisel Semantic Index。
3. `src/coupledl2/assertion_renderer.py` 依赖 profile marker、固定 target 和字符串模板，不支持通用 generator/config、typed expression 或独立 wrapper。
4. `src/coupledl2/rtl_property_labeler.py` 的 exact label 思路可保留，但当前 API 依赖 CoupledL2 catalog、base-label 规则与 source suffix。
5. `src/core/workflow.py` 是大型通用编辑 agent loop，允许较宽的文件工具和自由代码修改；V5 Stage 1 只应接受三个 typed candidate submission，不能给模型 `edit_file` 或任意 Scala 写入能力。
6. `src/core/jaspergold_runner.py` 与 `src/core/jaspergold_quality.py` 分别面向旧交互流和 evaluation quality；都不能直接作为 V5 的 exact operation backend。

### 2.3 当前 checkout 的两个 P0 阻塞

#### P0-A：source locator 被现有 benchmark emitter 删除

`benchmark/synth` 的现有 Chisel emitter 普遍使用 `--strip-debug-info`。这适合 translation/SEC 产物，却会破坏 V5 所需的 source-to-emitted identity。

处理方式：

- 不修改现有 `make verilog` 和 SEC 路径的含义；
- V5 在 run-local workspace 生成独立 `EmitSpecFlow`；
- verification elaboration 禁止 `--strip-debug-info`；
- preflight 检查 emitted property 是否仍有 overlay source locator；缺失即 fail closed；
- source locator 只用于 deterministic identity，generated RTL 不进入 Stage 1 模型上下文。

#### P0-B：benchmark source 含有可泄漏的 variant/bug 信息

当前 `FirstCounter.scala` 同时包含 reference、多个 buggy variant 及解释 bug 的注释。直接把整文件交给 Stage 1 会污染 bug-detection 实验。

处理方式：

- production flow 本身不认识 `reference`、`buggy`、trigger 或 diff；
- project contract 声明 `model_visible_roots`，Stage 1 只能通过 source ID 读取这些根；
- counter vertical slice 将 variant registry/evaluator metadata 与 model-visible generator source 分开；
- CEX target 使用 opaque configuration ID；authoring 模型不读取 hidden evaluator manifest；
- 对 buggy target 的主验收优先采用“冻结 reference run 的 reviewed package，Stage 2-only replay”方式，避免根据 bug source 定向创作性质。

## 3. 目标目录与职责

```text
src/
  core/
    artifact_contract.py        # 从 coupledl2 抽出的通用 artifact/handoff
    formal_operations.py        # 无领域语义的 exact-set accounting
  chiselspecflow/
    __init__.py
    config.py                    # 输入、build、formal、visibility contract
    stages.py                    # 三个 StageSpec
    workspace.py                 # isolated run、round、resume 与 manifest
    source_index.py              # Scala source facts 的 Python adapter
    semantic_index.py            # source facts + baseline elaboration facts 合并
    assets.py                    # reusable/run-local/promoted 资产加载与 hash
    review.py                    # run review、diagnosis review、promotion gate
    authoring.py                 # Stage 1 bounded model controller
    authoring_tools.py           # 三个 strict named-tool schema
    monitor_compiler.py          # Monitor IR -> bounded Chisel overlay AST/source
    elaboration.py               # clean build、verification emitter、certificate
    property_identity.py         # source property -> exact emitted property
    backend.py                   # operation plan -> JG -> raw exact results
    result_contract.py           # V5 semantic/evidence reducer
    trace_projection.py          # exact trace -> bound object/monitor state
    diagnosis.py                 # Stage 3 candidate classification/revision
    runner.py                    # lifecycle、round progression、final verdict
    ir/
      obligation.py
      semantic.py
      binding.py
      monitor.py
      expression.py
    property_assets/
      obligation_schemas/
      monitor_archetypes/
      api_adapters/
      reviews/
  specflow_evaluation/           # 与生产 authoring/verification 隔离
    task_contract.py             # circuit--spec task 与 hidden evaluator schema
    split_contract.py            # asset-development/held-out split freeze
    baseline_adapter.py          # P0--P6 同任务输入输出适配
    equivalence_evaluator.py     # strict/partial FER
    mutation_evaluator.py        # BKR/FAR 与 valid-mutant gate
    diagnosis_evaluator.py       # 条件性 Track D ranking 评分
    metrics.py                   # property/task-level 指标归约
    statistics.py                # paired CI/test/effect size/Holm
    report.py                    # 三张主表与逐 task 失败账本
tools/
  chisel-source-indexer/
    build.sbt
    src/main/scala/chisellmfv/indexer/Main.scala
tests/
  test_specflow_*.py
  test_specflow_eval_*.py
```

不在 `src/coupledl2/` 下建立 V5 adapter、reader 或 schema。CoupledL2 只在通用 primitive 抽取时更新 import；它的 active artifact 名称与四阶段 contract 不因 V5 改写。

`src/specflow_evaluation/` 不是第四个生产 stage，也不被 `src/chiselspecflow/` 导入。依赖方向只能是 evaluator 调用公开的 SpecFlow CLI/读取完成 artifacts，不能让 production runner 读取 hidden oracle、baseline result 或论文统计。

## 4. 输入与 CLI contract

### 4.1 `specflow_project.v1`

每个 target 由一个显式 project contract 描述。最小字段如下：

```json
{
  "schema_version": "specflow_project.v1",
  "project_id": "counter",
  "project_root": "benchmark/synth/counter",
  "source_roots": ["src/main/scala"],
  "model_visible_roots": ["src/main/scala"],
  "build": {
    "kind": "sbt",
    "compile_argv": ["sbt", "compile"],
    "overlay_source_root": "src/main/scala/chisellmfv/generated",
    "elaborate_main": "chisellmfv.generated.EmitSpecFlow",
    "generated_sv_globs": ["specflow-generated/**/*.sv"]
  },
  "generator": {
    "package": "withw.counter",
    "constructor": "new FirstCounter(CounterVariant(...))",
    "top_name": "SpecFlowTop",
    "configuration_schema": "counter_config.v1"
  },
  "formal": {
    "clock": "clock",
    "reset": "reset",
    "reset_active_high": true,
    "backend": "jaspergold"
  }
}
```

约束：

- 命令保存为 argv，不接受模型生成的 shell string；
- `constructor`、imports 和 build argv 属于 user/repository-owned build contract，必须被 hash；
- 模型只能选择已声明的 configuration ID/parameter value，不能生成 constructor；
- `model_visible_roots` 必须是 `source_roots` 的子集；Stage 1 source tool 拒绝其他路径；
- Verilog、generated、formal results、hidden evaluator metadata 永不进入 Stage 1 prompt bundle。

### 4.2 `public_spec_package.v1` 与 suite freeze

当前 checkout 已有 11 份 reviewed public spec：

```text
benchmark/synth/SPECIFICATIONS.md
benchmark/synth/SPECIFICATIONS.sha256
benchmark/synth/<family>/specflow/spec.md
```

实现不再“新建”这些规范文本，而是新增 deterministic suite validator，把现有 Markdown authority snapshot 解析为只含元数据和引用的 `public_spec_package.v1`：

```text
specification_id
version
family
difficulty                S | M | L
spec_path
spec_sha256
suite_ledger_sha256
authority_refs[]
configuration_refs[]
normative_clause_ids[]
expected_property_ids[]
allowed_assumption_ids[]
review {reviewer, reviewed_at, decision}
visibility                 public
```

约束：

- `SPECIFICATIONS.sha256` 必须先通过整套校验；单个 spec hash 漂移时所有正式实验停止；
- Markdown 中的 normative clause、expected property、assumption 与 review ID 必须唯一；
- parser 只抽取稳定标识与 section boundary，不把自然语言 clause 提前翻译成 hidden gold obligation；
- spec 内容只读，baseline 或 V5 失败不得回写当前版本；语义修改必须升版、重新 review 并刷新 suite ledger；
- `public_spec_package.v1` 可进入模型上下文；hidden evaluator manifest 永不进入 production run。

每个正式 task 另有 evaluator-only `circuit_spec_task.v1`：

```text
task_id
public_spec_sha256
project/config/formal hashes
reference_target
buggy_targets[]
gold_obligations[]
gold_bindings[]
expected_mutant_relations[]
diagnosis_oracles[]
sec_evidence_refs[]
split
```

该文件位于 production workspace 之外，只能由 `src/specflow_evaluation/` 读取。SpecFlow run manifest 最多记录 opaque `task_id` 和 public spec hash，不记录 hidden 字段、路径或数量。

### 4.3 Production CLI

在 `main.py` 新增嵌套命令，不复用 `run`：

```text
python main.py specflow start \
  --project-contract <project.json> \
  --spec <spec.md|json> \
  --config <config.json> \
  --run-root runs/specflow

python main.py specflow review \
  --run <run_dir> \
  --review-record <review.json>

python main.py specflow resume \
  --run <run_dir> \
  --through compile_verify|diagnose
```

行为：

- `start` 创建 workspace、执行 deterministic scan 和 Stage 1 authoring，通常停在 `awaiting_review`；
- `review` 只校验并安装外部人工/Codex review，不调用模型；
- `resume` 验证 manifest、source/config/index/review hash 后继续；
- 若 Stage 3 请求修订，`resume --new-round` 创建下一 immutable round；
- 不提供 `--auto-approve`、模型 review tool 或绕过 hash 的 `--force`。

### 4.4 Evaluation CLI

评价入口与 production CLI 分开：

```text
python main.py specflow-eval validate-suite \
  --suite-manifest <suite.json>

python main.py specflow-eval run-track-p \
  --experiment <experiment.json> \
  --method P0|P1|P2|P3|P4|P5|P6

python main.py specflow-eval run-track-d \
  --experiment <experiment.json> \
  --method tarsel|wit-hw|pecker|v5

python main.py specflow-eval summarize \
  --experiment-root <root>
```

`specflow-eval` 可以为 RTL/SVA baseline 物化同配置 emitted RTL，但该输入视图与 V5 Stage 1 workspace 物理隔离。任何 baseline adapter 都必须保存实际可见文件 manifest，不能只在报告中声称“同输入”。

## 5. Run workspace 与不可变 round

```text
runs/specflow/<timestamp>-<project>-<id>/
  manifest.json
  inputs/
    specification
    project_contract.json
    configuration.json
    input_hashes.json
  workspace/
    project/                    # 隔离副本
  indexes/
    source_index.json
    baseline_elaboration.json
    chisel_semantic_index.json
  logs/
    events.jsonl
    model_calls.jsonl
  rounds/
    0001/
      01_asset_authoring/
      02_compile_verify/
      03_diagnose/
    0002/
      ...
  final_result.json
  run_cost_summary.json
```

`manifest.json` 使用 `specflow_run_manifest.v1`，至少记录：

- input/spec/project/config SHA-256；
- copied workspace hash；
- visible source root allowlist；
- Chisel/Scala/CIRCT/sbt 版本；
- current round、parent round 与 revision request hash；
- stage contracts；
- review state；
- 可选的 opaque evaluation task ID 与 public spec hash；不记录 hidden evaluator 的存在性、
  路径、字段数量或内容。

任何修订都创建新 round，不覆盖旧 obligation、property package、certificate、proof 或 diagnosis。Stage handoff 只保存路径、hash 和少量状态，不复制业务 payload。

### 5.1 Evaluation workspace

正式实验使用独立根目录，不能把 hidden oracle 复制进任一 method run：

```text
runs/specflow-eval/<experiment_id>/
  experiment_manifest.json
  suite_snapshot/
    public_spec_manifest.json
    generalization_split.json
    baseline_registry.json
  evaluator_private/             # 仅 evaluator process 可读
    tasks/<task_id>.json
  methods/<method_id>/
    seeds/<seed>/
      input_visibility_manifest.json
      run_ref.json
      method_output.json
  scores/
    properties.jsonl
    tasks.jsonl
    diagnosis.jsonl
  statistics/
    paired_intervals.json
    paired_tests.json
  reports/
    track_p.md
    track_d.md
    ablation.md
    failures.jsonl
```

`experiment_manifest.v1` 在第一个 method run 前冻结：task set、split、method versions、model、temperature、五个 seed、token/tool/repair budget、formal contract、timeout、CPU/memory 和 evaluator version。任何变化生成新 experiment ID，不在原结果目录增量覆盖。

### 5.2 Evaluation row contract

评测不能从 Markdown 表格反推结果。至少冻结以下 machine-readable rows：

- `baseline_registry.v1`：`method_id`、方法名、`official_artifact | paper_faithful |
  reported_only`、commit/adapter hash、允许输入视图、模型与 budget policy；
- `method_run.v1`：task、method、seed、实际 input manifest hash、status、cost、人工
  intervention、输出 artifact refs；
- `property_score.v1`：generated property identity、syntax/elaboration、strict/partial
  equivalence、clean FPV outcome、non-vacuity gates、gold-obligation matches；
- `mutation_score.v1`：mutant identity、translation SEC、clean non-equivalence、eligibility、
  killed-by exact property IDs；
- `task_score.v1`：所有 metric 的 numerator、denominator、exclusion rows、macro group 和
  completeness；
- `diagnosis_score.v1`：仅 Track D 使用，记录共同 CEX、candidate universe、first true
  rank、Top-K、EXAM、source projection 和 no-answer。

`metrics.py` 只从上述 raw rows 归约，`report.py` 不重新实现指标。每个 aggregate 保存
input-row-set hash；人工 review/edit 时间、token/tool calls、formal wall time 与失败成本都进入
cost rows，不能只统计成功 run。

## 6. 四层 IR 的实现 contract

四层 IR 分文件、分 validator、分 schema version。不得重新合成一个允许任意字段的 `verification_ir.json`。

### 6.1 `verification_obligations.v1`

每条 obligation 的必需字段：

```text
obligation_id
clause_ref {spec_sha256, locator, text_sha256}
family
polarity                 guarantee | assumption
entities[]
trigger                   typed expression/event IR
guard                     typed expression IR
expected                  relation IR
temporal {kind, min_cycles, max_cycles}
reset_semantics
observation_roles[]
configuration_domain
support_status             candidate | supported | unsupported | ambiguous
authoring_provenance       model call、reused asset、review refs
```

首轮 family 只允许：

- `combinational_mapping`；
- `reset_initialization`；
- `state_transition`；
- `stability`；
- `cardinality`；
- `bounded_response`。

未知 family 必须输出 `unsupported`，不能降级为自由文本 assertion。

### 6.2 `chisel_semantic_index.v1`

每个 object row 必需字段：

```text
object_id
source_anchor {path, line_start, line_end, enclosing_symbol, source_sha256}
hardware_kind             port | reg | wire | enum | aggregate | instance | derived_event
scala_hardware_domain     elaboration | hardware | mixed | unknown
chisel_type {kind, width, signed, fields, index_domain}
direction                 input | output | internal | none
owner_module
guard_context {
  elaboration_conditions[],
  hardware_guards[]
}
clock_reset {
  clock_domain,
  reset_domain,
  reset_kind
}
configuration_condition
accessibility             direct | wrapper | layer | probe | boring | unavailable
fact_status               source_candidate | elaboration_confirmed | ambiguous
evidence_refs[]
```

索引采用两级事实源：

1. `tools/chisel-source-indexer` 用 ScalaMeta AST 提取 package/class/object/val、IO、Reg/Wire、Bundle/Vec/Enum、Scala `if` 与 Chisel `when/switch` 的 source candidates；
2. clean baseline elaboration 提供真实 top、ports、width、instances、clock/reset 和 configuration existence。

`semantic_index.py` 只在 source anchor、owner、type 与 elaboration evidence 一致时把 row 标为 `elaboration_confirmed`。正则结果只能作为辅助 candidate，不能单独满足 binding gate。无法确定 width、domain 或 owner 时保留 `unknown/ambiguous` 并禁止相关 binding。

### 6.3 `chisel_bindings.v1`

每个 binding row：

```text
binding_id
obligation_id
semantic_role
object_id
instance_selector
configuration_domain
compatibility {
  type,
  width,
  ownership,
  clock,
  reset,
  configuration
}
acquisition {strategy, host_scope, adapter_id}
rationale
rejected_alternatives[]
review_state
```

`validate_binding()` 必须逐项返回 machine-readable error：

- `object_not_elaboration_confirmed`；
- `type_mismatch`；
- `width_mismatch`；
- `owner_unreachable`；
- `clock_domain_mismatch`；
- `reset_semantics_mismatch`；
- `configuration_not_applicable`；
- `observer_strategy_unsupported`。

错误最多允许一次 bounded candidate repair；不得通过拼接 Scala path 猜测内部对象。

### 6.4 `chisel_monitors.v1`

Monitor IR 不保存自由 Scala，而保存：

```text
monitor_id
obligation_id
archetype_id + archetype_sha256
binding_refs[]
state[] {state_id, type, init, update, clear}
properties[] {
  source_property_id,
  role,
  expression_ir,
  guard_ir,
  expected_label
}
reset_policy
overlay {strategy, wrapper_top, host_scope}
required_observations[]
configuration_domain
```

`expression_ir.v1` 使用有界 typed AST：literal、object ref、not/and/or、eq/neq、
unsigned/signed comparison、add/sub、mux、past-valid、previous-value、onehot/popcount、
bit-select/slice 与 bounded counter relation。所有 operator 在 lowering 前做 type、width 和
index-bound 检查；不存在 raw Scala/text escape。

### 6.5 `elaboration_certificate.v1`

Certificate 连接 reviewed package 与 emitted formal model：

```text
verification_package_sha256
project/config/workspace/build hashes
toolchain versions
overlay source hashes
top module
elaborated instances[]
properties[] {
  source_property_id,
  expected_label,
  property_kind,
  overlay_source_anchor,
  obligation_id,
  binding_refs,
  emitted_property_id,
  rtl_file_sha256,
  rtl_line,
  elaborated_instance
}
observations[]
identity_failures[]
certificate_sha256
```

同一个 expected label 缺失、出现多次、被优化掉或无法连接到 exact source locator，Stage 2 都必须停止，不进入 JG。

## 7. Stage 1：`asset_authoring`

### 7.1 输入与模型可见边界

模型只收到：

- spec clause slices；
- project/config 的公开摘要；
- `chisel_semantic_index.v1` 中允许公开的 object rows；
- reviewed obligation schema、monitor archetype 与 API adapter 摘要；
- 上一 round 的 typed revision request（若有）。

模型不收到：

- generated Verilog/FIRRTL 路径或内容；
- JasperGold property name；
- hidden benchmark label、bug diff、trigger 或正确版本；
- repository review 写工具；
- shell、任意文件读取或 `edit_file`。

### 7.2 三个 named tools

`authoring_tools.py` 只注册三类候选写工具：

1. `submit_obligation_candidates`；
2. `submit_binding_candidates`；
3. `submit_monitor_candidates`。

另有 `report_spec_ambiguity`，它是终止结果而非第四种写入路径。每次调用要求：

- `strict: true`；
- exactly one function call；
- `parallel_tool_calls=false`；
- enum/ID 来自本次 stage inputs；
- 无 source code 字段；
- malformed JSON 最多一次协议修复。

Stage 1 controller 的主流程：

```text
build_stage_inputs()
  -> request obligations
  -> validate obligations
  -> request bindings from compatible object IDs
  -> validate bindings
  -> request monitors/archetypes
  -> compile Monitor IR statically
  -> write candidate package + review request
  -> awaiting_review
```

### 7.3 Review gate

Stage 1 产物分为：

- `authoring_candidates.json`：模型候选与 provenance；
- `candidate_asset_delta.json`：相对 reviewed library 的新增/修改；
- `review_request.json`：所有待审 hash 与 evidence；
- `review_record.json`：外部 reviewer 写入；
- `verification_package.json`：仅在 review 通过后生成的 canonical package。

`review_record.v1` 必需：

```text
reviewer                  codex | human:<id>
decision                  approved | rejected
reviewed_hashes[]
evidence_refs[]
semantic_intent_decisions[]
reviewed_at
reason
```

若 reviewer 是 API model、hash 不匹配、evidence 为空或只审批 schema 而未审批本 run 的 binding/monitor intent，则不能产生成功 handoff。

### 7.4 Stage 1 artifact contract

成功 handoff 必须包含：

```text
stage_inputs.json
authoring_candidates.json
candidate_asset_delta.json
review_record.json
verification_package.json
```

`awaiting_review` 状态只写 `stage_result.json` 和失败关闭的 handoff，不伪装成成功 completion。

## 8. Monitor compiler 与 Chisel overlay

### 8.1 首轮 overlay 策略

Milestone 1 只实现 `wrapper`：

- 在 copied project 的 `overlay_source_root` 生成完整 `SpecFlowOverlay.scala`；
- wrapper 实例化 user-owned constructor；
- 根据 Semantic Index 复制并连接 DUT primary inputs；
- 直接读取 DUT outputs；
- monitor state、assert/assume/cover 全部位于 wrapper；
- 不修改原 DUT 文件，不插 marker，不在生成 RTL 上创作 SVA。

后续策略按独立 adapter 增加：`same_module`、verification layer、probe/boring。未实现的 acquisition strategy 直接 unsupported，不回退到 guessed RTL path。

### 8.2 `monitor_compiler.py`

主要 API：

```text
validate_monitor_ir(monitor, semantic_index, asset_library)
lower_expression(expr_ir, typed_bindings) -> ChiselExpr
lower_monitor(monitor_ir, project_contract) -> OverlayUnit
render_overlay(units, project_contract) -> RenderedOverlay
```

`ChiselExpr` 和 `OverlayUnit` 是内部 typed dataclass，不接收任意 raw Scala。只有 repository-owned archetype 可提供固定 Scala skeleton；model candidate 只能组合 DSL node。

编译输出：

- `SpecFlowOverlay.scala`；
- `source_assertion_delta.json`；
- `overlay_manifest.json`；
- `overlay_diff.patch`，只用于审计 run-local 新文件；
- 每条 source property 的 overlay line/column 和 expected label。

### 8.3 首批 archetype

| Archetype | Monitor state | 主要用途 | 必需 evidence operations |
|---|---|---|---|
| `direct_relation.v1` | 无 | combinational、state legality、cardinality | primary + activation + observer |
| `previous_value.v1` | valid bit + previous value | next-cycle、hold、stability | primary + activation + state + observer |
| `bounded_counter.v1` | active + counter | bounded response | primary + activation + state + assumption-sat |

Milestone 1 只要求前两个；`bounded_counter` 在 Milestone 2 接入。Scoreboard/lifecycle 不提前塞入首个 vertical slice。

## 9. Stage 2：`compile_verify`

### 9.1 强制零模型调用

`CompileVerifyStage` 构造函数不接收 `llm_client`。runner 对该 stage 不创建 model routing context，stage result 固定记录 `model_calls: 0`。测试使用 poison client，任何模型访问立即失败。

### 9.2 执行顺序

```text
validate reviewed verification_package
  -> render overlay transactionally
  -> clean compile
  -> verification elaboration with source locators
  -> build elaboration certificate
  -> build exact operation plan
  -> prepare run-local JG input/Tcl
  -> execute every operation
  -> exact-set accounting
  -> semantic evidence reduction
```

任何前置步骤失败都仍要产生总账：计划中尚未执行的 operation 记录为 `not_run` 或 `tool_error`，但 stage success 不能被解释为 semantic acceptance。

### 9.3 Property identity

将 `rtl_property_labeler.py` 的思想重写到 `property_identity.py`：

- expected label 由 `source_property_id + role + target` 的 canonical hash 生成，前缀 `CSF_`；
- 匹配依据是 overlay source locator、property kind、expected label token 与 elaborated instance；
- 对匹配的 emitted property 赋唯一 backend label；
- certificate 记录赋值前后 identity；
- 不通过 generated signal similarity 或 suffix fallback 识别 property；
- 不把该映射暴露给 Stage 1。

### 9.4 `verification_operation_plan.v2`

每条 primary property 按 Monitor IR 声明 exact roles：

```text
primary_assertion
activation_cover
observer_cover
state_cover
assumption_sat
negative_oracle          # 后续 milestone，可选
```

每个 operation row 必需：

```text
operation_id
source_property_id
obligation_id
role
target
emitted_property_id
expected_statuses[]
trace_required
budget_class
certificate_sha256
```

空 assumption set 不生成伪造的 `cover(true)`；该 monitor 的 required roles 中不包含 `assumption_sat`，并在 semantic evidence 中记录 `not_applicable:no_assumptions`。

### 9.5 `property_result_map.v5`

operation 状态只允许：

```text
proven
cex
covered
unreachable
inconclusive
timeout
not_run
tool_error
missing
```

结果分层：

- `execution_status`: completed | partial | tool_error；
- `formal_outcome`: all_proven | cex | inconclusive | not_run；
- `evidence_status`: complete | vacuous | incomplete | invalid；
- `semantic_candidate`: supported | violated_candidate | inconclusive；
- `final_verdict` 不在 Stage 2 产生。

primary `proven` 只有在 activation/observer/state/assumption required roles 全部满足时，才得到 `semantic_candidate=supported`。`unreachable` 不等于 `proven`，operation 数量对齐也不等于 evidence complete。

### 9.6 Stage 2 artifact contract

```text
verification_package_ref.json
overlay_manifest.json
source_assertion_delta.json
elaboration_certificate.json
verification_operation_plan.json
property_result_map.json
semantic_evidence.json
trace_manifest.json
proof_events.jsonl
jaspergold.log
```

## 10. Stage 3：`diagnose`

### 10.1 Deterministic evidence projection

`trace_projection.py` 先完成：

- trace hash 与 operation/certificate identity 校验；
- cycle sampling 与 reset-valid window；
- emitted observation 到 source binding/object ID 的 exact join；
- monitor state transition reconstruction；
- obligation trigger/guard/expected relation 的逐周期求值；
- source slice refs 与 spec clause refs 生成。

输出 `evidence_projection.v1`。存在 missing signal、identity mismatch、非法环境或无法判定的 monitor state 时，projection 标记 incomplete；模型不能补猜。

### 10.2 模型参与条件

以下情况不调用模型：

- 所有 primary proven，且 evidence gates complete；
- Stage 1 已明确 unsupported/ambiguous；
- identity/tool failure 已机械判定 inconclusive。

只有 CEX、vacuity、binding uncertainty 或多原因 inconclusive 才调用模型。模型提交 `diagnosis_candidate.v1`，classification 只允许：

```text
design_violation
obligation_error
binding_error
monitor_error
assumption_error
tool_or_identity_error
inconclusive
```

每个结论必须引用 exact operation、trace cycle、object ID、source anchor 和 spec clause。模型不能直接写 `final_verdict=violated`。

当实验以 Track D 模式运行时，`diagnosis_candidate.v1` 还必须提交
`ranked_source_candidates[]`。每个候选只包含稳定的 `candidate_id`、Chisel
source anchor、统一 RTL 粒度位置、并列 rank group 和 evidence refs；不能用自由文本
文件名猜测替代 source projection，也不能把模型置信度直接当最终排名。deterministic
reviewer 负责去重、检查位置映射和执行固定的 tie rule。

### 10.3 Deterministic verdict reducer

`final_verdict.v1` 只允许：

- `accepted`：Stage 1 semantic intent 已审、certificate exact、operation complete、primary proven、required evidence gates 全部通过；
- `violated`：exact primary CEX、合法环境、projection complete，且 design-violation diagnosis 经 reviewer 审批；
- `inconclusive`：timeout、tool error、identity incomplete、证据不足或 diagnosis 未审批；
- `unsupported`：spec ambiguity、unsupported family、不可观察对象或不支持的 configuration/clock domain。

模型 classification 本身不是最终结论。对 property/binding/monitor/assumption error，Stage 3 生成 `revision_request.v1`：

```text
revision_layer
old_asset_sha256
evidence_refs[]
allowed_change_scope
parent_round
```

runner 只有在显式 `--new-round` 时创建下一 round；设计源码不被自动修改。

### 10.4 Stage 3 artifact contract

```text
evidence_projection.json
diagnosis_candidate.json
diagnosis_review.json
source_ranking.json
revision_request.json
final_verdict.json
counterexample_analysis.md
```

无 CEX 时 `diagnosis_candidate` 与 `diagnosis_review` 使用明确的 `not_required` object，不能省略文件或伪造模型调用。
`source_ranking.json` 始终属于 contract：普通 production run 或无有效 CEX 时写入
`not_required`；启用 Track D 时记录 reviewed candidate ordering、并列规则、Chisel-to-RTL
映射和 evaluator-consumable ranks。这样 Track D 不需要另建一条不可恢复的诊断路径。

## 11. Generic core 抽取方式

### 11.1 `src/core/artifact_contract.py`

从 CoupledL2 移动：

- `StageArtifactError`；
- `file_sha256()`；
- `validate_stage_artifacts()`；
- `write_stage_outcome()`；
- `validate_completed_stage()`。

用一个最小 `StageContract` Protocol 约束 `name` 与 `artifact_contract`，不依赖任何具体 stage enum。完成后删除 `src/coupledl2/artifacts.py`，统一更新 import；不保留双份实现或 re-export compatibility shim。

### 11.2 `src/core/formal_operations.py`

只放领域无关函数：

- `canonical_sha256()`；
- `stable_operation_id(parts)`；
- `validate_expected_operation_set()`；
- `materialize_missing_rows()`；
- `join_exact_operation_rows()`。

状态枚举、role、semantic gate 和 schema version 仍由各 workflow 的 `result_contract.py` 定义。这样 V5 不复制 exact-set 算法，也不迫使 CoupledL2 接受 V5 schema。

### 11.3 JG backend

Iteration 0 不做第三次“大一统 runner”。先在 `src/chiselspecflow/backend.py` 组合：

- 通用 process launch/writable tool-home helper；
- V5 自己的 Tcl renderer；
- `parse_jaspergold_report()` 的通用化版本；
- `formal_operations.py` exact join。

等 counter 与至少一个 controller 都跑通后，再判断是否把 process/Tcl helper 下沉到 `src/core/`。不能为了抽象而同时重写三个现有 JG runner。

## 12. Public spec suite 与 Counter vertical slice

### 12.1 当前 suite 是输入基线，不是待实现项

当前 checkout 已有 11 个 Chisel 6.7.0 family 的 reviewed public spec，并由 `benchmark/synth/SPECIFICATIONS.sha256` 冻结：

| 难度 | Family | Public spec | 首要实现语义 |
|---|---|---|---|
| S | `alu` | `alu/specflow/spec.md` | combinational/data/flags |
| S | `decoder_3_to_8` | `decoder_3_to_8/specflow/spec.md` | truth table/active-low |
| S | `counter` | `counter/specflow/spec.md` | reset/next-state/overflow |
| S | `fsm_16` | `fsm_16/specflow/spec.md` | complete state transition |
| M | `arbiter` | `arbiter/specflow/spec.md` | capture/ordering/grant |
| M | `led_controller` | `led_controller/specflow/spec.md` | timed controller FSM |
| M | `i2c` | `i2c/specflow/spec.md` | WISHBONE/I2C lifecycle |
| M | `sdram_controller` | `sdram_controller/specflow/spec.md` | init/refresh/read/write |
| L | `reed_solomon_decoder` | `reed_solomon_decoder/specflow/spec.md` | algorithmic relation |
| L | `sha3` | `sha3/specflow/spec.md` | padded Keccak relation |
| L | `gigamax` | `gigamax/specflow/spec.md` | coherence/interlock/liveness |

Iteration 1 的 spec validator 必须直接消费这套已存在的 authority snapshot，并先验证 suite ledger。它不从 README、reference RTL、Chisel behavior 或 run result重建规范。

### 12.2 Model-visible source materialization

11 个 family 的 translation source 仍可能把 selected generator、variant registry 和 evaluator-oriented comment 放在同一 Scala 文件。每个 `project.json` 必须实现 `materialize_model_view(config_id)`：

- 只保留单一 target configuration 所需的 Chisel source；
- 删除/隔离 reference/buggy 命名、variant 列表、mutation comment 和 evaluator helper；
- 保留 package、type、generator 与 build 所需语义；
- 输出 `model_view_manifest.v1`，逐文件记录 source path/hash 与变换 provenance；
- 对 materialized view 与真实 selected target 做 elaboration/SEC 或结构一致性 gate；
- authoring source tool 只能读取 manifest 中的 source IDs。

若某个 family 无法建立无泄漏 single-config view，该 task 记录 `unsupported:model_view_not_isolated`，不能直接把整个 translation file 交给模型。

### 12.3 Counter vertical slice

Counter 目录补充 project/config contract，但复用现有 reviewed `spec.md`：

```text
benchmark/synth/counter/specflow/
  spec.md                         # 已存在，suite ledger 冻结
  project.json                    # 待实现
  configs/cfg_000.json            # opaque clean target
  configs/cfg_001.json            # opaque faulty target
```

Hidden cfg-to-variant mapping、gold obligation、gold binding、mutation relation 和 diagnosis oracle 存在独立 evaluator package，不放入上述 public 目录。

首轮 reviewed verification package 至少包含：

1. `CTR-N-011` enabled next-state obligation；
2. history-valid/reset guard；
3. `enable && !reset` activation cover；
4. `counter_out` observer cover；
5. previous-value monitor 的 valid/state cover。

验收分两次 Stage 2：

- opaque clean config：primary proven，所有 required covers 可达；
- frozen package + opaque faulty config：同一 exact source property 得到 CEX；
- 两次 run 的 obligation、monitor archetype 与 binding semantic IDs 相同，仅 configuration/elaboration certificate 不同；
- authoring model call log 和 input visibility manifest 中不存在 `buggy`、diff、private trigger、Verilog path、gold mapping 或 expected verdict；
- evaluator 独立计算 Strict FER、NVR、BKR/FAR 所需原始 rows，但这些结果不回流 Stage 1。

### 12.4 Family-specific evaluation boundary

- 前 10 个 Wit-HW-derived family 的 reference 与 buggy Chisel translation 必须分别对其
  原始 Verilog/SMV target 通过 SEC；buggy target 还必须对 clean reference 通过
  non-equivalence/trigger-validity gate，才进入 BKR denominator；
- Gigamax 当前只有 error-bearing variants，在获得 reviewed clean implementation 前只进入 bug-detection/liveness challenge，不计 FAR 或 clean-reference FPV denominator；
- reference Verilog/SMV 只用于 corpus integrity 与 baseline-specific emitted-RTL view，不是 V5 的 authoring authority；
- public expected-property table 可以被方法读取，但 hidden clause-to-gold-obligation equivalence map 只能被 evaluator 读取。

## 13. 分步实施计划

### Iteration 0：冻结 contract 与抽取通用 primitive

改动：

- 新增 `src/core/artifact_contract.py`、`src/core/formal_operations.py`；
- 原子更新 CoupledL2 import 与测试；
- 新增 `src/chiselspecflow/{config,stages,workspace}.py`；
- 冻结本文件所列 schema version、stage artifact contract 与 verdict enum；
- 在 `main.py` 增加 `specflow --help`，但尚不承诺真实 run。

Gate：

- 当前 V4 focused regression 不退化；
- generic primitive 单元测试覆盖 missing、duplicate、unexpected row 和 hash mismatch；
- `python -m compileall`、`git diff --check` 通过；
- 不出现旧模块 re-export 或双写 handoff。

### Iteration 1：Project input、visibility 与 Chisel Semantic Index

实现状态（2026-07-18）：已完成。

当前落点：

- `config.py` 与 `specification.py` 已实现 exact-field project/config 校验、整套
  `SPECIFICATIONS.sha256` 先验校验和 `public_spec_package.v1` 元数据抽取；
- `workspace.py` 已实现 fail-if-existing 的原子隔离副本、immutable round、输入/hash
  manifest，以及只包含 positive allowlist source ID 的 `model_view_manifest.v1`；
- `tools/chisel-source-indexer` 已使用 ScalaMeta AST 识别 IO、Reg/Wire、source anchor、
  owner、type/width，以及 Scala `if` 与 Chisel `when/elsewhen` 的 guard domain；
- `elaboration.py` 使用无 `--strip-debug-info` 的 verification-only emitter 执行真实 clean
  compile/elaborate，`semantic_index.py` 只在 source anchor、owner、width、direction 和 emitted
  locator 一致时标记 `elaboration_confirmed`；
- Counter 的模型可见 `FirstCounter.scala` 已与 `CounterVariants.scala` registry 物理分离，
  新增 `project.json`、`cfg_000.json`、`cfg_001.json` 和 `EmitSpecFlow.scala`；原有
  `EmitCounterVariants` 的 reference SV 与改动前产物逐字节一致；
- `prepare_iteration1_workspace()` 只完成 deterministic preflight/index，仍不伪装成尚属
  Iteration 2 的 Stage 1 authoring；因此 `main.py specflow` 暂时继续保持 help-only 边界。

验收证据：`tests/test_specflow_*.py` 11 passed（包含真实 `sbt compile`、真实 Chisel
elaboration、五个 Counter object 的 exact confirmation 与 guard-domain hostile checks）；V4
focused regression 29 passed；11-family suite checksum、`compileall` 与 `git diff --check`
通过。未知 width/owner、source hash 漂移、额外/缺失 config field、model-view 泄漏 token、
source locator 缺失和重复 round 均 fail closed。

改动：

- 实现 project/config/spec validator；
- 实现 isolated workspace 与 immutable round；
- 新增 ScalaMeta source indexer；
- 新增 baseline elaboration adapter 与 semantic merge；
- 添加 counter project contract；
- 处理 `--strip-debug-info` 与 hidden variant leakage。

Gate：

- counter reference clean compile/elaborate；
- index 精确识别 `enable`、`counter_out`、`overflow_out`、`counter`、`overflow` 的 source anchor、type、owner 和 hardware domain；
- Scala `if (variant.resetCounter)` 与 Chisel `when(reset.asBool)` 被分到不同 guard domain；
- model-visible bundle 只引用 allowlisted Chisel source；
- 对未知 width/owner 的 fixture fail closed。

### Iteration 2：Stage 1 authoring、review 与 direct/history compiler

实现状态（2026-07-18）：已完成。

当前落点：

- `ir/{obligation,semantic,binding,monitor,expression}.py` 已分别实现 exact-schema、typed
  expression、semantic fact、逐项 binding compatibility 与 archetype/role 校验；unknown object、
  width/type、owner、clock/reset、configuration 和 acquisition 错误均 fail closed；
- `authoring_tools.py` 只暴露三个 strict candidate submission 与终止型 ambiguity report，
  `authoring.py` 强制 exactly-one call、`parallel_tool_calls=false`，每步最多一次 bounded repair，
  并将所有 call outcome 写入 immutable `model_calls.jsonl`；模型没有 Scala、file、review 或
  approval 字段；
- `assets.py` 已分离 Codex-reviewed repository asset、reviewed run-local package 与显式 promotion
  gate；首批库资产固定为 obligation schema、`direct_relation.v1`、`previous_value.v1` 和
  Counter wrapper API adapter；
- `review.py` 要求外部 `codex | human:<id>` reviewer、exact candidate/review-request hashes、
  non-empty evidence，以及逐 obligation/binding/monitor 的 semantic-intent decision；未审 run
  保持 `awaiting_review`，审批后才生成唯一 canonical `verification_package.v1`；
- `monitor_compiler.py` 使用内部 `ChiselExpr`/`OverlayUnit` lowering typed AST，在 isolated copy
  的 `overlay_source_root` 新建 `SpecFlowOverlay.scala`，不修改 DUT；已支持 direct 与
  previous-value state、assert/activation/observer/state cover，并产出 source assertion delta、
  overlay manifest 和审计 patch；
- `main.py specflow start|review` 已开放 Iteration 2 的 production 边界；`compile_verify` 与
  `diagnose` 仍未伪装为可运行命令。

验收证据：`tests/test_specflow_*.py` 18 passed，其中 Counter mock-model authoring、未审停止、
hash-bound review、canonical package 与 previous-value overlay 使用真实 `sbt compile` 闭环；
V4 focused regression 29 passed。hostile checks 覆盖 raw Scala/approval smuggling、一次 repair
耗尽、repository/run review hash drift、binding type mismatch 与 expression width mismatch。

改动：

- 实现四层 IR validator 和 expression IR；
- 实现三个 named tools 与 bounded controller；
- 实现 reviewed/run-local/promotion 三类 asset loader；
- 实现 run review gate；
- 实现 `direct_relation` 与 `previous_value` overlay lowering。

Gate：

- mock model 只能提交 ID/typed IR，无法写 Scala 或 approve；
- malformed/unknown object/type mismatch 能产生明确 repair 或 unsupported；
- 未安装 review 时 run 停在 `awaiting_review`；
- hash-bound review 后生成唯一 canonical `verification_package.json`；
- 生成的 counter overlay 通过 `sbt compile`。

### Iteration 3：Stage 2 elaboration identity 与真实 formal

实现状态（2026-07-18）：已完成。license 恢复后已用 JasperGold 2020.03 fresh-run
reference proof 与 frozen-package faulty replay，并逐项复核 certificate、operation/result map、
semantic evidence、proof events、trace manifest、artifact contract 与 cross-run package identity。

当前落点：

- `monitor_compiler.py` 已将 primary property 修正为 guard implication，并在 run-local
  overlay 中生成 verification-only `EmitSpecFlowOverlay`；firtool 使用
  `--emit-chisel-asserts-as-sva` 与 `verifLabels`，保留 source locator，不修改 DUT；
- `elaboration.py` 与 `property_identity.py` 已完成 clean compile/elaborate、certificate 和
  exact property relabel：只依据 overlay source locator、property kind、reviewed label token
  与 wrapper top 建立 identity；locator 缺失、重复匹配、label 重复或文件 hash 漂移均 fail
  closed；
- `result_contract.py` 已实现 `verification_operation_plan.v2`、exact operation join、
  `property_result_map.v5` 和 `semantic_evidence.v3`，将 execution/formal/evidence/semantic
  四层状态分开，并要求 primary 的 observed property identity 与 trace membership 精确一致；
- `backend.py` 已实现小型 V5-only JG Tcl/process adapter、逐 operation 结果解析、CEX VCD/FST
  manifest、proof events 和 missing/timeout/tool-error 总账；`CompileVerifyStage` 构造函数不接收
  LLM client，stage result 固定记录 `model_calls: 0`；
- `runner.py` 已接入 `main.py specflow resume --through compile_verify`，进入 Stage 2 前重验
  input/index/source/review/package hashes，并支持从已审批 reference run 做 Stage-2-only frozen
  package replay；target run 不执行 Stage 1，也不生成 authoring model log；
- Counter test package 增加 `previous_enable` history state，使 `CTR-N-011` 检查上一周期 enable
  对应的 next-state relation，而不是错误使用当前周期 enable。

当前验证证据：`tests/test_specflow_iteration3.py` 8 passed，覆盖真实 Chisel compile/elaboration、
4 条 source property 的唯一 emitted identity、4/4 fake proof exact accounting、冻结 cfg_000 package
到 cfg_001 的 Stage-2-only exact CEX/trace replay、source/hash drift、observed-property mismatch、
license failure classification，以及 locator 缺失/重复 hostile fixtures。完整
`tests/test_specflow_*.py` 为 26 passed，CoupledL2 V4 focused regression 为 29 passed；11-family
suite checksum、`compileall` 与 `git diff --check` 通过。

真实 reference acceptance 位于
`runs/specflow-acceptance/iteration3-reference-license-restored`：Stage 2 为 4/4 accounted、
`execution_status=completed`、`formal_outcome=all_proven`、`evidence_status=complete`、
`semantic_candidate=supported` 和 `model_calls=0`。`CTR-P-TIM-001` 为 unbounded `proven`；
activation、observer、state 三个 required cover 分别在 1、16、2 cycles `covered`。

真实 faulty replay 位于
`runs/specflow-acceptance/iteration3-faulty-replay-license-restored`：它没有 Stage 1 model log，
通过 `verification_package_ref.v1.mode=frozen_replay` 绑定 reference run。两次 run 的 reviewed
package SHA-256 均为
`417cfb7d8bed5469de75d119967a101e2616ca3b8527846103c424e8cf943098`，
`source_assertion_delta.json` hash 与四条 source/emitted property identity 也完全相同。cfg_001
结果为 4/4 accounted、`execution_status=completed`、`formal_outcome=cex` 和
`semantic_candidate=violated_candidate`；primary 在 2 cycles 得到 exact CEX，FST trace hash 为
`7e773611aa6d0c0d3ed68a765f2dbd0d0d2b6c7d062d94f3331a33781433a1f9`。由于步长 2 从 reset
值 0 不能到达 15，冻结 package 中 `counter_out == 15` 的 observer cover 被真实证明
`unreachable`，所以该 run 保守记录 `evidence_status=incomplete`，没有把 primary CEX 自动解释为
最终 `violated`；这一结果满足 Stage 2 exact CEX/trace gate，并把最终分类留给 Iteration 4。

旧 `runs/specflow-acceptance/iteration3-reference` 仍仅是 license server FLEXnet `-96` 时的
失败关闭诊断档案，不参与上述验收结论。

改动：

- 实现 verification emitter、certificate、property labeler；
- 实现 operation plan、V5 result map 和 semantic evidence reducer；
- 实现 V5 JG Tcl/backend、FST/VCD trace manifest；
- runner 接入 `compile_verify`，保证零模型调用。

Gate：

- certificate 对每个 source property 恰好一个 emitted identity；
- 删除 locator、重复 label 或优化掉 property 的 fixture 全部失败关闭；
- reference counter 至少一条 primary proven，所有 required evidence operations 有真实结果；
- faulty counter 使用冻结 package 得到 exact CEX 和 trace；
- expected operation count 与 accounted rows、trace-required rows、hash 全部一致；
- stage success 与 semantic candidate 分开记录。

### Iteration 4：Stage 3 source projection、verdict 与 revision round

实现状态（2026-07-18）：已完成。Stage 3 已接入 production runner/CLI，并在 Iteration 3
的真实 reference proof 与 faulty FST 证据上分别完成零模型 accepted 归约和确定性
CEX-to-source projection；模型 classification 与最终 verdict/revision authority 保持分离。

当前落点：

- `trace_projection.py` 已实现 `observation_map.v1` 与 `evidence_projection.v1`，先重验
  verification package、certificate、operation plan、result map 和 trace manifest 的完整 hash/
  exact-operation identity，再把 wrapper signal 精确 join 回 reviewed binding/object ID；VCD 直接
  解析，FST 通过确定性的 `fst2vcd` 临时转换进入相同采样路径，missing/X/Z/identity mismatch
  全部失败关闭；
- projection 已实现 rising-edge sampling、reset-valid window、typed expression IR 逐周期求值和
  previous-value monitor init/clear/update reconstruction。真实 cfg_001 FST 被确定为 cycle 1、
  time 10 失败：`counter_out=2`、`previous_counter=0`，并精确映射到 `CTR-N-011`、
  `src/main/scala/FirstCounter.scala:26` 与三个 history state；
- `diagnosis.py` 已实现 conditional bounded diagnosis：完整 proof/evidence 直接零模型归约；只有
  exact CEX 等条件路径开放一个 strict named tool、一次 bounded repair，模型只能从 projection
  枚举中选择 classification、operation、cycle、object/state/spec/evidence ID，不能提交 verdict、
  review、patch 或未投影 source location；
- diagnosis candidate 未经外部 hash-bound Codex/human review 时，`final_verdict.v1` 固定为
  `inconclusive`。review 后 reducer 只在 exact CEX、legal/complete projection 与 approved
  `design_violation` 同时成立时给出 `violated`；proof 路径给出 `accepted`，其余证据不足或
  asset error 保守归约为 `inconclusive`；
- `source_ranking.v1` 始终物化；普通 production/无 CEX 时为 `not_required`，Track D 模式下只
  接受 reviewed object ordering，并由 deterministic reducer 补齐 Chisel source anchor、统一
  emitted RTL location 与固定 tie rule；
- typed `revision_request.v1` 只允许 obligation/binding/monitor/assumption 的 round-local 修改，
  显式禁止 DUT、repository property assets 与 prior round。`resume --new-round` 绑定 request hash
  创建下一 immutable round；revision authoring 会加载 parent request/package 并拒绝越出 typed
  scope 或 no-op 的 candidate，旧 round、DUT 和 repository asset hash 保持不变；
- `main.py specflow resume --through diagnose` 与统一 `specflow review` 已接入 Stage 3；
  `--track-d` 和显式 `--new-round` 均不绕过 review/hash gate。

当前验证证据：完整 `tests/test_specflow_*.py` 为 31 passed，覆盖 proof 零模型 accepted、
CEX projection/未审 inconclusive/审批后 violated、binding-error revision round、parent/DUT/
repository-asset hash 不变、typed revision scope hostile check、真实 FST 与 CLI；相关 generic
core/CoupledL2 focused regression 为 26 passed。真实 proof run
`runs/specflow-acceptance/iteration3-reference-license-restored` 的 Stage 3 已完成，记录
`projection_status=complete`、`final_verdict=accepted`、`model_calls=0`，且其成功 handoff 的
七个 contract artifacts 均有 hash。真实 faulty run 的 775-byte FST hash 保持为
`7e773611aa6d0c0d3ed68a765f2dbd0d0d2b6c7d062d94f3331a33781433a1f9`，read-only projection
为 complete；尚未把测试 fake diagnosis 伪装成真实 API-model acceptance artifact。

改动：

- 实现 observation map、trace projection 和 monitor reconstruction；
- 实现 conditional model diagnosis；
- 实现 diagnosis review、final verdict reducer；
- 实现 immutable next-round creation 与 typed revision request。

Gate：

- proof run 零 Stage 3 模型调用并得到 `accepted`；
- CEX run 能指出 spec clause、`counter_out` source anchor、previous-value monitor state 和失败周期；
- 未审批 diagnosis 只能 `inconclusive`；
- property/binding error 只能创建新 round，不能修改 DUT 或 repository assets；
- 旧 round 的所有 hashes 在新 round 后保持不变。

### Iteration 5：多语义与中型 controller 扩展

实现状态（2026-07-19）：实现项基本完成，最终 formal gate 仍进行中。通用多语义/compiler、
reviewed component decomposition、跨 configuration replay、bounded API-model authoring 证据、
challenge-family capability classification 和代表性真实 formal 路径均已落地。I2C
`I2C-P008.bounded-retirement` 已得到完整 non-vacuity covers 和多轮 exact CEX projection，
但当前 256-cycle 主断言在 900 秒真实 JasperGold 预算内仍为 `inconclusive`；依赖 reference
proof 的 `cfg_001` frozen replay 因此按合同 fail closed。不能把这一状态写成 proof-complete，
也不能把 Iteration 5 标为全部验收完成。

当前落点：

- `direct_relation.v1`、`previous_value.v1` 已补充数据驱动的 bounded state contract；新增
  Codex hash-bound 的 `bounded_counter.v1` 与 `lifecycle.v1`。Stage 1 tool enum 与 Monitor
  validator 从 reviewed asset library 动态读取 archetype，不再按 Python 名称硬编码两个旧资产；
- `expression_ir.v1`、tool schema、Chisel lowering 与 CEX projection evaluator 已一致支持
  `bit_select`/`slice`，index 越界在 Scala 编译前 deterministic reject；这使 I2C command/status
  位可以在 typed IR 中表达，而不需要 raw Scala；
- 新增 `package_applicability.v1`。Stage 2 在 frozen replay 前按 exact object ID、type、owner、
  clock/reset、accessibility 与 source anchor 判定 `reusable`、`re_instantiated` 或
  `not_applicable`；判定 artifact/hash 已进入 Stage 2 单一 artifact contract；
- project contract 新增 hash-bound `model_view` materialization。`fsm_16`、`i2c` 与
  `led_controller` 使用保行号的 line redaction 物理移除 variant registry/bug label，同时保留
  actual project source hash 作为 semantic/runtime integrity authority；源码 hash 或 exclusion
  range 漂移会 fail closed；
- 新增上述三个 family 的 opaque `project.json`、clean/faulty config、verification-only emitter
  与 reviewed wrapper adapter。baseline elaboration 已修复 multi-module SV 解析，不再把 I2C
  inline BlackBox 文件的第一个 module 误当唯一 top；
- monitor reset lowering 现在服从 project formal reset contract。I2C 的 state/property guard 使用
  `wb_rst_i`，而不是错误使用未被 formal reset 驱动的 wrapper implicit reset；active-low contract
  由 compiler 生成显式反相；
- I2C preflight 已确认 22 个 exact top-level semantic objects，其中 `cr`、`tip`、`prer`、
  `ctr`、`txr`、`rxack` 是 `accessibility=wrapper` 的内部 observer。使用 `cr(4)`、`tip`、
  pending/age state 的 lifecycle overlay 已通过真实 `sbt compile`，没有 generated-RTL path
  authoring；
- 新增 reviewer-approved `specflow_property_decomposition.v1`：I2C 的 13 个 public expected
  rows 全量建账，`I2C-P008` 的 command admission、bounded retirement、interrupt set 和 IACK
  priority 具有稳定 component identity/role hint。run config 可按 exact expected-property 和
  component ID 缩小 authoring scope；`property_decomposition.json`、`authoring_scope.json` 及其
  hash 已进入 immutable inputs/manifest，cover/state/assumption component 不会被误当 obligation；
- authoring tool schema、prompt 与 validator 已从少数硬编码形状扩展到 reviewed archetype 的完整
  typed IR，并显式关闭 DeepSeek thinking/parallel tool calls。`candidate_attempts.jsonl` 保存每次
  typed rejection/repair payload。真实 bounded API-model run
  `runs/specflow-acceptance/20260718T145410Z-i2c-86b93d5c` 发起 4 次模型调用：首次类型错误被
  deterministic repair，随后候选通过 schema 但因 admission/retirement 方向颠倒和
  `!tip && tip` vacuity 被外部 `reviewer=codex` hash-bound rejection；该 run 不计为模型成功；
- 新增 `capability_assessment.v1` 和 exact loader/validator。`reed_solomon_decoder`、`sha3` 与
  `gigamax` 均物化为 reviewer-approved `unsupported`，分别记录 algorithmic reference、
  compositional monitor、transaction scoreboard、hierarchical observer 或 reviewed clean
  reference 的缺失，不再以缺 run/project 静默跳过 challenge family；
- internal observer lowering 使用 Chisel `BoringUtils.bore` 将 reviewed source object 接入
  `SpecFlowOverlay.dut.<name>`，并已真实编译 I2C overlay。Stage 2 同时清理 CIRCT 末尾
  `firrtl_black_box_resource_files.f` payload；property identity 以共享 locator cone 的 primary
  locator 和完整 statement terminator 定位，trace projection 对 internal binding 使用实际 nested
  DUT 路径。普通 SystemVerilog `default:` label 不再跨模块误匹配后续 property；
- held-out `led_controller` 已完成 mock-model candidate、外部 Codex review、真实 Chisel
  compile/elaboration、真实 JasperGold 与 Stage 3 zero-model reduction。durable run 位于
  `runs/specflow-acceptance/iteration5-led-heldout-real`：3/3 operations accounted、
  `formal_outcome=all_proven`、`evidence_status=complete`、final verdict `accepted`；
- `fsm_16` S7/input-11 history obligation 已完成 clean proof 与 frozen faulty replay。reference
  run `runs/specflow-acceptance/iteration5-fsm16-reference-real` 为 4/4 accounted、all proven、
  complete；`iteration5-fsm16-faulty-replay-real` 保持同一 source assertion delta，applicability
  为 `re_instantiated`，4/4 accounted、exact CEX、complete。read-only projection 将失败定位到
  cycle 4、`input1/input2/state` source objects 与四个 history states；
- `tests/test_specflow_iteration5.py` 覆盖 asset state contract、bounded/lifecycle lowering、
  bit-select hostile bound、跨配置 applicability、三个 hash-bound leakage-free model view、
  I2C internal-observer lifecycle 的真实 compile，以及 held-out LED 三阶段 fake-backend replay。

I2C real-formal refinement 证据保存在独立、不可覆盖的
`runs/specflow-acceptance/iteration5-i2c-bounded-retirement-real-v15` 至 `v23`。这些 run 依次暴露
并修正了 active-command overwrite、非 idle admission、reset 后非法 session 自动恢复、以及
SCL/SDA synchronizer/filter 未稳定等 monitor/environment 缺口；每个 CEX 均有 exact FST、完整
projection 和合法性判定。`v21` 使用最终 256-cycle/9-bit contract，5/5 operations accounted，
四个 evidence roles 全部 `covered`，主断言在 depth 510、900 秒后仍 `inconclusive`；`v22` 对
更强 128-cycle bound 给出 cycle 136 的真实 CEX，因此已拒绝该过紧 bound。`v23` 的 192-cycle
校准没有 CEX但仍 inconclusive，最终代码恢复保守 256-cycle contract。上述状态不能折算为
`all_proven`，Stage 3 和 cfg001 promotion replay 均未被错误启动。

当前回归证据：完整 `tests/test_specflow_*.py` 为 49 passed（300.09s），CoupledL2 V4 focused
regression 为 40 passed；11-family `SPECIFICATIONS.sha256` 全部通过，`compileall` 与
`git diff --check` 通过。上述 durable FSM/LED run 另使用 JasperGold 2020.03 真实执行，未以
pytest fake backend 替代其 formal 结论。

原剩余项中，bounded API-model rejection/repair evidence、I2C reviewed decomposition 和三个
challenge-family `unsupported` artifact 已关闭。仍需关闭：

- 让最终 256-cycle I2C bounded-retirement 主断言得到 canonical `all_proven/complete`，或在保持
  public assumption 不变的前提下得到可由 Codex 分类的 exact CEX；`v21` 的
  `inconclusive/incomplete` 不能进入 thesis-facing supported denominator；
- reference proof 闭合后，使用 `tools/specflow_iteration5_i2c_replay.py` 在 `cfg_001` 执行 frozen
  package replay，核对 `re_instantiated` applicability、source assertion delta identity、5/5
  operation accounting 和 Stage 3 zero-model reduction。脚本当前会在 source 不是
  `all_proven/complete` 时先行拒绝，这是预期的 promotion gate；
- 继续把 I2C-P008 command-admission、interrupt-set、IACK-priority 以及 serial ordering/status
  components 接入真实 formal/projection。完整 13-row hidden evaluator coverage 属于 Iteration 6，
  但 Iteration 5 的 controller semantic-shape gate 至少需要上述代表 component 的 artifact 闭环；
- 在 reference proof 后将同一 reviewed lifecycle package 复用到另一个 configuration/property，
  才能将现有 compiler-level reuse 支持升级为 promotion-level reuse 证据。

按 split-aware 顺序接入，而不是先遍历所有 family：

1. asset-development：`counter`、`fsm_16`、`i2c`，用于稳定
   direct/history/state/lifecycle、internal observer 和 configuration variation；
2. held-out common：`alu`、`decoder_3_to_8`、`arbiter`、`led_controller`、
   `sdram_controller`，只使用冻结后的通用 asset；
3. held-out challenge：`reed_solomon_decoder`、`sha3`、`gigamax`，在控制类
   contract 稳定后研究 compositional/algorithmic property。

每新增一种语义形状都要求：

- 至少一个模型提出的 run-local candidate；
- deterministic rejection/compile evidence；
- Codex/human review；
- promotion 前回归；
- 在另一个 property/config 上复用，才能声称 reusable asset。

Gate：

- asset-development 三个 family 的主要语义形状均能闭环；
- 至少一个 held-out 中型 controller 完成 authoring、compile、formal 与 projection；
- held-out task 运行后不得反向修改通用 asset；若必须修改，实验版本递增并从头重跑；
- challenge family 未满足 observer/composition 前置条件时明确记为 `unsupported`，不静默删除。

当前 gate 判定：held-out controller 与 challenge classification 已通过；asset-development 的
compiler/authoring/decomposition 子门已通过，但 I2C lifecycle proof 与 promotion replay 未通过。
因此 Iteration 5 的代码实现可进入维护状态，实验验收状态仍是 `in_progress`，不得作为
Iteration 6/7 的 frozen promoted lifecycle asset。

### Iteration 6：冻结 suite、split 与 hidden evaluator contract

改动：

- 实现 `task_contract.py`、`split_contract.py` 和 suite validator；
- 将现有 11 份 `specflow/spec.md` 与 `SPECIFICATIONS.sha256` 固化为
  `public_spec_package.v1` snapshot；
- 为每个 configuration 建立 evaluator-only `circuit_spec_task.v1`，保存 gold
  obligations、binding equivalence、mutant relation、diagnosis oracle 与 SEC evidence；
- 固化 asset-development、held-out common、held-out challenge 三类 split；
- 为每种 method 生成 input visibility manifest，并物理隔离 hidden evaluator root；
- 清点前 10 个 family 的 10 个 clean reference、41 个 buggy translation 和 Gigamax 的
  3 个 error-bearing variant，并对 translation SEC 与 clean non-equivalence 分别建账；
- 对 valid mutant 建立 translation-SEC/non-equivalence gate；Gigamax 单独标记
  `no_reviewed_clean_reference`。

Gate：

- 11 个 family 全部通过 spec hash、review state、ID uniqueness 和 split validation；
- public snapshot 中不存在 gold mapping、expected verdict、bug label、private trigger 或
  reference diff；
- production `src/chiselspecflow/` 在 import、runtime path 和 artifact hash 上均不依赖
  evaluator-private 文件；
- 所有 task 均有 `ready`、`unsupported` 或 `blocked_by_asset` 的显式分类；
- held-out split 和 evaluator version 在首个正式实验前冻结。

### Iteration 7：Track P 内部主实验

改动：

- 实现统一 baseline registry 和 P0--P6 adapters：direct LLM、AssertLLM、
  Spec2Assertion、AssertGen、SANGAM、ProofLoop 与 V5；
- 固定每个方法可见输入、模型、temperature、seed、token/tool/formal budget 和失败政策；
- 实现 SPR、Strict/Partial FER、FPV P/T、COI/Proof/Formal coverage、BKR、
  pass@1/pass@5、Obligation Precision/Recall/F1、NVR、FAR 与 cost/intervention；
- 保存 property-level raw rows，但只用 task-level macro average 做主统计单位；
- 对 11 个 family 均发起运行，每种方法使用 5 个独立 seed。

Gate：

- 所有适用 baseline 由同一 experiment manifest 调度，输入差异仅来自预注册的
  baseline protocol；
- 11 个 task 全部 attempted，至少 8 个 task 在 small/medium/large 三个难度层次上
  形成完整可评分结果；
- Strict FER、BKR、FAR 三个主 endpoint 的分母和 exclusion reason 可逐 row 回溯；
- Gigamax 在 clean reference 未审定前不进入 FAR、clean FPV 或 Strict FER 的 clean
  denominator；
- AssertLLM2/FVEval 只登记为 evaluator/corpus，不伪装成 end-to-end baseline。

### Iteration 8：外部泛化、统计与复现包

改动：

- 从 AssertLLM2 固定选择 8--12 个外部 case，翻译为 Chisel，并以 SEC 证明
  original/translated pair 的配置语义一致；
- 接入 FVEval strict/partial evaluator，记录 commit、license、adapter hash 与过滤规则；
- 实现 5-seed task-level aggregation、paired bootstrap 95% CI、McNemar exact test、
  Wilcoxon signed-rank、effect size 和 Holm correction；
- 生成主结果表、ablation 表和 failure-analysis 表，同时保存 raw rows 与生成脚本。

Gate：

- 外部 track 与内部 suite 不共享 hidden gold 或 task-specific asset；
- 所有被计入的 AssertLLM2 translation 均有可复查 SEC artifact，失败 case 不被事后替换；
- 统计脚本拒绝以 property row 冒充独立样本，缺 seed/缺 pair 时 fail closed；
- report 中每个数值都能回溯到 experiment manifest、method run、evaluator version 和
  denominator ledger。

### Iteration 9：Track D 条件性诊断实验

只有论文保留 source-level diagnosis claim 时实施本 iteration。

改动：

- 固化共同的 valid CEX、时间预算、候选位置粒度和 Chisel-to-RTL location map；
- 先生成 `design/variant/bug-location/trigger` intersection manifest，明确内部 corpus 与
  Pecker/Wit-HW 的真实交集，不按相同 bug 总数推断 corpus 相同；
- 为 Tarsel、Wit-HW、Pecker 与 V5 实现 baseline adapter；
- Stage 3 生成 `source_ranking.json`，统一处理并列候选、unmapped location 和
  no-answer；
- 实现 Top-1/3/5、MFR、EXAM score 与 Chisel source projection accuracy。

Gate：

- 各方法接收同一 CEX 和相同诊断预算；
- ranking tie rule、candidate universe 与 no-answer penalty 在运行前冻结；
- hidden diagnosis oracle 只在评分阶段可见；
- 若 baseline 无法在共同 RTL/source 粒度上公平复现，则缩小或删除 diagnosis claim，
  不用不可比结果填表。

## 14. 测试矩阵

### 14.1 单元测试

```text
tests/test_specflow_config.py
tests/test_specflow_workspace.py
tests/test_specflow_obligation_ir.py
tests/test_specflow_semantic_index.py
tests/test_specflow_binding_ir.py
tests/test_specflow_monitor_ir.py
tests/test_specflow_authoring.py
tests/test_specflow_review.py
tests/test_specflow_monitor_compiler.py
tests/test_specflow_elaboration_certificate.py
tests/test_specflow_operation_contract.py
tests/test_specflow_trace_projection.py
tests/test_specflow_verdict.py
tests/test_specflow_runner.py
tests/test_specflow_eval_task_contract.py
tests/test_specflow_eval_suite.py
tests/test_specflow_eval_visibility.py
tests/test_specflow_eval_equivalence.py
tests/test_specflow_eval_mutation.py
tests/test_specflow_eval_metrics.py
tests/test_specflow_eval_statistics.py
tests/test_specflow_eval_baselines.py
```

必须包含 hostile fixtures：

- 普通 Scala `assert` 被误识别为 hardware property；
- Scala `if` 与 Chisel `when` 混淆；
- width/signedness 不匹配但 Scala 可编译；
- Bundle/Vec path 越界；
- property label 缺失/重复；
- source locator 被 strip；
- configuration 下 object 不存在；
- assumption unsat；
- activation unreachable；
- operation row missing/extra/duplicate；
- CEX trace hash 或 property ID 不匹配；
- 模型尝试写 approval 或 raw Scala；
- Stage 1 prompt 泄漏 Verilog/bug label/diff；
- public spec 或 suite ledger hash 漂移；
- public package 泄漏 gold obligation mapping、expected verdict 或 private trigger；
- held-out method adapter 得到额外 source/reference view；
- 未通过 SEC/non-equivalence gate 的 mutant 进入 BKR denominator；
- 因 bug 数量相同而把 Pecker/Wit-HW 与内部 corpus 误判为同一集合；
- Gigamax 被错误计入 FAR/clean denominator；
- FAR、FER 或 coverage 的 numerator/denominator 配对错误；
- 将同一 task 的 property rows 当作独立统计样本；
- 缺 seed、缺 paired task 或 Holm family 不完整时仍输出显著性结论。

### 14.2 集成测试

1. fake model + fake build/JG：覆盖 author-review-resume、tool error、revision round；
2. real `sbt compile`：counter overlay 的 direct/history lowerings；
3. real elaboration：source locator、top、port、property identity；
4. real JG：reference proof 与 faulty CEX；
5. replay：同一 package 跨 opaque config 的 reusable/re-instantiated/not-applicable 判定；
6. suite freeze：11 份 public spec、checksum ledger、split 和 task manifest 全量验证，并核对
   10 clean + 41 buggy translations 与 3 个 Gigamax error-bearing variants 的 corpus inventory；
7. baseline parity：同一 task/seed/budget 下各 adapter 的 input visibility 与运行账本一致；
8. external track：AssertLLM2-to-Chisel translation 的 SEC 与 FVEval adapter replay；
9. aggregate replay：5-seed raw rows 重建主表、CI、paired tests 与 Holm-adjusted result。

### 14.3 推荐验证命令

```bash
rtk codex-run env PYTHONPATH=. pytest -q tests/test_specflow_*.py
rtk codex-run env PYTHONPATH=. pytest -q \
  tests/test_coupledl2_refactor_v4_iteration0.py \
  tests/test_coupledl2_refactor_v4_iteration1.py \
  tests/test_coupledl2_backend.py \
  tests/test_coupledl2_runner.py
rtk codex-run sha256sum -c benchmark/synth/SPECIFICATIONS.sha256
rtk codex-run python -m compileall -q \
  src/core src/coupledl2 src/chiselspecflow src/specflow_evaluation
rtk codex-run git diff --check
```

真实 JG 命令必须使用 named run root、完整 log 和 artifact directory；验收读取 certificate、operation plan、result map、semantic evidence、trace manifest 与 final verdict，不能根据 PID、退出码或 `stage_result.success` 推断成功。

## 15. 主要风险与止损条件

### 15.1 Semantic Index 退化为字符串检索

若 Milestone 1 后 binding 仍主要依据名称正则，而 type/owner/guard/config 无法由 source + elaboration evidence 确认，则停止扩展 benchmark，先修索引。不能把模型 rationale 当确定性 type evidence。

### 15.2 Wrapper 只能观察 IO

这是首轮有意边界。进入 I2C/SDRAM 前必须独立实现并验收一种 internal observer adapter；若最终依赖 generated RTL path authoring，则降级论文主张。

### 15.3 Source locator 不稳定

对 Chisel/CIRCT 版本和配置做 certificate replay。若同一 overlay 的 locator/label 不能稳定重建 exact property，停止跨配置实验，不用模糊 suffix 修补。

### 15.4 Review 成为隐藏人工实现

review record 必须记录：模型候选、人工修改 diff、耗时、拒绝原因和 promotion evidence。若 reviewer 实际重写大部分 obligation/monitor，应在实验中计入 intervention cost，不能记作模型成功。

### 15.5 为兼容旧 workflow 产生双路径

V5 不读取 CoupledL2 profile、binding manifest、TileLink signal index 或旧 run。发现为了通过旧测试而引入双写 artifact、suffix fallback 或兼容 reader 时，应删除/重写对应测试，而不是扩大 V5 contract。

### 15.6 Public spec 被实验反馈污染

正式 experiment 开始后，不能根据 hidden failure、gold mapping 或某个 baseline 的输出重写
public spec。确需纠错时必须提升 suite version、记录 breaking diff，并使所有方法从相同
snapshot 重跑；否则该轮结果只可作为开发数据，不能进入主表。

### 15.7 Baseline 输入或预算不公平

不同论文方法可能假定 Verilog、自然语言、formal feedback 或 repair loop。每个 adapter 必须
在预注册 protocol 中声明输入视图和预算，`input_visibility_manifest` 做机械审计。若无法得到
paper-faithful implementation，应标记 unavailable/approximate 并限制结论，不能给 V5 暗中增加
信息或给 baseline 使用不相称的弱配置。

### 15.8 统计伪重复与分母漂移

property rows 共享同一 task/spec/config，不能当作独立样本扩大显著性。所有主结论以 task
为统计单位，5 seeds 在 task 内聚合；FER、BKR、FAR 的 exclusion reason 必须冻结。统计器若
发现缺 pair、事后删 task 或 denominator hash 不一致，应拒绝生成显著性结论。

### 15.9 Evaluator 侵入 production contribution

hidden gold、mutant relation、diagnosis oracle 和 scoring code 只能存在于
`src/specflow_evaluation/` 与 evaluator-private workspace。若 production stage 为通过 evaluator
而读取这些数据，立即停止实验并判定该 run 污染。论文贡献仍是三阶段方法，不把 benchmark
胶水或 evaluator 包装成第四阶段。

### 15.10 External corpus 漂移与不可复现

AssertLLM2/FVEval 必须固定 commit、case list、license、adapter hash 和 filtering rationale。
translation 没有 SEC、上游 case 被静默替换或 evaluator version 不可恢复时，外部 track 只作
qualitative evidence，不进入量化泛化主张。

### 15.11 Gigamax clean denominator 不成立

在 reviewed clean implementation 和独立 reference evidence 缺失时，Gigamax 只能用于
bug-detection/liveness challenge。任何 report 将其计入 FAR、clean FPV 或 clean-reference FER
分母都必须 fail closed，而不是用“预期正确”配置代替基准真值。

### 15.12 论文主张收缩 gate

若 full method 不优于 P0 direct Chisel assertion、性质仍主要依赖 generated RTL path、配置
变化需要手工重绑、CEX 不能回到 Chisel source，或人工 archetype/adapter 成本超过直接验证且
不可复用，则停止“通用/更有效”主张。工程 artifact 可以保留，但论文结论必须降级为已验证的
能力边界和机制分析。

## 16. 实现 Definition of Done

实现完成必须同时满足：

- 输入 contract 明确为冻结的已知 spec、目标 Chisel design 和 generator configuration；
- `main.py specflow` 与现有 `formal/run/v2c` 隔离；
- production 不以 Verilog/SVA 为 property-authoring interface；Stage 1 模型看不到 Verilog、
  hidden bug/evaluator 信息，且没有任意代码写权限；
- 四层 IR 各有独立 validator、schema version 与 fail-closed error；
- monitor compiler 至少支持 direct、history/stability、state/cardinality、lifecycle 和
  data relation；
- Semantic Index 区分 elaboration-time Scala 与 hardware-time Chisel guard；
- binding 检查 type、width、owner、clock/reset 与 configuration existence；
- model output 只能产生 run-local candidate，不能 approval/promotion；
- wrapper/monitor 不修改 DUT 功能源码；
- verification elaboration 保留 source locator；
- certificate 对每条 source property 建立唯一 exact emitted identity；
- Stage 2 构造时无 LLM client，结果对 exact operation 全核算；
- `proven` 必须经过 activation/observer/state/assumption required gates；
- CEX 能投影到 clause、object、source anchor 和 monitor state；
- final `violated` 需要 reviewed diagnosis，模型不能直接决定；
- revision 创建新 immutable round，不覆盖旧证据；
- counter reference proof 与 frozen-package faulty CEX 均有真实 JG artifacts；
- 至少一个 configuration replay 判定 reusable、re-instantiated 或 not-applicable；
- 所有模型调用、review、人工修改、失败和 inconclusive 可审计；
- CoupledL2 V4 focused regression 保持通过；
- 11 个 family 均有冻结 public spec、opaque task/config identity、hidden evaluator record 和
  input visibility manifest，checksum ledger 可重放；
- 11 个内部 task 全部 attempted，至少 8 个在 small/medium/large 难度层次上完整评分；
- 至少一个中型 controller 与一个 algorithmic/challenge design 完成 Stage 1--3，并给出
  accepted、violated 或 evidence-backed inconclusive；preflight `unsupported` 不算端到端完成；
- Track P 的 P0--P6 所有适用方法使用冻结输入/预算完成；AssertLLM2/FVEval 仅作为
  evaluator/corpus，不被列为伪 end-to-end baseline；
- Strict FER、BKR、FAR 为预注册主 endpoint，FPV、coverage、pass@k、Obligation
  P/R/F1 与 NVR 的原始 rows、分母和 exclusion reason 均可审计；
- 主结果以 task-level macro average 报告，覆盖 5 seeds、paired bootstrap 95% CI、
  McNemar/Wilcoxon、effect size 与 Holm correction；
- FAR 明确测量 clean/reference false acceptance，人工 review/edit 计入 intervention cost；
- 外部 track 固定 8--12 个 AssertLLM2-to-Chisel SEC case，使用版本锁定的 FVEval
  strict/partial evaluator，并完成 V5 与 applicable baseline 的 paired evaluation；
- 若保留 diagnosis claim，Track D 对 Tarsel/Wit-HW/Pecker/V5 报告共同 CEX 下的
  Top-1/3/5、MFR、EXAM 与 Chisel projection accuracy；否则删除该主张而非保留无实验结论；
- 未达到“全 11 attempted、至少 8 complete、覆盖 S/M/L”和主 endpoint 统计要求时，
  论文只能声称 engineering prototype，不能声称 benchmark-wide effectiveness 或 generality；
- 只有 V5 相对 strongest applicable automatic baseline 在至少两个难度层改善预注册主要
  有效性指标，且 known-good FAR 不恶化，才允许声称“有效性更高”；否则如实报告能力边界
  与机制证据；
- benchmark/evaluator 不列为 method contribution，相关工作不声称首次 Chisel
  verification、Chisel IR 或 Chisel formal。

## 17. 与 `refactor_v5.md` 宏观目标的对应关系

| V5 宏观目标 | 实现落点 | 核心验收证据 |
|---|---|---|
| Spec-to-Chisel obligation authoring | `ir/obligation.py` + `authoring.py` + review gate | clause hash、typed obligation、candidate/review diff |
| Elaboration-aware source binding | ScalaMeta source index + baseline elaboration + `ir/binding.py` | confirmed object/type/guard/config rows |
| Chisel-native monitor synthesis | expression/monitor IR + `monitor_compiler.py` + wrapper | 无 raw Scala model output、overlay compile |
| Bidirectional evidence flow | certificate + exact operations + trace projection | source property -> emitted ID -> trace -> source anchor |
| Hybrid 而非伪确定性 | Stage 1/复杂 Stage 3 用模型，Stage 2 零模型 | model call log 与 poison-client test |
| 三类资产状态 | `assets.py` + run review + promotion gate | reviewed hash、promotion regression |
| 11-family public spec suite | `public_spec_package.v1` + checksum/split validator | suite snapshot、11 task manifests、visibility audit |
| Track P 公平比较 | P0--P6 baseline adapters + unified evaluator | Strict FER/BKR/FAR、5-seed raw rows、budget ledger |
| 可选 Track D 诊断主张 | Stage 3 `source_ranking.json` + diagnosis evaluator | Top-k/MFR/EXAM、共同 CEX、projection accuracy |
| 外部泛化 | AssertLLM2-to-Chisel SEC + FVEval adapter | fixed case list、SEC artifacts、versioned evaluator |
| benchmark 不是 contribution | target adapter 与 hidden evaluator boundary | production package 不含 reference Verilog/bug metadata |

这套实现顺序的核心不是先做出更多 assertion，而是先让一条 property 的语义、绑定、
elaboration identity、formal operation 和 source-level verdict 在同一条 hash-bound evidence
chain 中闭合。counter vertical slice 同时通过 proof 与 CEX 后，先冻结通用 asset、suite
snapshot、split、hidden evaluator 和 baseline protocol，再进入正式多语义/多 seed 实验；不能
先看 held-out 或 evaluator 结果再反向修订方法。内部 11-family、外部泛化和条件性 Track D
共同构成论文证据，但都不改变 V5 仍为三阶段 production method 的边界。
