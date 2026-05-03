package llmverify

import chisel3._
import chisel3.util._

// State definitions for cache conflict state machine
object ConfState {
  val IDLE = 0.U(2.W)
  val STORE = 1.U(2.W)
  val COMMIT = 2.U(2.W)
  val RETRY = 3.U(2.W)
}

// State definitions for ext requests state machine  
object ExtState {
  val IDLE = 0.U(4.W)
  val STORE = 1.U(4.W)
  val PROBE = 2.U(4.W)
  val DRV_SPILL_ADDR = 3.U(4.W)
  val WAIT_XFER = 4.U(4.W)
  val DRV_DATA = 5.U(4.W)
  val WAIT1 = 6.U(4.W)
  val DRV_FILL_ADDR = 7.U(4.W)
  val XFER_DATA = 8.U(4.W)
  val WAIT2 = 9.U(4.W)
  val RETRY = 10.U(4.W)
  val NON_CACHE_R = 11.U(4.W)
  val NON_CACHE_W = 12.U(4.W)
  val NON_CACHE_WAIT = 13.U(4.W)
  val NON_CACHE_DRV = 14.U(4.W)
  val NON_CACHE_PAUSE = 15.U(4.W)
}

// State definitions for pending store
object StoState {
  val IDLE = 0.U(3.W)
  val B_PROBE = 1.U(3.W)
  val F_PROBE = 2.U(3.W)
  val PENDING = 3.U(3.W)
  val F_MISSED = 4.U(3.W)
  val B_MISSED = 5.U(3.W)
}

class ldStoCtrlIO extends Bundle {
  // Inputs
  val MemOp_s1m = Input(UInt(3.W))
  val byteOffset_s1m = Input(UInt(2.W))
  val InstrIsLoad_s1m = Input(Bool())
  val InstrIsStore_s1m = Input(Bool())
  val BoostedInstr_s1m = Input(Bool())
  val HLNotReady_s2e = Input(Bool())
  val NonCacheable_v2m = Input(Bool())
  val IStall_s1 = Input(Bool())
  val IFetchStall_s1 = Input(Bool())
  val valid_v2m = Input(Bool())
  val match_v2m = Input(Bool())
  val cacheConflict_v1m = Input(Bool())
  val dirty_v2m = Input(Bool())
  val cacheBusSignBits_v2m = Input(UInt(2.W))
  val stoBufferEmpty_s1 = Input(Bool())
  val stoBufferStall_s1m = Input(Bool())
  val SeqConflict_v1m = Input(Bool())
  val lineOffset_s1w = Input(UInt(5.W))
  val missOp_s1w = Input(UInt(3.W))
  val ExtDataValid_s2 = Input(Bool())
  val L2Miss_s2 = Input(Bool())
  val Except_s1w = Input(Bool())
  val Reset_s1 = Input(Bool())
  
