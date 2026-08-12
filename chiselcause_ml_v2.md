# 基于多波形关系一致性的硬件源码错误定位方案

本文分为两部分。第一部分独立介绍算法，不依赖任何具体仓库、工具版本、实验编号或参数；第二部分是面向 Codex 的实施与实验契约，用于在主仓库和因果分析子仓库的独立分支上继续开发、验证和决策。

## 第一部分：算法与思路

### 1. 要解决的问题

目标是在原生 Verilog 电路出现错误输出后，根据错误电路本身、测试程序和运行波形，从全部可执行 Verilog 语句中找出最可能导致错误的语句。

单条失败波形只能展示一种输入和状态路径。某条语句在这条路径上靠近错误输出，不等于它就是错误源；它可能只是正常传播了更早产生的异常。覆盖率高也不等于可疑，因为许多正常语句会同时出现在通过和失败运行中。

因此，本方案不只统计一条语句是否执行，而是分析信号值如何沿组合依赖和时序依赖传播，并比较同一错误在多条失败和通过轨迹中的稳定差异。

### 2. 核心思想

把电路运行表示为带时间的有向关系图：

- 节点表示某个信号在某个周期的值；
- 边表示组合赋值、条件控制、端口连接或寄存器更新；
- 每条边绑定产生这段关系的 Verilog 语句；
- 首次错误输出是反向分析的起点。

对于一条失败轨迹，从首次错误输出沿实际执行过的依赖关系向前回溯，可以得到本次失败的动态因果路径。对同一个错误收集多条轨迹后，再观察：

- 哪些语句反复出现在不同失败轨迹的因果路径中；
- 哪些语句虽然经常执行，却也同样频繁地出现在通过轨迹中；
- 哪些语句稳定地靠近异常首次产生的位置；
- 哪些语句只是位于下游，持续传播已经存在的异常。

稳定出现在失败因果路径、较少出现在通过轨迹、并且接近首次异常的语句应获得更高可疑分数。

### 3. 正确的训练单位

训练单位是“一个错误及其整组轨迹”，不是单条波形。

同一错误的所有通过和失败轨迹必须一起进入训练集或测试集。不能把同一错误的一部分轨迹用于训练，另一部分用于测试，然后把结果解释为模型能够定位未知错误。

也不为每个错误单独训练一个监督模型。单个错误的多条轨迹用于计算关系一致性、失败特异性和时间稳定性；真正的排序模型必须在多个错误之间共享，并在未见过的错误和电路上评价。

### 4. 输入信息

每个错误需要以下信息：

- 包含错误的原生 Verilog 设计；
- 能驱动设计运行并观察输出的测试程序；
- 多组输入及其通过或失败结果；
- 失败运行的内部信号波形；
- 首次可观察错误的输出信号和周期；
- Verilog 语句、信号依赖边和波形节点之间的确定映射。

正确实现可以在构造基准时用于判断输出是否错误和建立评价标签，但定位算法在推理时不应读取正确源码、源码差异或正确实现的内部波形。否则任务会退化为差异比较，而不是从错误电路和失败行为中定位原因。

### 5. 波形对齐与信号配对

每条波形先按真实时钟边沿离散成周期。失败轨迹以首次错误输出为锚点，所有时间距离都表示为“距离首次错误还有多少周期”。

组合依赖连接同一周期内的信号，时序依赖连接前一周期的状态和后一周期的寄存器值。周期关系由电路依赖图确定，不能根据名称或经验再次移动。

不同轨迹之间不要求绝对时间相同，只比较相对于失败锚点的位置。信号必须使用完整实例层级和位宽精确识别；短名相同但实例不同的信号不能合并。未知值、缺失信号和宽度不一致必须标记为不可用，不能猜测。

### 6. 关系特征

首先在每条失败轨迹上计算语句级动态因果证据：

- 该语句是否出现在从错误输出回溯得到的路径中；
- 与该语句关联的依赖边有多少得到波形支持；
- 这些边的最大和平均贡献度；
- 该语句距离首次错误输出有多少周期；
- 该语句主要属于组合传播还是时序更新。

随后在同一错误的多条轨迹之间聚合：

- 失败轨迹因果覆盖率：语句出现在多少条失败因果路径中；
- 失败执行率：语句在多少条失败轨迹中执行；
- 通过执行率：语句在多少条通过轨迹中执行；
- 执行特异性：失败执行率减去通过执行率；
- 因果支持强度：失败轨迹中动态贡献度的均值和最大值；
- 临近失败程度：语句在多少条失败轨迹中靠近首次错误；
- 时间稳定性：语句相对首次错误的出现位置是否稳定；
- 证据覆盖率：有多少依赖边和波形值能够被精确解析。

通过轨迹主要提供“正常执行也会覆盖哪些语句”的反证；失败轨迹提供从错误输出反向得到的因果路径。两者共同减少单纯覆盖率带来的误报。

### 7. 模型怎样使用这些信息

第一阶段先使用不训练的透明规则，例如优先排列“失败因果覆盖率高、执行特异性高、距离首次错误近”的语句。这个阶段用于验证关系表示本身是否有区分力。

关系特征通过检查后，再训练一个跨错误共享的轻量排序器。模型只组合已经定义的动态关系和覆盖率特征，不读取信号名称、文件名、错误编号、错误标记注释或电路身份。

推理时，输入一个未见过的错误设计及其轨迹，重复构图、关系聚合和排序过程，输出 Verilog 语句列表。模型不需要、也不能预先知道正确错误位置。

### 8. 为什么不直接使用复杂图模型

复杂图模型需要大量独立电路、独立错误和语义多样的轨迹。若数据主要由少量电路中的多个相似错误组成，复杂模型很容易记住结构、命名和局部模板。

因此应按以下顺序推进：

1. 证明原生 Verilog 能被稳定解析并生成完整候选集合；
2. 证明真实错误语句能够进入动态因果候选；
3. 证明无训练关系规则优于或补充覆盖率基线；
4. 证明轻量排序器能够泛化到未见错误和未见电路；
5. 只有前四项成立并且独立电路数量足够时，才比较一个复杂图模型。

### 9. 评价方法

评价至少包含三种隔离：

- 留出整个错误，检查对未知错误的泛化；
- 留出整个设计，检查对未知实现的泛化；
- 留出整个电路家族，检查对不同逻辑结构的泛化。

同一错误的所有轨迹必须留在同一侧。所有方法必须使用同一批输入、轨迹、候选语句和评价标签。

主要指标包括正确语句首次出现的名次、前若干名命中率、平均倒数排名和需要检查的源码比例。若错误语句无法被候选生成器表示，必须计为端到端失败，不能只报告候选集合内部的条件排名。

正确位置可能对应多条语句或一个语句区间，因此评价标签允许是集合；删除语句造成的错误若在错误源码中没有可表示位置，应明确报告为不可表示，而不能偷偷改用正确源码中的已删除行。

### 10. 能够成立的结论边界

该方法能够学习的是：在已执行的测试和已观察的内部状态下，哪些 Verilog 语句及信号关系与失败稳定相关。

它不能证明该语句在所有输入下必然导致错误，也不能替代形式验证。基于仿真的结果属于动态、观测性的因果定位证据；未被测试激活的路径、未记录的信号和仿真器未保留的未知值语义都不在结论范围内。

---

## 第二部分：Codex 迭代开发与实验契约

### 1. Material Passport

- Origin Skill: `experiment-agent`
- Origin Mode: `plan`
- Origin Date: `2026-08-12`
- Current Evidence Status: `REPOSITORY_ASSETS_VERIFIED`
- Proposed Algorithm Status: `PLANNING_ONLY`
- Target Document: `chiselcause_ml_v2.md`
- Parent Repository: `/pub/netdisk1/chenty/chisellmfv_v2`
- VCA Subrepository: `/pub/netdisk1/chenty/chisellmfv_v2/VerilogCausalAnalysis`

本部分是后续实现的执行合同。第一部分只说明独立算法；仓库路径、分支、代码入口、实验规模、artifact 和决策门只出现在本部分。

文件名保留 `chiselcause_ml_v2.md` 仅用于延续当前讨论。新实现不得读取 Chisel、Scala、elaboration、source locator 或 Chisel 生成 RTL；代码和实验 artifact 使用 `verilogcause` 命名。

### 2. 当前基线与停止状态

旧 ChiselCause ML 的最终状态仍是 `failed_stop`，只作为历史负结果保留。旧 run、candidate authority、clean/faulty Chisel miter、源码投影和 LOFO 数值不再是本方案的数据或效果基线，也不得被改写。

当前原生 Verilog 资产已经通过只读检查：

- `benchmark/Wit-HW/buggy_designs` 包含 `10` 个设计族、`41` 个错误 case；
- case metadata 提供正确设计、错误设计、testbench、输入信号和触发输入；
- 当前找到 `11` 个 SystemVerilog testbench，均包含时钟；
- 现有 Verilator 路径能够编译正确/错误设计，输出结果、行覆盖率和 VCD；
- 当前环境存在 Verilator 和 `vcd2fst`；
- VCA structural graph 已为 edge 保存 RTL artifact、行范围、表达式、条件和动态 contribution；
- VCA semantic request 目前仍只接受 Chisel profile，尚无原生 Verilog profile；
- `41` 个 case 的 `bug_loc` 全部为空或缺失，不能直接作为评价 gold。

已确认的主要风险：

