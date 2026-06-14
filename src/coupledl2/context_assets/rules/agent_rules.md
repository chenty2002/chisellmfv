# CoupledL2 Agent Rules

- Only read and write files inside the run workspace.
- Use indexed context and line-limited reads before broad source inspection.
- Do not paste whole generated Verilog, HuanCun, rocket-chip, or waveforms into
  prompts.
- Assertions must appear in the emitted design.
- JasperGold timeout or inconclusive status is not a pass.
