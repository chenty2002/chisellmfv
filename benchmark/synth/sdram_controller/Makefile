SBT ?= sbt
PYTHON ?= python3
GENERATED_DIR ?= generated

.PHONY: all compile verilog check-rtl equivalence four-state clean

all: verilog

compile:
	$(SBT) compile

verilog:
	$(SBT) "runMain withw.sdramcontroller.EmitSdramControllerVariants $(GENERATED_DIR)"

check-rtl: verilog
	$(PYTHON) formal/check_generated.py --generated-root $(GENERATED_DIR)

equivalence: check-rtl
	$(PYTHON) formal/run_equivalence.py --generated-root $(GENERATED_DIR)
	$(PYTHON) formal/run_four_state.py --generated-root $(GENERATED_DIR)

four-state: check-rtl
	$(PYTHON) formal/run_four_state.py --generated-root $(GENERATED_DIR)

clean:
	rm -rf $(GENERATED_DIR) formal/results formal/four_state_results formal/__pycache__ target project/target project/project