| 风险 | 当前证据 | 处理 |
|---|---|---|
| 标签缺失 | `bug_loc` 没有可用内容 | 从正确/错误源码差异生成候选，再人工审查并冻结集合 gold |
| 标签泄漏 | 部分错误源码含 `//buggy`、repair 注释或显眼文件名 | 生成 hash-bound sanitized faulty RTL；模型不得读取原路径和注释 |
| 删除型错误 | 正确语句在 faulty RTL 中不存在 | 映射到最小可执行包围语句；无确定映射时记 `gold_unrepresentable` |
| 无统一失败端点 | testbench 主要写输出文件 | evaluator 比较正确/错误输出，冻结首次差异信号和周期 |
| 当前 VCA profile 不通用 | request contract 写死 Chisel | 在 VCA 独立分支增加最小 Verilog profile，不重写引擎 |
| 仿真语义有限 | Verilator 主要提供动态、二值执行证据 | 只声明 simulation-observed localization，不称形式因果证明 |

文档重写时，主仓库和 VCA 子仓库都位于 `SpecFlow` 分支，且工作树已有用户改动。VCA 中 `install_hdlConvertor.sh`、嵌套 `hdlConvertor` 和 `TOOLCHAIN.lock.json` 不属于本路线，禁止 reset、stash、clean 或顺带提交。

计划创建但本次文档修改不创建的开发分支：

- 主仓库：`experiment/native-verilog-cause`；
- VCA 子仓库：`feature/native-verilog-profile`。

本段记录的是规划时状态；当前实施状态和证据以第 17 节为准。ML 效果证据仍不存在。

### 3. 研究问题、假设与非目标

#### 3.1 研究问题

给定原生 faulty Verilog、testbench、失败输出端点以及同一错误的多条通过/失败轨迹，基于跨周期 RTL 因果路径一致性的语句排序，能否在 held-out bug、design 和 family 上优于同一轨迹预算下的覆盖率定位方法？

#### 3.2 预冻结假设

- H1：真实错误的 reviewed gold 至少有一个候选能够由 faulty RTL candidate universe 表示，并进入失败端点的动态因果图；
- H2：gold 候选在失败轨迹中的 causal trace coverage、contribution 和 near-failure 稳定性高于负候选；
- H3：在加入通过轨迹的覆盖率反证后，简单线性 ranker 能够在 LOFO family macro 上提高 MRR、降低 EXAM，且 Top-K 不下降；
- H4：增量同时出现在组合逻辑和时序控制设计中，而不是由单一设计族驱动。

#### 3.3 零假设与停止解释

- H0a：VCA 无法从 native Verilog/FST 生成包含 gold 的完整候选图；
- H0b：多轨迹因果关系对 gold 与负候选没有稳定区分力；
- H0c：关系特征不优于 Wit-HW/Tarsel 或简单动态回溯；
- 任一零假设在预冻结 gate 上成立时，写 `failed_stop` 并保留诊断；
- gate 失败不授权切换模型、扩大 trace budget、重选成功 case、修改 gold 或只报告条件指标。

#### 3.4 非目标

- 不读取或生成任何 Chisel、Scala、FIRRTL、elaboration 或 source-provenance 信息；
- 不把正确 Verilog、源码 diff、`//buggy` 标记或 gold 行号提供给 feature extractor；
- 不把 testbench 行纳入 design-source candidate universe；
- 不为每个错误训练独立模型；
- 不从 raw waveform 端到端训练大模型；
- 不引入 PyTorch、PyG、XGBoost 或新训练框架；
- 不实现完整 Verilog 仿真器或表达式解释器；
- 不把 Verilator 结果称为形式 counterexample 或完备因果证明；
- 不修改 Wit-HW 上游 benchmark 文件；
- 不删除、重命名或兼容改造旧 ChiselCause run。

### 4. 必须保持的实验不变量

1. 模型可见输入只能包含 sanitized faulty RTL、faulty-design coverage、faulty-design waveform、failure endpoint/cycle 和 VCA 输出。
2. correct RTL、源码 diff、正确内部波形和 reviewed gold 只能位于 evaluator-private artifact；它们不得进入 `samples.jsonl.features`。
3. 正确设计允许用于离线比较外部输出、判定 pass/fail 和确定首次差异；每次使用都必须写入 manifest 的 `oracle_only_inputs`。
4. sanitized faulty RTL 必须去掉 `buggy`、`repair`、注释中的修改说明及路径标签，但不得改变可执行 token；sanitized 与原 faulty RTL 必须通过同一 testbench 输出等价检查。
5. gold 是 reviewed 的 set-valued `(artifact_id, statement_id)` 集合。只用行号显示，不用行号作为稳定身份。
6. 删除型或缺失语句错误若无法映射到 faulty RTL 中现存的最小可执行包围语句，必须记为 `gold_unrepresentable`，不得从端到端分母静默删除。
7. trace pool 在任何 ranking、gold rank 或模型结果可见前按固定随机种子和预算一次生成；不得顺序生成到结果改善。
8. 同一 bug 的全部 pass/fail traces 必须位于同一 fold；同一 workload、VCD/FST 或 RTL hash 不得跨 train/test。
9. 所有方法使用相同 case、trace pool、candidate universe、gold、tie 规则和超时；不允许方法专属删样本。
10. unreachable、compile error、simulation error、endpoint missing、graph incomplete 和 gold unrepresentable 均写入 canonical ledger；不以进程退出码或存在 VCD 推断成功。
11. candidate identity 使用完整 artifact 和 statement ID；禁止 basename、模糊路径、snippet 相似度或信号短名匹配。
12. X/Z、波形缺失、层级歧义和时钟不明确写为 `incomplete` 或 `relation_unavailable`，不得转换为零关系。
13. feature extractor 不读取文件名词汇、信号名词汇、case ID、family ID、`is_gold` 或 fold 身份。
14. Verilator 的二值仿真能力与四态 RTL 语义分开报告；本路线不声称覆盖未观察的 X/Z 行为。
15. feature、gold、trace selection、candidate schema、metric 或 evaluator 任一改变都创建新的 run root。
16. 当前两个工作树中的无关改动归用户所有；禁止 reset、clean、自动 stash、覆盖旧 run 或广泛重构。

### 5. 当前代码与 artifact 接口

#### 5.1 代码入口

主仓库分支 `experiment/native-verilog-cause` 负责：

- 新增一个主入口 `src/experiments/verilogcause.py`；
- 新增一个 focused test `tests/test_verilogcause.py`；
- 读取 Wit-HW case metadata，但不修改 `benchmark/Wit-HW`；
- 创建 sanitized run-local RTL、reviewed gold manifest 和固定 workload pool；
- 通过安全的 run-local 目录调用 Verilator，保存完整命令和日志；
- 比较外部输出，生成 pass/fail 和首次差异 endpoint；
- 调用 VCA native Verilog API，保存 graph 和 candidate universe；
- 聚合 coverage、causal relation、训练样本和指标；
- 复用现有 `chiselcause_ml.py` 中稳定的线性训练/排名函数，不复制第二套 trainer；若私有接口无法安全复用，只移动必要的纯函数一次并同时更新旧调用方。

第一轮不得直接调用 Wit-HW 中会 `rm -rf` 固定目录的 legacy runner。复用其 case schema、testbench、Verilator flags、coverage 解析和输入变异逻辑，但新 runner 只允许写入新建的 run-local 目录。

VCA 子仓库分支 `feature/native-verilog-profile` 负责：

- 在现有 request contract 中增加显式 `verilog` profile；
- 第一阶段只开放通用 feature：instance graph、组合/端口依赖、register transition 和 temporal interval；
- Verilog profile 必须拒绝 `source_provenance` 和 Chisel annotation semantic input；
- 保留现有 `build_structural_graph` 行为，不把新 semantic path 作为结构基线 fallback；
- 复用现有 `make_request` 和 `build_causal_graph`，不增加仅转发参数的 Verilog 专用包装函数；
- 只新增确有独立输出职责的 `build_rtl_candidates`；
- 为每条 graph edge 提供 exact `rtl_evidence.statement_id`；
- 生成完整 executable RTL candidate universe，包括未进入当前因果图的候选；
- 新增 focused contract/engine/ranking tests，不修改 `hdlConvertor` vendored tree。

两个仓库冻结以下共享接口：

`rtl_candidates.json`：

```json
{
  "schema_version": "rtl_candidate_universe.v1",
  "rtl_set_sha256": "<hash>",
  "candidates": [
    {
      "artifact_id": "rtl_0001",
      "statement_id": "<stable-id>",
      "line_start": 1,
      "line_end": 1,
      "statement_kind": "assignment",
      "executable": true,
      "snippet_sha256": "<hash>"
    }
  ]
}
```

`causal_graph.json` 继续使用 VCA graph schema，但 native path 的每条 RTL edge 必须满足：

```json
{
  "rtl_evidence": {
    "artifact_id": "rtl_0001",
    "statement_id": "<stable-id>",
    "line_start": 1,
    "line_end": 1,
    "snippet_sha256": "<hash>",
    "expression": "...",
    "condition": "..."
  }
}
```

主仓库不得读取 VCA 私有 parser 字段；VCA 不负责读取 benchmark gold、运行 ML 或决定论文效果。

#### 5.2 当前数据事实

