# CoupledL2 Harness

Keep edits inside the run workspace copy. Preserve the existing VerifyTop
topology unless a build error proves the harness is stale.

If the existing VerifyTop harness already matches the build contract and can
emit formal RTL, do not edit source files; call `complete_stage` with the
inspected harness path and build evidence.
