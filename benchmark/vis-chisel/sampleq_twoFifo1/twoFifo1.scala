package llmverify

import chisel3._
import chisel3.util._

class sampleq(WIDTH: Int = 2, LENGTH: Int = 4, LOGLENGTH: Int = 2) extends Module {
  val io = IO(new Bundle {
    val reset = Input(Bool())
    val inaddr = Input(UInt(WIDTH.W))
    val validin = Input(Bool())
    val readin = Input(Bool())
    val clkin = Input(Clock())
    val bus_gnt_raw = Input(Bool())
    
    val outaddr = Output(UInt(WIDTH.W))
    val validout = Output(Bool())
    val outisaread = Output(Bool())
    val readheadentry = Output(UInt(WIDTH.W))
  })
  
  // FIFO storage
  val readfifo = RegInit(VecInit(Seq.fill(LENGTH)(0.U(WIDTH.W))))
  val writefifo = RegInit(VecInit(Seq.fill(LENGTH)(0.U(WIDTH.W))))
  
  // Pointers
  val readtail = RegInit(0.U(LOGLENGTH.W))
  val readhead = RegInit(0.U(LOGLENGTH.W))
  val writehead = RegInit(0.U(LOGLENGTH.W))
  val writetail = RegInit(0.U(LOGLENGTH.W))
  
  // Control signals
  val match_reg = RegInit(false.B)
  val inputmatch = RegInit(false.B)
  val outaddr_reg = RegInit(0.U(WIDTH.W))
  val outisaread_reg = RegInit(false.B)
  val validout_reg = RegInit(false.B)
  val bus_gnt = RegInit(false.B)
  val storeaddr = RegInit(0.U(LOGLENGTH.W))
  
  // FIFO status signals
  val readempty = (readtail === readhead)
  val writeempty = (writetail === writehead)
  val readfull = ((readtail + 1.U) & ((1.U << LOGLENGTH) - 1.U)) === readhead
  val writefull = ((writetail + 1.U) & ((1.U << LOGLENGTH) - 1.U)) === writehead
  
  // Output assignments
  io.outaddr := outaddr_reg
  io.validout := validout_reg
  io.outisaread := outisaread_reg
  io.readheadentry := readfifo(readhead)
  
  // Sequential logic
  when(io.reset) {
    bus_gnt := false.B
    readtail := 0.U
    writetail := 0.U
    readhead := 0.U
    writehead := 0.U
    validout_reg := false.B
    outisaread_reg := false.B
    outaddr_reg := 0.U
    match_reg := false.B
    inputmatch := false.B
    storeaddr := 0.U
    
    // Initialize FIFOs
    for (i <- 0 until LENGTH) {
      readfifo(i) := 0.U
      writefifo(i) := 0.U
    }
  }.otherwise {
    bus_gnt := io.bus_gnt_raw
    
    // Input: queue requests in respective FIFOs
    when(io.validin && io.readin && !readfull) {
      readfifo(readtail) := io.inaddr
      readtail := (readtail + 1.U) & ((1.U << LOGLENGTH) - 1.U)
    }.elsewhen(io.validin && !io.readin && !writefull) {
      writefifo(writetail) := io.inaddr
      writetail := (writetail + 1.U) & ((1.U << LOGLENGTH) - 1.U)
    }
    
    when(bus_gnt) {
      // Check for match between next read queue entry and any entry in write queue
      match_reg := false.B
      
      for (i <- 0 until LENGTH) {
        val writeEntryValid = Mux(
          writehead < writetail,
          (i.U >= writehead) && (i.U < writetail),
          (i.U >= writehead) || (i.U < writetail)
        )
        
        when(!readempty && writeEntryValid && (readfifo(readhead) === writefifo(i))) {
          match_reg := true.B
          storeaddr := readhead
        }
      }
      
      // Output logic: priority to reads unless there's a match
      when(!readempty && !match_reg) {
        outaddr_reg := readfifo(readhead)
        readhead := (readhead + 1.U) & ((1.U << LOGLENGTH) - 1.U)
        outisaread_reg := true.B
        validout_reg := true.B
      }.elsewhen(!writeempty) {
        outaddr_reg := writefifo(writehead)
        writehead := (writehead + 1.U) & ((1.U << LOGLENGTH) - 1.U)
        outisaread_reg := false.B
        validout_reg := true.B
      }.otherwise {
        validout_reg := false.B
      }
    }
  }
}

object VerilogGenerator extends App {
  emitVerilog(new sampleq(), args)
}