- Wit-HW case metadata 的 `correct_design` 与 `buggy_design` 足以构造 oracle 和 sanitized faulty artifact；
- testbench 已能从 `workload.in` 读取输入，并在 `DUMP_TRACE` 下生成设计内部 VCD；
- 现有 Verilator runner 使用 line coverage、trace 和 trace-structs；
- 现有 fuzz path 在 faulty design 上生成 coverage/VCD，并用 correct output 判断 pass/fail；
- 原始 fuzz path 没有完整的 durable artifact/hash/resume contract，且含直接目录删除和 shell copy，不能原样成为论文 runner；
- 当前所有 case 缺少非空 bug location；gold 必须新建、人工复核并与 sanitized RTL hash 绑定；
- 某些 faulty RTL 含显式 `//buggy` 或 repair 注释；不清理会产生直接标签泄漏；
- 一些错误是删除语句或注释掉赋值，未必能在 faulty executable statement universe 中直接表示；
- VCA structural edge 已包含 line、condition、expression、contribution 和 change examples，但还缺少公开的 statement candidate universe 与 edge→statement ID 合同；
- 当前 semantic API、模块命名和 source ranking 仍以 Chisel 为中心，不能把它们直接宣称为 native Verilog capability。

### 6. 关系特征的精确定义

#### 6.1 节点配对

本方案不再配对 clean/faulty DUT 内部信号。每条 trace 只分析 sanitized faulty design。

稳定身份定义为：

```text
trace_id     = hash(bug_id, workload_hash, faulty_rtl_set_hash, simulator_config)
node_key     = (full_hierarchical_signal, cycle, packed_range)
edge_key     = VCA edge_id
candidate_id = (artifact_id, statement_id)
```

规则：

1. cycle 必须来自 testbench 中已确认的 clock edge；
2. signal 使用完整实例层级，不得用 basename 合并；
3. edge 通过 exact `rtl_evidence.statement_id` 映射到 candidate；
4. line number 只用于展示和 gold review，不作为跨版本 stable ID；
5. 同一 statement 的多条 edge 在 trace 内先聚合，再跨 trace 聚合；
6. testbench statement 和 correct design statement 不进入 candidate universe；
7. 缺少 statement ID 的 edge 保留在 graph diagnostics，但不能猜 candidate。

#### 6.2 边上的差异传播

这里的“关系”表示失败波形中一条 RTL 依赖边得到多少动态支持，不再表示 clean/faulty 内部值的 Hamming 差异。

对失败 trace `t` 中的 edge `e`：

```text
support(e,t)   = edge.contribution_score
active(e,t)    = 1 if contribution evidence is supported and support(e,t) > 0 else 0
proximity(e,t) = 1 / (1 + max(0, failure_cycle - dst_cycle))
```

约束：

- `support` 必须是 VCA 已验证的有限值；unsupported/inconclusive 不转换为 supported；
- `dst_cycle` 已由 graph 绑定，sequential edge 不再次人工 shift；
- failure cycle 之前的 edge 才参与 primary score；失败后的污染路径只作诊断；
- edge 的 condition/expression 由 VCA parser 提供，本路线不另写表达式解释器；
- unknown waveform value、缺少 endpoint node 或无法解析的 edge 写 `relation_unavailable`；
- 同一 candidate 的重复 edge ID 先去重。

candidate `c` 在单条失败 trace `t` 上：

```text
trace_causal_present = any(active(e,t) for e mapped to c)
trace_support_max     = max(support(e,t))
trace_support_mean    = mean(support(e,t))
trace_proximity_max   = max(proximity(e,t))
trace_sequential_ratio= sequential_edges / mapped_edges
trace_relation_coverage = usable_mapped_edges / mapped_edges
```

若 candidate 没有 mapped edge，数值字段写 `0`，同时 `relation_applicable=false`；若有 edge 但全部不可用，`relation_applicable=true` 且 coverage 为 `0`。

#### 6.3 RTL evidence 解析

新增 exact resolver，输入 `rtl_candidates.json` 和 `causal_graph.json`：

```python
def resolve_rtl_evidence(
    candidates: dict[str, Any],
    graph: dict[str, Any],
) -> dict[tuple[str, str], list[dict[str, Any]]]: ...
```

只允许：

- exact `artifact_id`；
- exact `statement_id`；
- edge 中相同的 `line_start/line_end/snippet_sha256` 用于一致性校验；
- candidate 与 graph 的 `rtl_set_sha256` 完全相同。

以下任一情况 fail closed：

- edge 引用不存在的 statement ID；
- artifact ID 不存在；
- 同一 statement ID 在多个 artifact 中冲突；
- line/snippet identity 与 candidate universe 不一致；
- graph 和 candidate universe 绑定不同 RTL set；
- native Verilog graph 出现 Chisel source provenance semantic node。

禁止通过 `endswith`、basename、行文本相似度或 correct/faulty diff 修复映射。

#### 6.4 candidate 关系特征

对同一 bug 的全部 faulty-design traces，先由输出 oracle 分为 failing 与 passing。passing trace 不需要构建“失败因果图”，只提供同一 faulty RTL 的 line execution coverage；failing trace同时提供 coverage 和 VCA causal evidence。

每个 candidate 生成：

```text
fail_execution_rate
pass_execution_rate
execution_specificity
causal_trace_coverage
causal_support_mean
causal_support_max
near_failure_mean
temporal_stability
sequential_ratio
relation_coverage
```

定义：

- `fail_execution_rate`：执行该语句的 failing trace 比例；
- `pass_execution_rate`：执行该语句的 passing trace 比例；
- `execution_specificity = fail_execution_rate - pass_execution_rate`；
- `causal_trace_coverage`：candidate 出现在 failing causal graph 的比例；
- `causal_support_mean/max`：所有 failing trace mapped edge support 的均值/最大值；
- `near_failure_mean`：所有 failing trace `trace_proximity_max` 的均值；
- `temporal_stability`：至少两条 failing trace 可用时，`1 / (1 + relative_offset_std)`；否则数值为 `0` 并标记 unavailable；
- `sequential_ratio`：可用 mapped edge 中时序边比例；
- `relation_coverage`：candidate 在全部 failing trace 上的平均 relation coverage。

passing trace 数为零时，`pass_execution_rate` 和 `execution_specificity` 不可用于训练，整个 bug 写 `contrast_incomplete`；不得把 pass rate 当成零。

#### 6.5 新 primary feature schema

第一版 primary 固定为：

```python
FEATURES = (
    "fail_execution_rate",
    "pass_execution_rate",
    "execution_specificity",
    "causal_trace_coverage",
    "causal_support_mean",
    "causal_support_max",
    "near_failure_mean",
    "temporal_stability",
    "sequential_ratio",
    "relation_coverage",
)
```

允许三组预冻结方法：

- `coverage_only`：前三项；
- `causal_only`：后七项；
- `ml_relation`：全部十项。

禁止加入文件名、signal 名、statement 文本 token、case/family identity、gold distance 或 correct/faulty diff。第一轮不使用 statement kind one-hot，避免重新引入跨 family 静态类型捷径。

#### 6.6 训练与聚合

- 先运行无训练 `causal_consistency_rule`，按 `causal_trace_coverage`、`execution_specificity`、`near_failure_mean` 的预冻结顺序加权；
- 训练器复用现有 deterministic averaged pairwise perceptron，固定 `20` epochs；
- pair loss 先按 bug 归一化，再按 positive/negative pair 归一化，避免大设计支配；
- 一个 bug 有多个 gold 时，任一 reviewed gold 都是 positive；其余 candidate 是 negative；
- 每个 bug 只产生一行聚合 sample，不把 trace 复制成独立监督样本；
- split 为 LOBO、LODO、LOFO，primary 为 LOFO family macro；
- 不进行超参搜索、seed sweep、模型选择器或第二训练器；
- model JSON 必须写 feature order、weights、train/test bug IDs、split、input hashes 和 VCA commit；
- Wit-HW/Ochiai、Tarsel、静态回溯和 `causal_consistency_rule` 使用同一 frozen trace pool。

### 7. Ledger 与输出契约

不复用旧 Chisel ML ledger，不创建多套重复真相源。每个新 run 只保留以下 canonical artifacts。

#### `dataset_contract.json`

必须包含：

- schema/version；
- parent 与 VCA implementation identity；
- simulator/version/config；
- trace generation seed/budget；
- sanitized RTL policy；
- gold review policy；
- candidate schema；
- feature schema；
- split policy；
- unreachable/tie policy；
- oracle-only input policy；
- baseline/method set。

#### `manifest.jsonl`

使用 `record_type=case|trace`：

case row 包含：

- bug/design/family；
- original faulty、sanitized faulty、correct-oracle、testbench、include set 的 path/hash；
- gold statement set、review status、reviewer；
- `gold_representable`；
- compile/simulation/VCA status；
- candidate/graph artifact references。

trace row 包含：

- case/trace/workload identity；
- pass/fail；
- correct output 与 faulty output hash；
- first divergence signal/cycle，passing 时为 null；
- coverage、VCD、FST hash；
- simulation command/log；
- VCA graph reference，passing trace可为 null；
- all input artifact hashes。

#### `samples.jsonl`

每个 bug/candidate 一行：

- candidate identity 与显示行；
- `relation_applicable`；
- coverage/causal evidence counts；
- unavailable reasons；
- 十项 frozen features；
- `is_gold` 只允许在 evaluator copy 中存在；trainer-visible sample 在写盘前必须删除该字段并通过独立 label map join。

#### `relation_diagnostic.json`

训练前唯一诊断文件：

- case/trace/data completeness；
- gold representable/reachable 数；
- all-candidate 与 gold-only relation coverage；
- gold-vs-negative pairwise win/tie/loss；
- combinational、sequential、family macro；
- zero-pass、zero-fail、endpoint missing、graph incomplete、gold unrepresentable 计数；
- label-leak scan；
- `decision=continue_to_train|failed_stop`。

#### `fold_metrics.jsonl` 与 `summary.json`

方法集合：

- `wit_hw_ochiai`；
- `tarsel`；
- `static_backward`；
- `causal_consistency_rule`；
- `coverage_only`；
- `causal_only`；
- `ml_relation`。

