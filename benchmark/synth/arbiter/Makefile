SBT ?= sbt
PYTHON ?= python3
GENERATED_DIR ?= generated

.PHONY: all compile verilog equivalence clean

all: verilog

compile:
	$(SBT) compile

verilog:
	$(SBT) "runMain withw.arbiter.EmitArbiterVariants $(GENERATED_DIR)"

equivalence: verilog
	$(PYTHON) formal/run_equivalence.py --generated-root $(GENERATED_DIR)

clean:
	rm -rf $(GENERATED_DIR) formal/results formal/__pycache__ target project/target project/project
