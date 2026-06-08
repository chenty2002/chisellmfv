#!/bin/bash
# VerilogCausalAnalysis 初始化脚本
# 用途: 安装所有依赖并配置环境
# 使用: bash init.sh [conda环境名称]
# 默认环境名称: llm

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
print_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
print_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
print_error() { echo -e "${RED}[ERROR]${NC} $1"; }

ENV_NAME="${1:-llm}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

print_info "开始安装 VerilogCausalAnalysis..."

# 检查 conda
if ! command -v conda &> /dev/null; then
    print_error "未找到 conda，请先安装 Anaconda 或 Miniconda"
    exit 1
fi

# 初始化 git 子模块
print_info "初始化 git 子模块..."
cd "$SCRIPT_DIR"
git submodule update --init --recursive
print_success "子模块初始化完成"

# 升级 pip
print_info "升级 pip 和相关工具..."
pip install --upgrade pip setuptools wheel build

# 安装 hdlConvertor
print_info "安装 hdlConvertor..."
bash install_hdlConvertor.sh "$ENV_NAME"

# 安装 Python 依赖
print_info "安装 Python 依赖..."
pip install -r requirements.txt

# 安装当前包
print_info "安装 verilog_causal_analysis 包..."
pip install -e .

print_success "安装完成!"

echo ""
echo "============================================================"
echo "VerilogCausalAnalysis 安装成功!"
echo ""
echo "使用示例:"
echo "  from verilog_causal_analysis import CausalGraphBuilder"
echo ""
echo "  builder = CausalGraphBuilder("
echo "      fst_path='counterexample.fst',"
echo "      verilog_paths=['design.v'],"
echo "      clock_signal='TestTop.clock'"
echo "  )"
echo "============================================================"
