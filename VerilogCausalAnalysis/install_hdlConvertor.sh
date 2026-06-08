#!/bin/bash
# hdlConvertor 自动安装脚本
# 用途: 在conda环境中安装hdlConvertor库
# 使用: bash install_hdlConvertor.sh [环境名称]
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

print_info "开始在conda环境 '${ENV_NAME}' 中安装 hdlConvertor..."

# 检查是否安装了conda
if ! command -v conda &> /dev/null; then
    print_error "未找到conda命令，请先安装Anaconda或Miniconda"
    exit 1
fi

print_success "找到conda: $(which conda)"

# 检查环境是否存在
if ! conda env list | grep -q "^${ENV_NAME} "; then
    print_warning "环境 '${ENV_NAME}' 不存在，正在创建..."
    conda create -n "${ENV_NAME}" python=3.11 -y
    print_success "环境 '${ENV_NAME}' 创建成功"
fi

# 检查必需的系统工具
print_info "检查系统工具..."
MISSING_TOOLS=()

for tool in gcc g++; do
    if ! command -v $tool &> /dev/null; then
        MISSING_TOOLS+=($tool)
    fi
done

if [ ${#MISSING_TOOLS[@]} -ne 0 ]; then
    print_warning "缺少以下工具: ${MISSING_TOOLS[*]}"
    print_warning "某些功能可能无法正常工作"
else
    print_success "所有必需的系统工具已安装"
fi

# 安装构建依赖
print_info "安装构建依赖..."
conda run -n "${ENV_NAME}" pip install --upgrade pip setuptools wheel
conda run -n "${ENV_NAME}" pip install Cython pybind11 ninja cmake
conda run -n "${ENV_NAME}" pip install "meson>=1.2.3,<1.6" meson-python
print_success "构建依赖安装完成"

# 进入 hdlConvertor 目录
cd "$SCRIPT_DIR/hdlConvertor"

# 清理之前的构建
print_info "清理之前的构建目录..."
rm -rf build .mesonpy-* subprojects/antlr4-runtime
print_success "构建目录清理完成"

# 安装 hdlConvertor
print_info "从本地源码安装 hdlConvertor..."
print_warning "此步骤可能需要几分钟时间，请耐心等待..."

if conda run -n "${ENV_NAME}" pip install . --no-build-isolation; then
    print_success "hdlConvertor安装成功！"
else
    print_error "hdlConvertor安装失败！"
    exit 1
fi

# 验证安装
print_info "验证安装..."
if conda run -n "${ENV_NAME}" python -c "from hdlConvertor import HdlConvertor; print('导入成功')" 2>/dev/null; then
    print_success "安装验证通过！"
else
    print_error "安装验证失败！"
    exit 1
fi

print_success "hdlConvertor 安装完成！"