  // Outputs
  val Stall_s1 = Output(Bool())
  val Stall_s2 = Output(Bool())
  val MemStall_s1 = Output(Bool())
  val valid_s1m = Output(Bool())
  val dTagRead_s1m = Output(Bool())
  val dTagWrite_s1m = Output(Bool())
  val latchStore_s1w = Output(Bool())
  val dTagIsLoad_s1m = Output(Bool())
  val dTagIsLoad_s2m = Output(Bool())
  val dCacheRead_s1m = Output(Bool())
  val dCacheWrite_s1m = Output(Bool())
  val dirty_s1 = Output(Bool())
  val latchExtData_s2 = Output(Bool())
  val dCacheIsLoad_s1m = Output(Bool())
  val dCacheIsStore_s1 = Output(Bool())
  val selFastStore_s1m = Output(Bool())
  val latchCacheData_s1 = Output(Bool())
  val drvSharedMemData_q2 = Output(Bool())
  val doBufferStore_s1m = Output(Bool())
  val popStoreBuffer_s1 = Output(Bool())
  val dCacheFill_s1 = Output(Bool())
  val MemOp_s2m = Output(UInt(3.W))
  val byteOffset_s2m = Output(UInt(2.W))
  val lineOffset_s1 = Output(UInt(5.W))
  val missOp_s1 = Output(UInt(3.W))
  val latchMissAddr_s2 = Output(Bool())
  val drvSharedMemAddr_q1 = Output(Bool())
  val pendStore_s2 = Output(Bool())
  val selStoreAddr_s1 = Output(Bool())
  val selMissAddr_s1 = Output(Bool())
  val selProbeAddr_s1 = Output(Bool())
  val selSpillAddr_s1 = Output(Bool())
  val selBuffMissAddr_s1 = Output(Bool())
  val selByte1Pass_s2m = Output(Bool())
  val selByte1One_v2m = Output(Bool())
  val selByte1Zero_v2m = Output(Bool())
  val selByte23Pass_s2m = Output(Bool())
  val selByte23One_v2m = Output(Bool())
  val selByte23Zero_v2m = Output(Bool())
  val drvMemBusCD_q2m = Output(Bool())
  val drvMemBusSMD_q2m = Output(Bool())
  val drvNonCache_q2m = Output(Bool())
  val DExtRequest_s1 = Output(Bool())
  val DExtRead_s1 = Output(Bool())
  val DNonCacheable_s1 = Output(Bool())
  val ReqLength_s1 = Output(UInt(6.W))
}

class ldStoCtrl extends Module {
  val io = IO(new ldStoCtrlIO)
  
  // State machine registers
  val extState_s1 = RegInit(ExtState.IDLE)
  val extState_s2 = RegInit(ExtState.IDLE)
  val confState_s1 = RegInit(ConfState.IDLE)
  val confState_s2 = RegInit(ConfState.IDLE)
  val storeState_s1 = RegInit(StoState.IDLE)
  val storeState_s2 = RegInit(StoState.IDLE)
  
  // Counters
  val offset_s1 = RegInit(0.U(4.W))
  val offset_s2 = RegInit(0.U(4.W))
  val saveOffset_s1 = RegInit(0.U(4.W))
  
  // Delayed signals
  val dTagIsLoad_s2m = RegInit(false.B)
  val dTagRead_s2m = RegInit(false.B)
  val ExtDataValid_s1 = RegInit(false.B)
  val InstrIsLoad_s2m = RegInit(false.B)
  val InstrIsStore_s2 = RegInit(false.B)
  val MemOp_s2m_reg = RegInit(0.U(3.W))
  val byteOffset_s2m_reg = RegInit(0.U(2.W))
  val Stall_s2 = RegInit(false.B)
  val nonCacheable_s1 = RegInit(false.B)
  val earlyDrvSMD_s1 = RegInit(false.B)
  val earlyDrvSMD_s2 = RegInit(false.B)
  val doStore_s2m = RegInit(false.B)
  val QualInstrIsLoad_s2m = RegInit(false.B)
  
  // Phase control for two-phase clocking simulation
  val phase = RegInit(false.B) // false = Phi1, true = Phi2
  phase := !phase
  
  // Convenience signals
  val dCacheMiss_v2m = Wire(Bool())
  val doStore_s1m = Wire(Bool())
  val doCacheStore_s1m = Wire(Bool())
  val doBufferStore_s1m_wire = Wire(Bool())
  val extState_v2 = WireDefault(ExtState.IDLE)
  val confState_v1 = WireDefault(ConfState.IDLE)
  val storeState_v1 = WireDefault(StoState.IDLE)
  val storeState_v2 = WireDefault(StoState.IDLE)
  val xferDone_s2 = Wire(Bool())
  val QualInstrIsStore_s1m = Wire(Bool())
  val QualInstrIsLoad_s1m = Wire(Bool())
  