每个 fold 记录：

- candidate recall；
- Top-1/3/5/10；
- MRR；
- EXAM；
- compile/simulation/graph completeness；
- runtime 和 peak RSS；
- paired bug IDs 与 exact input hashes。

`summary.json` 的 primary 是 LOFO family macro。`gate_report.json` 只汇总 canonical ledger 的 gate 状态和 hashes，不重新计算另一套业务数据。

### 8. 实施工作包

计划分支：

- parent：`experiment/native-verilog-cause`；
- VCA：`feature/native-verilog-profile`。

| 工作包 | 仓库/分支 | 内容 | 依赖 | 共享接口 | 冲突点 | 完成证据 |
|---|---|---|---|---|---|---|
| W0 分支与合同冻结 | 两仓 | 记录 dirty status/base，创建两条分支，冻结 candidate/graph/ledger 合同和 pilot case | 无 | 本文件第 4–7 节 | 不得带入无关 dirty 文件 | branch/status/base 记录与 contract review |
| W1 语料与仿真 | parent | sanitized RTL、reviewed gold、run-local Verilator、output comparator、VCD→FST、trace manifest | W0 | `manifest.jsonl`、endpoint contract | benchmark 文件只读；gold 与 runtime input 分离 | ALU/Counter/FSM prepare smoke |
| W2 原生 VCA | VCA | Verilog profile、exact statement universe、edge→statement ID、native graph API | W0 | `rtl_candidates.json`、`causal_graph.json` | contracts/engine/`__init__.py`；不碰 hdlConvertor dirty | focused VCA tests + native Verilog smoke |
| W3 固定语料 replay | parent + accepted VCA commit | 13-case deterministic pilot；只跑 candidate/causal/baselines，不训练 | W1+W2 | frozen VCA commit 与 run manifest | parent submodule pointer 只能由集成者更新 | candidate gate、relation diagnostic、baseline metrics |
| W4 新鲜端到端复验 | parent | 全 41-case fixed-budget trace generation、dataset、LOBO/LODO/LOFO training/eval | W3 pass | feature/split/method schema | trace pool、gold、模型不可同时变化 | full ledgers、fold metrics、gate report |
| W5 可选扩展 | 条件决定 | 增加独立 native Verilog corpus或一个复杂模型 | W4 pass | 保持 primary contract | corpus/model 只能单变量变化 | 新 fresh run 与独立效果门 |

并行开发边界：

- W0 完成后，W1 与 W2 可以在两条分支、两个独立会话中并行；
- W1 不修改 `VerilogCausalAnalysis` gitlink，W2 不修改 parent 文件；
- shared JSON 字段一经 W0 冻结，任何变更必须同时通知两侧并先更新 contract test；
- parent 的 `VerilogCausalAnalysis` gitlink 是唯一高冲突点，只允许 W3 集成会话更新；
- 不允许两个会话同时修改 `src/experiments/verilogcause.py` 或 VCA `contracts.py`；
- W3/W4 必须基于已提交的 VCA commit，不能消费 VCA dirty worktree状态。

同步点：

1. W0：两侧确认 schema 和 branch base；
2. W2 完成：VCA 输出一个已测试 commit 和 changelog；
3. W1 完成：parent 输出一个不依赖 moving VCA branch 的 simulation/gold commit；
4. W3 集成：parent 更新 submodule pointer，运行一例 API smoke 后再跑 13-case pilot；
5. W4 前：冻结 full corpus、trace generator seed/budget、feature 和 method set。

最终 merge 顺序：

```text
VCA W2 commits
  -> 合入 VCA 目标分支并取得 accepted commit
parent W1 commits
  -> parent 更新 VCA submodule pointer
  -> parent W3 integration commit
  -> 13-case gate
  -> W4 experiment-only commits/artifacts
  -> 条件决定是否合入最终 parent 分支
```

### 9. 最小测试要求

VCA 子仓库 focused tests 至少覆盖：

1. native Verilog request 接受允许的 generic features；
2. Verilog profile 拒绝 `source_provenance` 和 Chisel annotations；
3. combinational assignment、conditional branch、port binding 和 nonblocking update；
4. sequential edge 使用正确前后周期；
5. full hierarchy 同名信号不冲突；
6. executable candidate universe 包含未进入当前 causal slice 的语句；
7. 每条可映射 edge 有 exact `statement_id`；
8. artifact/line/snippet identity drift fail closed；
9. missing clock、endpoint、RTL hash 或 waveform signal 返回 `incomplete`；
10. 既有 structural/Chisel tests 不回归。

parent focused test 至少覆盖：

1. 读取一个 case metadata 并解析完整编译文件集；
2. sanitizer 只删除泄漏注释，不改变 executable token；
3. sanitizer 前后同 workload 外部输出一致；
4. correct RTL、diff、gold、`buggy` token 不进入 trainer-visible sample；
5. 首次输出差异信号/周期确定且可复现；
6. passing trace 的 failure endpoint 必须为 null；
7. exact candidate/gold join 和 set-valued rank；
8. deletion bug 的 `gold_unrepresentable`；
9. same-bug traces 不跨 fold；
10. same workload/VCD/FST/RTL hash 不跨 train/test；
11. tie 使用平均名次；
12. unreachable 计 `MRR=0`、`EXAM=100%`、Top-K false；
13. failing/pass coverage 和 causal feature 的边界值；
14. relation gate 失败时 trainer 不被调用。

最小检查命令：

```bash
rtk codex-run env PYTHONPATH=VerilogCausalAnalysis/src \
  /home/chenty/llm/eda-agent/chisellmfv/.venv/bin/python \
  -m pytest -q \
  VerilogCausalAnalysis/tests/test_verilog_profile.py \
  VerilogCausalAnalysis/tests/test_rtl_candidates.py

rtk codex-run env PYTHONPATH=.:VerilogCausalAnalysis/src \
  /home/chenty/llm/eda-agent/chisellmfv/.venv/bin/python \
  -m pytest -q tests/test_verilogcause.py

rtk codex-run \
  /home/chenty/llm/eda-agent/chisellmfv/.venv/bin/ruff check \
  src/experiments/verilogcause.py tests/test_verilogcause.py \
  VerilogCausalAnalysis/src/verilog_causal_analysis \
  VerilogCausalAnalysis/tests/test_verilog_profile.py \
  VerilogCausalAnalysis/tests/test_rtl_candidates.py

rtk codex-run env PYTHONPATH=.:VerilogCausalAnalysis/src \
  /home/chenty/llm/eda-agent/chisellmfv/.venv/bin/python \
  -m compileall -q \
  src/experiments/verilogcause.py \
  VerilogCausalAnalysis/src/verilog_causal_analysis

git diff --check -- chiselcause_ml_v2.md src/experiments tests
git -C VerilogCausalAnalysis diff --check
```

路径是计划目标；若实际实现用现有测试文件承载同一 focused coverage，应更新本节后再编码，不能先写代码再改变验收条件。

测试通过只证明代码和合同能力，不证明定位有效。

### 10. W3 固定语料 replay

W3 不复用旧 Chisel artifacts，也不运行 formal。它使用冻结的 native Verilog pilot：

- `alu`：6 个错误；
- `counter`：3 个错误；
- `fsm_16`：4 个错误。

共 13 个错误，覆盖组合逻辑、简单寄存器更新和状态机控制。选择在结果可见前冻结；不得用“哪些 case 跑得通”重新选择。

W3 顺序：

1. 读取 case metadata，冻结 original file hashes；
2. 生成 sanitized faulty RTL；
3. 从 clean/faulty diff 提议 gold，Codex 逐 case 审查后写 `reviewer=codex`；
4. 对 deletion/missing statement 明确 `representable|unrepresentable`；
5. 使用原触发输入分别运行 correct 与 sanitized faulty design；
6. 保存输出、coverage、VCD、FST、命令和日志；
7. 确定首次输出差异 endpoint；
8. 对 sanitized faulty RTL/FST 构建 VCA candidate universe 和 causal graph；
9. 计算 candidate recall 与 `relation_diagnostic.json`；
10. 只有 relation gate 通过才运行 deterministic baselines；
11. W3 不训练 `ml_relation`。

建议命令接口：

```bash
cd /pub/netdisk1/chenty/chisellmfv_v2

VERILOGCAUSE_RUN=runs/verilogcause/<fresh-pilot-run-id>
test ! -e "$VERILOGCAUSE_RUN"

rtk codex-run env PYTHONPATH=.:VerilogCausalAnalysis/src \
  /home/chenty/llm/eda-agent/chisellmfv/.venv/bin/python \
  -m src.experiments.verilogcause prepare \
  --run "$VERILOGCAUSE_RUN" \
  --corpus benchmark/Wit-HW/buggy_designs \
  --families alu,counter,fsm_16

rtk codex-run env PYTHONPATH=.:VerilogCausalAnalysis/src \
  /home/chenty/llm/eda-agent/chisellmfv/.venv/bin/python \
  -m src.experiments.verilogcause pilot \
  --run "$VERILOGCAUSE_RUN"
```

`prepare` 只生成待 review gold 时必须停下；没有 Codex review record 不得自动进入 `pilot`。命令名在实现前冻结，禁止增加 retry 或隐式 fallback。

W3 是 capability/feasibility evidence，不是完整 ML effectiveness evidence。

### 11. 关系可分性门与效果门

#### 11.1 训练前关系门

W3 必须同时满足：

