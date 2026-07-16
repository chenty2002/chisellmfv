SBT ?= sbt
PYTHON ?= python3
GENERATED_DIR ?= generated

.PHONY: all compile verilog equivalence four-state acceptance clean

all: verilog

compile:
	$(SBT) compile

verilog:
	$(SBT) "runMain withw.i2c.EmitI2CVariants $(GENERATED_DIR)"

equivalence: verilog
	$(PYTHON) formal/run_equivalence.py --generated-root $(GENERATED_DIR)

four-state: verilog
	$(PYTHON) formal/run_four_state.py --generated-root $(GENERATED_DIR)

acceptance: verilog
	$(PYTHON) formal/run_equivalence.py --generated-root $(GENERATED_DIR)
	$(PYTHON) formal/run_four_state.py --generated-root $(GENERATED_DIR)

clean:
	rm -rf $(GENERATED_DIR) formal/results formal/four_state_results formal/__pycache__ target project/target project/project