  // --- Stall Signals ---
  io.MemStall_s1 := (extState_s1 =/= ExtState.IDLE ||
                     confState_s1 =/= ConfState.IDLE ||
                     io.stoBufferStall_s1m) && !io.Reset_s1
  io.Stall_s1 := io.IStall_s1 || io.MemStall_s1
  
  // --- dTag Interface ---
  QualInstrIsLoad_s1m := io.InstrIsLoad_s1m && !io.stoBufferStall_s1m
  QualInstrIsStore_s1m := io.InstrIsStore_s1m
  
  doCacheStore_s1m := (io.selFastStore_s1m || 
                       (!io.InstrIsLoad_s1m && !io.stoBufferEmpty_s1)) && !io.Stall_s1
  doBufferStore_s1m_wire := QualInstrIsStore_s1m && 
                            (!io.stoBufferEmpty_s1 || io.BoostedInstr_s1m) && !io.Stall_s1
  doStore_s1m := doCacheStore_s1m || io.popStoreBuffer_s1
  
  io.dTagRead_s1m := ((QualInstrIsLoad_s1m || doStore_s1m) &&
                      !io.dTagWrite_s1m && !io.IStall_s1) ||
                     (storeState_s1 === StoState.B_PROBE) ||
                     (storeState_s1 === StoState.F_PROBE) ||
                     (extState_s1 === ExtState.PROBE && !io.Reset_s1)
  
  io.dTagWrite_s1m := extState_s1 === ExtState.XFER_DATA && ExtDataValid_s1
  io.dTagIsLoad_s1m := (QualInstrIsLoad_s1m || io.selFastStore_s1m) &&
                       (extState_s1 === ExtState.IDLE)
  io.valid_s1m := extState_s1 === ExtState.XFER_DATA
  
  dCacheMiss_v2m := dTagRead_s2m && !(io.valid_v2m && io.match_v2m)
  
  // --- dCache Interface ---
  io.dCacheRead_s1m := (QualInstrIsLoad_s1m && (confState_s1 =/= ConfState.STORE &&
                        extState_s1 === ExtState.IDLE) ||
                        (confState_s1 === ConfState.RETRY)) ||
                       (extState_s1 === ExtState.PROBE) ||
                       (extState_s1 === ExtState.DRV_SPILL_ADDR) ||
                       (extState_s1 === ExtState.WAIT_XFER) ||
                       (extState_s1 === ExtState.DRV_DATA) ||
                       ((extState_s1 === ExtState.RETRY) &&
                        (storeState_s1 =/= StoState.F_MISSED) &&
                        (storeState_s1 =/= StoState.B_MISSED))
  
  io.dCacheWrite_s1m := (storeState_s1 === StoState.PENDING && !QualInstrIsLoad_s1m &&
                         extState_s1 === ExtState.IDLE) ||
                        (storeState_s1 === StoState.PENDING &&
                         confState_s1 === ConfState.STORE &&
                         extState_s1 === ExtState.IDLE) ||
                        (extState_s1 === ExtState.STORE &&
                         storeState_s1 === StoState.PENDING) ||
                        (((extState_s1 === ExtState.XFER_DATA) ||
                          (extState_s1 === ExtState.WAIT2)) && ExtDataValid_s1) ||
                        ((extState_s1 === ExtState.RETRY) &&
                         (storeState_s1 === StoState.F_MISSED ||
                          storeState_s1 === StoState.B_MISSED))
  
  io.dirty_s1 := !(extState_s1 === ExtState.XFER_DATA)
  io.latchStore_s1w := doStore_s1m
  io.latchExtData_s2 := io.ExtDataValid_s2
  io.dCacheIsLoad_s1m := QualInstrIsLoad_s1m && (confState_s1 === ConfState.IDLE &&
                          extState_s1 === ExtState.IDLE)
  io.dCacheIsStore_s1 := (storeState_s1 === StoState.PENDING && !QualInstrIsLoad_s1m &&
                          (extState_s1 === ExtState.IDLE ||
                           extState_s1 === ExtState.STORE)) ||
                         (confState_s1 === ConfState.STORE) ||
                         ((extState_s1 === ExtState.RETRY) &&
                          (storeState_s1 === StoState.F_MISSED ||
                           storeState_s1 === StoState.B_MISSED))
  
