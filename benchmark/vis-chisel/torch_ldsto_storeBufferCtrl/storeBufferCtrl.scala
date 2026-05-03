package llmverify

import chisel3._
import chisel3.util._

class storeBufferCtrl extends Module {
  val io = IO(new Bundle {
    // Clocks (Phi1 is implicit clock, Phi2 is derived)
    val Reset_s1 = Input(Bool())
    val Stall_s1 = Input(Bool())

    // Inputs
    val Commit_s1e = Input(Bool())
    val Squash_s1e = Input(Bool())
    val Except_s1w = Input(Bool())
    val InstrIsLoad_s1m = Input(Bool())
    val BoostedInstr_s1m = Input(Bool())
    val doBufferStore_s1m = Input(Bool())
    val popStoreBuffer_s1 = Input(Bool())
    val dCacheFill_s1 = Input(Bool())
    val AMatch_v1m = Input(UInt(4.W))
    val BMatch_v1m = Input(UInt(4.W))

    // Outputs
    val Alpha_q2 = Output(UInt(4.W))
    val Beta_q2 = Output(UInt(4.W))
    val SeqConflict_v1m = Output(Bool())
    val AbufSel_s1w = Output(UInt(4.W))
    val BbufSel_s1w = Output(UInt(4.W))
    val selAstore_s1w = Output(Bool())
    val stoBufferEmpty_s1 = Output(Bool())
    val stoBufferStall_s1m = Output(Bool())
    val MemExcept_s2m = Output(Bool())
    val Commit_s1w = Output(Bool())
    
    // Debug outputs to preserve signals
    val Avalid_s1_debug = Output(UInt(4.W))
    val Bvalid_s1_debug = Output(UInt(4.W))
    val Ahead_s1_debug = Output(UInt(4.W))
    val Atail_s1_debug = Output(UInt(4.W))
    val Bhead_s1_debug = Output(UInt(4.W))
    val Btail_s1_debug = Output(UInt(4.W))
    val Asequential_s1m_debug = Output(Bool())
  })

  // Phi2 is complement of Phi1 (clock)
  val Phi2 = !clock.asBool

  // Registers
  val Avalid_s1 = RegInit(0.U(4.W))
  val Avalid_s2 = RegInit(0.U(4.W))
  val Bvalid_s1 = RegInit(0.U(4.W))
  val Bvalid_s2 = RegInit(0.U(4.W))
  val Ahead_s1 = RegInit(1.U(4.W))
  val Ahead_s2 = RegInit(1.U(4.W))
  val Atail_s1 = RegInit(1.U(4.W))
  val Atail_s2 = RegInit(1.U(4.W))
  val Bhead_s1 = RegInit(1.U(4.W))
  val Bhead_s2 = RegInit(1.U(4.W))
  val Btail_s1 = RegInit(1.U(4.W))
  val Btail_s2 = RegInit(1.U(4.W))
  val Asequential_s1m = RegInit(false.B)
  val Asequential_s2m = RegInit(false.B)

  // Delayed signals
  val popStoreBuffer_s2 = RegInit(false.B)
  val MemExcept_s2m_reg = RegInit(false.B)

  // Delay pong until WB stage
  val Commit_s2e = RegInit(false.B)
  val Commit_s1m = RegInit(false.B)
  val Commit_s2m = RegInit(false.B)
  val Commit_s1w_reg = RegInit(false.B)
  val Squash_s2e = RegInit(false.B)
  val Squash_s1m = RegInit(false.B)
  val Squash_s2m = RegInit(false.B)

  // Convenience signals
  val doBufferStore_s2m = RegInit(false.B)
  val BoostedInstr_s2m = RegInit(false.B)

  // Combinational logic
  val Astore_s2m = doBufferStore_s2m && (BoostedInstr_s2m ^ Asequential_s2m)
  val Bstore_s2m = doBufferStore_s2m && (BoostedInstr_s2m ^ !Asequential_s2m)
  
  io.Alpha_q2 := Mux(Phi2 && Astore_s2m, Ahead_s2, 0.U)
  io.Beta_q2 := Mux(Phi2 && Bstore_s2m, Bhead_s2, 0.U)

  // Conflict detection
  val BoostConflict_v1m = Mux(Asequential_s1m, 
                             (Bvalid_s1 & io.BMatch_v1m).orR,
                             (Avalid_s1 & io.AMatch_v1m).orR)
  
  io.SeqConflict_v1m := Mux(Asequential_s1m,
                            (Avalid_s1 & io.AMatch_v1m).orR,
                            (Bvalid_s1 & io.BMatch_v1m).orR)

  // Clear signals
  val clearA_s1 = io.Reset_s1 || (io.Except_s1w && !Asequential_s1m) ||
                  (io.Except_s1w && Commit_s1w_reg && Asequential_s1m)
  val clearB_s1 = io.Reset_s1 || (io.Except_s1w && Asequential_s1m) ||
                  (io.Except_s1w && Commit_s1w_reg && !Asequential_s1m)
  
  val popA_s1 = Mux(io.popStoreBuffer_s1 && Asequential_s1m, ~Atail_s1, 0xf.U)
  val popB_s1 = Mux(io.popStoreBuffer_s1 && !Asequential_s1m, ~Btail_s1, 0xf.U)