1. 13/13 case 都有 hash-bound sanitized RTL、reviewed gold decision 和完整 manifest；
2. label-leak scan 中 `buggy/repair/gold/diff` 可见计数为 0；
3. sanitizer 前后对冻结 workload 的外部输出完全一致；
4. compile、simulation、output compare、VCD→FST、endpoint detection 都有 canonical status，失败 case 不被删除；
5. 所有 representable gold 至少有一个 candidate 精确命中；
6. 所有 complete failing trace 的 graph status 为 complete，或 case 明确计端到端失败；
7. unknown statement reference、fuzzy mapping 和 Chisel provenance 计数均为 0；
8. all-candidate 与 gold-only relation coverage 完整报告；
9. `causal_trace_coverage` 的 gold-vs-negative pairwise win rate 在三个 pilot family 中都严格大于 0.5；
10. 每个进入 contrast feature 的 bug 至少有一条 failing 和一条 passing faulty-design trace；
11. 不根据诊断结果改用另一个 primary relation feature。

任一条件失败：写 `failed_stop/do_not_train`，保留 artifacts，不扩大 corpus、不调 fuzz 参数、不换模型。

#### 11.2 训练后 primary 效果门

只有 W3 通过后，W4 才能训练。W4 primary 为 LOFO family macro。

`ml_relation` 必须相对同一 trace pool 中最强的非 ML baseline：

- MRR 严格提高；
- EXAM 严格降低；
- Top-1、Top-3、Top-5 均不下降；
- combinational 与 sequential group 的 MRR 均不下降；
- candidate recall、compile rate 和 graph completeness 相同；
- LOBO/LODO 提升不能抵消 LOFO 失败。

同时报告：

- 相对 Wit-HW/Ochiai、Tarsel、static backward 和 `causal_consistency_rule` 的 paired per-bug delta；
- family macro，不用大 family 的 bug 数加权掩盖小 family；
- paired bootstrap confidence interval作为不确定性描述，不把显著性替代效果门；
- `gold_unrepresentable`、incomplete 和 unreachable 的端到端计数。

W4 通过只能支持“在当前 native Verilog corpus 和仿真观测范围内，多轨迹关系特征具有跨 family 增量”，不能外推到任意 Verilog、形式反例或工业规模设计。

### 12. W4 新鲜端到端复验要求

只有 W3 训练前关系门和 deterministic baseline gate 通过才执行。

W4 使用全部 41 个 Wit-HW case，并在结果可见前冻结：

- case list 和 original hashes；
- reviewed gold 与 representability；
- simulator flags；
- input mutation algorithm、随机种子列表和固定预算；
- timeout；
- candidate/graph/feature schema；
- LOBO/LODO/LOFO split；
- baseline 与 primary method。

每个 bug 的原触发输入必须保留；额外 workload 由同一 gold-blind generator 一次生成。所有方法共享 frozen workload pool。不得为某个方法重新 fuzz。

W4 每个 case 必须：

- 在新 run-local directory 编译 correct oracle 与 sanitized faulty design；
- correct design 只输出 public oracle，不保存为 model-visible internal graph；
- faulty design 保存 output、coverage、VCD/FST；
- 每个 failing trace 保存 first divergence endpoint 和 causal graph；
- passing trace只保存 faulty coverage/波形及 pass status，不伪造 failure endpoint；
- 保存 implementation files、parent/VCA commit、submodule pointer、完整命令、日志和 hashes；
- 数据门失败时不得调用 `train`；
- detached 执行时报告 PID、cwd、log、exact command、expected artifacts 和 continuation prompt；
- 完成后读取 canonical ledgers，不能从 PID、部分日志或 VCD 文件存在推断成功。

W4 不改变 W3 验证过的 candidate 和 relation schema。若 full corpus 暴露新的 parser/identity 缺口，只允许修复共享根因并创建全新 run；不得在旧 run 中补写。

### 13. 决策树

```text
W0 无法冻结无泄漏 gold/candidate 合同
  -> failed_stop；先解决任务定义，不创建模型

W1 sanitizer 或 endpoint comparator 不可复现
  -> 修复同一数据根因；不进入 VCA/训练

W2 native Verilog graph 无法 exact 绑定 statement
  -> 修复 VCA shared identity；不在 parent 添加 fuzzy fallback

W3 representable gold candidate recall 不完整
  -> failed_stop；定位瓶颈是 parser/candidate/graph，不换 ML 模型

W3 relation coverage 完整但 gold-vs-negative 无跨 family 方向
  -> failed_stop；保留 coverage/causal deterministic baseline

W3 通过
  -> 冻结 W4 full corpus、trace budget 和 split

W4 数据门失败
  -> failed_stop；按 exact artifact cause 修复后使用新 run

W4 数据门通过但 ml_relation LOFO 不超过 strongest baseline
  -> failed_stop；不调参、不换复杂模型

W4 数据门与 primary effect gate 都通过
  -> 允许增加一个独立 native Verilog corpus做外部复验

外部复验仍通过且数据规模支持
  -> 才允许比较一个小型 temporal/message-passing model
```

### 14. 复杂模型的后置准入条件

只有全部满足时才能讨论 GNN/Transformer：

1. native Verilog candidate/graph 在多个独立 corpus 上保持高 gold recall；
2. `causal_consistency_rule` 已证明关系表示本身有区分力；
3. 简单 `ml_relation` 已在 fresh LOFO 和外部 corpus 上超过 strongest baseline；
4. 训练数据增加的是独立设计、独立错误和不同逻辑结构，不只是更多 waveform hash；
5. 预先定义复杂模型唯一新增能力，例如对多跳、分支汇合或长时序依赖的非线性建模；
6. 固定相同 candidate universe、trace pool、split 和指标；
7. 只比较一个复杂模型，不做架构搜索；
8. 复杂模型失败时保留简单模型结果，不添加 fallback 或重选 case。

### 15. Codex 实施边界与交接清单

首次开发会话：

1. 在两个仓库分别运行 `git status --short --branch` 和 `git rev-parse HEAD`；
2. 确认计划分支尚不存在；存在时停止并报告，不自动改名；
3. 在保留 dirty worktree 的前提下创建：

```bash
git switch -c experiment/native-verilog-cause
git -C VerilogCausalAnalysis switch -c feature/native-verilog-profile
```

4. 不自动 stash/reset/clean；
5. 每次 commit 只 `git add` 当前工作包文件；
6. VCA commit 禁止包含 `install_hdlConvertor.sh`、`hdlConvertor` 或无关 `TOOLCHAIN.lock.json` 变化；
7. parent commit 禁止包含当前其他用户修改和旧 run。

每次迭代开始前：

- 读本文件第二部分；
- 明确本次只执行一个 W 包；
- 读取两仓 status 和当前 branch；
- 复核上游输入和 accepted VCA commit；
- 若 shared contract 发生 drift，停止集成而不是兼容猜测。

VCA W2 交接必须提供：

- branch 与 commit；
- 修改文件；
- public API；
- candidate/graph schema；
- focused/full test命令与结果；
- 已知 unsupported Verilog construct；
- 明确未包含 nested hdlConvertor dirty changes。

parent W1 交接必须提供：

- branch 与 commit；
- frozen case list；
- sanitizer/gold review状态；
- simulator command、version 和 run-local layout；
- manifest/schema；
- focused tests；
- 明确 correct RTL 只在 oracle side。

W3 集成者：

- 先确认 VCA commit 可从子仓库 branch 独立检出；
- 更新 parent submodule pointer；
- 运行一例 native API smoke；
- 再运行 13-case pilot；
- 只在 gate 通过后提交 integration；
- 不把 run artifacts 混入代码 commit，除非仓库已有明确 artifact policy。

每次迭代结束时报告：

- 两仓 branch/status/commit；
- 修改文件与最小 diff；
- 实际命令；
- focused tests、Ruff、compile/diff check；
- run path、输入/输出 hashes；
- data、candidate、relation、effectiveness gate 状态；
- 结论属于实现能力、simulation capability、candidate recall、fixed pilot 还是 ML effectiveness；
- 下一工作包是否得到 gate 授权。

禁止事项：

- 不修改或补写旧 ChiselCause run；
- 不把 correct RTL/diff 作为模型 feature；
- 不保留 `//buggy` 等标签泄漏；
- 不把 compile/simulate 成功称为定位成功；
- 不把 candidate recall 称为 ranking effectiveness；
- 不把 fixed pilot 称为 full-corpus evidence；
- 不把 LOBO/LODO 替代 LOFO；
- 不根据 gold rank 调 fuzz、feature、model 或 case；
- 不添加 retry、auto-repair、model fallback、compatibility reader 或调度框架；
- 不在 W3 前扩展 corpus；
- 不在 W4 前引入复杂模型；
- 不让两个仓库通过 dirty worktree 隐式耦合。

### 16. 完成定义

本路线只有在以下条件同时满足时才完成：

1. 两个计划分支按边界建立，原有 dirty changes 未丢失、未混入；
2. parent 有安全的 run-local native Verilog simulation/trace pipeline；
3. 41-case gold 全部经过 Codex review，并明确 representable/unrepresentable；
4. VCA 有不读取 Chisel 信息的 native Verilog profile；
5. executable RTL candidate universe 和 graph edge 使用 exact statement identity；
6. 13-case W3 pilot 的数据、candidate 和 relation gate 可独立停止；
7. W3 通过后完成全 41-case fresh W4；
8. all methods 共享相同 trace pool、candidate、gold、split 和 evaluator；
9. `ml_relation` 在预指定 LOFO 联合门上超过 strongest non-ML baseline；
10. parent 绑定已测试的 VCA commit，两个仓库的合并顺序和交接记录完整；
11. 文档只按实际 artifact 更新，不把计划、能力或候选召回写成效果结论。

