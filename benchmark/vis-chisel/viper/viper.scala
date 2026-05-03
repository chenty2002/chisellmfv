package llmverify

import chisel3._
import chisel3.util._

// VIPER microprocessor states
object ViperState extends ChiselEnum {
  val FETCH, EXEC = Value
}

class viper extends Module {
  val io = IO(new Bundle {
    val addr = Output(UInt(20.W))
    val datai = Input(UInt(32.W))
    val datao = Output(UInt(32.W))
    // Additional outputs to preserve internal state
    val regfile_out = Output(Vec(4, UInt(32.W)))
    val B_out = Output(Bool())
    val STOP_out = Output(Bool())
    val IR_out = Output(UInt(32.W))
    val state_out = Output(ViperState())
  })
  
  // Registers
  val regfile = RegInit(VecInit(Seq.fill(4)(0.U(32.W))))
  val B = RegInit(false.B)
  val STOP = RegInit(false.B)
  val IR = RegInit(0.U(32.W))
  val state = RegInit(ViperState.FETCH)
  val addr = RegInit(0.U(20.W))
  val datao = RegInit(0.U(32.W))
  
  // Instruction format fields
  val rf = IR(31, 30)  // register select field
  val mf = IR(29, 28)  // memory select field
  val df = IR(27, 25)  // destination select field
  val cf = IR(24)      // comparison flag field
  val ff = IR(23, 20)  // function select field
  val tail = IR(19, 0) // address or offset field
  
  // Register and memory values
  val r = regfile(rf)
  val m = Mux(mf === 0.U, Cat(0.U(12.W), tail), io.datai)
  
  // Destination selection function
  def destination(df: UInt, B: Bool): UInt = {
    val result = Wire(UInt(2.W))
    result := 0.U // default case
    
    when(df === 5.U) { // 3'b101
      when(!B) {
        result := 3.U // regfile[3] (P)
      }
    }.elsewhen(df === 4.U) { // 3'b100
      when(B) {
        result := 3.U // regfile[3] (P)
      }
    }.elsewhen(df === 3.U) { // 3'b011
      result := 3.U // regfile[3] (P)
    }.elsewhen(df === 2.U) { // 3'b010
      result := 2.U // regfile[2] (Y)
    }.elsewhen(df === 1.U) { // 3'b001
      result := 1.U // regfile[1] (X)
    }.elsewhen(df === 0.U) { // 3'b000
      result := 0.U // regfile[0] (A)
    }
    // cases for df in {111,110} are missing (unspecified)
    
    result
  }
  
  val d = destination(df, B)
  
  // Absolute value function
  def abs(data: UInt): UInt = {
    Mux(data(31), data, 0.U(32.W) - data)
  }
  
  // Arithmetic operations
  val rpm = Cat(0.U(1.W), r) + Cat(0.U(1.W), m)
  val rmm = Cat(0.U(1.W), r) - Cat(0.U(1.W), m)
  
  // State machine
  val reg_P = regfile(3)
  
  // Default register updates (no change)
  val next_regfile = Wire(Vec(4, UInt(32.W)))
  next_regfile := regfile
  val next_B = Wire(Bool())
  next_B := B
  val next_STOP = Wire(Bool())
  next_STOP := STOP
  val next_state = Wire(ViperState())
  next_state := state
  
