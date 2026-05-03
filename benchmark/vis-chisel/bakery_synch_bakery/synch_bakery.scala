package llmverify

import chisel3._
import chisel3.util._

// Enum for program counter locations
object Loc extends ChiselEnum {
  val L1, L2, L3, L4, L5, L6, L7, L8, L9, L10, L11 = Value
}

class Bakery(val tkMsb: Int = 1, val hiProc: Int = 1, val selMsb: Int = 1) extends Module {
  val io = IO(new Bundle {
    val pause = Input(Bool())
    // Add outputs to preserve internal state for verification
    val ticket = Output(Vec(hiProc + 1, UInt((tkMsb + 1).W)))
    val choosing = Output(Vec(hiProc + 1, Bool()))
    val pc = Output(Vec(hiProc + 1, Loc()))
    val j = Output(Vec(hiProc + 1, UInt((selMsb + 1).W)))
  })
  
  // The ticket numbers of the processes
  val ticket = RegInit(VecInit(Seq.fill(hiProc + 1)(0.U((tkMsb + 1).W))))
  
  // More than one process may be choosing a ticket
  val choosing = RegInit(VecInit(Seq.fill(hiProc + 1)(false.B)))
  
  // The program counters of the processes
  val pc = RegInit(VecInit(Seq.fill(hiProc + 1)(Loc.L1)))
  
  // The loop indices of the processors
  val j = RegInit(VecInit(Seq.fill(hiProc + 1)(0.U((selMsb + 1).W))))
  
  // Connect outputs to preserve state
  io.ticket := ticket
  io.choosing := choosing
  io.pc := pc
  io.j := j
  
  // Process each process sequentially in the clock cycle
  // This mimics the original Verilog where processes are called in sequence
  for (sel <- 0 to hiProc) {
    val selUInt = sel.U
    
    switch(pc(selUInt)) {
      is(Loc.L1) {
        choosing(selUInt) := true.B
        pc(selUInt) := Loc.L2
      }
      is(Loc.L2) {
        // Find maximum ticket and add 1
        // Use a reduction to find the maximum without creating combinational cycles
        val maxTicket = ticket.fold(ticket(selUInt)) { (acc, t) =>
          Mux(t > acc, t, acc)
        }
        ticket(selUInt) := maxTicket + 1.U
        pc(selUInt) := Loc.L3
      }
      is(Loc.L3) {
        choosing(selUInt) := false.B
        pc(selUInt) := Loc.L4
      }
      is(Loc.L4) {
        j(selUInt) := 0.U
        pc(selUInt) := Loc.L5
      }
      is(Loc.L5) {
        when(j(selUInt) <= hiProc.U) {
          pc(selUInt) := Loc.L6
        }.otherwise {
          pc(selUInt) := Loc.L9
        }
      }
      is(Loc.L6) {
        val k = j(selUInt)
        when(choosing(k)) {
          pc(selUInt) := Loc.L6
        }.otherwise {
          pc(selUInt) := Loc.L7
        }
      }
      is(Loc.L7) {
        val k = j(selUInt)
        when((ticket(k) =/= 0.U) && 
             (ticket(k) < ticket(selUInt) || 
              (ticket(k) === ticket(selUInt) && k < selUInt))) {
          pc(selUInt) := Loc.L7
        }.otherwise {
          pc(selUInt) := Loc.L8
        }
      }
      is(Loc.L8) {
        j(selUInt) := j(selUInt) + 1.U
        pc(selUInt) := Loc.L5
      }
      is(Loc.L9) {
        when(io.pause) {
          pc(selUInt) := Loc.L9
        }.otherwise {
          pc(selUInt) := Loc.L10
        }
      }
      is(Loc.L10) {
        ticket(selUInt) := 0.U
        pc(selUInt) := Loc.L11
      }
      is(Loc.L11) {
        when(io.pause) {
          pc(selUInt) := Loc.L11
        }.otherwise {
          pc(selUInt) := Loc.L1
        }
      }
    }
  }
}

object VerilogGenerator extends App {
  emitVerilog(new Bakery(), Array("--target-dir", "generated"))
}