  io.selFastStore_s1m := QualInstrIsStore_s1m &&
                         (io.stoBufferEmpty_s1 && !io.BoostedInstr_s1m)
  io.drvSharedMemData_q2 := (extState_s2 === ExtState.DRV_SPILL_ADDR ||
                             earlyDrvSMD_s2) && phase
  io.pendStore_s2 := (storeState_s2 === StoState.F_PROBE ||
                      storeState_s2 === StoState.B_PROBE ||
                      storeState_s2 === StoState.PENDING)
  io.latchCacheData_s1 := earlyDrvSMD_s1 || extState_s1 === ExtState.DRV_SPILL_ADDR
  
  // --- Addr Datapath ---
  io.drvSharedMemAddr_q1 := (extState_s1 === ExtState.DRV_SPILL_ADDR ||
                             extState_s1 === ExtState.DRV_FILL_ADDR ||
                             extState_s1 === ExtState.NON_CACHE_W ||
                             extState_s1 === ExtState.NON_CACHE_R) && !phase
  io.selStoreAddr_s1 := extState_s1 === ExtState.IDLE &&
                        (confState_s1 === ConfState.IDLE ||
                         confState_s1 === ConfState.STORE)
  io.selMissAddr_s1 := extState_s1 =/= ExtState.IDLE ||
                       confState_s1 === ConfState.RETRY
  io.selProbeAddr_s1 := false.B
  io.selSpillAddr_s1 := extState_s1 === ExtState.DRV_SPILL_ADDR
  io.selBuffMissAddr_s1 := storeState_s1 === StoState.B_MISSED
  io.latchMissAddr_s2 := !Stall_s2
  
  // --- Sign Extend ---
  io.selByte1Pass_s2m := io.MemOp_s2m(1) || io.MemOp_s2m(0)
  io.selByte1One_v2m := io.MemOp_s2m === 0.U && io.cacheBusSignBits_v2m(0)
  io.selByte1Zero_v2m := io.MemOp_s2m === 4.U ||
                        (io.MemOp_s2m === 0.U && !io.cacheBusSignBits_v2m(0))
  io.selByte23Pass_s2m := io.MemOp_s2m === 3.U
  io.selByte23One_v2m := (io.MemOp_s2m === 0.U && io.cacheBusSignBits_v2m(0)) ||
                         (io.MemOp_s2m === 1.U && io.cacheBusSignBits_v2m(1))
  io.selByte23Zero_v2m := (io.MemOp_s2m === 4.U || io.MemOp_s2m === 5.U ||
                           (io.MemOp_s2m === 0.U && !io.cacheBusSignBits_v2m(0)) ||
                           (io.MemOp_s2m === 1.U && !io.cacheBusSignBits_v2m(1)))
  
  // --- Datapath ---
  io.drvMemBusCD_q2m := QualInstrIsLoad_s2m &&
                        extState_s2 =/= ExtState.NON_CACHE_WAIT && phase
  io.drvMemBusSMD_q2m := (extState_s2 === ExtState.NON_CACHE_WAIT) && phase
  io.drvNonCache_q2m := (extState_s2 === ExtState.NON_CACHE_W) && phase
  
