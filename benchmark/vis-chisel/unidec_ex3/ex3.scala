package llmverify
import chisel3._
import chisel3.util._

class unidec extends Module {
  val io = IO(new Bundle {
    val sel1 = Input(UInt(3.W))
    val sel2 = Input(UInt(3.W))
    val found = Output(Bool())
  })
  
  // Internal registers
  val word = RegInit(0.U(7.W))
  val foundReg = RegInit(false.B)
  val init = RegInit(true.B)
  
  // Wire for other
  val other = Wire(UInt(7.W))
  
  // Code function implementation using Mux chain
  def code(sel: UInt): UInt = {
    Mux(sel === 0.U, "b0001010".U(7.W), // 010
    Mux(sel === 1.U, "b0011000".U(7.W), // 0001
    Mux(sel === 2.U, "b0010110".U(7.W), // 0110
    Mux(sel === 3.U, "b0010011".U(7.W), // 1100
    Mux(sel === 4.U, "b0111000".U(7.W), // 00011
    Mux(sel === 5.U, "b0101100".U(7.W), // 00110
    Mux(sel === 6.U, "b0101111".U(7.W), // 11110
    Mux(sel === 7.U, "b1110101".U(7.W), // 101011
    0.U(7.W)))))))))
  }
  
  // Prefix function implementation using Mux chain
  def prefix(wordIn: UInt, sel: UInt): UInt = {
    Mux(sel === 1.U, Mux(wordIn(6,2) === 0.U, "b0111111".U(7.W), Cat(6.U(1.W), wordIn(0))),
    Mux(sel === 2.U, Mux(wordIn(6,3) === 0.U, "b0111111".U(7.W), Cat(5.U(1.W), wordIn(1,0))),
    Mux(sel === 3.U, Mux(wordIn(6,4) === 0.U, "b0111111".U(7.W), Cat(4.U(1.W), wordIn(2,0))),
    Mux(sel === 4.U, Mux(wordIn(6,5) === 0.U, "b0111111".U(7.W), Cat(3.U(1.W), wordIn(3,0))),
    Mux(sel === 5.U, Mux(wordIn(6) === 0.U, "b0111111".U(7.W), Cat(2.U(1.W), wordIn(4,0))),
    "b0111111".U(7.W)))))
  }
  
  // Suffix function implementation using Mux chain
  def suffix(wordIn: UInt, sel: UInt): UInt = {
    Mux(sel === 1.U, Cat(0.U(1.W), wordIn(6,1)),
    Mux(sel === 2.U, Cat(0.U(2.W), wordIn(6,2)),
    Mux(sel === 3.U, Cat(0.U(3.W), wordIn(6,3)),
    Mux(sel === 4.U, Cat(0.U(4.W), wordIn(6,4)),
    Mux(sel === 5.U, Cat(0.U(5.W), wordIn(6,5)),
    0.U(7.W))))))
  }
  
  // Combinational assignment for other
  other := code(io.sel1)
  
  // Sequential logic using withReset
  withReset(reset) {
    when(reset.asBool) {
      word := code(io.sel1)
      foundReg := false.B
      init := true.B
    }.otherwise {
      foundReg := !init && (word === other)
      init := false.B
      
      when(other === prefix(word, io.sel2)) {
        // There is a code word that is a prefix of the current word.
        // Make the suffix of the current word the next word.
        word := suffix(word, io.sel2)
      }.elsewhen(prefix(other, io.sel2) === word) {
        // The current word is a prefix of another code word.
        // Make the suffix of the other word the next word.
        word := suffix(other, io.sel2)
      }.otherwise {
        // Neither applies. Go to trap state.
        word := 0.U
      }
    }
  }
  
  // Output assignment
  io.found := foundReg
}

object VerilogGenerator extends App {
  emitVerilog(new unidec(), args)
}