若第 3、4、5、6、7、8 或 9 项失败，正确完成状态是保留 `failed_stop` 和完整诊断，而不是继续增加模型复杂度。

### 17. 实施进度

#### 2026-08-12：W0 完成

- parent 从 `SpecFlow` 的 `b27b35681488ce75f8852d1067ba032bb812e1c1` 创建 `experiment/native-verilog-cause`；
- VCA 从 `SpecFlow` 的 `c5ecf94a907ab9b5b824511914262bb8bac5d4cb` 创建 `feature/native-verilog-profile`；
- 第 4–7 节接口保持冻结，W1/W2 不增设兼容 schema、重试器或第二套训练器；
- parent 原有修改和未跟踪文件保持不动；VCA 的 `hdlConvertor`、`install_hdlConvertor.sh`、`TOOLCHAIN.lock.json` 明确排除在本路线修改之外；
- 决策：W1 与 W2 按仓库边界并行，W3 由 parent 集成；只有 W3 关系门通过才授权 W4。

#### 2026-08-12：W1/W2 完成

- parent W1 提交为 `8fe282bd`、`38e82f40`、`2292aeac`：新增 `src/experiments/verilogcause.py` 和一个 focused test 文件，完成 Wit-HW metadata 解析、run-local 注释清洗、Verilator correct/original-faulty/sanitized-faulty 仿真、外部输出比较、DUT-only line coverage、VCD→FST、hash-bound manifest 和 gold proposal review-stop；
- correct RTL、source diff、original faulty RTL 和 gold 只保存在 evaluator-private 目录；model input 使用无 `buggy/repair/gold/diff` 泄漏的 canonical 文件名和 sanitized faulty RTL；
- VCA W2 分支提交为 `0c2670b`、`94c7da1`、`39c3b54`：新增显式 `verilog-semantic-profile`、public `build_rtl_candidates`、candidate/graph 共用 `rtl_set_sha256`，并把 parser 的 exact `statement_id` 透传到 native semantic graph；
- `39c3b54` 修复了共享转换根因：`_convert_graph` 会排序 edge，旧上层按未排序 slicer edge `zip` 回填 `statement_id`，会造成 statement ID 与 source line 错绑。现在 Verilog 调用在排序前直接透传 ID，structural graph 默认 schema 不变；
- native profile 明确拒绝 Chisel annotations、`semantic_inputs`、projection 和 `source_provenance`，只允许 `instance_graph/register_transition/temporal_interval`；
- 当前 parser 能稳定标识 assignment、nonblocking register update 和 port binding；不提供独立 `if/case` guard identity，删除语句也不能成为 faulty-RTL candidate。该边界不使用 fuzzy/邻行回退。

检查结论：parent+VCA focused tests 为 `10 passed`；所改文件 scoped Ruff、compileall 和 diff check 通过。VCA 全量测试为 `94 passed, 3 failed`；三项失败是既有 legacy `BackwardSlicer(search_policy=None)` 两例和 unknown-X structural status 期望一例，不属于本路线修改，未添加兼容兜底。

#### 2026-08-12：W3 完成并触发 failed-stop

最终验收 run：`runs/verilogcause/20260812-native-pilot-v5`。

- 绑定 parent `3de5aeeddbfc960bc79eed54468ebbf029aa4e68` 和 VCA `39c3b54f4d8b25d76688c638d5b80f0184a877d6`；
- 13/13 case 均完成 correct/original-faulty/sanitized-faulty compile+simulation、sanitizer 外部输出等价、失败端点、coverage、VCD、FST、candidate 和 causal graph；26 条 manifest 记录完整，13/13 graph status 为 `complete`；
- Codex review 结论为 9 个 representable、4 个 unrepresentable。unrepresentable 分别是 ALU-2 的常量 default assignment 未进入依赖型 candidate universe、Counter-3 的删除语句、FSM-16-3/4 的独立 guard；没有把邻行 assignment 冒充 gold；
- 9 个 representable case 中只有 4 个 gold 进入当前动态因果图：ALU-1、ALU-4、Counter-1、Counter-2；其余明确计 `gold_unreachable`；
- exact join 审计为 13/13，通过 161 个 artifact path/bytes/SHA-256 复核；unknown statement、fuzzy mapping、Chisel provenance 和 model-input 泄漏计数均为 0；
- all-candidate relation coverage 为 `0.06607929515418502`，gold-only 为 `0.4`；`causal_trace_coverage` gold-vs-negative win rate 为 ALU `0.15789473684210525`、Counter `0.5`、FSM-16 `0.0`，family macro 为 `0.2192982456140351`，均未满足每个 family 严格大于 `0.5`；
- 固定 workload budget 为每 case 一个 metadata trigger，13 个 case 均有 failing trace、均无 passing trace，因此 13/13 为 `contrast_incomplete`；未把缺失 pass rate 写成 0；
- `relation_diagnostic.json` 和 `gate_report.json` 的最终决定为 `failed_stop`，下一动作为 `do_not_train`。

中间 run 不作为效果证据：v3 暴露 edge 排序错绑并促成共享根因修复；v4 在 gate 前因实现身份和诊断合同更新而中止；两者均未被补写或冒充 fresh 验收。

#### 决策与后续步骤

1. W3 前置门失败，按第 11、13、16 节停止；不运行 deterministic baselines、trainer、W4 全 41 case 或 W5，不扩大 corpus、不调 fuzz、不换模型；
2. 本轮正确完成状态是 `failed_stop/do_not_train`。已证明的是 native Verilog 仿真、candidate identity、exact graph join 和 13-case capability；没有 ML effectiveness 或跨 family 增量结论；
3. 若以后明确开启新迭代，按第 18 节依次修复 waveform/oracle 周期合同、statement-level execution authority 和 gold-blind contrast pool；任何 candidate、trace 或 evaluator 变化都必须使用新的 run root 重跑 W3；
4. 只有新的 W3 同时通过 candidate、relation 和 contrast gate，才授权 W4。当前不再增加代码或测试。

### 18. W3 failed-stop 根因诊断与根本修复计划

#### Material Passport

- Origin Skill：experiment-agent
- Origin Mode：run + validate + plan
- Origin Date：2026-08-12
- Verification Status：VERIFIED_FAILED_STOP（实现、focused checks 与 fresh W3-R canonical artifacts）
- Version Label：native_verilog_root_repair_plan_v1

#### 18.1 核心结论

核心问题不是模型容量、特征权重、搜索 bounds 或 edge ID，而是**训练前的 statement-level execution authority 尚未闭合**：当前 VCA 从 signal dependency edge 间接推断 statement；它能给语句稳定 ID，却不能完整回答“在失败周期，究竟是哪条 assignment/guard 真实执行并产生了目标值”。同时，parent 把 testbench 在 `posedge` 写出的 CSV 行号直接当成 VCA 的 cycle-end FST 周期，导致部分 case 在错误 workload vector 或错误状态转换上切片。固定 trace pool 又只有已知 failing trigger，没有任何 contrast。

因此 v5 的低 win rate 是上游 authority 和 sampling contract 的结果，不能通过训练器、调权、扩大 bounds 或更换模型修复。需要先修复两个共享根：

1. **VCA statement execution semantics**：从 parser AST 建立完整 executable statement universe，并对具体 `(target, cycle)` 给出 exact active assignment/guard；
2. **parent waveform/contrast contract**：correct/faulty oracle 与 VCA 使用同一 FST 时钟边界和采样相位，并在看见 gold/rank 前一次性冻结 gold-blind passing/failing workload pool。

#### 18.2 证据链

最终 v5 的关键事实如下：

| 证据 | v5 结果 | 说明 |
|---|---:|---|
| candidate 总数 | 227 | candidate universe 已生成，不是空集问题 |
| graph complete | 13/13 | 只证明搜索未异常终止，不证明 statement authority 正确 |
| representable case | 9/13 | ALU-2、Counter-3、FSM-16-3/4 缺 statement kind |
| reviewed gold statement | 10 | ALU-1 是两语句 set-valued gold，其余 representable case 各一条 |
| 有 graph relation 的 candidate | 15/227，6.61% | relation 极稀疏 |
| 有 graph relation 的 gold | 4/10，40% | 只有 ALU-1 的一条、ALU-4、Counter-1、Counter-2 |
| gold-negative pair | 146 | 其中 win/tie/loss 为 15/128/3，87.67% 为 tie |
| family win rate | ALU 0.158；Counter 0.5；FSM-16 0.0 | 三个 family 均未严格大于 0.5 |
| passing/failing | 0/13 有 passing；13/13 有 failing | contrast feature 按合同不可训练 |
| identity/leak | unknown/fuzzy/Chisel provenance/leak 均为 0 | 已排除 ID 错绑和标签泄漏作为当前主因 |

调用链显示两个具体根因。

第一，周期采样合同不一致：

```text
prepare
  -> _prepare_case
  -> compare_outputs                  # 把 output-signals.txt 行号记为 failure cycle
  -> _vca_request                     # 原样传给 endpoint.cycle
  -> build_causal_graph
  -> CycleAlignedWaveform.get_signal_value
                                      # 读取本 cycle 末、下一上升沿之前的值
```

Wit-HW testbench 在 `@(posedge clk)` 立即 `$fwrite`。组合输出记录的是当前上升沿值；nonblocking register output 通常是该上升沿更新前的值。VCA 的 `get_signal_value(cycle)` 则读取下一上升沿前的 cycle-end 值。两者不能用同一个整数行号直接连接。