  // --- Cache Miss ---
  io.DExtRequest_s1 := extState_s1 === ExtState.DRV_SPILL_ADDR ||
                       extState_s1 === ExtState.DRV_FILL_ADDR ||
                       extState_s1 === ExtState.NON_CACHE_R ||
                       extState_s1 === ExtState.NON_CACHE_W
  io.DExtRead_s1 := extState_s1 === ExtState.DRV_FILL_ADDR ||
                    extState_s1 === ExtState.NON_CACHE_R
  io.DNonCacheable_s1 := nonCacheable_s1
  io.ReqLength_s1 := Mux(extState_s1 === ExtState.DRV_SPILL_ADDR ||
                         extState_s1 === ExtState.DRV_FILL_ADDR, 32.U,
                         Mux(extState_s1 === ExtState.NON_CACHE_R ||
                             extState_s1 === ExtState.NON_CACHE_W, 4.U, 0.U))
  
  io.dCacheFill_s1 := extState_s1 === ExtState.XFER_DATA
  
  xferDone_s2 := offset_s2(2)
  io.lineOffset_s1 := Mux(extState_s1 === ExtState.PROBE ||
                          extState_s1 === ExtState.DRV_SPILL_ADDR ||
                          extState_s1 === ExtState.XFER_DATA ||
                          extState_s1 === ExtState.DRV_DATA,
                          Cat(saveOffset_s1(1,0), 0.U(3.W)), io.lineOffset_s1w)
  
  io.missOp_s1 := Mux(extState_s1 =/= ExtState.IDLE && extState_s1 =/= ExtState.RETRY,
                      2.U, io.missOp_s1w)
  
  // --- Store Buffer ---
  io.popStoreBuffer_s1 := !io.stoBufferEmpty_s1 &&
                          (storeState_s1 =/= StoState.F_MISSED) &&
                          (storeState_s1 =/= StoState.B_MISSED) &&
                          ((!QualInstrIsLoad_s1m && !io.IStall_s1 &&
                            extState_s1 === ExtState.IDLE) ||
                           confState_s1 === ConfState.STORE ||
                           io.stoBufferStall_s1m)
  
  io.doBufferStore_s1m := doBufferStore_s1m_wire
  
  // --- State Machine Next State Logic ---
  
  // Store state machine next state (NS1)
  storeState_v1 := StoState.IDLE
  when(!io.Reset_s1) {
    switch(storeState_s1) {
      is(StoState.IDLE) {
        when(io.selFastStore_s1m && !io.Stall_s1) {
          storeState_v1 := StoState.F_PROBE
        }.elsewhen(io.popStoreBuffer_s1) {
          storeState_v1 := StoState.B_PROBE
        }.otherwise {
          storeState_v1 := StoState.IDLE
        }
      }
      is(StoState.F_PROBE) {
        when(io.Except_s1w) {
          storeState_v1 := StoState.IDLE
        }.otherwise {
          storeState_v1 := StoState.F_PROBE
        }
      }
      is(StoState.PENDING) {
        when(QualInstrIsLoad_s1m) {
          storeState_v1 := StoState.PENDING
        }.elsewhen(extState_s1 =/= ExtState.IDLE && extState_s1 =/= ExtState.STORE) {
          storeState_v1 := StoState.PENDING
        }.elsewhen(io.selFastStore_s1m && !io.Stall_s1) {
          storeState_v1 := StoState.F_PROBE
        }.elsewhen(io.popStoreBuffer_s1) {
          storeState_v1 := StoState.B_PROBE
        }.otherwise {
          storeState_v1 := StoState.IDLE
        }
      }
      is(StoState.B_PROBE) {
        storeState_v1 := StoState.B_PROBE
      }
      is(StoState.F_MISSED) {
        storeState_v1 := StoState.F_MISSED
      }
      is(StoState.B_MISSED) {
        storeState_v1 := StoState.B_MISSED
      }
    }
  }
  
