package llmverify

import chisel3._
import chisel3.util._

// State definitions
object ExtIntControlStates {
  val idle :: miss :: nonCache :: extReqst :: missedL2 :: Nil = Enum(5)
}

object ExtIntControl {
  def apply(): extIntControl = new extIntControl()
}

class extIntControl extends Module {
  val io = IO(new Bundle {
    // Reset
    val Reset_ww = Input(Bool())
    
    // Signals to the datapath section
    val ratio_s2 = Output(UInt(4.W))
    val selShMemAddr_s1 = Output(Bool())
    val drvShMemAddr_q1 = Output(Bool())
    val loadNewAddr_s1 = Output(Bool())
    
    // Signals for tlb to start a request
    val ReqLength_s1 = Input(UInt(6.W))
    val ExtRead_s1 = Input(Bool())
    val ExtRequest_s1 = Input(Bool())
    val NonCacheable_s1 = Input(Bool())
    val L1Hit_s1 = Input(Bool())
    val ExtDataValid_s2 = Output(Bool())
    val L2Miss_s2 = Output(Bool())
    val Reset_s1 = Output(Bool())
    val ReqNextBlock_s2 = Input(Bool())
    
    // The LSunit's signals for starting a request
    val DExtRequest_s1 = Input(Bool())
    val DNonCacheable_s1 = Input(Bool())
    val DExtRead_s1 = Input(Bool())
    
    // Signals from the pads, i.e. from outside world
    val ReqICache_s1 = Input(Bool())
    val ReqDCache_s1 = Input(Bool())
    val ReqBus_s1 = Input(Bool())
    val L2Valid_s2 = Input(Bool())
    val BusError_s2 = Input(Bool())
    val ConfigIn = Input(Bool())
    val tagMatch_v2 = Input(Bool())
    
    // Signals to the pads (not outside world)
    val DrivePadAddr_s1 = Output(Bool())
    val DriveSharedMemAddr_s1 = Output(Bool())
    val DriveSharedMemData_s2 = Output(Bool())
    val DrivePadData_s2 = Output(Bool())
    val Grant_s1 = Output(Bool())
    val ConfigOut = Output(Bool())
    val ExternRead_s1 = Output(Bool())
    val NonCacheableOp_s1 = Output(Bool())
    
    // Additional outputs for debugging
    val mainState_s1 = Output(UInt(3.W))
    val mainState_s2 = Output(UInt(3.W))
    val rwState_s1 = Output(Bool())
    val rwState_s2 = Output(Bool())
    val busState_s1 = Output(Bool())
    val busState_s2 = Output(Bool())
  })
  
  import ExtIntControlStates._
  
  // Constants
  val READ = true.B
  val WRITE = false.B
  val GRANTED = true.B
  val NOT_GRANTED = false.B
  val TRUE = true.B
  val FALSE = false.B
  
  // Simulate two-phase clock using a toggle register
  val phi1Toggle = RegInit(false.B)
  phi1Toggle := !phi1Toggle
  val phi1Bool = phi1Toggle
  val phi2Bool = !phi1Toggle
  
  // To simplify the logic
  val extRequest_s1 = io.ExtRequest_s1 || io.DExtRequest_s1
  val extRead_s1 = io.ExtRead_s1 | io.DExtRead_s1
  val nonCacheable_s1 = (io.NonCacheable_s1 & io.ExtRequest_s1) |
                       (io.DNonCacheable_s1 & io.DExtRequest_s1)
  
  // Registers for the state machines
  val mainState_s1 = RegInit(idle)
  val mainState_s2 = RegInit(idle)
  val rwState_s1 = RegInit(FALSE)
  val rwState_s2 = RegInit(FALSE)
  val prevRwState_s1 = RegInit(FALSE)
  val prevRwState_s2 = RegInit(FALSE)
  val busState_s1 = RegInit(FALSE)
  val busState_s2 = RegInit(FALSE)
  
  // Request length and number of addresses
  val reqLength_s1 = RegInit(0.U(3.W))
  val reqLength_s2 = RegInit(0.U(3.W))
  val numberAddrs_s1 = RegInit(0.U(3.W))
  val numberAddrs_s2 = RegInit(0.U(3.W))
  
  // L2 miss handling
  val l2Miss_s1 = RegInit(FALSE)
  val l2Miss_s2 = RegInit(FALSE)
  
  // Data ready and bus request
  val extDataReady_s2 = RegInit(FALSE)
  val reqBus_s2 = RegInit(FALSE)
  
  // Counters
  val nextAddr_s1 = RegInit(0.U(5.W))
  val nextAddr_s2 = RegInit(0.U(5.W))
  val nextData_s1 = RegInit(0.U(3.W))
  val nextData_s2 = RegInit(0.U(3.W))
  