  when(!STOP && !(reg_P > 0x000fffff.U)) {
    switch(state) {
      is(ViperState.FETCH) {
        addr := reg_P(19, 0)
        IR := io.datai
        next_state := ViperState.EXEC
      }
      is(ViperState.EXEC) {
        next_regfile(3) := 0x00000008.U + reg_P
        
        // Decode and execute instruction
        when(cf) {
          // Comparison instructions
          switch(ff) {
            is(0.U) { next_B := r < m }
            is(1.U) { next_B := r >= m }
            is(2.U) { next_B := r === m }
            is(3.U) { next_B := r =/= m }
            is(4.U) { next_B := r <= m }
            is(5.U) { next_B := r > m }
            is(6.U) { next_B := abs(r) < m }
            is(7.U) { next_B := abs(~r) < m }
            is(8.U) { next_B := B | (r < m) }
            is(9.U) { next_B := B | (r >= m) }
            is(10.U) { next_B := B | (r === m) }
            is(11.U) { next_B := B | (r =/= m) }
            is(12.U) { next_B := B | (r <= m) }
            is(13.U) { next_B := B | (r > m) }
            is(14.U) { next_B := B | (abs(r) < m) }
            is(15.U) { next_B := B | (abs(~r) < m) }
          }
        }.otherwise {
          // Not comparison instructions
          when(!((df === 7.U) || (df === 6.U))) {
            switch(ff) {
              is(0.U) { // negate m
                next_regfile(d) := 0.U(32.W) - m
              }
              is(1.U) { // call 
                next_regfile(2) := regfile(3)
                next_regfile(3) := m
              }
              is(2.U) { // read from peripheral
                next_regfile(d) := m
              }
              is(3.U) { // read from memory
                next_regfile(d) := m
              }
              is(4.U) { // add r and m and store carry in B
                next_B := rpm(32)
                next_regfile(d) := rpm(31, 0)
              }
              is(5.U) { // add r and m and stop on overflow
                next_STOP := rpm(32)
                next_regfile(d) := rpm(31, 0)
              }
              is(6.U) { // subtract r and m and store borrow in B
                next_B := rmm(32)
                next_regfile(d) := rmm(31, 0)
              }
              is(7.U) { // subtract r and m and stop on overflow
                next_STOP := rmm(32)
                next_regfile(d) := rmm(31, 0)
              }
              is(8.U) { // XOR r and m
                next_regfile(d) := r ^ m
              }
              is(9.U) { // AND r and m
                next_regfile(d) := r & m
              }
              is(10.U) { // NOR r and m
                next_regfile(d) := ~(r | m)
              }
              is(11.U) { // AND r and NOT(m)
                next_regfile(d) := r & ~m
              }
              is(12.U) { // shift operations
                switch(mf) {
                  is(0.U) { // Shift right, copy the sign bit
                    next_regfile(d) := Cat(r(31), r(31, 1))
                  }
                  is(1.U) { // Shift right through B
                    next_regfile(d) := Cat(B, r(31, 1))
                  }
                  is(2.U) { // Shift left, stop on overflow
                    next_STOP := r(31)
                    next_regfile(d) := Cat(r(30, 0), 0.U)
                  }
                  is(3.U) { // Shift left through B
                    next_B := r(31)
                    next_regfile(d) := Cat(r(30, 0), 0.U)
                  }
                }
              }
              is(13.U) { next_STOP := true.B } // illegal instruction
              is(14.U) { next_STOP := true.B } // illegal instruction
              is(15.U) { next_STOP := true.B } // illegal instruction
            }
          }.elsewhen(df === 7.U) { // write_mem
            switch(mf) {
              is(0.U) { addr := tail }
              is(1.U) { addr := tail }
              is(2.U) { addr := regfile(1) + tail }
              is(3.U) { addr := regfile(2) + tail }
            }
            datao := r
          }.elsewhen(df === 6.U) { // should be write_io
            switch(mf) {
              is(0.U) { addr := tail }
              is(1.U) { addr := tail }
              is(2.U) { addr := regfile(1) + tail }
              is(3.U) { addr := regfile(2) + tail }
            }
            datao := r
          }
        }
        
        next_state := ViperState.FETCH
      }
    }
  }
  
  // Register updates on clock edge
  regfile := next_regfile
  B := next_B
  STOP := next_STOP
  state := next_state
  
  // Connect outputs
  io.addr := addr
  io.datao := datao
  io.regfile_out := regfile
  io.B_out := B
  io.STOP_out := STOP
  io.IR_out := IR
  io.state_out := state
}

object VerilogGenerator extends App {
  emitVerilog(new viper(), args)
}