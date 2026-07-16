VARIANTS := reed_solomon_decoder reed_solomon_decoder_buggy_1 reed_solomon_decoder_buggy_2 reed_solomon_decoder_buggy_3

.PHONY: generate lint four-state sec verify clean-formal

generate:
	@for variant in $(VARIANTS); do \
		sbt "run $$variant generated/$$variant" || exit $$?; \
	done

lint:
	@for variant in $(VARIANTS); do \
		verilator --lint-only --timing -Wno-fatal -Wno-INITIALDLY generated/$$variant/RS_dec.sv || exit $$?; \
	done

four-state:
	python formal/check_four_state.py

sec:
	python formal/run_equivalence.py

verify: generate lint four-state sec

clean-formal:
	rm -rf formal/results
