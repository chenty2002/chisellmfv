# Verilog Causal Analysis - 因果分析模块

## 概述 (Overview)

本模块实现了一个基于**因果推断**的硬件反例根因分析框架，通过构建因果有向无环图 (Causal DAG) 来自动定位硬件验证失败的根本原因。

**核心思想**：当形式化验证工具报告断言失败时，我们不仅需要知道**什么信号**在**哪个周期**取了**错误的值**（这是反例波形告诉我们的），更需要知道**为什么**这个信号会取这个值——即追溯其因果链条，最终找到**根本原因** (Root Cause)。

## 特性

- 🔍 **自动根因分析**: 从断言失败点自动反向追溯因果链
- 📊 **可视化输出**: 支持 JSON、DOT、PNG、SVG、PDF 多种输出格式
- 🧠 **反事实评估**: 使用 Pearl 因果推断理论进行因果性验证
- 🔧 **完整 RTL 上下文**: 集成代码行号和表达式信息

## 架构设计 (Architecture)

### 四层模块架构

```
┌─────────────────────────────────────────────────────────────┐
│                  CausalGraphBuilder                         │
│  (高层API: 协调各模块，提供统一接口)                           │
└─────────────────────────────────────────────────────────────┘
                            │
        ┌───────────────────┼───────────────────┐
        │                   │                   │
        ▼                   ▼                   ▼
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│VerilogParser │   │CycleAligned  │   │BackwardSlicer│
│              │   │Waveform      │   │              │
│(RTL依赖分析)  │   │(波形解析)      │   │(因果切片)     │
└──────────────┘   └──────────────┘   └──────────────┘
```

### 数据流 (Data Flow)

```
FST波形文件 ──┐
             ├─→ CycleAlignedWaveform ──┐
Clock信号  ──┘                           │
                                        ├─→ BackwardSlicer ──→ Causal DAG
Verilog源码 ───→ VerilogParser ─────────┘
```

## 安装

### 1. 克隆仓库

```bash
git clone --recursive https://github.com/your-org/VerilogCausalAnalysis.git
cd VerilogCausalAnalysis
```

### 2. 运行安装脚本

```bash
bash init.sh
```

## 快速开始

### 命令行使用

使用 `analyze.py` 命令行工具进行因果分析：

```bash
# 完全自动检测模式（推荐）
# - 从 FST 文件名解析断言信号名
# - 从波形分析断言触发的周期
# - 从波形顶层模块自动查找 clock/clk 信号
python analyze.py \
    --fst tests/philo4.System_should_not_deadlock_when_all_philosophers_are_hungry.fst \
    --verilog tests/TestTop.sv \
    --output output/philo4

# 手动指定时钟信号（其他参数自动检测）
python analyze.py \
    --fst tests/philo4.System_should_not_deadlock_when_all_philosophers_are_hungry.fst \
    --verilog tests/TestTop.sv \
    --clock philo4.clock \
    --output output/philo4

# 完全手动指定所有参数
python analyze.py \
    --fst tests/philo4.System_should_not_deadlock_when_all_philosophers_are_hungry.fst \
    --verilog tests/TestTop.sv \
    --clock philo4.clock \
    --endpoint "philo4.System_should_not_deadlock_when_all_philosophers_are_hungry" \
    --cycle 298 \
    --output output/philo4

# 列出可用的断言信号（从 Verilog 源码提取 SVA）
python analyze.py \
    --fst tests/philo4.System_should_not_deadlock_when_all_philosophers_are_hungry.fst \
    --verilog tests/TestTop.sv \
    --list-signals
```

### 命令行参数

| 参数 | 缩写 | 必需 | 说明 |
|------|------|------|------|
| `--fst` | `-f` | ✓ | FST 波形文件路径 |
| `--verilog` | `-v` | ✓ | Verilog/SV 源文件（可多个） |
| `--clock` | `-c` | | 时钟信号名称（不指定则自动检测） |
| `--output` | `-o` | ✓ | 输出目录 |
| `--endpoint` | `-e` | | 端点信号名（不指定则从文件名解析） |
| `--cycle` | `-n` | | 端点周期（不指定则从波形检测断言触发时刻） |
| `--max-depth` | `-d` | | 最大追溯深度（默认: 20） |
| `--max-nodes` | `-m` | | 最大节点数（默认: 200） |
| `--format` | | | 图像格式: png/svg/pdf（默认: png） |
| `--dpi` | | | 图像分辨率（默认: 300） |
| `--list-signals` | | | 列出可用断言信号后退出 |
| `--quiet` | `-q` | | 静默模式 |


### Python API 使用

```python
from verilog_causal_analysis import CausalGraphBuilder

# 创建构建器
builder = CausalGraphBuilder(
    fst_path="counterexample.fst",
    verilog_paths=["design.v", "testbench.v"],
    clock_signal="clk"
)

# 构建因果图
result = builder.build(
    endpoint_signal="assertion_fail",
    endpoint_cycle=100
)

# 导出结果
builder.export_json("output/causal_graph.json")
builder.export_graph("output/causal_graph.png")  # 直接生成图像
builder.export_dot("output/causal_graph.dot")

# 获取摘要
print(builder.get_natural_language_summary())

# 关闭资源
builder.close()
```



tests目录下有若干子目录，每个子目录包括了一个benchmark（Chisel代码+Verilog代码+所有触发断言的波形图+对其中某一个反例分析生成的波形图），使用因果图分析，对所有benchmark进行因果图生成测试：每个benchmark仅需任选一个波形图，生成结果到results目录下对应目录；之后，对照dot格式因果图与verilog/chisel代码，检查因果图分析是否有任何形式的错误