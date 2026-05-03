package llmverify

import chisel3._
import chisel3.util._

// Constants
object Constants {
  val ADD = 0.U(2.W)
  val JMP = 1.U(2.W)
  val AND = 2.U(2.W)
  val XOR = 3.U(2.W)
  
  val INSTR_WIDTH = 11
  val OPCODE_WIDTH = 2
  val DATA_ADDR_WIDTH = 2
  val PROG_ADDR_WIDTH = 2
  val DATA_WIDTH = 4
}

// Top-level processor module
class adnanProc extends Module {
  val io = IO(new Bundle {
    // Debug outputs to preserve signals
    val aluOut = Output(UInt(Constants.DATA_WIDTH.W))
    val memOut1 = Output(UInt(Constants.DATA_WIDTH.W))
    val memOut2 = Output(UInt(Constants.DATA_WIDTH.W))
    val instruction = Output(UInt(Constants.INSTR_WIDTH.W))
    val opcode = Output(UInt(Constants.OPCODE_WIDTH.W))
    val progCntr = Output(UInt(Constants.PROG_ADDR_WIDTH.W))
  })
  
  // Internal wires
  val aluOut = Wire(UInt(Constants.DATA_WIDTH.W))
  val memOut1 = Wire(UInt(Constants.DATA_WIDTH.W))
  val memOut2 = Wire(UInt(Constants.DATA_WIDTH.W))
  val readLoc1 = Wire(UInt(Constants.DATA_ADDR_WIDTH.W))
  val readLoc2 = Wire(UInt(Constants.DATA_ADDR_WIDTH.W))
  val writeLoc = Wire(UInt(Constants.DATA_ADDR_WIDTH.W))
  val instruction = Wire(UInt(Constants.INSTR_WIDTH.W))
  val opcode = Wire(UInt(Constants.OPCODE_WIDTH.W))
  val progCntr = Wire(UInt(Constants.PROG_ADDR_WIDTH.W))
  
  // Instantiate submodules
  val memory = Module(new memory())
  val program = Module(new program())
  val decodeOpcd = Module(new decodeOpcd())
  val decodeLoc1 = Module(new decodeLoc1())
  val decodeLoc2 = Module(new decodeLoc2())
  val decodeLoc3 = Module(new decodeLoc3())
  val alu = Module(new alu())
  val pc = Module(new pc())
  
  // Connect memory
  memory.io.clk := clock
  memory.io.opcode := opcode
  memory.io.readLoc1 := readLoc1
  memory.io.readLoc2 := readLoc2
  memory.io.writeLoc := writeLoc
  memory.io.data := aluOut
  memOut1 := memory.io.memOut1
  memOut2 := memory.io.memOut2
  
  // Connect program memory
  program.io.clk := clock
  program.io.progCntr := progCntr
  instruction := program.io.instruction
  
  // Connect instruction decoders
  decodeOpcd.io.clk := clock
  decodeOpcd.io.instruction := instruction
  opcode := decodeOpcd.io.opcode
  
  decodeLoc1.io.clk := clock
  decodeLoc1.io.instruction := instruction
  readLoc1 := decodeLoc1.io.readLoc1
  
  decodeLoc2.io.clk := clock
  decodeLoc2.io.instruction := instruction
  readLoc2 := decodeLoc2.io.readLoc2
  
  decodeLoc3.io.clk := clock
  decodeLoc3.io.instruction := instruction
  writeLoc := decodeLoc3.io.writeLoc
  
  // Connect ALU
  alu.io.clk := clock
  alu.io.opcode := opcode
  alu.io.operand1 := memOut1
  alu.io.operand2 := memOut2
  aluOut := alu.io.aluOut
  
  // Connect program counter
  pc.io.clk := clock
  pc.io.opcode := opcode
  pc.io.operand1 := memOut1
  pc.io.operand2 := memOut2
  progCntr := pc.io.progCntr
  
  // Debug outputs
  io.aluOut := aluOut
  io.memOut1 := memOut1
  io.memOut2 := memOut2
  io.instruction := instruction
  io.opcode := opcode
  io.progCntr := progCntr
}