  // Store state machine next state (NS2)
  storeState_v2 := StoState.IDLE
  switch(storeState_s2) {
    is(StoState.B_PROBE) {
      when(io.NonCacheable_v2m) {
        storeState_v2 := StoState.IDLE
      }.elsewhen(dCacheMiss_v2m) {
        storeState_v2 := StoState.B_MISSED
      }.otherwise {
        storeState_v2 := StoState.PENDING
      }
    }
    is(StoState.F_PROBE) {
      when(io.NonCacheable_v2m) {
        storeState_v2 := StoState.IDLE
      }.elsewhen(dCacheMiss_v2m) {
        storeState_v2 := StoState.F_MISSED
      }.otherwise {
        storeState_v2 := StoState.PENDING
      }
    }
    is(StoState.F_MISSED) {
      when(extState_s2 === ExtState.RETRY) {
        storeState_v2 := StoState.IDLE
      }.otherwise {
        storeState_v2 := StoState.F_MISSED
      }
    }
    is(StoState.B_MISSED) {
      when(extState_s2 === ExtState.RETRY) {
        storeState_v2 := StoState.IDLE
      }.otherwise {
        storeState_v2 := StoState.B_MISSED
      }
    }
    is(StoState.IDLE) {
      storeState_v2 := StoState.IDLE
    }
    is(StoState.PENDING) {
      storeState_v2 := StoState.PENDING
    }
  }
  
  // Conflict state machine next state
  confState_v1 := ConfState.IDLE
  when(!io.Reset_s1) {
    switch(confState_s1) {
      is(ConfState.IDLE) {
        when(QualInstrIsLoad_s1m && !io.IStall_s1 &&
             (io.SeqConflict_v1m ||
              (io.cacheConflict_v1m && storeState_s1 === StoState.PENDING))) {
          confState_v1 := ConfState.STORE
        }.elsewhen(io.InstrIsLoad_s1m && io.stoBufferStall_s1m) {
          confState_v1 := ConfState.COMMIT
        }.otherwise {
          confState_v1 := ConfState.IDLE
        }
      }
      is(ConfState.STORE) {
        when(io.stoBufferEmpty_s1 && storeState_s1 =/= StoState.B_MISSED) {
          confState_v1 := ConfState.RETRY
        }.otherwise {
          confState_v1 := ConfState.STORE
        }
      }
      is(ConfState.COMMIT) {
        when(io.stoBufferStall_s1m) {
          confState_v1 := ConfState.COMMIT
        }.otherwise {
          confState_v1 := ConfState.RETRY
        }
      }
      is(ConfState.RETRY) {
        confState_v1 := ConfState.IDLE
      }
    }
  }
  
  // External state machine next state
  extState_v2 := ExtState.IDLE
  switch(extState_s2) {
    is(ExtState.IDLE) {
      when(io.NonCacheable_v2m && InstrIsLoad_s2m) {
        extState_v2 := ExtState.NON_CACHE_R
      }.elsewhen(io.NonCacheable_v2m && doStore_s2m) {
        extState_v2 := ExtState.NON_CACHE_W
      }.elsewhen(!dCacheMiss_v2m) {
        extState_v2 := ExtState.IDLE
      }.elsewhen(storeState_s2 === StoState.PENDING) {
        extState_v2 := ExtState.STORE
      }.otherwise {
        extState_v2 := ExtState.PROBE
      }
    }
    is(ExtState.STORE) {
      extState_v2 := ExtState.PROBE
    }
    is(ExtState.PROBE) {
      when(io.dirty_v2m) {
        extState_v2 := ExtState.DRV_SPILL_ADDR
      }.otherwise {
        extState_v2 := ExtState.DRV_FILL_ADDR
      }
    }
    is(ExtState.DRV_SPILL_ADDR) {
      extState_v2 := ExtState.WAIT_XFER
    }
    is(ExtState.WAIT_XFER) {
      when(io.ExtDataValid_s2) {
        extState_v2 := ExtState.DRV_DATA
      }.otherwise {
        extState_v2 := ExtState.WAIT_XFER
      }
    }
    is(ExtState.DRV_DATA) {
      when(xferDone_s2) {
        extState_v2 := ExtState.WAIT1
      }.elsewhen(io.ExtDataValid_s2) {
        extState_v2 := ExtState.DRV_DATA
      }.otherwise {
        extState_v2 := ExtState.WAIT_XFER
      }
    }
    is(ExtState.WAIT1) {
      extState_v2 := ExtState.DRV_FILL_ADDR
    }
    is(ExtState.DRV_FILL_ADDR) {
      extState_v2 := ExtState.XFER_DATA
    }
    is(ExtState.XFER_DATA) {
      when(xferDone_s2) {
        extState_v2 := ExtState.RETRY
      }.otherwise {
        extState_v2 := ExtState.XFER_DATA
      }
    }
    is(ExtState.WAIT2) {
      extState_v2 := ExtState.RETRY
    }
    is(ExtState.RETRY) {
      extState_v2 := ExtState.IDLE
    }
    is(ExtState.NON_CACHE_W) {
      extState_v2 := ExtState.NON_CACHE_PAUSE
    }
    is(ExtState.NON_CACHE_R) {
      extState_v2 := ExtState.NON_CACHE_WAIT
    }
    is(ExtState.NON_CACHE_WAIT) {
      when(io.ExtDataValid_s2) {
        extState_v2 := ExtState.IDLE
      }.otherwise {
        extState_v2 := ExtState.NON_CACHE_WAIT
      }
    }
    is(ExtState.NON_CACHE_PAUSE) {
      extState_v2 := ExtState.IDLE
    }
  }
  
