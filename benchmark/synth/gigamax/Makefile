SBT ?= sbt
PYTHON ?= python3
VERILATOR ?= verilator
GENERATED_DIR ?= generated

.PHONY: all compile verilog lint static-check check clean

all: check

compile:
	$(SBT) compile

verilog:
	$(SBT) "runMain withw.gigamax.EmitGigamaxVariants $(GENERATED_DIR)"

lint: verilog
	$(VERILATOR) --lint-only -Wall -Wno-fatal $(GENERATED_DIR)/gigamax/main.sv
	$(VERILATOR) --lint-only -Wall -Wno-fatal $(GENERATED_DIR)/gigamax_fixed/main.sv
	$(VERILATOR) --lint-only -Wall -Wno-fatal $(GENERATED_DIR)/gigamax_paper_deadlock/main.sv

static-check: verilog
	$(PYTHON) audit/check_generated.py --generated-root $(GENERATED_DIR) \
		--output audit/generated_check.json

check: compile lint static-check

clean:
	rm -rf $(GENERATED_DIR) target project/target project/project audit/generated_check.json