// Register file (memory) module
class memory extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val opcode = Input(UInt(Constants.OPCODE_WIDTH.W))
    val readLoc1 = Input(UInt(Constants.DATA_ADDR_WIDTH.W))
    val readLoc2 = Input(UInt(Constants.DATA_ADDR_WIDTH.W))
    val writeLoc = Input(UInt(Constants.DATA_ADDR_WIDTH.W))
    val data = Input(UInt(Constants.DATA_WIDTH.W))
    val memOut1 = Output(UInt(Constants.DATA_WIDTH.W))
    val memOut2 = Output(UInt(Constants.DATA_WIDTH.W))
  })
  
  // 8 registers
  val regs = RegInit(VecInit(Seq(
    1.U(Constants.DATA_WIDTH.W),  // m0
    0.U(Constants.DATA_WIDTH.W),  // m1
    0.U(Constants.DATA_WIDTH.W),  // m2
    0.U(Constants.DATA_WIDTH.W),  // m3
    0.U(Constants.DATA_WIDTH.W),  // m4
    0.U(Constants.DATA_WIDTH.W),  // m5
    0.U(Constants.DATA_WIDTH.W),  // m6
    0.U(Constants.DATA_WIDTH.W)   // m7
  )))
  
  // Read logic using direct indexing
  io.memOut1 := regs(io.readLoc1)
  io.memOut2 := regs(io.readLoc2)
  
  // Write logic
  when(io.opcode =/= Constants.JMP) {
    regs(io.writeLoc) := io.data
  }
}

// Program ROM module
class program extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val progCntr = Input(UInt(Constants.PROG_ADDR_WIDTH.W))
    val instruction = Output(UInt(Constants.INSTR_WIDTH.W))
  })
  
  // Program instructions
  val instructions = VecInit(Seq(
    576.U,  // instr0
    1152.U, // instr1
    1728.U, // instr2
    2304.U, // instr3
    505.U,  // instr4
    0.U,    // instr5
    0.U,    // instr6
    0.U     // instr7
  ))
  
  io.instruction := instructions(io.progCntr)
}

// Opcode decoder module
class decodeOpcd extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val instruction = Input(UInt(Constants.INSTR_WIDTH.W))
    val opcode = Output(UInt(Constants.OPCODE_WIDTH.W))
  })
  
  io.opcode := io.instruction(1, 0)  // bits [1:0]
}

// Source register 1 decoder module
class decodeLoc1 extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val instruction = Input(UInt(Constants.INSTR_WIDTH.W))
    val readLoc1 = Output(UInt(Constants.DATA_ADDR_WIDTH.W))
  })
  
  io.readLoc1 := io.instruction(4, 2)  // bits [4:2]
}

// Source register 2 decoder module
class decodeLoc2 extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val instruction = Input(UInt(Constants.INSTR_WIDTH.W))
    val readLoc2 = Output(UInt(Constants.DATA_ADDR_WIDTH.W))
  })
  
  io.readLoc2 := io.instruction(7, 5)  // bits [7:5]
}

// Destination register decoder module
class decodeLoc3 extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val instruction = Input(UInt(Constants.INSTR_WIDTH.W))
    val writeLoc = Output(UInt(Constants.DATA_ADDR_WIDTH.W))
  })
  
  io.writeLoc := io.instruction(10, 8)  // bits [10:8]
}

// ALU module
class alu extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val opcode = Input(UInt(Constants.OPCODE_WIDTH.W))
    val operand1 = Input(UInt(Constants.DATA_WIDTH.W))
    val operand2 = Input(UInt(Constants.DATA_WIDTH.W))
    val aluOut = Output(UInt(Constants.DATA_WIDTH.W))
  })
  
  // ALU operations using when/else chain
  io.aluOut := 0.U(Constants.DATA_WIDTH.W)
  when(io.opcode === Constants.ADD) {
    io.aluOut := io.operand1 + io.operand2
  }.elsewhen(io.opcode === Constants.XOR) {
    io.aluOut := io.operand1 ^ io.operand2
  }.elsewhen(io.opcode === Constants.AND) {
    io.aluOut := io.operand1 & io.operand2
  }
}

// Program counter module
class pc extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val opcode = Input(UInt(Constants.OPCODE_WIDTH.W))
    val operand1 = Input(UInt(Constants.DATA_WIDTH.W))
    val operand2 = Input(UInt(Constants.DATA_WIDTH.W))
    val progCntr = Output(UInt(Constants.PROG_ADDR_WIDTH.W))
  })
  
  val progCntrReg = RegInit(0.U(Constants.PROG_ADDR_WIDTH.W))
  
  when(io.opcode === Constants.JMP && io.operand1 === 0.U) {
    progCntrReg := io.operand2(1, 0)  // bits [1:0]
  }.otherwise {
    progCntrReg := progCntrReg + 1.U
  }
  
  io.progCntr := progCntrReg
}

// Main object for generating Verilog
object VerilogGenerator extends App {
  emitVerilog(new adnanProc(), args)
}