  // --- Register Updates ---
  
  // Phase 2 updates (equivalent to Phi2)
  when(phase) {
    saveOffset_s1 := offset_s2
    extState_s1 := extState_v2
    confState_s1 := confState_s2
    storeState_s1 := storeState_v2
    
    ExtDataValid_s1 := io.ExtDataValid_s2
    nonCacheable_s1 := io.NonCacheable_v2m && extState_s2 === ExtState.IDLE
    earlyDrvSMD_s1 := extState_s2 === ExtState.DRV_DATA
    
    // Offset counter logic
    when(extState_s2 === ExtState.DRV_SPILL_ADDR) {
      offset_s1 := 1.U
    }.elsewhen(extState_s2 === ExtState.DRV_FILL_ADDR) {
      offset_s1 := 0.U
    }.otherwise {
      offset_s1 := Mux(io.ExtDataValid_s2, offset_s2 + 1.U, offset_s2)
    }
  }
  
  // Phase 1 updates (equivalent to Phi1)
  when(!phase) {
    offset_s2 := Mux(io.Reset_s1, 0.U, offset_s1)
    extState_s2 := Mux(io.Reset_s1, ExtState.IDLE, extState_s1)
    confState_s2 := confState_v1
    storeState_s2 := storeState_v1
    
    dTagIsLoad_s2m := io.dTagIsLoad_s1m
    dTagRead_s2m := io.dTagRead_s1m
    
    when(!io.Stall_s1) {
      InstrIsStore_s2 := QualInstrIsStore_s1m
      MemOp_s2m_reg := io.MemOp_s1m
      InstrIsLoad_s2m := io.InstrIsLoad_s1m
      byteOffset_s2m_reg := io.byteOffset_s1m
      QualInstrIsLoad_s2m := QualInstrIsLoad_s1m
    }
    
    Stall_s2 := io.IFetchStall_s1 || io.MemStall_s1
    earlyDrvSMD_s2 := earlyDrvSMD_s1
    doStore_s2m := doStore_s1m
  }
  
  // Output assignments
  io.MemOp_s2m := MemOp_s2m_reg
  io.byteOffset_s2m := byteOffset_s2m_reg
  io.Stall_s2 := Stall_s2
  io.dTagIsLoad_s2m := dTagIsLoad_s2m
}

object VerilogGenerator extends App {
  emitVerilog(new ldStoCtrl(), args)
}