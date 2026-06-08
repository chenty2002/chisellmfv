package llmverify

import circt.stage.ChiselStage

object VerilogGenerator extends App {
  ChiselStage.emitSystemVerilogFile(
    gen = new MatrixMul(N = 2, W = 8),
    args = args
  )
}