- ALU-3：CSV 的 `cycle=1/time=15` 为 `y=0, zero=0`，真实 faulty statement 是 line 26；v5 graph 的 `cycle=1` 却读到下一 vector 的 `y=0xBF`，选择 line 28。只读诊断把 endpoint 临时移到 cycle 0 后，gold line 26 才进入 graph；
- ALU-5/6：两个分支的 faulty RHS 相同，仅靠 observed RHS 会选择第一条匹配分支，无法证明真正 guard；
- FSM-16-1/2：v5 分别应定位 line 79/101，graph 却都选择 line 34 的 `state==S0 -> S1`。这不仅有输出/波形相位错位，还暴露 sequential guard 没有用 `state(t-1)` 和 localparam 常量做 exact evaluation。

第二，candidate/graph 以 dependency 为中心，而 evaluator 的对象是 executable statement：

- `_process_assignment` 会记录 assignment，但 `_process_statement` 没有形成独立 guard candidate；case default 也没有完整进入当前 AST traversal，所以 ALU-2 line 22 缺失；
- Counter-3 是删除型错误，faulty RTL 中没有被删 assignment；没有 exact conditional guard candidate 时不能映射到最小 executable enclosure；
- FSM-16-3/4 的错误本身是 guard，assignment candidate 不能代替 guard gold；
- constant assignment 没有 RHS signal dependency；即使 assignment 在 candidate 中，也可能没有可承载它的 positive dependency edge；
- sequential state guard 中的 target self-state 被 dependency self-loop 过滤，S0/S7 等 localparam 又没有进入完整 value environment，因此“RHS 与 observed target 相同”的第一条语句会被误当成活动分支；
- 当前 `causal_trace_coverage` 以 `contribution.status=supported && score>0` 为存在条件，把“语句真实执行”与“某个输入 toggle 有正贡献”混为一件事。常量写和控制 guard 即使真实执行，也可能合法地没有正 RHS contribution。

#### 18.3 根本修复原则

只修共享根，不增加模型或兜底：

1. **同相位比较**：failure endpoint 必须由 correct/faulty FST 在同一 rising-edge cycle-end sampler 上比较得到；legacy CSV 只保留为 simulator diagnostic，不再提供 VCA cycle；禁止用固定 `cycle-1` 猜测，因为首周期、组合逻辑和 NBA 的相位并不等价；
2. **statement-first**：parser 先建立 executable statement records，再由 records 派生 dependency；candidate 不再由“是否有 source edge”决定；
3. **activation 与 contribution 分离**：`active_exact` 回答“哪条语句执行”，intervention contribution 回答“哪个输入变化有贡献”。ranking relation coverage 使用前者，contribution score 仍作为独立可用特征；
4. **exact 或停止**：guard、case default、localparam、previous-state 任一无法精确求值时记录 `ambiguous|unavailable` 并使 gate 失败；不采用首个 RHS 匹配、邻行、basename、fuzzy 或 correct-RTL runtime fallback；
5. **fresh run**：sampling、candidate、graph 或 trace pool 任一改变都创建新 run root；v5 永久保留为本次诊断证据，不补写。

#### 18.4 分步实施计划

##### R0：冻结修复合同

在编码前只冻结以下最小变化：

- `rtl_candidate_universe.v2` 新增 `conditional_guard`，继续使用 `(artifact_id, statement_id)` identity；
- semantic graph 复用已有 `semantic_nodes/edges`，不增加第二套 graph：
  - `semantic_nodes.type=rtl_statement_activation`，节点携带 exact `semantic_id/artifact_id/statement_id/target_node_id/cycle/activation_status`；
  - relation 只增加 `active_statement_write` 和 `active_guard`，从 activation semantic node 指向对应 signal node；
  - 每条 relation 必须重复 exact `artifact_id/statement_id/target_node_id/cycle/activation_status`，且 `dst_node_id == target_node_id`；
- manifest 明确记录 `endpoint_sampling=cycle_end_before_next_rising` 和 workload-pool hash；
- manifest 中 trace 以 `(case_id, trace_id)` 唯一标识，每条 failing trace 写独立 graph 路径；candidate universe 每 case 只构建一次，并确定性使用排序后的第一条 failing trace 组成 VCA request；
- `causal_trace_coverage` 改为每条 failing trace 中 candidate 是否存在 `active_exact` statement relation；input contribution 不再决定 statement 是否存在；
- 旧 v1 artifact 不加 compatibility reader，新的 W3-R 只读 v2 contract。

验收：先用一个 contract test 固定上述字段；parent/VCA 任一侧若需要新增其他 schema，停止并先更新本节。

##### R1：修复 waveform/oracle 对齐（parent）

修改范围限定在 `src/experiments/verilogcause.py` 和现有 focused test：

1. correct、original-faulty、sanitized-faulty 都保留可转换的 run-local trace；correct/original trace 继续放 evaluator-private；
2. 使用现有 `CycleAlignedWaveform`、同一个 exact DUT clock、同一个 cycle-end sampler 读取公开 output；
3. correct 与 sanitized-faulty 按 `(exact output signal, cycle-end cycle)` 比较，直接产生 failure endpoint；
4. original-faulty 与 sanitized-faulty 使用同一 sampler 做 sanitizer equivalence；
5. CSV 仍保存用于人工检查，但其行号不再进入 `_vca_request`；
6. manifest 记录两侧 FST hash、clock identity、sampling phase 和 first divergent values。

最小测试只保留一个参数化用例，覆盖组合和 sequential 两种相位：ALU-3 必须在 cycle-end cycle 0 观察到 correct/faulty `zero=1/0`；FSM-16-1 必须在 cycle-end cycle 4 观察到 correct/faulty `state=0/1`。测试不得用手工 `cycle-1` 修正；active statement line 由 R3 的集成检查负责。

R1 gate：同一 case 的 oracle faulty endpoint value 必须与 VCA endpoint signal/cycle 读取值逐位一致；13 case 任一不一致即 `failed_stop`，不进入 candidate 修复效果判断。

##### R2：补齐 executable statement universe（VCA）

修改范围限定在 parser、candidate builder 和一个 focused test：

1. 复用 hdlConvertor AST walker，为以下现存 executable entity 统一生成 `StatementEvidence`：
   - blocking/nonblocking assignment；
   - case item 和 default 内 assignment；
   - `if/else-if`、case-item guard；
   - port binding；
2. guard identity 使用 module、规范化完整 guard path 和所包围 statement IDs；line 只用于 evidence/display，不进入 stable ID；若 identity collision 或 source position 不精确则 fail closed；
3. 收集 parameter/localparam 的 exact constant value environment；不能解析的常量显式 unavailable；
4. `build_rtl_candidates` 直接遍历 statement records，不要求该 statement 已产生 dependency edge；
5. 删除型 gold 仍不自动生成不存在的 assignment。Counter-3 只能由 Codex 在 review 时决定是否映射到 exact reset guard 这个最小现存 executable enclosure。

R2 focused acceptance：

- ALU-2 line 22 default assignment 出现在 candidate；
- Counter-3 reset guard 有 exact candidate；
- FSM-16-3 line 88 和 FSM-16-4 lines 45/52 是 `conditional_guard`；
- 原 assignment/register-update/port-binding identity 仍通过 line-insertion stability check；
- 无 source 的 constant assignment 仍进入 universe。

##### R3：建立 exact statement activation relation（VCA）

这是核心修复，复用现有 parser records、waveform reader 和 expression evaluator，不新建分析框架：

1. 对 causal slice 访问到的每个 `(target signal, cycle)`，枚举该 target 的 statement records；
2. combinational statement 在同 cycle-end snapshot 求值完整 guard path 和 RHS；
3. sequential statement 用 `guard/RHS@cycle-1`、`observed target@cycle`；target register 的 previous-state 必须参与 guard，不能因 dependency self-loop 过滤而丢失；
4. 用 parameter/localparam environment 解析 S0、S7 等状态常量；
5. 仅当完整 guard 为 true 且 RHS 与 observed target exact match 时输出 `active_statement_write(active_exact)`；其 enclosing guard 同时输出 `active_guard(active_exact)`；
6. 多条语句同时满足时保留 `ambiguous` 诊断，不选择 source order 第一条；X/Z、缺 operand、常量未解析均为 `unavailable`；
7. constant RHS 无需伪造 source dependency，也能通过 statement activation relation 进入 graph；
8. 现有 signal dependency/contribution edges 保留，供 contribution 特征使用，但不再承担 statement reachability authority。

R3 的最小反例检查固定为：

| Case | exact active statement 期望 |
|---|---|
| ALU-3 | line 26，而不是 line 28 |
| ALU-5 | line 28，而不是 line 26 |
| ALU-6 | line 34，而不是 line 32 |
| FSM-16-1 | line 79，而不是 line 34 |
| FSM-16-2 | line 101，而不是 line 34 |

这些是根因回归检查，不是只对五个 case 写 special case。实现中禁止 case name、line number或 gold 条件分支。

##### R4：冻结 gold-blind contrast pool（parent）

候选、gold review 和 rank 可见前，对每个 metadata trigger 一次性生成同一规则的最多四个 workload：

1. 原始完整 trigger；
2. 只保留第一条 input vector；
3. 与原 workload 行数相同的全零 vectors；
4. 与原 workload 行数相同的全一 vectors；
5. 按 workload SHA-256 去重；字节级保留逗号格式和末尾无换行约束。

规则只读取 metadata input widths 和 trigger bytes，不读取 correct output、gold、candidate、graph 或既有 pass/fail。四个 workload 全部生成后才运行 correct oracle 分类，禁止“生成到出现 passing 为止”。