  // Configuration registers
  val rate_s1 = RegInit(1.U(5.W))
  val latency_s1 = RegInit(1.U(5.W))
  val ratio_s2 = RegInit(1.U(4.W))
  
  // Done signal
  val done_s2 = RegInit(FALSE)
  
  // Synchronized reset
  val Reset_s1 = RegInit(FALSE)
  
  // Helper signals
  val dataHere_s2 = nextData_s2 === 0.U
  val driveAddr_s1 = nextAddr_s1 === 0.U
  
  // L2 miss detection
  val l2Miss_v2 = ((dataHere_s2 && !io.tagMatch_v2 && rwState_s2 === READ) ||
                   (dataHere_s2 && io.L2Valid_s2 === FALSE && rwState_s2 === READ) ||
                   (mainState_s2 === missedL2 && 
                    (busState_s2 === NOT_GRANTED || reqBus_s2 === TRUE)))
  
  // Synchronized reset logic
  when(phi2Bool) {
    Reset_s1 := io.Reset_ww
  }
  
  // Configuration registers
  when(phi1Bool) {
    ratio_s2 := 1.U // Simplified RATIO
  }
  
  when(phi2Bool) {
    rate_s1 := 1.U // Simplified RATE
    latency_s1 := 1.U // Simplified LATENCY
  }
  
  // L2 miss and bus request registers
  when(phi1Bool) {
    when(Reset_s1) {
      l2Miss_s2 := FALSE
      reqBus_s2 := FALSE
    }.otherwise {
      l2Miss_s2 := l2Miss_s1
      reqBus_s2 := io.ReqBus_s1
    }
  }
  
  // Request length next state logic
  val reqLength_v1 = Wire(UInt(3.W))
  reqLength_v1 := MuxCase(reqLength_s1, Seq(
    Reset_s1 -> 0.U,
    (mainState_s1 === idle && extRequest_s1 === TRUE) -> io.ReqLength_s1(5,3),
    (nextData_s1 === 1.U) -> (reqLength_s1 - 1.U)
  ))
  
  // Number of addresses next state logic
  val numberAddrs_v1 = Wire(UInt(3.W))
  numberAddrs_v1 := MuxCase(numberAddrs_s1, Seq(
    Reset_s1 -> 0.U,
    (mainState_s1 === idle && extRequest_s1 === TRUE) -> 
      ((io.ReqLength_s1(5,3) - 1.U) & Fill(3, !nonCacheable_s1)),
    driveAddr_s1 -> (numberAddrs_s1 - 1.U)
  ))
  
  // Main state machine next state logic (phase 1)
  val mainState_v1 = Wire(UInt(8.W)) // Use UInt(8.W) to match Enum type
  mainState_v1 := MuxCase(mainState_s1, Seq(
    Reset_s1 -> idle,
    (mainState_s1 === idle) -> MuxCase(mainState_s1, Seq(
      ((io.ExtRequest_s1 === TRUE && io.NonCacheable_s1 === TRUE) ||
       (io.DExtRequest_s1 === TRUE && io.DNonCacheable_s1 === TRUE)) -> nonCache,
      ((io.ExtRequest_s1 === TRUE && io.NonCacheable_s1 === FALSE) ||
       (io.DExtRequest_s1 === TRUE && io.DNonCacheable_s1 === FALSE)) -> miss,
      ((io.ReqICache_s1 === TRUE || io.ReqDCache_s1 === TRUE) &&
       extRequest_s1 =/= TRUE) -> extReqst
    )),
    (mainState_s1 === nonCache) -> Mux(
      (rwState_s1 === READ && (busState_s1 === NOT_GRANTED || io.ReqBus_s1 === TRUE)),
      nonCache, idle
    ),
    (mainState_s1 === missedL2) -> Mux(
      (io.ReqBus_s1 === FALSE && busState_s1 === GRANTED),
      idle, missedL2
    )
  ))
  
  // Next address counter logic
  val nextAddr_v1 = Wire(UInt(5.W))
  nextAddr_v1 := MuxCase(nextAddr_s1, Seq(
    Reset_s1 -> 0.U,
    ((mainState_s1 === idle && extRequest_s1 === TRUE) || driveAddr_s1) -> (rate_s1 - 1.U),
    (numberAddrs_s1 =/= 0.U) -> (nextAddr_s1 - 1.U)
  ))
  
  // Next data counter logic
  val nextData_v1 = Wire(UInt(3.W))
  nextData_v1 := MuxCase(nextData_s1, Seq(
    Reset_s1 -> 0.U,
    (mainState_s1 === idle && extRequest_s1 === TRUE) -> latency_s1(2,0),
    (nextData_s1 === 1.U) -> rate_s1(2,0),
    (nextData_s1 =/= 0.U) -> (nextData_s1 - 1.U)
  ))
  
