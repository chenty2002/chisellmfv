SBT ?= sbt
PYTHON ?= python3
IVERILOG ?= iverilog
GENERATED_DIR ?= generated

.PHONY: all verify-sources compile verilog syntax four-state equivalence clean

all: verilog

verify-sources:
	$(PYTHON) formal/verify_sources.py

compile: verify-sources
	$(SBT) compile

verilog: verify-sources
	$(SBT) "runMain withw.sha3.EmitSha3Variants $(GENERATED_DIR)"

syntax: verilog
	@set -e; for file in $(GENERATED_DIR)/*/keccak.sv; do \
		stem=$$(basename $$(dirname $$file)); \
		$(IVERILOG) -g2012 -s keccak -o /tmp/wit_hw_sha3_$$stem.vvp $$file; \
	done

four-state: syntax
	$(PYTHON) formal/run_four_state.py --generated-root $(GENERATED_DIR)

equivalence: syntax
	$(PYTHON) formal/run_equivalence.py --generated-root $(GENERATED_DIR)
	$(PYTHON) formal/run_four_state.py --generated-root $(GENERATED_DIR)

clean:
	rm -rf $(GENERATED_DIR) formal/results formal/__pycache__ target project/target project/project