为避免重复编译，直接复用 Verilator binary：每个 correct/original-faulty/sanitized-faulty 版本只编译一次，每个 workload 在独立 trace cwd 执行并产生独立 output、coverage、VCD/FST。无需 scheduler、retry 或 cache framework。

R4 gate：每个 case 至少一条 failing 和一条 passing faulty-design trace；如果固定池仍缺一类，记录 `contrast_incomplete` 并停止，不追加第五个定制 workload。

##### R5：重新审查 gold 并运行 fresh W3-R

R1–R4 合入并提交后创建全新 run root，严格执行：

```text
prepare all four workloads
  -> stop_for_codex_gold_review
  -> build rtl_candidate_universe.v2
  -> Codex review 13-case gold/representability
  -> build failing-trace causal graphs
  -> exact candidate/activation join
  -> relation diagnostic
  -> gate
```

W3-R 必须同时满足：

1. 13/13 manifest、sanitizer equivalence、FST endpoint sampling 完整且 hash-bound；
2. 13/13 有 Codex review decision；预期 guard/enclosure 修复后可到 13/13 representable，但必须由实际 candidate review 确认，不能预填；
3. 每个 representable gold 至少一个 statement 在至少一条 failing trace 中为 `active_exact`；
4. `ambiguous/unavailable/unknown/fuzzy/Chisel provenance/leak` 均为 0；
5. 13/13 各有至少一条 failing 和 passing trace；
6. all-candidate、gold-only activation coverage 完整报告；
7. `causal_trace_coverage` gold-vs-negative win rate 在 ALU、Counter、FSM-16 三个 family 都严格大于 0.5。

任何一项失败仍写 `failed_stop/do_not_train`。只有全部通过才运行计划中已有 deterministic baselines；W3-R 仍不训练 ML。

##### R6：W4 条件授权

只有 W3-R 和 deterministic baseline gate 都通过，才冻结 41-case candidate/gold/trace/split 并创建 fresh W4。W4 之前不实现新模型；继续复用现有 deterministic averaged pairwise perceptron。若 full corpus 暴露新 statement kind，回到 R2 修共享 parser 并重新运行 fresh W3-R，不在 W4 加 case-local fallback。

#### 18.5 并行边界、冲突点与合并顺序

R0 完成后可并行两条仓库隔离工作线：

| 工作线 | 顺序 | 写入范围 | 共享接口 | 主要冲突 |
|---|---|---|---|---|
| parent | R1 -> R4 | `src/experiments/verilogcause.py`、现有 focused test | endpoint sampling、workload/manifest schema | 两项都改同一 parent 文件，必须同线串行 |
| VCA | R2 -> R3 | parser、engine/candidate、现有 focused test | candidate v2、activation relation | R2/R3 共用 statement records，必须同线串行 |

同步点：

1. R0 双方确认 candidate/graph/manifest exact fields；
2. R1 给出 ALU-3/FSM-16-1 phase smoke；
3. R2 给出四类此前缺失 candidate；
4. R3 给出五个错误分支回归；
5. parent 只在 VCA commit 完成后更新 gitlink；
6. 集成者运行 focused tests 和一个 native API smoke，再创建 W3-R run。

最终合并顺序：

```text
VCA R2 candidate commit
  -> VCA R3 activation commit
  -> parent R1 sampling commit
  -> parent R4 contrast-pool commit
  -> parent 更新 VCA gitlink
  -> focused checks / one-case smoke
  -> fresh W3-R artifacts
  -> 文档记录 gate 结论
```

#### 18.6 明确不做

- 不调整当前十项 feature 的权重，不增加 GNN/Transformer；
- 不扩大 causal search bounds；
- 不用 `cycle-1`、邻行、首个 RHS match 或 case-specific mapping；
- 不自动把 deletion 映射为 assignment；
- 不添加旧 v1 run reader、retry、auto-repair、scheduler 或第五个自适应 workload；
- 不在 W3-R 通过前运行 41-case、LOBO/LODO/LOFO 或报告 ML effectiveness。

#### 18.7 当前状态与下一步

##### 2026-08-12：R0–R5 已实施，fresh W3-R 保持 failed-stop

实现提交与范围：

- VCA `db6ba515a29b384c461298105a00b4951ec6b3da` 完成 R2/R3：parser 建立 assignment/register-update/port-binding/conditional-guard records，补齐 case default 与 parameter/localparam value environment；candidate 升为 `rtl_candidate_universe.v2`；native graph 增加 hash-bound `rtl_statement_activation`、`active_statement_write` 和 `active_guard`，组合逻辑在同 cycle 求值、时序逻辑在 `cycle-1` 求 guard/RHS 并与 `cycle` target exact 比较；
- parent `feae02133da1542dcccd28a1fc5f777b7d2d64b8` 完成 R1/R4 与集成：correct/original-faulty/sanitized-faulty 各编译一次，固定生成 trigger/first-vector/all-zero/all-one 四个 gold-blind workload 并按 SHA-256 去重；每个 workload 独立运行并保留 VCD/FST/coverage；oracle 与 sanitizer equivalence 都使用 exact `testbench.DUT.clk` 的 `cycle_end_before_next_rising` sampler；多 failing trace 各写独立 graph，passing trace 只提供 execution contrast；
- parent consumer 同时校验 candidate v2、activation semantic node/edge、status enum、target/cycle identity、proposal/candidate/RTL hashes；`causal_trace_coverage` 和 `gold_reachable` 只接受 `active_exact`，contribution 保持独立特征；
- 没有新增兼容 reader、retry、scheduler、第五个自适应 workload、新模型或 case/line 特判；旧 v5 未修改，原有两仓 dirty worktree 未混入提交。

Focused checks：

- VCA `test_verilog_profile.py + test_rtl_candidates.py` 为 `6 passed`；其中一个参数化真实 Wit-HW 检查确认 ALU-2 line 22、Counter-3 line 38、FSM-16-3 line 88、FSM-16-4 lines 45/52 candidate，以及 ALU-3/5/6、FSM-16-1/2 五个 exact active branch；
- parent `tests/test_verilogcause.py` 为 `7 passed`，包含 ALU-3 cycle 0 `zero=1/0` 和 FSM-16-1 cycle 4 `state=0/1` 的同相位 oracle；两仓 scoped Ruff、compileall 和 diff check 通过。

fresh run：`runs/verilogcause/20260812-native-pilot-w3r`。

- dataset contract 绑定上述 parent/VCA commit；13/13 case、52/52 trace 完整，每 case 固定 4 个 workload，22 条 failing、30 条 passing，13/13 contrast complete；
- candidate v2 共 528 条；13/13 gold 由 Codex 重新审查并分别绑定 proposal/candidate/RTL hash，没有复制 v5 review；ALU-2 constant default、Counter-3 reset guard、FSM-16-3 guard 和 FSM-16-4 两个交换 guard 均进入 exact candidate；
- 22/22 failing graph 为 `complete`，共 66 条 `active_exact` relation；activation ambiguous/unavailable、unknown statement、fuzzy mapping、Chisel provenance 和 trainer-visible leak 均为 0；
- 12/13 case 的 reviewed gold 有 `active_exact`，all-candidate activation coverage 为 `0.07196969696969698`，gold-only 为 `0.8`；
- family gold-vs-negative win rate 为 ALU `0.945273631840796`、Counter `0.47058823529411764`、FSM-16 `0.7766749379652605`；Counter 未严格大于 `0.5`；
- 唯一 authority failure 是 Counter-3：faulty RTL 删除了 `counter_out` reset write，reviewed reset guard 虽是 exact executable candidate，但 `counter_out` 失败 slice 中没有可由 faulty RTL 证明的 enclosing active write，因此该 gold 的 `causal_trace_coverage=0`。没有用 correct diff、邻行或任意 guard attachment 伪造 relation。

最终结论与决策：

1. `relation_diagnostic.json` 与 `gate_report.json` 的权威决定为 `failed_stop/do_not_train`；失败项只有 `counter-3:gold_unreachable` 和 `counter:pairwise_win_rate_not_above_half`；
2. 已证明的是 R1–R4 实现能力、同相位 native Verilog simulation capability、13/13 candidate representability 和 12/13 exact activation authority；没有 deterministic baseline、ML effectiveness、跨 family 增量或 41-case 证据；
3. 按 R5 gate 不运行 baseline，不授权 R6/W4，不调用旧 Chisel trainer，也不因 ALU/FSM 提升而覆盖 Counter failure；
4. 下一步只能先在新的预冻结合同中解决 deletion gold 的任务定义：要么把 faulty RTL 中不存在且无法获得 exact target activation 的 deletion 明确为 `gold_unrepresentable`，要么定义不读取 correct diff 的 exact missing-write authority。该决策必须在看新 run 结果前冻结，并使用全新 run root 重跑 R5；当前 run 不补写、不重审 gold。

canonical hashes：

- `dataset_contract.json`: `ffaa3cb23024f89f24da2e8a0616446cc1367a0f780b8ad296a4eb356b732d5f`
- `manifest.jsonl`: `ee977b0997b45a4742efc4e52fa7e1c5697ff142a536dc3d5ec2ec6f9a8a10f3`
- `samples.jsonl`: `4efc688efab5ac51355f51798fb93e7a5f434d521d32607edcbf59ece82e9639`
- `evaluator_labels.jsonl`: `94b286caf12989ada2c6842937fc603ee0db96a09def5cb76e43635399cecb46`
- `relation_diagnostic.json`: `7465712773732fe8e25bd6d0f2ada4f789f49030bd076aa4be4c66e440a0137a`
- `gate_report.json`: `44c1d2e4b093f7532426629b891e9e129166558ec517ff992a57a0a9d2849fda`