  // Buffer selection
  io.AbufSel_s1w := Atail_s1
  io.BbufSel_s1w := Btail_s1
  io.selAstore_s1w := Asequential_s1m

  // Full/Empty detection
  val Afull_s1 = (Ahead_s1 === Atail_s1) && Avalid_s1.orR
  val Bfull_s1 = (Bhead_s1 === Btail_s1) && Bvalid_s1.orR
  val boostFull_s1 = Mux(Asequential_s1m, Bfull_s1, Afull_s1)
  val seqntFull_s1 = Mux(Asequential_s1m, Afull_s1, Bfull_s1)
  io.stoBufferEmpty_s1 := Mux(Asequential_s1m, !Avalid_s1.orR, !Bvalid_s1.orR)
  io.stoBufferStall_s1m := !io.stoBufferEmpty_s1 && Commit_s1m

  // Exception logic
  val MemExcept_v1m = (io.InstrIsLoad_s1m && io.BoostedInstr_s1m && BoostConflict_v1m) ||
                      (io.doBufferStore_s1m && 
                       ((io.BoostedInstr_s1m && boostFull_s1) ||
                        (!io.BoostedInstr_s1m && seqntFull_s1)))

  // Two-phase clocking simulation using enable signals
  val phi1_enable = clock.asBool
  val phi2_enable = !clock.asBool
  
  // Phase 1 logic (rising edge)
  when(phi1_enable) {
    // Valid bits update
    Avalid_s2 := Mux(!clearA_s1, popA_s1 & Avalid_s1, 0.U)
    Bvalid_s2 := Mux(!clearB_s1, popB_s1 & Bvalid_s1, 0.U)
    
    // Head/Tail pointers
    Ahead_s2 := Mux(clearA_s1, 1.U, Ahead_s1)
    Atail_s2 := Mux(clearA_s1, 1.U, Atail_s1)
    Bhead_s2 := Mux(clearB_s1, 1.U, Bhead_s1)
    Btail_s2 := Mux(clearB_s1, 1.U, Btail_s1)
    
    // Pong logic
    when(!io.Stall_s1) {
      Asequential_s2m := io.Reset_s1 || Asequential_s1m
    }
    
    // Delay latches
    doBufferStore_s2m := !io.Except_s1w && io.doBufferStore_s1m
    
    when(!io.Stall_s1) {
      BoostedInstr_s2m := io.BoostedInstr_s1m
      Commit_s2e := io.Commit_s1e
      Commit_s2m := Commit_s1m
      Squash_s2e := io.Squash_s1e
      Squash_s2m := Squash_s1m
    }
    
    popStoreBuffer_s2 := io.popStoreBuffer_s1
    
    when(!io.Stall_s1) {
      MemExcept_s2m_reg := MemExcept_v1m
    }
  }
  
  // Phase 2 logic (falling edge)
  when(phi2_enable) {
    // Valid bits update
    Avalid_s1 := Mux(Squash_s2m && !Asequential_s2m, 0.U,
                    Mux(Astore_s2m, Ahead_s2 | Avalid_s2, Avalid_s2))
    Bvalid_s1 := Mux(Squash_s2m && Asequential_s2m, 0.U,
                    Mux(Bstore_s2m, Bhead_s2 | Bvalid_s2, Bvalid_s2))
    
    // Head/Tail pointers update
    Ahead_s1 := Mux(Squash_s2m && !Asequential_s2m, 1.U,
                    Mux(Astore_s2m, Cat(Ahead_s2(2,0), Ahead_s2(3)), Ahead_s2))
    Bhead_s1 := Mux(Squash_s2m && Asequential_s2m, 1.U,
                    Mux(Bstore_s2m, Cat(Bhead_s2(2,0), Bhead_s2(3)), Bhead_s2))
    Atail_s1 := Mux(Squash_s2m && !Asequential_s2m, 1.U,
                    Mux(Asequential_s2m && popStoreBuffer_s2, 
                        Cat(Atail_s2(2,0), Atail_s2(3)), Atail_s2))
    Btail_s1 := Mux(Squash_s2m && Asequential_s2m, 1.U,
                    Mux(!Asequential_s2m && popStoreBuffer_s2,
                        Cat(Btail_s2(2,0), Btail_s2(3)), Btail_s2))
    
    // Sequential buffer toggle
    Asequential_s1m := Asequential_s2m ^ Commit_s2m
    
    // Commit/Squash pipeline
    Commit_s1m := Commit_s2e
    Commit_s1w_reg := Commit_s2m
    Squash_s1m := Squash_s2e
  }
  
  // Output assignments
  io.MemExcept_s2m := MemExcept_s2m_reg
  io.Commit_s1w := Commit_s1w_reg
  
  // Debug outputs
  io.Avalid_s1_debug := Avalid_s1
  io.Bvalid_s1_debug := Bvalid_s1
  io.Ahead_s1_debug := Ahead_s1
  io.Atail_s1_debug := Atail_s1
  io.Bhead_s1_debug := Bhead_s1
  io.Btail_s1_debug := Btail_s1
  io.Asequential_s1m_debug := Asequential_s1m
}

object VerilogGenerator extends App {
  emitVerilog(new storeBufferCtrl(), args)
}