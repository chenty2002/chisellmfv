SBT_VERSION ?= 1.10.3
SBT_LAUNCH ?= .cache/sbt-launch-$(SBT_VERSION).jar
SBT_LAUNCH_URL ?= https://repo1.maven.org/maven2/org/scala-sbt/sbt-launch/$(SBT_VERSION)/sbt-launch-$(SBT_VERSION).jar
CURL ?= curl
MILL ?= mill

ifeq ($(origin SBT), undefined)
SBT := java -jar $(SBT_LAUNCH)
SBT_PREREQ := $(SBT_LAUNCH)
endif

.DEFAULT_GOAL := test

.PHONY: test sbt-test mill-test test-all clean

test: sbt-test

sbt-test: $(SBT_PREREQ)
	$(SBT) test

mill-test:
	$(MILL) root.test

test-all: sbt-test mill-test

clean:
	$(SBT) clean

$(SBT_LAUNCH):
	mkdir -p $(dir $@)
	$(CURL) -fL -o $@ $(SBT_LAUNCH_URL)