  // Read/Write state logic
  val rwState_v1 = Wire(Bool())
  rwState_v1 := MuxCase(rwState_s1, Seq(
    Reset_s1 -> FALSE,
    (mainState_s1 === idle && io.ExtRequest_s1 === TRUE) -> io.ExtRead_s1,
    (mainState_s1 === idle && io.DExtRequest_s1 === TRUE) -> io.DExtRead_s1,
    (mainState_s1 === idle) -> FALSE
  ))
  
  // Bus state logic
  val busState_v1 = Wire(Bool())
  busState_v1 := Mux(
    (io.ReqBus_s1 === TRUE && (mainState_s1 === idle || 
     mainState_s1 === nonCache || mainState_s1 === missedL2)),
    GRANTED, NOT_GRANTED
  )
  
  // External data ready logic
  val extDataReady_v1 = Wire(Bool())
  extDataReady_v1 := Mux(
    ((mainState_s1 === miss && nextData_s1 === 1.U && rwState_s1 === READ) ||
     (mainState_s1 === nonCache && rwState_s1 === READ && 
      busState_s1 === GRANTED && io.ReqBus_s1 === FALSE)),
    TRUE, FALSE
  )
  
  // Phase 1 register updates
  when(phi1Bool) {
    reqLength_s2 := reqLength_v1
    numberAddrs_s2 := numberAddrs_v1
    mainState_s2 := mainState_v1
    nextAddr_s2 := nextAddr_v1
    nextData_s2 := nextData_v1
    rwState_s2 := rwState_v1
    busState_s2 := busState_v1
    extDataReady_s2 := extDataReady_v1
    prevRwState_s2 := rwState_s1
  }
  
  // Phase 2 register updates
  when(phi2Bool) {
    rwState_s1 := rwState_s2
    busState_s1 := busState_s2
    numberAddrs_s1 := numberAddrs_s2
    l2Miss_s1 := l2Miss_v2
    nextData_s1 := nextData_s2
    nextAddr_s1 := nextAddr_s2
    reqLength_s1 := reqLength_s2
    prevRwState_s1 := prevRwState_s2
  }
  
  // Main state machine next state logic (phase 2)
  val mainState_v2 = Wire(UInt(8.W)) // Use UInt(8.W) to match Enum type
  mainState_v2 := MuxCase(mainState_s2, Seq(
    (mainState_s2 === miss) -> MuxCase(mainState_s2, Seq(
      ((reqLength_s2 === 0.U && rwState_s2 === READ) ||
       (numberAddrs_s2 === 0.U && rwState_s2 === WRITE)) -> idle,
      (dataHere_s2 && (!io.tagMatch_v2 || io.L2Valid_s2 === FALSE) && 
       (rwState_s2 === READ)) -> missedL2
    )),
    (mainState_s2 === extReqst) -> Mux(
      done_s2 === TRUE, idle, extReqst
    )
  ))
  
  // Update main state in phase 2
  when(phi2Bool) {
    mainState_s1 := mainState_v2
  }
  
  // Output assignments
  io.ConfigOut := FALSE
  io.ExternRead_s1 := ((mainState_s1 === idle && extRead_s1) ||
                      (mainState_s1 === miss && rwState_s1 === READ))
  io.NonCacheableOp_s1 := nonCacheable_s1
  io.L2Miss_s2 := l2Miss_s2
  io.ExtDataValid_s2 := extDataReady_s2 ||
                       (nextAddr_s2 === 1.U && rwState_s2 === WRITE && mainState_s1 === miss)
  io.Grant_s1 := (mainState_s1 === missedL2)
  io.DriveSharedMemData_s2 := (rwState_s2 === READ)
  io.DriveSharedMemAddr_s1 := FALSE
  io.DrivePadAddr_s1 := extRequest_s1 ||
                        (numberAddrs_s1 =/= 0.U && driveAddr_s1 && mainState_s1 === miss)
  io.DrivePadData_s2 := !(prevRwState_s2 === READ)
  io.loadNewAddr_s1 := ((mainState_s1 === miss && driveAddr_s1) ||
                        (mainState_s1 === idle && extRequest_s1))
  io.selShMemAddr_s1 := (mainState_s1 === idle)
  io.drvShMemAddr_q1 := ((mainState_s1 === miss && driveAddr_s1) && phi1Bool)
  io.ratio_s2 := ratio_s2
  
  // Debug outputs
  io.mainState_s1 := mainState_s1
  io.mainState_s2 := mainState_s2
  io.rwState_s1 := rwState_s1
  io.rwState_s2 := rwState_s2
  io.busState_s1 := busState_s1
  io.busState_s2 := busState_s2
  io.Reset_s1 := Reset_s1
}

object VerilogGenerator extends App {
  emitVerilog(new extIntControl(), args)
}