# Verilog2Chisel Makefile
# Compiles Chisel code to Verilog

BUILD_DIR ?= generated

.PHONY: all
all: verilog

.PHONY: verilog
verilog:
	@echo "Compiling Chisel to Verilog..."
	@mkdir -p $(BUILD_DIR)
	sbt "runMain llmverify.VerilogGenerator --target-dir $(BUILD_DIR)"

.PHONY: clean
clean:
	rm -rf $(BUILD_DIR)
	rm -rf target project/target project/project

.PHONY: clean-all
clean-all: clean
	rm -rf *.scala

.PHONY: help
help:
	@echo "Verilog2Chisel Makefile"
	@echo "  make verilog  - Compile Chisel to Verilog"
	@echo "  make clean    - Clean build artifacts"
	@echo "  make help     - Show this help message"
