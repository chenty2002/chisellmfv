BUILD_DIR ?= generated

.PHONY: all
all: chiselfv

# Precompile the ChiselFV library (useful as a smoke test of the environment).
.PHONY: chiselfv
chiselfv:
	sbt "project chiselfv" "compile"

.PHONY: clean
clean:
	rm -rf $(BUILD_DIR)
	rm -rf target project/target project/project
	rm -rf chiselfv/target

.PHONY: clean-all
clean-all: clean
